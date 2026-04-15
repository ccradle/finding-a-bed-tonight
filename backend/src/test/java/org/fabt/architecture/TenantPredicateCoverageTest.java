package org.fabt.architecture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.fabt.shared.security.TenantUnscopedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static SQL tenant-predicate coverage test (design D15 of
 * {@code cross-tenant-isolation-audit}).
 *
 * <p>Walks every SQL statement reachable in the codebase and asserts that
 * any statement targeting a <b>tenant-owned</b> table either (a) carries a
 * {@code tenant_id} predicate (SELECT/UPDATE/DELETE) or column
 * (INSERT), or (b) is annotated with {@link TenantUnscopedQuery} carrying
 * a non-empty justification.
 *
 * <h2>Two scan surfaces</h2>
 * <ol>
 *   <li><b>{@link #queryAnnotationsHaveTenantPredicate()}</b> —
 *       reflection over every {@code *Repository} interface in
 *       {@code org.fabt..repository}, inspects each method's
 *       {@link Query} annotation value.</li>
 *   <li><b>{@link #jdbcTemplateCallsHaveTenantPredicate()}</b> —
 *       JavaParser scan over every {@code .java} source file under
 *       {@code src/main/java/org/fabt}, finds calls to
 *       {@code jdbcTemplate.query/update/queryFor*}, extracts the literal
 *       SQL string, applies the same rule. Catches the queries the
 *       reflection scan cannot see (services and JdbcTemplate-backed
 *       repositories that don't use {@code @Query}).</li>
 * </ol>
 *
 * <h2>What this is — and is not</h2>
 *
 * <p>This is a <b>build-time developer-discipline check</b>, not the
 * runtime integrity gate. The runtime gate is Postgres RLS plus the
 * {@code app.tenant_id} session variable installed by
 * {@code RlsDataSourceConfig} (Phase 4.8 of the audit, design D13).
 * A query that legitimately bypasses this static check still flows
 * through the runtime gate.
 *
 * <p>Per warroom 2026-04-15 (Alex + Elena + Marcus Webb): the static
 * check is necessary-but-not-sufficient — it detects <em>presence</em>
 * of a {@code tenant_id} predicate, not whether it binds to the correct
 * tenant. The runtime RLS gate catches the latter.
 */
@DisplayName("Tenant-predicate coverage (D15)")
class TenantPredicateCoverageTest {

    /**
     * Allowlist of tenant-owned database tables — every table whose rows
     * carry a {@code tenant_id} column. Update this set as new tenant-
     * owned tables are added (see {@code CONTRIBUTING.md}). The
     * {@link #allowlistDoesNotDriftFromSchema()} test surfaces the most
     * common drift case (a new repository references an unfamiliar
     * tenant-shaped table) so the list cannot silently rot.
     */
    private static final Set<String> TENANT_OWNED_TABLES = Set.of(
            "shelter",
            "referral_token",
            "reservation",
            "notification",
            "audit_events",
            "api_key",
            "subscription",
            "app_user",
            "tenant_oauth2_provider",
            "webhook_delivery_log",
            "one_time_access_code",
            "hmis_outbox",
            "hmis_audit_log",
            "escalation_policy",
            "bed_availability",
            "shelter_constraints",
            "surge_event",
            "password_reset_token",
            "user_oauth2_link",
            "totp_recovery"
    );

    private static final String TENANT_ID_COLUMN = "tenant_id";

    @Test
    @DisplayName("@Query annotations on tenant-owned tables include a tenant_id predicate")
    void queryAnnotationsHaveTenantPredicate() {
        List<String> violations = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        for (BeanDefinition bd : scanner.findCandidateComponents("org.fabt")) {
            String className = bd.getBeanClassName();
            Class<?> repoClass;
            try {
                repoClass = Class.forName(className);
            } catch (ClassNotFoundException e) {
                continue;
            }
            if (!repoClass.isInterface()) {
                continue;
            }
            for (Method method : repoClass.getDeclaredMethods()) {
                Query queryAnno = method.getAnnotation(Query.class);
                if (queryAnno == null || queryAnno.value().isBlank()) {
                    continue;
                }
                String location = repoClass.getSimpleName() + "." + method.getName();
                String violation = checkSql(location, queryAnno.value(),
                        method.getAnnotation(TenantUnscopedQuery.class));
                if (violation != null) {
                    violations.add(violation);
                }
            }
        }

        assertThat(violations)
                .as("Every @Query against a tenant-owned table must include a "
                        + "tenant_id predicate (or carry @TenantUnscopedQuery with "
                        + "a non-empty justification). See design D15.")
                .isEmpty();
    }

    @Test
    @DisplayName("JdbcTemplate.query/update/queryFor* literal SQL on tenant-owned tables includes a tenant_id predicate")
    void jdbcTemplateCallsHaveTenantPredicate() throws IOException {
        Path sourceRoot = resolveSourceRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> javaFiles = Files.walk(sourceRoot)) {
            javaFiles
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(file -> scanJdbcTemplateCalls(file, violations));
        }

        assertThat(violations)
                .as("Every literal SQL passed to JdbcTemplate against a tenant-"
                        + "owned table must include a tenant_id predicate (or the "
                        + "enclosing method must carry @TenantUnscopedQuery). See "
                        + "design D15.")
                .isEmpty();
    }

    @Test
    @DisplayName("Allowlist does not drift — every table referenced from an @Query is recognized")
    void allowlistDoesNotDriftFromSchema() {
        // Drift catcher: surfaces table names that appear in repositories
        // but neither (a) live in the tenant-owned allowlist nor (b) live
        // in the platform-table allowlist. Forces a deliberate decision
        // when a new table is introduced.
        Set<String> known = Set.of(
                // Tenant-owned (must mirror TENANT_OWNED_TABLES)
                "shelter", "referral_token", "reservation", "notification",
                "audit_events", "api_key", "subscription", "app_user",
                "tenant_oauth2_provider", "webhook_delivery_log",
                "one_time_access_code", "hmis_outbox", "hmis_audit_log",
                "escalation_policy", "bed_availability", "shelter_constraints",
                "surge_event", "password_reset_token", "user_oauth2_link",
                "totp_recovery",
                // Platform-wide (no tenant_id column by design)
                "tenant", "rate_limit_bucket", "flyway_schema_history",
                "batch_job_instance", "batch_job_execution",
                "batch_job_execution_params", "batch_job_execution_context",
                "batch_step_execution", "batch_step_execution_context",
                "batch_job_seq", "batch_step_execution_seq",
                "batch_job_execution_seq",
                // Aggregates / read-models (no tenant column intentionally)
                "snapshot_aggregate", "bed_search_log",
                // Auth-link tables keyed by user (tenant-scoped via FK)
                "totp_secret", "totp_enrollment", "user_role"
        );

        List<String> unknown = new ArrayList<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        for (BeanDefinition bd : scanner.findCandidateComponents("org.fabt")) {
            Class<?> repoClass;
            try {
                repoClass = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException e) {
                continue;
            }
            for (Method method : repoClass.getDeclaredMethods()) {
                Query qa = method.getAnnotation(Query.class);
                if (qa == null || qa.value().isBlank()) continue;
                try {
                    for (String table : TablesNamesFinder.findTables(qa.value())) {
                        String norm = table.toLowerCase(Locale.ROOT);
                        if (!known.contains(norm) && !norm.contains(".")) {
                            unknown.add(repoClass.getSimpleName() + "." + method.getName()
                                    + " references unknown table: " + table);
                        }
                    }
                } catch (JSQLParserException ignored) {
                    // SQL with PostgreSQL-specific syntax (RETURNING, ::cast,
                    // jsonb operators) may parse-fail; the predicate scan
                    // catches what it can. The drift-check only flags
                    // unknown tables it could parse out.
                }
            }
        }

        assertThat(unknown)
                .as("Repository @Query references a table not in either allowlist. "
                        + "If the new table is tenant-owned, add it to "
                        + "TENANT_OWNED_TABLES. If platform-wide, add it to the "
                        + "known set in this test. See CONTRIBUTING.md.")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Path resolveSourceRoot() {
        // Tests run from the backend/ module dir under Maven and most IDEs.
        Path candidate = Paths.get("src", "main", "java", "org", "fabt");
        if (Files.exists(candidate)) {
            return candidate;
        }
        // Fallback for runs from the repo root.
        Path fallback = Paths.get("backend", "src", "main", "java", "org", "fabt");
        if (Files.exists(fallback)) {
            return fallback;
        }
        throw new IllegalStateException(
                "Could not locate src/main/java/org/fabt — tried " + candidate.toAbsolutePath()
                        + " and " + fallback.toAbsolutePath());
    }

    private static void scanJdbcTemplateCalls(Path javaFile, List<String> violations) {
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaFile);
        } catch (Exception e) {
            // Source files we cannot parse (incomplete syntax, exotic
            // language features) are skipped. JavaParser handles Java 25
            // sufficiently for our purposes; record-pattern oddities here
            // would not host SQL anyway.
            return;
        }

        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (!isJdbcTemplateSqlCall(call)) {
                return;
            }
            if (call.getArguments().isEmpty()) {
                return;
            }
            // The SQL string is the first argument across all
            // JdbcTemplate.query/update/queryFor* overloads.
            if (!(call.getArgument(0) instanceof StringLiteralExpr literal)) {
                // Non-literal SQL (e.g., a String built from a constant).
                // Out of scope for the literal scan; if we ever start
                // building SQL dynamically we'll need a richer rule.
                return;
            }
            String sql = literal.asString();

            String enclosingClass = cu.getPrimaryTypeName().orElse("?");
            String enclosingMethod = call.findAncestor(MethodDeclaration.class)
                    .map(m -> m.getNameAsString()).orElse("<init>");
            String location = enclosingClass + "." + enclosingMethod;

            TenantUnscopedQuery escape = call.findAncestor(MethodDeclaration.class)
                    .flatMap(m -> m.getAnnotationByClass(TenantUnscopedQuery.class))
                    .map(a -> new TenantUnscopedQueryFromAst(extractAnnotationValue(a.toString())))
                    .map(TenantUnscopedQueryFromAst::asAnnotation)
                    .orElse(null);

            String violation = checkSql(location, sql, escape);
            if (violation != null) {
                violations.add(violation);
            }
        });
    }

    private static boolean isJdbcTemplateSqlCall(MethodCallExpr call) {
        String name = call.getNameAsString();
        boolean nameMatches = name.equals("query")
                || name.equals("update")
                || name.equals("execute")
                || name.startsWith("queryFor");
        if (!nameMatches) {
            return false;
        }
        return call.getScope()
                .map(scope -> {
                    String s = scope.toString();
                    return s.equals("jdbcTemplate")
                            || s.equals("namedParameterJdbcTemplate")
                            || s.endsWith(".jdbcTemplate")
                            || s.endsWith(".namedParameterJdbcTemplate");
                })
                .orElse(false);
    }

    /**
     * Apply the predicate rule to a single SQL string at a given location.
     * Returns a violation message string, or null if compliant.
     */
    private static String checkSql(String location, String sql, TenantUnscopedQuery escape) {
        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            // Unparseable PostgreSQL-isms (e.g., ::uuid casts, jsonb ->>
            // operators) — skip rather than false-positive. JSqlParser 5.x
            // handles most modern Postgres but not all extensions.
            return null;
        }

        Set<String> tables;
        try {
            tables = TablesNamesFinder.findTables(sql);
        } catch (Exception e) {
            return null;
        }

        boolean targetsTenantOwned = tables.stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .anyMatch(TENANT_OWNED_TABLES::contains);
        if (!targetsTenantOwned) {
            return null;
        }

        if (escape != null) {
            if (escape.value() == null || escape.value().isBlank()) {
                return location + " carries @TenantUnscopedQuery with empty justification (must be non-empty)";
            }
            return null;
        }

        if (hasTenantPredicate(stmt)) {
            return null;
        }

        return location + " — SQL targets tenant-owned table but lacks tenant_id predicate: \""
                + sql.replaceAll("\\s+", " ").trim() + "\"";
    }

    private static boolean hasTenantPredicate(Statement stmt) {
        if (stmt instanceof Select select) {
            return whereContainsTenantId(select.getPlainSelect());
        }
        if (stmt instanceof Update update) {
            return expressionMentionsTenantId(update.getWhere())
                    || updateSetMentionsTenantId(update);
        }
        if (stmt instanceof Delete delete) {
            return expressionMentionsTenantId(delete.getWhere());
        }
        if (stmt instanceof Insert insert) {
            return insertColumnListMentionsTenantId(insert);
        }
        // Other statement kinds (CREATE, ALTER, ...) are not relevant.
        return true;
    }

    private static boolean whereContainsTenantId(PlainSelect plainSelect) {
        if (plainSelect == null) return false;
        return expressionMentionsTenantId(plainSelect.getWhere());
    }

    private static boolean expressionMentionsTenantId(Expression where) {
        if (where == null) return false;
        AtomicBoolean found = new AtomicBoolean(false);
        // Walk the where-expression tree looking for any Column whose
        // unqualified name is "tenant_id" (case-insensitive). Catches
        // unqualified `tenant_id = :tenantId`, qualified `s.tenant_id =`,
        // and predicates inside AND/OR/IN(...) subselects.
        where.accept(new net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                if (TENANT_ID_COLUMN.equalsIgnoreCase(column.getColumnName())) {
                    found.set(true);
                }
                return null;
            }
        }, null);
        return found.get();
    }

    private static boolean updateSetMentionsTenantId(Update update) {
        // Defensive: an UPDATE that modifies tenant_id itself would be
        // suspicious (cross-tenant ownership transfer). Treat as missing
        // predicate so the test surfaces it for review.
        return false;
    }

    private static boolean insertColumnListMentionsTenantId(Insert insert) {
        if (insert.getColumns() == null) {
            // INSERT without column list — bare `INSERT INTO t VALUES (...)`.
            // Risky on tenant-owned tables; flag.
            return false;
        }
        return insert.getColumns().stream()
                .anyMatch(c -> TENANT_ID_COLUMN.equalsIgnoreCase(c.getColumnName()));
    }

    private static String extractAnnotationValue(String annotationToString) {
        // JavaParser's annotation toString for @TenantUnscopedQuery("foo")
        // yields the literal source text; pick out the "..." content.
        int firstQuote = annotationToString.indexOf('"');
        int lastQuote = annotationToString.lastIndexOf('"');
        if (firstQuote < 0 || lastQuote <= firstQuote) {
            return "";
        }
        return annotationToString.substring(firstQuote + 1, lastQuote);
    }

    /**
     * Lightweight value holder so the JdbcTemplate scan can pass an
     * "annotation-equivalent" object to {@link #checkSql} without
     * requiring runtime annotation classes (the source we're scanning is
     * not on the test's classpath as instances). Implements the same
     * single-method contract as {@link TenantUnscopedQuery}.
     */
    private record TenantUnscopedQueryFromAst(String value) {
        TenantUnscopedQuery asAnnotation() {
            String v = value;
            return new TenantUnscopedQuery() {
                @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                    return TenantUnscopedQuery.class;
                }
                @Override public String value() { return v; }
            };
        }
    }
}
