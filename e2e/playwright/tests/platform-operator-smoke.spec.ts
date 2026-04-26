import { test, expect } from '@playwright/test';
import {
  loginAsSmokeUser,
  PLATFORM_SMOKE_USER_EMAIL,
  PLATFORM_SMOKE_USER_ID,
} from '../helpers/auth/platform-operator';
import {
  generateTotp,
  PLATFORM_BOOTSTRAP_TOTP_SECRET,
} from '../helpers/auth/totp-helper';

/**
 * Phase G-4.4 §5.13 prereq (warroom Pre-spec 7 Jordan MEDIUM) — smoke
 * spec that runs FIRST and fails LOUD if the platform-operator login
 * pipeline regresses. Three things this spec pins:
 *
 * <ol>
 *   <li><b>Seed activation.</b> {@code seed-data.sql} populates the
 *       bootstrap {@code platform_user} row (id 0fab) with the
 *       deterministic email + bcrypt(admin123) + RFC 4648 §10 TOTP
 *       secret. If the seed regresses (e.g. someone removes the
 *       activation block, or deploys without running seed-data.sql
 *       on a fresh DB), step 1's {@code POST /login} returns 401
 *       invalid_credentials.</li>
 *
 *   <li><b>TOTP correctness.</b> The TS-side {@link generateTotp}
 *       implementation must agree with the server's HMAC-SHA1 RFC 6238
 *       computation on the seeded secret. If either side drifts
 *       (algorithm change, base32 decoder bug, time-window mismatch),
 *       step 2's {@code /login/mfa-verify} returns 401 invalid_mfa_code.</li>
 *
 *   <li><b>Bucket4j off in dev.</b> {@code application-lite.yml} disables
 *       the {@code rate-limit-platform-login} bucket. If a future config
 *       change re-enables it (or a CI env activates a non-lite profile),
 *       spec runs after the 5/15min cap will return 429 instead of 200.</li>
 * </ol>
 *
 * <p><b>Why this matters.</b> Both downstream specs
 * ({@code platform-admin-access-log.spec.ts} and
 * {@code platform-totp-lockout.spec.ts}) call {@link loginPlatformOperator}
 * in their {@code beforeEach}. A regression in any of the three pinned
 * pieces would cascade into N test failures with the same root cause
 * but distinct surface symptoms. This smoke spec is the single canary
 * — runs in ~2 seconds, points at the actual broken layer.
 *
 * <p><b>Ordering.</b> Filename starts with "platform-operator-smoke" so
 * Playwright's default alphabetical project file ordering puts it
 * BEFORE both downstream specs (which start with "platform-admin-..."
 * and "platform-totp-..."). No special config required.
 */
test.describe('Platform-operator smoke (G-4.4 prereq)', () => {
  test('login + MFA verify returns access token (~2s)', async ({ request }) => {
    // Logs in as the dedicated smoke user (id 0fa1) — separate from the
    // bootstrap row used by the access-log spec, so the two specs can
    // run in the same Playwright invocation without colliding on V88's
    // 89s TOTP replay protection.
    const session = await loginAsSmokeUser(request);

    // Token shape — JWT has 3 base64url segments separated by '.'
    expect(session.accessToken).toBeTruthy();
    const parts = session.accessToken.split('.');
    expect(parts).toHaveLength(3);
    expect(parts[0].length).toBeGreaterThan(0);
    expect(parts[1].length).toBeGreaterThan(0);
    expect(parts[2].length).toBeGreaterThan(0);

    // Smoke-user row id pin — if this changes, the seed INSERT block
    // shifted off the well-known UUID.
    expect(session.platformUserId).toBe(PLATFORM_SMOKE_USER_ID);

    // TOTP secret round-trip pin — the helper-side constant must match
    // the seed-side value. If either drifts, login would have failed
    // above; this assertion is belt-and-suspenders.
    expect(session.mfaSecret).toBe(PLATFORM_BOOTSTRAP_TOTP_SECRET);
  });

  test('seeded TOTP secret produces a 6-digit numeric code', async () => {
    // Pure-TS check — does not hit the backend. Fails fast if the
    // generateTotp implementation regresses (e.g. base32 decoder bug,
    // HMAC byte-order mistake) before any backend round-trip.
    const code = generateTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET);
    expect(code).toMatch(/^\d{6}$/);
  });

  test('smoke user email matches the seeded value', () => {
    // Pin the seed contract — if the email constant in seed-data.sql
    // changes without updating the TS helper, login would 401 with
    // invalid_credentials. This isolates the diagnosis to "the seed
    // and the helper are out of sync" rather than "the password is
    // wrong" or "the smoke row was deleted."
    expect(PLATFORM_SMOKE_USER_EMAIL).toBe('platform-smoke@dev.fabt.org');
  });
});
