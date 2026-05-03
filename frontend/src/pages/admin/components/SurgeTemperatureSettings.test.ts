import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../services/api';
import { parseTemperatureError } from './SurgeTemperatureSettings';

/**
 * platform-observability-split tasks §6.5 + §10.1 — pure unit coverage of
 * the temperature-threshold ApiError extraction logic. The component
 * itself can't be rendered in this Vitest tree (no RTL / jsdom — see
 * {@code DvPolicySettings.test.ts} for the same pattern); component
 * behavior is Playwright-tested at §10.2.
 *
 * The contract under test: the component MUST detect the structured
 * out-of-range / wrong-type error from the backend ({@code
 * tenant.surgeThreshold.outOfRange}) and surface a localized message.
 * Anything else → generic fallback.
 */

const intl: any = {
  formatMessage: ({ id }: { id: string }) => `[i18n:${id}]`,
};

describe('parseTemperatureError', () => {
  it('returns out-of-range with localized message for the spec error code', () => {
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'temperature_threshold_f must be in [-50.0, 150.0]°F',
      context: {
        errorCode: 'tenant.surgeThreshold.outOfRange',
        field: 'temperature_threshold_f',
        received: 200.0,
      },
    });

    const result = parseTemperatureError(err, intl);

    expect(result.kind).toBe('out-of-range');
    if (result.kind === 'out-of-range') {
      expect(result.message).toBe('[i18n:admin.observability.thresholdError]');
    }
  });

  it('returns out-of-range for missing-field 400 (same error code)', () => {
    // Server emits the same error code for missing-field as for
    // out-of-range — the FE message can stay generic-ish.
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'Missing field: temperature_threshold_f',
      context: {
        errorCode: 'tenant.surgeThreshold.outOfRange',
        field: 'temperature_threshold_f',
      },
    });

    const result = parseTemperatureError(err, intl);
    expect(result.kind).toBe('out-of-range');
  });

  it('returns out-of-range for type-mismatch 400 (same error code)', () => {
    // Warroom round 4 B4: the controller now uses the SAME code for both
    // type-mismatch and bounds-mismatch, so the FE only needs one branch.
    // This test pins that contract — if a future refactor splits the
    // codes, the component handler must be updated.
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'temperature_threshold_f must be a number',
      context: {
        errorCode: 'tenant.surgeThreshold.outOfRange',
        field: 'temperature_threshold_f',
        received: 'not-a-number',
      },
    });

    const result = parseTemperatureError(err, intl);
    expect(result.kind).toBe('out-of-range');
  });

  it('returns generic when ApiError has no context', () => {
    const err = new ApiError({
      status: 500,
      error: 'internal_server_error',
      message: 'Something broke',
    });

    const result = parseTemperatureError(err, intl);

    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      expect(result.message).toBe('Something broke');
    }
  });

  it('returns generic when context has wrong errorCode', () => {
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'Validation failed',
      context: {
        errorCode: 'platform.observability.intervalOutOfRange',
        detail: 'unrelated',
      },
    });

    const result = parseTemperatureError(err, intl);

    expect(result.kind).toBe('generic');
  });

  it('returns generic for non-ApiError throwables', () => {
    // Network errors, type errors, etc. — anything that's not a
    // structured ApiError gets the generic fallback.
    const err = new TypeError('Network failed');

    const result = parseTemperatureError(err, intl);

    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      // Plain Error has no message extraction path; component falls
      // back to the i18n string at render time.
      expect(result.message).toBeUndefined();
    }
  });

  it('returns generic for null/undefined throwables', () => {
    // JS lets you `throw null` or `throw undefined` — defensive coverage.
    expect(parseTemperatureError(null, intl).kind).toBe('generic');
    expect(parseTemperatureError(undefined, intl).kind).toBe('generic');
  });

  it('returns generic when errorCode is present but not the surge threshold one', () => {
    // Confidence check on the dispatch shape — non-matching errorCode +
    // valid context shouldn't wrongly route to out-of-range.
    const err = new ApiError({
      status: 400,
      error: 'bad_request',
      message: 'Some other validation',
      context: {
        errorCode: 'shelter.dvShelter.requiresDvPolicy',
      },
    });

    const result = parseTemperatureError(err, intl);
    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      expect(result.message).toBe('Some other validation');
    }
  });
});
