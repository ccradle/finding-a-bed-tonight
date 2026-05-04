import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../services/api';
import { parseContactEmailError } from './ContactSettings';

/**
 * info-email-contact §5.9 — pure-helper tests for the contact-email
 * PATCH error parser. Same Vitest pattern as
 * {@code parseDvPolicyError} in DvPolicySettings.test.ts.
 *
 * <p>Component-level rendering (input disabled state, success toast,
 * etc.) is exercised via Playwright; this file pins the three error-
 * shape branches the parser produces so the dispatch logic in
 * {@code ContactSettings.handleSave} can rely on stable inputs.
 */
describe('parseContactEmailError', () => {
    it('returns dvPolicyForbidden for the structured DV-policy rejection', () => {
        const err = new ApiError({
            status: 400,
            error: 'bad_request',
            message: 'Contact-email override is not allowed while the tenant DV-policy flag is enabled.',
            context: {
                errorCode: 'tenant.contactEmail.dvPolicyForbidden',
                dv_policy_enabled: true,
            },
        });
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('dvPolicyForbidden');
    });

    it('returns beanValidation for a 400 with context.detail (malformed email)', () => {
        const err = new ApiError({
            status: 400,
            error: 'bad_request',
            message: 'Validation failed',
            context: {
                detail: 'email must be a well-formed RFC 5322 address',
            },
        });
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('beanValidation');
        if (result.kind === 'beanValidation') {
            expect(result.detail).toBe('email must be a well-formed RFC 5322 address');
        }
    });

    it('returns beanValidation for the >254 chars rejection', () => {
        // The other Bean Validation case the §3 backend test covers.
        const err = new ApiError({
            status: 400,
            error: 'bad_request',
            message: 'Validation failed',
            context: { detail: 'email must be <= 254 characters' },
        });
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('beanValidation');
        if (result.kind === 'beanValidation') {
            expect(result.detail).toBe('email must be <= 254 characters');
        }
    });

    it('returns generic for an ApiError without context', () => {
        const err = new ApiError({
            status: 500,
            error: 'internal_server_error',
            message: 'Database is down',
        });
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('generic');
        if (result.kind === 'generic') {
            expect(result.message).toBe('Database is down');
        }
    });

    it('returns generic for ApiError with empty-string detail (treats as no detail)', () => {
        // Defensive: an empty detail string is functionally equivalent to
        // no detail at all; the parser should NOT surface "" as a
        // beanValidation message because the UI would render an empty
        // banner that confuses the operator.
        const err = new ApiError({
            status: 400,
            error: 'bad_request',
            message: 'Validation failed',
            context: { detail: '' },
        });
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('generic');
        if (result.kind === 'generic') {
            expect(result.message).toBe('Validation failed');
        }
    });

    it('returns generic for non-ApiError throwables (network error)', () => {
        // fetch() can reject with a TypeError on network failure; the
        // parser MUST handle this without throwing.
        const err = new TypeError('Failed to fetch');
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('generic');
    });

    it('returns generic for non-Error values (defensive)', () => {
        // unknown can be anything thrown — string, undefined, number.
        // Parser MUST NOT throw on any input.
        expect(parseContactEmailError('string thrown').kind).toBe('generic');
        expect(parseContactEmailError(undefined).kind).toBe('generic');
        expect(parseContactEmailError(42).kind).toBe('generic');
    });

    it('does NOT mistake a different errorCode for the DV-policy rejection', () => {
        // Regression guard: the DV-policy branch matches the EXACT error
        // code; a different code (e.g., a future "tenant.contactEmail.foo")
        // must fall through to beanValidation or generic, not silently
        // surface as the DV-disabled message.
        const err = new ApiError({
            status: 400,
            error: 'bad_request',
            message: 'Some other rejection',
            context: { errorCode: 'tenant.contactEmail.someOtherFutureCode' },
        });
        const result = parseContactEmailError(err);
        expect(result.kind).toBe('generic');
    });
});
