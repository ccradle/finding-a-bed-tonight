import { test, expect } from '@playwright/test';
import {
  loginAsLockoutTestUser,
  PLATFORM_LOCKOUT_TARGET_EMAIL,
  PLATFORM_LOCKOUT_TARGET_PASSWORD,
  PLATFORM_LOCKOUT_TARGET_USER_ID,
} from '../helpers/auth/platform-operator';
import {
  generateFutureWrongTotp,
  generateTotp,
  PLATFORM_BOOTSTRAP_TOTP_SECRET,
} from '../helpers/auth/totp-helper';

/**
 * Phase G-4.4 §5.13 — pin the END-TO-END contract that 5 wrong TOTP
 * verifications lock a `platform_user`, that a 6th attempt with the
 * CORRECT code is still rejected (lockout supersedes valid TOTP), and
 * that the test-only unlock endpoint clears the lockout for subsequent
 * runs.
 *
 * <p><b>Isolation strategy.</b> This spec deliberately exhausts the
 * per-account 5-fail counter and locks the target row. To stay
 * parallel-safe with the access-log spec (which targets the bootstrap
 * row at id `0fab`), this spec targets a SECOND seeded platform_user
 * at id `0fa2` (`platform-lockout@dev.fabt.org`). Both rows are
 * provisioned by `infra/scripts/seed-data.sql`; they share password +
 * TOTP secret but their lockout counters are independent. F17 tracks
 * full N-way isolation via per-call UUIDs as a G-4.5 follow-up.
 *
 * <p><b>Wrong-code strategy.</b> Each attempt mints a code at
 * counter+10..14 windows (5..7 minutes in the future), well past the
 * server's ±1 acceptance band. Zero collision probability with the
 * current valid window — see {@link generateFutureWrongTotp} docstring.
 *
 * <p><b>Cleanup.</b> The {@code afterAll} hook calls the dev-profile-
 * gated `POST /api/v1/test/platform/unlock-expired?windowMin=0` to
 * clear the lockout immediately, even though the lockout-target row
 * is isolated from the bootstrap. Required for re-runs (CI retries +
 * local iteration) so the spec's first attempt of a fresh run starts
 * with `account_locked = false`.
 *
 * <p><b>Out of scope</b> (covered elsewhere):
 * <ul>
 *   <li>Per-IP password-attempt throttling on `/login` — Bucket4j
 *       layer; tested by `PlatformAuthIntegrationTest`.</li>
 *   <li>The `PLATFORM_USER_LOCKED_OUT` audit_event row content —
 *       covered by `PlatformAuthServiceTest` IT.</li>
 *   <li>Backup-code-based recovery flow — separate slice; not in
 *       v0.53 scope.</li>
 * </ul>
 */
test.describe.configure({ mode: 'serial' });

