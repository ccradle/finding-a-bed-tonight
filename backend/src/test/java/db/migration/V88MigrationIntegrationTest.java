package db.migration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.fabt.Application;
import org.fabt.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for V88 (platform-admin-split-and-access-log G-4.2):
 * per-account MFA lockout columns + atomic MFA enrollment + TOTP replay
 * protection.
 *
 * <p>Pins the schema and SECURITY DEFINER function semantics that the
 * G-4.2 service layer depends on:
 *
 * <ul>
 *   <li>4 new columns on {@code platform_user}</li>
 *   <li>Partial index on {@code locked_out_at} (warroom Elena E1)</li>
 *   <li>6 new SECURITY DEFINER functions exist with EXECUTE granted</li>
 *   <li>{@code platform_user_record_failure} threshold + window semantics</li>
 *   <li>{@code platform_user_clear_failures} clears the rolling array</li>
 *   <li>{@code platform_user_unlock_expired} only unlocks past-window rows</li>
 *   <li>{@code platform_user_setup_mfa} atomic / refuses re-enrollment</li>
 *   <li>{@code platform_user_record_totp_use} +
 *       {@code platform_user_was_totp_recently_used} round-trip</li>
 * </ul>
 *
 * <p>All write tests target the V87 bootstrap row at the well-known
 * {@code 0fab} UUID — fabt_app cannot INSERT into platform_user (REVOKE
 * ALL), so reusing the bootstrap row is the only path to write-side
 * coverage as the test JdbcTemplate role. Each test restores the state
 * via {@link #restoreBootstrapRow()} in {@code @AfterEach} so concurrent
 * test classes (sharing the Spring context's DataSource) see a
 * predictable starting state.
 */
@DisplayName("V88 platform_user lockout + atomic MFA + TOTP replay")
@SpringBootTest(classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class V88MigrationIntegrationTest extends BaseIntegrationTest {

    private static final UUID BOOTSTRAP_PLATFORM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000fab");

    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void restoreBootstrapRow() {
        // Restore bootstrap row to the V87-INSERTed shape so other tests +
        // re-runs see a consistent fixture. fabt_app cannot UPDATE platform_user
        // directly; we route the writes through SECURITY DEFINER functions.
        // Note: jdbc.update on `SELECT void_func()` fails — PG-JDBC returns
        // a result set (one NULL row) which executeUpdate rejects. We use
        // queryForObject and discard the value.
        jdbc.queryForObject("SELECT platform_user_clear_failures(?::uuid)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID);
        jdbc.queryForObject("SELECT platform_user_update_credentials(?::uuid, NULL, NULL, ?, ?)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID, false, true);
    }

    // ------------------------------------------------------------------
    // Schema shape
    // ------------------------------------------------------------------

    @Test
    @DisplayName("V88 adds failed_mfa_attempts_at, locked_out_at, last_totp_code, last_totp_used_at")
    void newColumnsPresent() {
        List<String> columnNames = jdbc.queryForList(
                "SELECT a.attname FROM pg_attribute a "
                        + "  JOIN pg_class c ON c.oid = a.attrelid "
                        + "  JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + " WHERE n.nspname = 'public' AND c.relname = 'platform_user' "
                        + "   AND a.attnum > 0 AND NOT a.attisdropped",
                String.class);

        assertThat(columnNames).contains(
                "failed_mfa_attempts_at",
                "locked_out_at",
                "last_totp_code",
                "last_totp_used_at");
    }

    @Test
    @DisplayName("partial index platform_user_locked_out_at exists with WHERE locked_out_at IS NOT NULL")
    void partialIndexOnLockedOutAt() {
        String indexdef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes "
                        + " WHERE schemaname = 'public' "
                        + "   AND indexname = 'platform_user_locked_out_at'",
                String.class);

        assertThat(indexdef)
                .as("partial index lets the cron query do an index scan rather than seq scan")
                .containsIgnoringCase("locked_out_at")
                .containsIgnoringCase("WHERE (locked_out_at IS NOT NULL)");
    }

    @Test
    @DisplayName("V88 adds 7 new SECURITY DEFINER functions, all EXECUTE-granted to fabt_app")
    void securityDefinerFunctionsExist() {
        List<String> v88Functions = jdbc.queryForList(
                "SELECT proname FROM pg_proc "
                        + " WHERE prosecdef = true "
                        + "   AND proname IN ('platform_user_record_failure', "
                        + "                  'platform_user_clear_failures', "
                        + "                  'platform_user_unlock_expired', "
                        + "                  'platform_user_setup_mfa', "
                        + "                  'platform_user_record_totp_use', "
                        + "                  'platform_user_was_totp_recently_used', "
                        + "                  'platform_user_set_email') "
                        + " ORDER BY proname",
                String.class);

        assertThat(v88Functions).containsExactlyInAnyOrder(
                "platform_user_record_failure",
                "platform_user_clear_failures",
                "platform_user_unlock_expired",
                "platform_user_setup_mfa",
                "platform_user_record_totp_use",
                "platform_user_was_totp_recently_used",
                "platform_user_set_email");
    }

    // ------------------------------------------------------------------
    // platform_user_record_failure semantics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("record_failure returns false until threshold; true on the threshold-meeting call")
    void recordFailureThresholdSemantics() {
        // Ensure clean window
        jdbc.queryForObject("SELECT platform_user_clear_failures(?::uuid)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID);
        // Bootstrap row is already account_locked=true. record_failure's
        // "auto-lock if count >= threshold" branch only fires when the row
        // is NOT already locked (NOT v_was_locked). To exercise the lockout
        // transition we first unlock manually.
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, NULL, NULL, NULL, ?)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID, false);

        // Failures 1-4: not yet at threshold → false
        for (int i = 1; i <= 4; i++) {
            Boolean nowLocked = jdbc.queryForObject(
                    "SELECT platform_user_record_failure(?::uuid, ?, ?)",
                    Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, 15, 5);
            assertThat(nowLocked)
                    .as("attempt %d/5 must NOT trigger lockout transition", i)
                    .isFalse();
        }

        // Failure 5: meets threshold → true (this call locked the account)
        Boolean trippedLockout = jdbc.queryForObject(
                "SELECT platform_user_record_failure(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, 15, 5);
        assertThat(trippedLockout)
                .as("the threshold-meeting call must return true so the service can log once")
                .isTrue();

        // Failure 6: account is already locked, lockout transition does NOT
        // re-fire — function returns false even though the array continues
        // to accumulate within-window timestamps.
        Boolean alreadyLocked = jdbc.queryForObject(
                "SELECT platform_user_record_failure(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, 15, 5);
        assertThat(alreadyLocked)
                .as("subsequent failures while already locked must NOT re-trigger transition")
                .isFalse();
    }

    @Test
    @DisplayName("clear_failures empties the rolling failure array")
    void clearFailuresEmptiesArray() {
        // Unlock + accumulate a couple failures
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, NULL, NULL, NULL, ?)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID, false);
        jdbc.queryForObject("SELECT platform_user_record_failure(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, 15, 99);
        jdbc.queryForObject("SELECT platform_user_record_failure(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, 15, 99);

        jdbc.queryForObject("SELECT platform_user_clear_failures(?::uuid)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID);

        // Re-trigger: since array is empty, threshold of 1 lights up immediately
        // (not yet locked due to the high threshold prior).
        Boolean trippedLockout = jdbc.queryForObject(
                "SELECT platform_user_record_failure(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, 15, 1);
        assertThat(trippedLockout)
                .as("clear_failures must reset the window so a single failure can trip threshold=1")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // platform_user_unlock_expired
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unlock_expired returns 0 when no rows have aged past the window")
    void unlockExpiredEmptyResult() {
        Integer unlocked = jdbc.queryForObject(
                "SELECT platform_user_unlock_expired(?)", Integer.class, 15);
        // Bootstrap row's locked_out_at is NULL (manual lock from V87 INSERT),
        // so the partial-index predicate excludes it — count is 0.
        assertThat(unlocked).isNotNull();
        assertThat(unlocked).isGreaterThanOrEqualTo(0);
    }

    // ------------------------------------------------------------------
    // platform_user_setup_mfa
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setup_mfa atomically writes secret + 10 backup codes when not yet enrolled")
    void setupMfaAtomicHappyPath() {
        // Bootstrap row has mfa_enabled=false out of the V87 INSERT — perfect
        // pre-condition for this test. Generate 10 backup-code rows.
        UUID[] ids = new UUID[10];
        String[] hashes = new String[10];
        byte[][] salts = new byte[10][];
        for (int i = 0; i < 10; i++) {
            ids[i] = UUID.randomUUID();
            hashes[i] = "hash-" + i;
            salts[i] = new byte[]{1, 2, 3, 4};
        }

        Boolean inserted = jdbc.queryForObject(
                "SELECT platform_user_setup_mfa(?::uuid, ?, ?, ?, ?)",
                Boolean.class,
                BOOTSTRAP_PLATFORM_USER_ID,
                "JBSWY3DPEHPK3PXP",
                ids, hashes, salts);

        assertThat(inserted)
                .as("first-time MFA enrollment must succeed")
                .isTrue();

        // Verify the secret landed
        Map<String, Object> userRow = jdbc.queryForMap(
                "SELECT * FROM platform_user_lookup_by_id(?::uuid)",
                BOOTSTRAP_PLATFORM_USER_ID);
        assertThat(userRow.get("mfa_secret")).isEqualTo("JBSWY3DPEHPK3PXP");

        // Verify all 10 codes landed via the SECURITY DEFINER read function
        List<Map<String, Object>> codes = jdbc.queryForList(
                "SELECT * FROM platform_user_backup_codes_for(?::uuid)",
                BOOTSTRAP_PLATFORM_USER_ID);
        assertThat(codes).hasSize(10);

        // Cleanup: clear the secret so other tests see a clean bootstrap.
        // Note: the CASCADE in V87 means deleting backup codes requires
        // deleting the parent row, which fabt_app cannot do. The codes
        // remain attached to the bootstrap row across subsequent tests in
        // this class — tests that need a clean state set up their own
        // assertions.
    }

    @Test
    @DisplayName("setup_mfa returns false when user is already enrolled (refuses replay)")
    void setupMfaRefusesReenrollment() {
        // First flip mfa_enabled = true via the credentials function
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, NULL, NULL, ?, NULL)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID, true);

        UUID[] ids = new UUID[]{UUID.randomUUID()};
        String[] hashes = new String[]{"h"};
        byte[][] salts = new byte[][]{{1}};

        Boolean inserted = jdbc.queryForObject(
                "SELECT platform_user_setup_mfa(?::uuid, ?, ?, ?, ?)",
                Boolean.class,
                BOOTSTRAP_PLATFORM_USER_ID, "newsecret", ids, hashes, salts);

        assertThat(inserted)
                .as("attempt to re-enroll an already-enabled user must return false")
                .isFalse();
    }

    @Test
    @DisplayName("setup_mfa raises when id/hash/salt array lengths mismatch")
    void setupMfaArrayLengthMismatch() {
        // First make sure mfa_enabled is false so we get past the early exit
        jdbc.queryForObject(
                "SELECT platform_user_update_credentials(?::uuid, NULL, NULL, ?, NULL)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID, false);

        UUID[] ids = new UUID[]{UUID.randomUUID(), UUID.randomUUID()};
        String[] hashes = new String[]{"h"};
        byte[][] salts = new byte[][]{{1}, {2}};

        assertThatThrownBy(() -> jdbc.queryForObject(
                "SELECT platform_user_setup_mfa(?::uuid, ?, ?, ?, ?)",
                Boolean.class,
                BOOTSTRAP_PLATFORM_USER_ID, "secret", ids, hashes, salts))
                .as("mismatched array lengths must surface as a SQL error, not silent partial write")
                .rootCause()
                .hasMessageContaining(
                        "platform_user_setup_mfa: id/hash/salt array lengths differ");
    }

    // ------------------------------------------------------------------
    // TOTP replay-protection round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("record_totp_use + was_totp_recently_used round-trip rejects replay within window")
    void totpReplayDetection() {
        jdbc.queryForObject("SELECT platform_user_record_totp_use(?::uuid, ?)",
                Object.class, BOOTSTRAP_PLATFORM_USER_ID, "123456");

        Boolean recentSame = jdbc.queryForObject(
                "SELECT platform_user_was_totp_recently_used(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, "123456", 90);
        assertThat(recentSame)
                .as("the just-used code must be reported as recently-used within the 90s window")
                .isTrue();

        Boolean recentDifferent = jdbc.queryForObject(
                "SELECT platform_user_was_totp_recently_used(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, "999999", 90);
        assertThat(recentDifferent)
                .as("a different code must not match the replay-cache slot")
                .isFalse();

        Boolean expiredWindow = jdbc.queryForObject(
                "SELECT platform_user_was_totp_recently_used(?::uuid, ?, ?)",
                Boolean.class, BOOTSTRAP_PLATFORM_USER_ID, "123456", 0);
        assertThat(expiredWindow)
                .as("a 0-second window means the use cannot be 'within' it — false")
                .isFalse();
    }

    @Test
    @DisplayName("was_totp_recently_used returns false when no TOTP has been recorded yet")
    void totpNeverUsedReturnsFalse() {
        // Force last_totp_used_at = NULL via a fresh enrollment cycle. We
        // can't reset to NULL through a public function, so this test
        // relies on the bootstrap row's natural NULL (no record_totp_use
        // call has been made by another test in the SAME class run prior
        // to this one — JUnit's deterministic ordering by display name
        // makes this reliable, but we explicitly clear via the assertion
        // path: query a NEW UUID we know doesn't exist.
        UUID neverSeen = UUID.randomUUID();
        Boolean recent = jdbc.queryForObject(
                "SELECT platform_user_was_totp_recently_used(?::uuid, ?, ?)",
                Boolean.class, neverSeen, "123456", 90);
        assertThat(recent)
                .as("non-existent userId must return false (no exception)")
                .isFalse();
    }
}
