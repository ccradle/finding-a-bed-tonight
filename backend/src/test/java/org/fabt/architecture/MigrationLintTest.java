package org.fabt.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lint rules over Flyway migration files. Complements the ArchUnit rules
 * on Java code (see {@code TenantContextTransactionalRuleTest},
 * {@code TenantPredicateCoverageTest}) with similar enforcement for SQL.
 *
 * <p>Task 3.14 (v0.45 task #165): forbid {@code SECURITY DEFINER} on
 * functions created by Flyway migrations. Rationale:
 * {@code SECURITY DEFINER} functions execute with the privileges of the
 * FUNCTION OWNER, not the calling role. In FABT's Phase B regime the
 * owner is {@code fabt} (superuser-adjacent; BYPASSRLS) and the caller
 * is {@code fabt_app} (RLS-enforced). A {@code SECURITY DEFINER} function
 * owned by {@code fabt} would bypass every RLS policy on the seven
 * regulated tables — exactly the owner-bypass vector Phase B's FORCE RLS
 * (V69) is designed to block.
 *
 * <p>When a future migration legitimately needs {@code SECURITY DEFINER}
 * (e.g., a superuser-only operation like {@code CREATE EXTENSION}), add
 * the migration's filename to {@link #SECURITY_DEFINER_ALLOWLIST} with
 * a comment explaining the justification. The owner-security-model
 * trade-off must be documented in the warroom / design doc first.
 *
 * <p>This is a FABT-specific SQL lint; ArchUnit has no native support
 * for SQL AST traversal, so a file-scan test is the right tool.
 */
@DisplayName("Flyway migration lint — task 3.14 (SECURITY DEFINER ban)")
class MigrationLintTest {

    private static final Path SQL_MIGRATIONS =
            Paths.get("src", "main", "resources", "db", "migration");
    private static final Path JAVA_MIGRATIONS =
            Paths.get("src", "main", "java", "db", "migration");

    /**
     * Allowlist of migration filenames that may use {@code SECURITY DEFINER}.
     * Additions MUST be approved in a warroom review + reference a
     * design-doc section. Empty today — Phase B's Flyway migrations all
     * run under {@code fabt} owner for DDL but define functions without
     * the {@code SECURITY DEFINER} attribute.
     */
    private static final List<String> SECURITY_DEFINER_ALLOWLIST = List.of(
            // V82 trigger function `tenant_dek_shred_guard()` — reads the session
            // GUC `fabt.shred_in_progress` in a BEFORE DELETE trigger. Must be
            // SECURITY DEFINER to read the GUC regardless of the invoking role's
            // search_path (attacker could override). Body is 3 lines of pure SQL,
            // no expansion surface. See:
            //   openspec/changes/multi-tenant-production-readiness/design-f6-real-cryptoshred.md §3 Q-F6-6
            //   warroom 2026-04-24 pass-2 (GUC-over-role resolution)
            "V82__tenant_dek_schema.sql",

            // V87 — 8 SECURITY DEFINER functions wrapping access to the
            // platform_user / platform_user_backup_code / platform_key_material
            // tables. The Phase B owner-bypass concern does NOT apply to these
            // tables because they have NO tenant_id column — there is no
            // RLS-protected tenant scope to bypass. The platform_user table
            // belongs to a separate identity surface (PLATFORM_OPERATOR) with
            // its own auth flow (iss=fabt-platform JWTs). REVOKE ALL on direct
            // table access from fabt_app + access via these SECURITY DEFINER
            // functions matches the Phase G-1 tenant_audit_chain_head pattern.
            // Function bodies are short, single-purpose, with SET search_path =
            // pg_catalog (anti-injection). Defensive ownership transfer to
            // `fabt` via DO-block (no-op in test env where fabt role doesn't
            // exist; transfers in prod). See:
            //   openspec/changes/platform-admin-split-and-access-log/design.md
            //     Decision 2 (separate platform_user table — no tenant_id, no RLS surface)
            //     Decision 8 (REVOKE+SECURITY DEFINER mirrors Phase G-1 chain-head)
            //   warroom synthesis 2026-04-25 (Elena: REVOKE + SECURITY DEFINER preferred over RLS for non-tenant-scoped tables)
            "V87__platform_user_and_key_material.sql",

            // V88 — 8 SECURITY DEFINER functions wrapping per-account MFA-lockout
            // state, atomic MFA enrollment, TOTP replay-protection, email
            // mutation, and bootstrap-reset (recovery + test cleanup) on
            // platform_user (G-4.2 / issue #141). The Phase B owner-bypass
            // concern does NOT apply — same access-control mechanic as V87:
            // platform_user has REVOKE ALL from fabt_app, the functions are
            // the only write path. The platform_user table has no tenant_id
            // column, so there is no RLS-protected tenant scope to bypass.
            // Functions added: platform_user_record_failure, _clear_failures,
            // _unlock_expired, _setup_mfa (atomic), _record_totp_use,
            // _was_totp_recently_used, _set_email, _reset_to_bootstrap. Each
            // is short, single-purpose, with
            // SET search_path = pg_catalog (anti-injection). Defensive
            // ownership transfer to `fabt` via DO-block (no-op in test env
            // where fabt role doesn't exist; transfers in prod). See:
            //   openspec/changes/platform-admin-split-and-access-log/design.md
            //     Decision 8 (REVOKE+SECURITY DEFINER mirrors V87 / G-1 pattern)
            //     Decision 5 (5-fail/15-min lockout, cron auto-unlock)
            //   warroom 2026-04-25 (Marcus M1: TOTP replay; Alex A1: atomic MFA setup)
            "V88__platform_user_lockout_columns.sql",

            // V90 — single SECURITY DEFINER function `platform_user_get_me()`
            // returning operator-self metadata for the F11 platform-operator
            // dashboard (id, email, mfa_enabled, last_login_at, mfa_enabled_at
            // derived from MIN(platform_user_backup_code.created_at),
            // backup_codes_remaining derived from COUNT WHERE used_at IS NULL).
            // Same Phase B exemption as V87/V88: platform_user has no
            // tenant_id column, so there is no RLS-protected tenant scope to
            // bypass; REVOKE ALL on direct table access from fabt_app + access
            // via this SECURITY DEFINER function matches the V87 pattern.
            // Function body is short (single SELECT + 2 correlated subqueries),
            // SET search_path = pg_catalog (anti-injection). See:
            //   openspec/changes/platform-operator-ui/design.md
            //     Decision D5 (narrow backend un-freeze for /me + /logout)
            //   openspec/changes/platform-operator-ui/specs/platform-operator-identity/spec.md
            //     Requirement: Platform operator metadata endpoint
            "V90__platform_user_get_me_function.sql"
    );

    private static final Pattern SECURITY_DEFINER = Pattern.compile(
            "\\bSECURITY\\s+DEFINER\\b", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("No Flyway migration uses SECURITY DEFINER (task 3.14)")
    void noMigrationUsesSecurityDefiner() throws IOException {
        List<String> violations = new ArrayList<>();
        collectViolations(SQL_MIGRATIONS, violations);
        collectViolations(JAVA_MIGRATIONS, violations);

        assertThat(violations)
                .as("SECURITY DEFINER functions bypass RLS on the owning role. "
                        + "Phase B FORCE RLS (V69) depends on every write path running as fabt_app, "
                        + "not the fabt owner — a SECURITY DEFINER function owned by fabt would "
                        + "reintroduce owner-bypass. If a new migration genuinely needs SECURITY "
                        + "DEFINER (e.g. wrapping CREATE EXTENSION), add its filename to "
                        + "SECURITY_DEFINER_ALLOWLIST with a warroom/design citation.")
                .isEmpty();
    }

    private void collectViolations(Path dir, List<String> violations) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("V")
                                && (name.endsWith(".sql") || name.endsWith(".java"));
                    })
                    .forEach(p -> scanFile(p, violations));
        }
    }

    private void scanFile(Path file, List<String> violations) {
        String name = file.getFileName().toString();
        if (SECURITY_DEFINER_ALLOWLIST.contains(name)) {
            return;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(file + " — could not read: " + e.getMessage());
            return;
        }
        // Strip SQL line comments so a -- "avoid SECURITY DEFINER" comment
        // doesn't itself trigger the rule.
        String stripped = content.replaceAll("(?m)--.*$", "");
        // Strip block comments.
        stripped = stripped.replaceAll("(?s)/\\*.*?\\*/", "");

        Matcher m = SECURITY_DEFINER.matcher(stripped);
        while (m.find()) {
            violations.add(name + " — contains SECURITY DEFINER at offset " + m.start());
        }
    }
}