test.describe('Platform TOTP lockout — end-to-end (G-4.4 §5.13)', () => {
  const apiUrl = process.env.API_URL || 'http://localhost:8080';

  test.afterAll(async ({ request }) => {
    // Cleanup: unlock any rows the spec's wrong-code attempts may have
    // locked, including the lockout-target row. windowMin=0 means
    // "unlock everything currently locked, regardless of how recently."
    // Idempotent — if no rows are locked, returns {"unlocked": 0}.
    const resp = await request.post(
      `${apiUrl}/api/v1/test/platform/unlock-expired?windowMin=0`
    );
    if (!resp.ok()) {
      // eslint-disable-next-line no-console
      console.error(
        `lockout afterAll: unlock-expired failed status=${resp.status()} ` +
          `body=${await resp.text()} — next run starts polluted`
      );
    }
    expect(
      resp.ok(),
      `Unlock-expired cleanup failed status=${resp.status()} — next ` +
        `lockout-spec run will see account_locked=true`
    ).toBeTruthy();
    // Diagnostic-only: surface zero-count silently-failing unlock. We don't
    // FAIL on zero (a passing prior run + clean re-run is a legit zero), but
    // a zero count after a run that should have locked a row hints at a
    // cleanup-contract regression. Logged as a warning for the operator
    // reviewing CI output.
    try {
      const body = await resp.json();
      if (typeof body.unlocked === 'number' && body.unlocked === 0) {
        // eslint-disable-next-line no-console
        console.warn(
          'lockout afterAll: unlock-expired returned {unlocked: 0}. If the ' +
            'spec actually locked a row this run, the SECURITY DEFINER '
            + 'function may have a windowMin comparison bug. Diagnostic only.'
        );
      }
    } catch {
      // Body parse error — ignore; afterAll already asserted resp.ok().
    }
  });

  test('happy path baseline: lockout-target user can log in before the test', async ({
    request,
  }) => {
    // Sanity: confirm the target row is unlocked + credentials are
    // correct before we start exhausting attempts. If this fails, the
    // afterAll cleanup from a prior run did not execute, OR the seed
    // did not run, OR the lockout-target row was deleted. Diagnostic
    // ordering matters: this test fails BEFORE the wrong-code attempts
    // pollute state further.
    const session = await loginAsLockoutTestUser(request);
    expect(session.platformUserId).toBe(PLATFORM_LOCKOUT_TARGET_USER_ID);
  });

  test('5 wrong TOTP codes lock the account', async ({ request }) => {
    // Acquire 5 fresh mfa-verify-scoped tokens (one per attempt, since
    // each /login resets the verify token to a new value) and submit a
    // distinct future-window wrong code with each. After the 5th
    // failed verify, the account_locked counter trips to true.
    for (let i = 0; i < 5; i++) {
      // Step 1 — login to get the verify-scoped token.
      const loginResp = await request.post(
        `${apiUrl}/api/v1/auth/platform/login`,
        {
          data: {
            email: PLATFORM_LOCKOUT_TARGET_EMAIL,
            password: PLATFORM_LOCKOUT_TARGET_PASSWORD,
          },
        }
      );
      expect(
        loginResp.ok(),
        `Wrong-code attempt ${i + 1}: /login should still succeed (account ` +
          `not yet locked, password correct). Got status=${loginResp.status()}.`
      ).toBeTruthy();
      const verifyToken = (await loginResp.json()).token;

      // Step 2 — submit a future-window wrong code (counter+10+i).
      const wrongCode = generateFutureWrongTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET, i);
      const verifyResp = await request.post(
        `${apiUrl}/api/v1/auth/platform/login/mfa-verify`,
        {
          headers: { Authorization: `Bearer ${verifyToken}` },
          data: { code: wrongCode },
        }
      );
      expect(
        verifyResp.status(),
        `Wrong-code attempt ${i + 1} must be rejected (401 invalid_mfa_code).`
      ).toBe(401);
    }
  });

  test('after 5 wrong codes, even a CORRECT code is rejected', async ({ request }) => {
    // Account should now be locked. Validate the contract: a 6th
    // login attempt either (a) returns 401 from /login itself
    // (account-locked path on the password check), OR (b) succeeds
    // at /login but rejects /login/mfa-verify even with a fresh,
    // valid TOTP code. Either is acceptable — both prove that
    // lockout supersedes valid credentials. The exact branch depends
    // on whether PlatformAuthService.login returns REJECTED on locked
    // accounts before issuing the verify-scoped token (current impl
    // does — V88 lockout state checked in login()).
    const loginResp = await request.post(
      `${apiUrl}/api/v1/auth/platform/login`,
      {
        data: {
          email: PLATFORM_LOCKOUT_TARGET_EMAIL,
          password: PLATFORM_LOCKOUT_TARGET_PASSWORD,
        },
      }
    );

    if (loginResp.status() === 401) {
      // Locked-on-/login path — no verify-scoped token issued.
      const body = await loginResp.json();
      expect(body.error, 'expected invalid_credentials per Decision 5')
        .toBe('invalid_credentials');
      // Positive proof the row is actually locked (vs e.g. anonymized,
      // disabled, or hit by a /login service-layer regression that 401s
      // for unrelated reasons). The cleanup endpoint reports how many
      // rows it unlocked; if the count is zero, the prior 5-attempt
      // test failed to lock anything and this test passed for the
      // wrong reason.
      const unlockResp = await request.post(
        `${apiUrl}/api/v1/test/platform/unlock-expired?windowMin=0`
      );
      expect(unlockResp.ok(),
        `unlock-expired probe failed status=${unlockResp.status()}`).toBeTruthy();
      const unlockBody = await unlockResp.json();
      expect(
        unlockBody.unlocked,
        'expected unlock-expired to unlock at least the lockout-target row '
          + '— if zero, the 5-wrong-codes test did not actually lock anything '
          + 'and this 6th-attempt 401 came from a different code path'
      ).toBeGreaterThanOrEqual(1);
      // We just unlocked the row to prove it was locked. Re-lock it with
      // 5 more wrong attempts so the afterAll cleanup is still operating
      // on a locked row (preserves test 4's pre-condition without making
      // it temporally coupled).
      for (let i = 0; i < 5; i++) {
        const reLockLogin = await request.post(
          `${apiUrl}/api/v1/auth/platform/login`,
          {
            data: {
              email: PLATFORM_LOCKOUT_TARGET_EMAIL,
              password: PLATFORM_LOCKOUT_TARGET_PASSWORD,
            },
          }
        );
        if (!reLockLogin.ok()) break;
        const verifyToken = (await reLockLogin.json()).token;
        await request.post(
          `${apiUrl}/api/v1/auth/platform/login/mfa-verify`,
          {
            headers: { Authorization: `Bearer ${verifyToken}` },
            data: { code: generateFutureWrongTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET, 5 + i) },
          }
        );
      }
      return;
    }

    // Locked-on-/verify path — login returns a verify token but
    // verify rejects even with a fresh valid code.
    expect(loginResp.ok(), 'login should issue verify token or 401').toBeTruthy();
    const verifyToken = (await loginResp.json()).token;
    const correctCode = generateTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET);
    const verifyResp = await request.post(
      `${apiUrl}/api/v1/auth/platform/login/mfa-verify`,
      {
        headers: { Authorization: `Bearer ${verifyToken}` },
        data: { code: correctCode },
      }
    );
    expect(
      verifyResp.status(),
      'CORRECT code must still be rejected when account is locked'
    ).toBe(401);
  });

  test('test-only unlock-expired endpoint clears the lockout (self-contained)', async ({ request }) => {
    // Pin the cleanup contract independent of the prior tests' state.
    // Sequence: clear any inherited state → lock the target → unlock →
    // verify unlocked. This makes the test's pre-condition self-owned,
    // so a regression in tests 2 or 3 doesn't muddy the diagnostic.

    // Step A — start clean. Idempotent unlock; if previous tests didn't
    // actually lock anything (regression), this is a no-op.
    await request.post(`${apiUrl}/api/v1/test/platform/unlock-expired?windowMin=0`);

    // Step B — lock the target with 5 fresh wrong attempts.
    for (let i = 0; i < 5; i++) {
      const loginResp = await request.post(
        `${apiUrl}/api/v1/auth/platform/login`,
        {
          data: {
            email: PLATFORM_LOCKOUT_TARGET_EMAIL,
            password: PLATFORM_LOCKOUT_TARGET_PASSWORD,
          },
        }
      );
      if (!loginResp.ok()) break;  // already locked from a prior /login probe
      const verifyToken = (await loginResp.json()).token;
      await request.post(
        `${apiUrl}/api/v1/auth/platform/login/mfa-verify`,
        {
          headers: { Authorization: `Bearer ${verifyToken}` },
          data: { code: generateFutureWrongTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET, 20 + i) },
        }
      );
    }

    // Step C — invoke the unlock contract under test. The `unlocked >= 1`
    // count IS the proof: the function reports how many rows it unlocked,
    // so a non-zero count for a single-row spec means the lockout-target
    // row went from `account_locked=true` to `account_locked=false` in
    // the same UPDATE statement. We deliberately do NOT do a re-login
    // here as a "second proof" — V88's TOTP replay protection
    // (last_totp_code, 89s window) would reject a second successful
    // login from the same TOTP window as test 1's sanity baseline,
    // producing a 401 invalid_mfa_code that masks an unrelated layer.
    // If you need a stronger proof in the future, query account_locked
    // via a test-only DB readback endpoint rather than re-driving the
    // login flow.
    const resp = await request.post(
      `${apiUrl}/api/v1/test/platform/unlock-expired?windowMin=0`
    );
    expect(resp.ok(), `unlock-expired status=${resp.status()}`).toBeTruthy();
    const body = await resp.json();
    expect(typeof body.unlocked).toBe('number');
    expect(
      body.unlocked,
      'expected at least the lockout-target row (id 0fa2) to be unlocked '
        + 'by Step C — if zero, Step B did not lock anything OR the unlock '
        + 'function has a windowMin=0 boundary bug'
    ).toBeGreaterThanOrEqual(1);
  });
});
