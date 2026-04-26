import { APIRequestContext, expect } from '@playwright/test';
import { generateTotp, PLATFORM_BOOTSTRAP_TOTP_SECRET } from './totp-helper';

/**
 * Phase G-4.4 §5.10 — helpers for minting a platform-operator JWT and
 * driving `@PlatformAdminOnly` endpoints from Playwright tests.
 *
 * <p>There is no platform-operator UI in v0.53 — all platform admin
 * actions are backend API surface only — so we expose helper functions
 * rather than a {@link Page} fixture. Tests that need a logged-in
 * platform operator pull these into their `test.beforeEach` and use
 * the returned token + helper to call `@PlatformAdminOnly` endpoints
 * directly.
 *
 * <p>Login flow (matches `PlatformAuthController`):
 * <ol>
 *   <li>POST `/api/v1/auth/platform/login` with email + password →
 *       receives a 5-minute MFA-verify-scoped token (since the seeded
 *       bootstrap user has `mfa_enabled = true`).</li>
 *   <li>POST `/api/v1/auth/platform/login/mfa-verify` with the
 *       MFA-verify token + a fresh TOTP code → receives the actual
 *       15-minute access token (with `mfaVerified=true`).</li>
 * </ol>
 *
 * <p>Justification: the access token alone is NOT enough to call a
 * `@PlatformAdminOnly` endpoint. Every such call also needs the
 * `X-Platform-Justification` header (≥10 chars after trim, ASCII).
 * {@link platformAdminFetch} bundles both into one helper.
 */

const API_URL = process.env.API_URL || 'http://localhost:8080';

/**
 * Default credentials for the dev-seeded bootstrap `platform_user`
 * (id `0fab`). Provisioned by `infra/scripts/seed-data.sql`. NOT a
 * production credential.
 */
export const PLATFORM_OPERATOR_EMAIL = 'platform-ops@dev.fabt.org';
export const PLATFORM_OPERATOR_PASSWORD = 'admin123';

/**
 * The token + secret bundle returned from {@link loginPlatformOperator}.
 * Holds the post-MFA access token for backend calls + the same secret
 * used to mint the verify code, so tests that need to mint additional
 * codes (e.g. step-up flows) don't have to re-derive.
 */
export interface PlatformOperatorSession {
  /** 15-min access token; MFA_VERIFIED authority granted. */
  accessToken: string;
  /** Base32 TOTP secret, same as `PLATFORM_BOOTSTRAP_TOTP_SECRET`. */
  mfaSecret: string;
  /** Platform user UUID (id `0fab` for the bootstrap row). */
  platformUserId: string;
}

/**
 * Drive the full platform login → MFA-verify flow against the dev
 * backend. Returns a session bundle the caller can plug into
 * {@link platformAdminFetch}.
 *
 * <p>Assumes the bootstrap `platform_user` row has been activated via
 * `seed-data.sql`. If the seed has not run, the login call returns
 * 401 invalid_credentials and this helper throws.
 *
 * @param request Playwright APIRequestContext (typically `page.request`)
 */
export async function loginPlatformOperator(
  request: APIRequestContext
): Promise<PlatformOperatorSession> {
  // Step 1 — initial login. Bootstrap user has mfa_enabled=true so the
  // server returns scope=mfa-verify-required.
  const loginResp = await request.post(`${API_URL}/api/v1/auth/platform/login`, {
    data: {
      email: PLATFORM_OPERATOR_EMAIL,
      password: PLATFORM_OPERATOR_PASSWORD,
    },
  });
  // Failure-message hygiene (Riley): keep the assertion message status-only
  // so the body doesn't get inlined into CI failure logs verbatim. On a real
  // failure we log the body via console.error so debugging is still possible
  // without the body landing in the final assertion message.
  if (!loginResp.ok()) {
    // eslint-disable-next-line no-console
    console.error(
      `loginPlatformOperator: /login failed status=${loginResp.status()} body=${await loginResp.text()}`
    );
  }
  expect(
    loginResp.ok(),
    `Platform login failed status=${loginResp.status()} (see console.error for body)`
  ).toBeTruthy();
  const loginBody = await loginResp.json();
  expect(loginBody.scope, `Expected mfa-verify scope; got ${loginBody.scope}`)
    .toBe('mfa-verify');
  const verifyToken: string = loginBody.token;

  // Step 2 — verify TOTP. Mint a fresh code from the seeded secret.
  const code = generateTotp(PLATFORM_BOOTSTRAP_TOTP_SECRET);
  const verifyResp = await request.post(
    `${API_URL}/api/v1/auth/platform/login/mfa-verify`,
    {
      headers: { Authorization: `Bearer ${verifyToken}` },
      data: { code },
    }
  );
  if (!verifyResp.ok()) {
    // eslint-disable-next-line no-console
    console.error(
      `loginPlatformOperator: /login/mfa-verify failed status=${verifyResp.status()} body=${await verifyResp.text()}`
    );
  }
  expect(
    verifyResp.ok(),
    `Platform MFA verify failed status=${verifyResp.status()} (see console.error for body)`
  ).toBeTruthy();
  const verifyBody = await verifyResp.json();

  return {
    accessToken: verifyBody.token,
    mfaSecret: PLATFORM_BOOTSTRAP_TOTP_SECRET,
    platformUserId: '00000000-0000-0000-0000-000000000fab',
  };
}

/**
 * Convenience wrapper for calling a `@PlatformAdminOnly` endpoint with
 * the bearer + justification headers pre-populated.
 *
 * @param request Playwright APIRequestContext
 * @param session result from {@link loginPlatformOperator}
 * @param method HTTP method
 * @param path full URL path (e.g. `/api/v1/batch/jobs/{date}`)
 * @param justification ≥10-char ASCII reason string; lands verbatim in
 *        the platform_admin_access_log row
 * @param body optional JSON body
 */
export async function platformAdminFetch(
  request: APIRequestContext,
  session: PlatformOperatorSession,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  justification: string,
  body?: unknown
) {
  const url = path.startsWith('http') ? path : `${API_URL}${path}`;
  const headers = {
    Authorization: `Bearer ${session.accessToken}`,
    'X-Platform-Justification': justification,
    'Content-Type': 'application/json',
  };
  switch (method) {
    case 'GET':
      return request.get(url, { headers });
    case 'POST':
      return request.post(url, { headers, data: body });
    case 'PUT':
      return request.put(url, { headers, data: body });
    case 'PATCH':
      return request.patch(url, { headers, data: body });
    case 'DELETE':
      return request.delete(url, { headers, data: body });
  }
}
