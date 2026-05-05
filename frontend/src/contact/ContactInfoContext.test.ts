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
});
