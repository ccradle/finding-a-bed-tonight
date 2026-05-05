import { createContext, useState, useEffect, useCallback, useContext, type ReactNode } from 'react';
import { api } from '../services/api';
import { AuthContext } from '../auth/AuthContext';

/**
 * info-email-contact §5.1+§5.2+§5.3 — React context provider that fetches
 * the platform contact email + (when authenticated) the caller's tenant
 * contact-email override from {@code GET /api/v1/public/contact-info}.
 *
 * <p>The endpoint's response shape varies by auth state:
 * <ul>
 *   <li>Anonymous: {@code { platform: { email }, tenant?: null }} — Spring's
 *       Jackson default elides null Map values, so the {@code tenant} key
 *       MAY be absent rather than rendered as explicit {@code null}.
 *       Consumers MUST treat both forms identically (use optional chaining
 *       or absent-check, NOT strict-equality with null). Documented in
 *       backend test {@code ContactInfoControllerTest.unauthedPlatformOnly}
 *       + tasks.md §5.2 N2-Riley note.</li>
 *   <li>Authenticated: {@code { platform: { email }, tenant: { slug, email } }}
 *       where {@code tenant.email} is null when (a) the operator hasn't set
 *       a per-tenant override OR (b) the tenant has {@code dv_policy_enabled=true}
 *       (read-side suppression per §4 H1-Casey).</li>
 * </ul>
 *
 * <p><b>Refetch on auth-state transition (§5.3 / H3):</b> the response shape
 * changes between anonymous and authenticated, so the provider subscribes
 * to {@link AuthContext} and refetches whenever {@code isAuthenticated} or
 * {@code tenantId} flip. Without this, a freshly-logged-in caller would
 * still see the anonymous-cached body (tenant block missing) until a hard
 * refresh.
 *
 * <p>Forward-compat per §5.8: this hook is the single subscription point
 * for the future Report-a-Problem footer (GH #67) + Help kebab + Feedback
 * &amp; Support landing. Today only the LoginPage footer + admin
 * ContactSettings consume it.
 *
 * <p><b>Test-coverage boundary (warroom round 2 N1-Riley-r2):</b> the pure
 * body-shape derivation in {@link #deriveContactInfoState} is covered by
 * Vitest (see {@code ContactInfoContext.test.ts}). The effect-trigger
 * (refetch on auth-state transition) is covered by Playwright at the
 * component level — the codebase intentionally avoids RTL / jsdom in
 * this tree, so direct unit tests of the {@code useEffect} dependency
 * array do NOT exist. Same posture as {@code AuthContext} +
 * {@code decodeJwtPayload.test.ts}.
 */

export interface ContactInfoState {
    /** Platform-wide contact email; empty string when unconfigured. */
    platformEmail: string;
    /**
     * Caller-tenant contact email when authenticated and the JSONB has a
     * non-empty value AND the tenant is NOT DV-policy-flagged (read-side
     * suppression). Null otherwise — anonymous, no override, or suppressed.
     */
    tenantEmail: string | null;
    /**
     * {@code tenantEmail || platformEmail || null}. The single value the
     * UI should render in placeholders. Falsy when neither is set, in
     * which case the consumer falls back to the GH-issues link (per
     * §6 / D6 design decision).
     */
    resolvedEmail: string | null;
    isLoading: boolean;
    error: Error | null;
}

const INITIAL_STATE: ContactInfoState = {
    platformEmail: '',
    tenantEmail: null,
    resolvedEmail: null,
    isLoading: true,
    error: null,
};

// eslint-disable-next-line react-refresh/only-export-components -- standard pattern: context + provider in one file
export const ContactInfoContext = createContext<ContactInfoState>(INITIAL_STATE);

/**
 * Raw shape returned by {@code GET /api/v1/public/contact-info}. The
 * {@code tenant} key may be absent (Jackson elision of null Map value)
 * for anonymous callers — that is why the type is {@code tenant?: ...}
 * and not {@code tenant: ... | null}.
 */
interface ContactInfoResponse {
    platform?: { email?: string };
    tenant?: { slug?: string; email?: string | null } | null;
}

/**
 * Pure function — exported for Vitest unit coverage (codebase convention,
 * see {@code parseDvPolicyError} in DvPolicySettings.tsx).
 *
 * <p>Maps the raw API response to the public {@link ContactInfoState}. The
 * absent-vs-null branching for the tenant key is handled here so consumers
 * never need to know which form the wire produced.
 */
// eslint-disable-next-line react-refresh/only-export-components -- pure helper exported for vitest
export function deriveContactInfoState(body: ContactInfoResponse | undefined): ContactInfoState {
    const platformEmail = body?.platform?.email ?? '';
    // Use optional chaining + nullish coalesce so an elided tenant key
    // (anonymous) and an explicit-null tenant.email (authed but no
    // override OR suppressed by DV-policy) both produce null.
    const tenantEmail = body?.tenant?.email ?? null;
    const resolvedEmail = tenantEmail || platformEmail || null;
    return {
        platformEmail,
        tenantEmail,
        resolvedEmail,
        isLoading: false,
        error: null,
    };
}

export function ContactInfoProvider({ children }: { children: ReactNode }) {
    const { isAuthenticated, user } = useContext(AuthContext);
    const tenantId = user?.tenantId ?? null;

    const [state, setState] = useState<ContactInfoState>(INITIAL_STATE);

    const fetchContactInfo = useCallback(async () => {
        // Mark loading without clobbering previously-fetched values — keeps
        // the UI from flicker-hiding the email during a re-fetch on auth
        // transition.
        setState((prev) => ({ ...prev, isLoading: true, error: null }));
        try {
            const body = await api.get<ContactInfoResponse>('/api/v1/public/contact-info');
            setState(deriveContactInfoState(body));
        } catch (err) {
            // Defense-in-depth fallback per §6 D6: on fetch failure the
            // consumer renders the GH-issues link. Surface the error in
            // state so a debug pane can read it; never throw.
            setState({
                platformEmail: '',
                tenantEmail: null,
                resolvedEmail: null,
                isLoading: false,
                error: err instanceof Error ? err : new Error(String(err)),
            });
        }
    }, []);

    // Refetch on mount AND on auth-state transition. The dependency list
    // intentionally includes both isAuthenticated AND tenantId — a tenant
    // claim change (future admin-switch-tenant feature) flips tenantId
    // without flipping isAuthenticated, and the response body's
    // tenant.slug + tenant.email would change with it.
    useEffect(() => {
        void fetchContactInfo();
    }, [fetchContactInfo, isAuthenticated, tenantId]);

    return (
        <ContactInfoContext.Provider value={state}>
            {children}
        </ContactInfoContext.Provider>
    );
}
