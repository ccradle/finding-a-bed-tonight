import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../services/api';
import { parseDvPolicyError } from './DvPolicySettings';

/**
 * dv-policy-tenant-flag tasks §9.1 + §9.2 — pure unit coverage of the
 * disable-rejection extraction logic. The component itself can't be
 * rendered in this Vitest tree (no RTL / jsdom — see
 * {@code ConfirmActionModal.test.ts} for the same pattern); component
 * behavior is Playwright-tested at §9.3-§9.5.
 *
 * The contract under test: the component MUST detect the structured
 * disable-rejection error from the backend ({@code
 * tenant.dvPolicy.cannotDisableWhileDvSheltersExist} + numeric count)
 * and surface the count to the user. Anything else → generic fallback.
 */

describe('parseDvPolicyError', () => {
  it('returns structured for the disable-rejection ApiError with count', () => {
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'DV operations are not enabled',
      context: {
        errorCode: 'tenant.dvPolicy.cannotDisableWhileDvSheltersExist',
        remaining_dv_shelter_count: 3,
      },
    });

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('structured');
    if (result.kind === 'structured') {
      expect(result.remainingDvShelters).toBe(3);
    }
  });

  it('returns structured with count=1 for single-shelter rejection', () => {
    // Plural-rule selection on the i18n side picks "1 shelter" vs
    // "N shelters" based on this exact value, so the helper must pass
    // it through cleanly (no off-by-one).
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'DV operations are not enabled',
      context: {
        errorCode: 'tenant.dvPolicy.cannotDisableWhileDvSheltersExist',
        remaining_dv_shelter_count: 1,
      },
    });

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('structured');
    if (result.kind === 'structured') {
      expect(result.remainingDvShelters).toBe(1);
    }
  });

  it('returns generic when ApiError has no context', () => {
    const err = new ApiError({
      status: 500,
      error: 'internal_server_error',
      message: 'Something broke',
    });

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      expect(result.message).toBe('Something broke');
    }
  });

  it('returns generic when context has wrong errorCode', () => {
    // A different ApiError with structured context — e.g., a Bean
    // Validation 400 — must NOT trigger the count-aware UI.
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'Validation failed',
      context: {
        errorCode: 'shelter.dvShelter.requiresDvPolicy',
        detail: 'unrelated',
      },
    });

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('generic');
  });

  it('returns generic when count is missing from disable-rejection error', () => {
    // Defensive: if the backend later changed and dropped the count,
    // we fall back to generic rather than rendering "{count} active DV
    // shelters" with undefined.
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'DV operations are not enabled',
      context: {
        errorCode: 'tenant.dvPolicy.cannotDisableWhileDvSheltersExist',
        // remaining_dv_shelter_count omitted
      },
    });

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('generic');
  });

  it('returns generic when count is non-numeric', () => {
    // Defensive: same idea as missing — if the backend payload shape
    // drifts (e.g., serialized as string), fall back instead of
    // rendering NaN.
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'DV operations are not enabled',
      context: {
        errorCode: 'tenant.dvPolicy.cannotDisableWhileDvSheltersExist',
        remaining_dv_shelter_count: '3',  // string, not number
      },
    });

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('generic');
  });

  it('returns generic for non-ApiError throwables', () => {
    // Network errors, type errors, etc. — anything that's not a
    // structured ApiError gets the generic fallback.
    const err = new TypeError('Network failed');

    const result = parseDvPolicyError(err);

    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      // Plain Error has no message extraction path; component falls
      // back to the i18n string at render time.
      expect(result.message).toBeUndefined();
    }
  });

  it('returns generic for null/undefined throwables', () => {
    // JS lets you `throw null` or `throw undefined` — defensive coverage.
    expect(parseDvPolicyError(null).kind).toBe('generic');
    expect(parseDvPolicyError(undefined).kind).toBe('generic');
  });
});
