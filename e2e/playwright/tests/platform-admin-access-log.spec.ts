import { test, expect } from '@playwright/test';
import {
  loginPlatformOperator,
  platformAdminFetch,
  PlatformOperatorSession,
} from '../helpers/auth/platform-operator';

/**
 * Phase G-4.4 §5.12 — pin the END-TO-END contract that an authenticated
 * platform-operator request flowing through real Spring Security + the
 * JustificationValidationFilter + the `@PlatformAdminOnly` aspect
 * reaches the controller and returns a sane HTTP response. Covers the
 * stack the IT layer (`PlatformAdminAccessAspectTest`, 10 tests) cannot
 * exercise: the actual servlet filter chain, JWT-iss-routed dispatch,
 * MFA_VERIFIED authority binding, and method-level @PreAuthorize.
 *
 * <p>Trigger: `BatchJobController.run` (the G-4.3 canary endpoint). Job
 * id is intentionally bogus so the controller's own logic 400s — the
 * aspect commits PAL+AE rows BEFORE proceed() runs (Decision 11), so
 * the audit pair is written even on the controller's 400. The spec
 * doesn't read those rows back (covered by IT); it pins the HTTP
 * pipeline shape only.
 *
 * <p><b>Out of scope</b> (covered by `PlatformAdminAccessAspectTest`):
 * row-content correctness, before_state / after_state snapshots,
 * request_body_excerpt redaction, audit_event chain hash, F13's
 * after_state-always-NULL limitation. This spec runs through the real
 * HTTP stack; the IT layer runs in-process and validates contents.
 *
 * <p><b>Failure-mode locations:</b>
 * <ul>
 *   <li>Seed not run → `loginPlatformOperator()` throws on /login 401.
 *       Caught by the platform-operator-smoke spec first.</li>
 *   <li>Filter ordering regression → unauthenticated probe returns 400
 *       instead of expected 401. Caught by the
 *       `unauthenticatedWithoutJustificationRejectedAtSecurity` IT,
 *       not this spec.</li>
 *   <li>JWT-iss-routed dispatch broken → /batch returns 401 even with
 *       a valid platform JWT. Caught here.</li>
 * </ul>
 */
test.describe('Platform admin access log — end-to-end pipeline (G-4.4 §5.12)', () => {
  let session: PlatformOperatorSession;

  test.beforeAll(async ({ request }) => {
    session = await loginPlatformOperator(request);
  });

  test('happy path: platform-operator + valid justification reaches BatchJobController.run', async ({
    request,
  }) => {
    const resp = await platformAdminFetch(
      request,
      session,
      'POST',
      '/api/v1/batch/jobs/nonexistent-canary-job/run',
      'Playwright G-4.4 §5.12 access-log pipeline pin'
    );

    // Either 200 (controller accepted, job runner silently no-op'd) or
    // 4xx (controller rejected the unknown job id) — both prove the
    // request reached the controller. Critically NOT 401 (security
    // rejected before controller) and NOT 403 (role gate or aspect
    // MFA_VERIFIED check rejected). The aspect's PAL+AE writes
    // committed regardless of the controller's downstream verdict
    // (Decision 11).
    const status = resp.status();
    expect(
      status >= 200 && status < 500,
      `Expected request to reach BatchJobController.run, got ${status}. ` +
        `401 = security/JWT broken; 403 = role gate or MFA_VERIFIED missing; ` +
        `5xx = aspect / persistence broken.`
    ).toBeTruthy();
    expect(status, 'must NOT be 401 — security must accept platform JWT')
      .not.toBe(401);
    expect(status, 'must NOT be 403 — role gate / MFA_VERIFIED must pass')
      .not.toBe(403);
  });

  test('missing justification header → 400 (filter rejects)', async ({ request }) => {
    // Bypass platformAdminFetch — we deliberately omit X-Platform-Justification
    // to verify the filter's missing-header branch fires.
    const resp = await request.post(
      `${process.env.API_URL || 'http://localhost:8080'}/api/v1/batch/jobs/nonexistent-canary-job/run`,
      {
        headers: {
          Authorization: `Bearer ${session.accessToken}`,
          'Content-Type': 'application/json',
        },
        data: {},
      }
    );
    expect(resp.status()).toBe(400);
    const body = await resp.json();
    expect(body.error).toBe('missing_justification');
  });

  test('justification < 10 chars → 400 (filter rejects)', async ({ request }) => {
    const resp = await request.post(
      `${process.env.API_URL || 'http://localhost:8080'}/api/v1/batch/jobs/nonexistent-canary-job/run`,
      {
        headers: {
          Authorization: `Bearer ${session.accessToken}`,
          'X-Platform-Justification': 'short',
          'Content-Type': 'application/json',
        },
        data: {},
      }
    );
    expect(resp.status()).toBe(400);
    const body = await resp.json();
    expect(body.error).toBe('justification_too_short');
  });

  test('unauthenticated request → 401 (security URL rule rejects)', async ({ request }) => {
    // Pin the post-Security filter ordering: anonymous POST gets 401
    // even with a valid justification header. Mirrors the IT
    // unauthenticatedWithJustificationRejectedAtSecurity probe but
    // through the real servlet filter chain end-to-end.
    const resp = await request.post(
      `${process.env.API_URL || 'http://localhost:8080'}/api/v1/batch/jobs/nonexistent-canary-job/run`,
      {
        headers: {
          'X-Platform-Justification': 'Playwright G-4.4 §5.12 anon probe',
          'Content-Type': 'application/json',
        },
        data: {},
      }
    );
    expect(
      resp.status(),
      'Anonymous probe must hit Spring Security URL rule (401), NOT filter (400)'
    ).toBe(401);
  });

  test('tenant JWT (cocadmin) → 403 (URL rule role gate rejects)', async ({ request }) => {
    // Mint a fresh COC_ADMIN tenant JWT via the regular /api/v1/auth/login
    // endpoint — self-contained, no dependency on prior fixture state.
    // The tenant JWT carries iss=fabt-tenant; URL rule /api/v1/batch/**
    // for write methods admits only PLATFORM_OPERATOR / PLATFORM_ADMIN.
    // COC_ADMIN's role array does NOT include either → 403 from Spring
    // Security URL rule, BEFORE the JustificationValidationFilter or
    // the @PlatformAdminOnly aspect even runs.
    const apiUrl = process.env.API_URL || 'http://localhost:8080';
    const tenantLoginResp = await request.post(`${apiUrl}/api/v1/auth/login`, {
      data: {
        tenantSlug: 'dev-coc',
        email: 'cocadmin@dev.fabt.org',
        password: 'admin123',
      },
    });
    expect(
      tenantLoginResp.ok(),
      `Tenant cocadmin login failed status=${tenantLoginResp.status()}`
    ).toBeTruthy();
    const tenantBody = await tenantLoginResp.json();
    const tenantToken: string = tenantBody.accessToken;
    expect(tenantToken, '/auth/login response missing accessToken').toBeTruthy();

    const resp = await request.post(
      `${apiUrl}/api/v1/batch/jobs/nonexistent-canary-job/run`,
      {
        headers: {
          Authorization: `Bearer ${tenantToken}`,
          'X-Platform-Justification': 'Playwright G-4.4 §5.12 tenant-JWT probe',
          'Content-Type': 'application/json',
        },
        data: {},
      }
    );
    expect(
      resp.status(),
      'COC_ADMIN tenant JWT must NOT pass /api/v1/batch/** write URL rule'
    ).toBe(403);
  });
});
