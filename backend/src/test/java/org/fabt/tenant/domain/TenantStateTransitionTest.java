package org.fabt.tenant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Full-matrix unit test for the {@link TenantState} FSM. Every (from, to) pair must
 * either be in the allowed set (design §D8) or throw {@link IllegalStateTransitionException}.
 *
 * <p>A regression that relaxes the matrix (e.g. allowing {@code ARCHIVED -> ACTIVE})
 * would silently land without this test — §D8 is the only guard between a hard-deleted
 * tenant and an attacker who wants to re-activate it.</p>
 */
class TenantStateTransitionTest {

    private static final Set<Pair> ALLOWED = new HashSet<>();

    static {
        ALLOWED.add(Pair.of(TenantState.ACTIVE, TenantState.SUSPENDED));
        ALLOWED.add(Pair.of(TenantState.ACTIVE, TenantState.OFFBOARDING));
        ALLOWED.add(Pair.of(TenantState.SUSPENDED, TenantState.ACTIVE));
        ALLOWED.add(Pair.of(TenantState.SUSPENDED, TenantState.OFFBOARDING));
        ALLOWED.add(Pair.of(TenantState.OFFBOARDING, TenantState.ARCHIVED));
        ALLOWED.add(Pair.of(TenantState.ARCHIVED, TenantState.DELETED));
    }

    @ParameterizedTest(name = "{0} -> (each other state)")
    @EnumSource(TenantState.class)
    @DisplayName("every (from, to) pair matches the §D8 matrix")
    void matrix(TenantState from) {
        for (TenantState to : TenantState.values()) {
            if (ALLOWED.contains(Pair.of(from, to))) {
                assertThat(from.canTransitionTo(to))
                    .as("%s -> %s should be allowed", from, to)
                    .isTrue();
                // No throw on assertTransition
                TenantState.assertTransition(from, to);
            } else {
                assertThat(from.canTransitionTo(to))
                    .as("%s -> %s should be disallowed", from, to)
                    .isFalse();
                assertThatThrownBy(() -> TenantState.assertTransition(from, to))
                    .as("%s -> %s should throw", from, to)
                    .isInstanceOf(IllegalStateTransitionException.class);
            }
        }
    }

    @Test
    @DisplayName("exactly 6 allowed pairs across the whole matrix (§D8 invariant)")
    void allowedMatrixIsExactlyTheSixDesignD8Pairs() {
        // Defense against lockstep edits: if someone relaxes TenantState AND
        // the ALLOWED set in this test file together (e.g. adding ARCHIVED ->
        // ACTIVE in both), the parameterized matrix test still passes because
        // both sides "agree." This assertion counts allowed pairs directly
        // from the enum — no shared fixture — so it fails when the matrix
        // grows or shrinks from the §D8-specified 6.
        int allowedCount = 0;
        for (TenantState from : TenantState.values()) {
            for (TenantState to : TenantState.values()) {
                if (from.canTransitionTo(to)) {
                    allowedCount++;
                }
            }
        }
        assertThat(allowedCount)
            .as("§D8 specifies exactly 6 allowed transitions: "
                + "ACTIVE->SUSPENDED, ACTIVE->OFFBOARDING, SUSPENDED->ACTIVE, "
                + "SUSPENDED->OFFBOARDING, OFFBOARDING->ARCHIVED, ARCHIVED->DELETED")
            .isEqualTo(6);
    }

    @Test
    @DisplayName("DELETED is terminal (no outgoing transitions)")
    void deletedIsTerminal() {
        assertThat(TenantState.DELETED.allowedNext()).isEmpty();
    }

    @Test
    @DisplayName("ARCHIVED cannot return to ACTIVE (§D8: re-onboarding is a fresh create)")
    void archivedCannotRevive() {
        assertThatThrownBy(() -> TenantState.assertTransition(TenantState.ARCHIVED, TenantState.ACTIVE))
            .isInstanceOf(IllegalStateTransitionException.class)
            .hasMessageContaining("ARCHIVED")
            .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("self-transition always throws (idempotency is caller's concern)")
    void selfTransitionThrows() {
        for (TenantState s : TenantState.values()) {
            assertThatThrownBy(() -> TenantState.assertTransition(s, s))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("self-transition");
        }
    }

    @Test
    @DisplayName("null args throw IllegalArgumentException (not IllegalStateTransitionException)")
    void nullArgsRejected() {
        assertThatThrownBy(() -> TenantState.assertTransition(null, TenantState.ACTIVE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantState.assertTransition(TenantState.ACTIVE, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ACTIVE has exactly two successors: SUSPENDED + OFFBOARDING")
    void activeSuccessors() {
        assertThat(TenantState.ACTIVE.allowedNext())
            .containsExactlyInAnyOrder(TenantState.SUSPENDED, TenantState.OFFBOARDING);
    }

    @Test
    @DisplayName("SUSPENDED has exactly two successors: ACTIVE + OFFBOARDING")
    void suspendedSuccessors() {
        assertThat(TenantState.SUSPENDED.allowedNext())
            .containsExactlyInAnyOrder(TenantState.ACTIVE, TenantState.OFFBOARDING);
    }

    @Test
    @DisplayName("OFFBOARDING has exactly one successor: ARCHIVED")
    void offboardingSuccessors() {
        assertThat(TenantState.OFFBOARDING.allowedNext())
            .containsExactly(TenantState.ARCHIVED);
    }

    @Test
    @DisplayName("ARCHIVED has exactly one successor: DELETED")
    void archivedSuccessors() {
        assertThat(TenantState.ARCHIVED.allowedNext())
            .containsExactly(TenantState.DELETED);
    }

    private record Pair(TenantState from, TenantState to) {
        static Pair of(TenantState from, TenantState to) {
            return new Pair(from, to);
        }
    }
}
