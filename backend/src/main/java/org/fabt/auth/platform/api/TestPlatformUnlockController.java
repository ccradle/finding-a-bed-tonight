package org.fabt.auth.platform.api;

import java.util.Map;

import org.fabt.auth.platform.repository.PlatformUserRepository;
import org.fabt.shared.security.TenantUnscopedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint for unlocking platform_user rows that were locked by
 * the per-account TOTP-failure counter. Gated on {@code @Profile("dev |
 * test")} — never instantiated in lite, standard, full, or prod profiles.
 *
 * <p>Phase G-4.4 §5.13 prereq (warroom Pre-spec 2): the
 * {@code platform-totp-lockout.spec.ts} Playwright spec exhausts the
 * per-account 5-fail counter to prove the lockout contract. Without a
 * deterministic unlock path, the bootstrap row stays locked for 15
 * minutes (the {@link org.fabt.auth.platform.PlatformLockoutCronJob}
 * window), polluting every other spec that uses
 * {@code loginPlatformOperator()}. This endpoint exposes the cron's
 * {@link PlatformUserRepository#unlockExpired(int)} call with a
 * {@code windowMin=0} override so the spec's {@code afterEach} can
 * unlock immediately.
 *
 * <p>Two layers of dev/test gating:
 * <ol>
 *   <li>{@code @Profile("dev | test")} — Spring will not instantiate the
 *       bean in any other profile (matches {@code TestResetController}
 *       and {@code TestDataController} patterns).</li>
 *   <li>No authentication required: this endpoint deliberately does NOT
 *       require a platform JWT. Reasoning: the spec calling it has
 *       potentially LOCKED its own operator session by exhausting MFA
 *       attempts, so requiring an auth header would be circular. The
 *       profile gate is the security boundary; if this controller
 *       reaches a non-dev/test profile, that is the bug to fix —
 *       not adding redundant auth on top of a profile gate that should
 *       have prevented instantiation.</li>
 * </ol>
 *
 * <p>SecurityConfig pairing: the URL pattern {@code /api/v1/test/**}
 * already permits PLATFORM_OPERATOR + PLATFORM_ADMIN; this endpoint
 * lives under that umbrella but is publicly callable when the bean
 * exists (which is only in dev/test profiles by design).
 */
@RestController
@RequestMapping("/api/v1/test/platform")
@Profile("dev | test")
public class TestPlatformUnlockController {

    private static final Logger log = LoggerFactory.getLogger(TestPlatformUnlockController.class);

    private final PlatformUserRepository platformUserRepository;

    public TestPlatformUnlockController(PlatformUserRepository platformUserRepository) {
        this.platformUserRepository = platformUserRepository;
    }

    /**
     * Unlocks every {@code platform_user} row whose {@code locked_out_at}
     * is older than {@code windowMin} minutes. The cron job uses
     * {@code windowMin=15} in production; tests pass {@code windowMin=0}
     * to unlock immediately.
     *
     * @param windowMin minutes since {@code locked_out_at} that must have
     *                  elapsed for a row to be unlocked. {@code 0} =
     *                  unlock-now (test-only semantic).
     * @return JSON {"unlocked": N} where N is the count of rows unlocked
     */
    @TenantUnscopedQuery("test-only platform_user unlock; class is @Profile(\"dev | test\")-gated and unreachable in lite/standard/full/prod")
    @PostMapping("/unlock-expired")
    public ResponseEntity<Map<String, Integer>> unlockExpired(
            @RequestParam(defaultValue = "0") int windowMin) {
        int unlocked = platformUserRepository.unlockExpired(windowMin);
        log.warn("TEST UNLOCK: cleared {} locked platform_user row(s) (windowMin={})",
                unlocked, windowMin);
        return ResponseEntity.ok(Map.of("unlocked", unlocked));
    }
}
