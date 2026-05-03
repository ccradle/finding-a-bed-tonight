import { describe, expect, it } from 'vitest';
import { parseObservabilityError } from './ObservabilityActionCard';

/**
 * Pure unit coverage of the observability error-parser. Mirrors the
 * extracted-helper pattern from DvPolicySettings / SurgeTemperatureSettings
 * — no RTL / jsdom in this Vitest tree, so component behavior is
 * Playwright-tested + business logic is unit-tested via this predicate.
 *
 * Pinning the error codes here so a backend rename (in ErrorCodes.java)
 * surfaces as a failing test, not a silent UX regression.
 */

describe('parseObservabilityError', () => {
  it('maps platform.observability.intervalOutOfRange → out-of-range', () => {
    const result = parseObservabilityError(400, {
      error: 'bad_request',
      message: 'monitor_stale_interval_minutes must be in [1, 1440] minutes',
      context: { errorCode: 'platform.observability.intervalOutOfRange' },
    });
    expect(result.kind).toBe('out-of-range');
    if (result.kind === 'out-of-range') {
      expect(result.message).toContain('1, 1440');
    }
  });

  it('maps platform.observability.tracingEndpointMalformed → malformed-endpoint', () => {
    const result = parseObservabilityError(400, {
      error: 'bad_request',
      message: 'tracing_endpoint must be a valid URI',
      context: { errorCode: 'platform.observability.tracingEndpointMalformed' },
    });
    expect(result.kind).toBe('malformed-endpoint');
  });

  it('maps platform.observability.fieldTypeMismatch → type-mismatch', () => {
    const result = parseObservabilityError(400, {
      error: 'bad_request',
      message: 'tracing_enabled must be a boolean',
      context: { errorCode: 'platform.observability.fieldTypeMismatch' },
    });
    expect(result.kind).toBe('type-mismatch');
  });

  it('maps platform.observability.unknownField → unknown-field', () => {
    const result = parseObservabilityError(400, {
      error: 'bad_request',
      message: 'Unknown field: not_a_real_key',
      context: { errorCode: 'platform.observability.unknownField' },
    });
    expect(result.kind).toBe('unknown-field');
  });

  it('maps missing_justification → missing-justification', () => {
    const result = parseObservabilityError(400, {
      error: 'bad_request',
      message: 'X-Platform-Justification header is required',
      context: { errorCode: 'missing_justification' },
    });
    expect(result.kind).toBe('missing-justification');
  });

  it('maps 403 with no errorCode → forbidden', () => {
    const result = parseObservabilityError(403, {
      error: 'forbidden',
      message: 'Access denied',
    });
    expect(result.kind).toBe('forbidden');
  });

  it('falls through to generic when errorCode is unknown', () => {
    const result = parseObservabilityError(400, {
      error: 'bad_request',
      message: 'Some other validation',
      context: { errorCode: 'shelter.dvShelter.requiresDvPolicy' },
    });
    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      expect(result.message).toBe('Some other validation');
    }
  });

  it('falls through to generic on null body', () => {
    const result = parseObservabilityError(500, null);
    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      expect(result.message).toBeUndefined();
    }
  });

  it('falls through to generic when body has no context', () => {
    const result = parseObservabilityError(500, { error: 'internal_server_error', message: 'Something broke' });
    expect(result.kind).toBe('generic');
    if (result.kind === 'generic') {
      expect(result.message).toBe('Something broke');
    }
  });

  it('403 with errorCode still routes to the matching code branch (not forbidden)', () => {
    // Defensive — the 403 fallback should only fire when no specific
    // errorCode is present. If the backend ever sends a 403 + a known
    // errorCode (none currently do, but the parse order matters for
    // future evolution), the specific branch wins.
    const result = parseObservabilityError(403, {
      error: 'bad_request',
      message: 'Out of range',
      context: { errorCode: 'platform.observability.intervalOutOfRange' },
    });
    expect(result.kind).toBe('out-of-range');
  });
});
