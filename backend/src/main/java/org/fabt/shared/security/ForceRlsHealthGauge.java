package org.fabt.shared.security;

import java.sql.Array;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code pg_class.relforcerowsecurity} every 60 seconds for the seven
 * Phase B regulated tables and publishes one Micrometer gauge per table:
 * {@code fabt.rls.force_rls_enabled{table="..."}}.
 *
 * <p>Value is {@code 1} when the table has FORCE ROW LEVEL SECURITY enabled
 * (the Phase B invariant), {@code 0} otherwise. A drop to zero means
 * either a rogue migration cleared the flag or the panic rollback script
 * ({@code scripts/phase-b-rls-panic.sh}) ran — both must fire an alert.</p>
 *
 * <p>Implemented as {@link AtomicInteger}-backed gauges so Micrometer's
 * dimensional collection sees the update without re-registering on every
 * poll. The AtomicInteger instance is retained behind the gauge via the
 * {@link Gauge.Builder#register(MeterRegistry)} reference contract.</p>
 *
 * <h2>Why a poll, not a listener?</h2>
 * PostgreSQL does not emit DDL-change notifications natively; the gauge
 * would otherwise require {@code pg_event_trigger} + a separate
 * notification channel. A 60-second poll is cheaper and its window is
 * bounded: a rollback script that clears FORCE RLS is an incident — the
 * delay between flip and alert is acceptable within runbook RTO.
 */
@Component
public class ForceRlsHealthGauge {

    /**
     * The seven Phase B regulated tables. Matches
     * {@code docs/security/pg-policies-snapshot.md} Section 3.
     */
    private static final List<String> REGULATED_TABLES = List.of(
            "audit_events",
            "hmis_audit_log",
            "hmis_outbox",
            "kid_to_tenant_key",
            "one_time_access_code",
            "password_reset_token",
            "tenant_key_material"
    );

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicInteger> gaugeValues = new LinkedHashMap<>();

    public ForceRlsHealthGauge(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerGauges() {
        for (String table : REGULATED_TABLES) {
            AtomicInteger holder = new AtomicInteger(-1);
            gaugeValues.put(table, holder);
            Gauge.builder("fabt.rls.force_rls_enabled", holder, AtomicInteger::doubleValue)
                    .description("1 if the table has FORCE ROW LEVEL SECURITY enabled "
                            + "(Phase B invariant); 0 if the flag is cleared. -1 before the "
                            + "first successful poll.")
                    .tag("table", table)
                    .register(meterRegistry);
        }
    }

    /**
     * Refresh all gauges. Runs at fixed 60-second cadence; first run is at
     * startup + 15s to give Flyway time to apply migrations.
     */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 15_000L)
    public void poll() {
        // W-GAUGE-3 warroom fix (v0.45.0): build the table-name list as a
        // java.sql.Array via Connection.createArrayOf("text", ...) rather
        // than the legacy PostgreSQL array-literal string. The literal form
        // "{tbl1,tbl2}" breaks for any element containing a comma, brace,
        // backslash, or double quote — our current table names are safe
        // but the pattern is a foot-gun for future additions. The typed
        // Array round-trip also gives us the correct sqltype instead of
        // an implicit cast to text[].
        List<Map<String, Object>> rows = jdbcTemplate.execute((java.sql.Connection conn) -> {
            // java.sql.Array is NOT AutoCloseable in JDBC 4.3, so we free()
            // it manually in a finally block rather than try-with-resources.
            Array arr = conn.createArrayOf("text", REGULATED_TABLES.toArray(new String[0]));
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT c.relname AS table_name, c.relforcerowsecurity AS force_flag "
                            + "FROM pg_class c "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = 'public' "
                            + "AND c.relname = ANY(?)")) {
                ps.setArray(1, arr);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> out = new java.util.ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("table_name", rs.getString("table_name"));
                        row.put("force_flag", rs.getBoolean("force_flag"));
                        out.add(row);
                    }
                    return out;
                }
            } finally {
                try {
                    arr.free();
                } catch (java.sql.SQLException ignored) {
                    // free() may no-op on some drivers; we're past the useful point either way.
                }
            }
        });

        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("table_name");
            Boolean forceFlag = (Boolean) row.get("force_flag");
            AtomicInteger holder = gaugeValues.get(name);
            if (holder != null) {
                holder.set(Boolean.TRUE.equals(forceFlag) ? 1 : 0);
            }
        }
    }
}
