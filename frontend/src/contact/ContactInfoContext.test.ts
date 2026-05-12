import { describe, expect, it } from 'vitest';
import { deriveContactInfoState } from './ContactInfoContext';

/**
 * info-email-contact §5.5 — pure-helper tests for the API-response →
 * hook-state mapping. Codebase convention: business logic extracted to a
 * pure function gets focused Vitest coverage; component / provider
 * behavior is exercised by Playwright (component tests would require
 * jsdom + RTL which this tree avoids).
 *
 * <p>The most important assertions pin the absent-vs-null tenant key
 * branching (round-1 N2-Riley) — Spring's Jackson default elides null
 * Map values, so the hook MUST handle both shapes identically.
 */
describe('deriveContactInfoState', () => {
    it('elided tenant key (anonymous response) → tenantEmail null, resolvedEmail = platform', () => {
        // Spring's default Jackson serialization for an unauthenticated
        // caller drops the null tenant entry from the JSON entirely.
        // Verified by ContactInfoControllerTest.unauthedPlatformOnly.
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
        });
        expect(state.platformEmail).toBe('info@findabed.org');
        expect(state.tenantEmail).toBeNull();
        expect(state.resolvedEmail).toBe('info@findabed.org');
        expect(state.isLoading).toBe(false);
        expect(state.error).toBeNull();
    });

    it('explicit-null tenant key → tenantEmail null, resolvedEmail = platform', () => {
        // The other branch of the absent-vs-null dichotomy — if a future
        // backend change rendered null explicitly, the hook MUST behave
        // identically.
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: null,
        });
        expect(state.tenantEmail).toBeNull();
        expect(state.resolvedEmail).toBe('info@findabed.org');
    });

    it('authed with non-empty tenant override → tenantEmail wins resolvedEmail', () => {
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: { slug: 'dev-coc-east', email: 'override@example.com' },
        });
        expect(state.tenantEmail).toBe('override@example.com');
        // tenantEmail || platformEmail || null — tenant wins when truthy.
        expect(state.resolvedEmail).toBe('override@example.com');
    });

    it('authed with null tenant.email (no override OR DV-suppressed) → resolvedEmail = platform', () => {
        // Backend ContactInfoController either leaves tenant.email null
        // when no override exists OR forces it to null per H1-Casey
        // suppression on DV-flagged tenants. The hook does NOT distinguish
        // these cases — both fall through to platform.
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: { slug: 'dev-coc-east', email: null },
        });
        expect(state.tenantEmail).toBeNull();
        expect(state.resolvedEmail).toBe('info@findabed.org');
    });

    it('empty platform email + no tenant → resolvedEmail null (GH-issues fallback case)', () => {
        // The not-yet-deployed branch where FABT_PLATFORM_CONTACT_EMAIL is
        // unset. The hook MUST resolve to null so the consumer renders the
        // GH-issues fallback per §6 design D6, NOT an empty-string mailto
        // that would produce a broken link.
        const state = deriveContactInfoState({
            platform: { email: '' },
        });
        expect(state.platformEmail).toBe('');
        expect(state.tenantEmail).toBeNull();
        expect(state.resolvedEmail).toBeNull();
    });

    it('empty platform email + non-empty tenant → resolvedEmail = tenant', () => {
        // Edge case: platform unconfigured but a tenant has set its own.
        // The tenant value wins; no fallback to GH-issues.
        const state = deriveContactInfoState({
            platform: { email: '' },
            tenant: { slug: 'dev-coc-east', email: 'tenant@example.com' },
        });
        expect(state.resolvedEmail).toBe('tenant@example.com');
    });

    it('undefined body (defensive) → all-empty state', () => {
        // Defensive against an api.get returning undefined. Should NOT
        // throw — the wrapping fetchContactInfo() catches throws but a
        // resolved-undefined value would slip through without this guard.
        const state = deriveContactInfoState(undefined);
        expect(state.platformEmail).toBe('');
        expect(state.tenantEmail).toBeNull();
        expect(state.resolvedEmail).toBeNull();
    });

    it('missing platform.email field → empty string', () => {
        // Forward-compat: if a future response shape drops platform.email,
        // the hook resolves to "" and the GH-issues fallback fires.
        const state = deriveContactInfoState({ platform: {} });
        expect(state.platformEmail).toBe('');
        expect(state.resolvedEmail).toBeNull();
    });

    // ----- DV-policy render-time signal (issue-reporting-feedback §12.4) ----

    it('authed with dvPolicyEnabled=true → tenant.dvPolicyEnabled true', () => {
        // Backend ContactInfoController surfaces the DV-policy flag in the
        // tenant block (per issue-reporting-feedback §12.1) so OUTREACH +
        // COORDINATOR consumers can route Report-a-Problem surfaces to a
        // mailto: fallback. Note: when DV-policy is true, the backend
        // suppresses tenant.email to null per H1-Casey; the hook still
        // exposes dvPolicyEnabled separately so the consumer can read both.
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: { slug: 'dev-coc-east', email: null, dvPolicyEnabled: true },
        });
        expect(state.tenant).not.toBeNull();
        expect(state.tenant?.dvPolicyEnabled).toBe(true);
        expect(state.tenant?.email).toBeNull();
        expect(state.tenant?.slug).toBe('dev-coc-east');
    });

    it('authed with dvPolicyEnabled=false → tenant.dvPolicyEnabled false', () => {
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: { slug: 'dev-coc-east', email: 'override@example.com', dvPolicyEnabled: false },
        });
        expect(state.tenant?.dvPolicyEnabled).toBe(false);
    });

    it('authed without dvPolicyEnabled field (forward-compat) → defaults to false', () => {
        // Stale-response defense: a backend response that predates §12 may
        // omit the flag. The hook MUST default to false rather than
        // accidentally tripping the DV-policy gate on every authed user.
        const state = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: { slug: 'dev-coc-east', email: null },
        });
        expect(state.tenant?.dvPolicyEnabled).toBe(false);
    });

    it('anonymous or platform-operator (no tenant block) → state.tenant is null', () => {
        // Both wire shapes — anonymous (Jackson elides null tenant) and
        // platform-operator (no bound tenantId, controller returns
        // `tenant: null`) — produce a null `state.tenant`. Consumers use
        // `state.tenant?.dvPolicyEnabled` which falls through to undefined
        // (falsy) for both, correctly routing to default GitHub-link
        // behavior per spec scenario "Platform-operator (no bound tenant)
        // falls through to GitHub".
        const anonymous = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
        });
        const explicitNull = deriveContactInfoState({
            platform: { email: 'info@findabed.org' },
            tenant: null,
        });
        expect(anonymous.tenant).toBeNull();
        expect(explicitNull.tenant).toBeNull();
        // Optional-chain access pattern that consumers will use:
        expect(anonymous.tenant?.dvPolicyEnabled).toBeUndefined();
        expect(explicitNull.tenant?.dvPolicyEnabled).toBeUndefined();
    });
});
