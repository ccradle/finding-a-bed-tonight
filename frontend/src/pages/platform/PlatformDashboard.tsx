/**
 * Platform-operator dashboard (F11 task 4.7 / spec Requirement: Platform
 * dashboard renders operator metadata + Action cards grouped by
 * category).
 *
 * Reached after successful login + MFA verify. Renders:
 *   - Page header with operator email + last-login + MFA-enrolled date
 *     + backup-codes-remaining badge (color-coded amber@3 / red@1, with
 *     a text urgency label so the warning is conveyed without color —
 *     WCAG 1.4.1)
 *   - Action cards grouped by category (Tenant Lifecycle, Operator
 *     Management, System Status), driven by the `platformActions.ts`
 *     config so adding new actions is a one-line config change
 *   - Lifecycle actions disabled with tooltip when the
 *     `fabt.tenant.lifecycle.enabled` flag is off (per design D3)
 *
 * Heading hierarchy h1 / h2 / h3 per warroom round 2 (Sam) — supports
 * screen-reader heading navigation.
 *
 * Round 7 fixes (warroom): the prior version mounted a
 * {@link ConfirmActionModal} with `expectedSlug: ''` (typed-slug bypass)
 * and a no-op `onConfirm` (silent-success timebomb). With all destructive
 * cards flag-gated disabled in v0.54, the modal never legitimately
 * fires — so we remove the wiring entirely. Slice E will re-introduce
 * the destructive flow with a real `expectedSlug` (the tenant slug the
 * operator is acting on) and a real POST handler.
 */

import { useState, useEffect, useCallback, Fragment } from 'react';
import { color } from '../../theme/colors';
import { usePlatformMetadata } from './PlatformMetadataContext';
import {
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  PLATFORM_ACTIONS,
  type ActionCategory,
  type PlatformAction,
} from './platformActions';
import { PlatformActionCard } from './components/PlatformActionCard';
import { ObservabilityActionCard } from './components/ObservabilityActionCard';
import { PLATFORM_OPERATOR_USER_GUIDE_URL } from './constants';
import { platformFetch } from './helpers/platformApi';

/**
 * Spring property `fabt.tenant.lifecycle.enabled` is currently NOT
 * exposed via /me (F43 backend follow-up). For v0.54 we hard-code
 * false — the dashboard renders lifecycle cards as disabled-with-
 * tooltip, which trains the operator's mental model and matches the
 * prod deploy posture (per OpenSpec design.md Decision D3). When F43
 * lands, replace this constant with `operator.lifecycleEnabled`.
 */
const TENANT_LIFECYCLE_ENABLED = false;

function actionEnabled(action: PlatformAction): boolean {
  if (action.flagGate === 'fabt.tenant.lifecycle.enabled') {
    return TENANT_LIFECYCLE_ENABLED;
  }
  return true;
}

interface BackupCodesUrgency {
  background: string;
  label: string;
}

function backupCodesUrgency(remaining: number): BackupCodesUrgency {
  if (remaining <= 1) return { background: color.error, label: 'Critical' };
  if (remaining <= 3) return { background: color.warning, label: 'Low' };
  return { background: color.successMid, label: 'Healthy' };
}

function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * Shape of GET /api/v1/platform/observability. Mirrors PlatformConfig.java
 * (camelCase on the wire — see PlatformObservabilityControllerTest assertions
 * that pin "prometheusEnabled" / "monitorStaleIntervalMinutes" etc.).
 */
interface ObservabilityConfig {
  prometheusEnabled: boolean;
  tracingEnabled: boolean;
  tracingEndpoint: string;
  monitorStaleIntervalMinutes: number;
  monitorDvCanaryIntervalMinutes: number;
  monitorTemperatureIntervalMinutes: number;
}

/**
 * Wire shape of GET /api/v1/tenants — see TenantResponse.java.
 * Warroom round 7 (2026-05-03): the previous flag-gated `View` button now
 * fetches via platformFetch + renders inline. Slice E will add sort/filter.
 */
interface TenantSummary {
  id: string;
  name: string;
  slug: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Returns a per-action-id "Current: …" string for the observability cards,
 * pre-formatted (booleans → "enabled" / "disabled", intervals → "N minutes",
 * endpoint as-is). Returns undefined for action ids the config doesn't speak to,
 * so non-observability cards render no value row.
 */
function formatCurrentValue(actionId: string, cfg: ObservabilityConfig | null): string | undefined {
  if (!cfg) return undefined;
  const fmtBool = (v: boolean) => (v ? 'enabled' : 'disabled');
  const fmtMin = (v: number) => `${v} minute${v === 1 ? '' : 's'}`;
  switch (actionId) {
    case 'obs-prometheus':       return fmtBool(cfg.prometheusEnabled);
    case 'obs-tracing':          return fmtBool(cfg.tracingEnabled);
    case 'obs-tracing-endpoint': return cfg.tracingEndpoint;
    case 'obs-stale-interval':   return fmtMin(cfg.monitorStaleIntervalMinutes);
    case 'obs-canary-interval':  return fmtMin(cfg.monitorDvCanaryIntervalMinutes);
    case 'obs-temp-interval':    return fmtMin(cfg.monitorTemperatureIntervalMinutes);
    default:                     return undefined;
  }
}

export default function PlatformDashboard() {
  const { data: operator, loading } = usePlatformMetadata();
  // `result` drives the dismiss-able banner above the operator metadata.
  // The observability cards manage their own per-card saving / saved /
  // error UI inline (warroom round 6) — they don't write to `result`.
  // `result` is reserved for future non-card actions (lifecycle Slice E).
  const [result, setResult] = useState<{ type: 'success' | 'error'; message: string } | null>(null);
  // Observability snapshot driving the "Current: …" line on each obs card.
  // Re-fetched on mount + after every successful PUT so the displayed value
  // never lags the platform_config row.
  const [obsConfig, setObsConfig] = useState<ObservabilityConfig | null>(null);

  // Tenant-list inline viewer state (warroom round 7). null = collapsed
  // (the View card is in resting state); empty array = open + currently
  // showing zero tenants; populated array = open + showing rows.
  const [tenantList, setTenantList] = useState<TenantSummary[] | null>(null);
  const [tenantListLoading, setTenantListLoading] = useState(false);
  const [tenantListError, setTenantListError] = useState<string | null>(null);

  const refreshObsConfig = useCallback(async () => {
    try {
      const resp = await platformFetch('/api/v1/platform/observability', { method: 'GET' });
      if (resp.ok) {
        const data = (await resp.json()) as ObservabilityConfig;
        setObsConfig(data);
      }
      // 401/403 are handled by platformFetch (auto-redirect); other errors
      // leave obsConfig null → cards render with no Current row, which is
      // the same as the pre-fetch state. No user-visible spinner since the
      // request typically completes in tens of ms; if a tester sees the
      // "Current: …" lines blank longer than that, the network tab tells
      // them why.
    } catch {
      // network failure — same as above; degrade gracefully.
    }
  }, []);

  useEffect(() => {
    refreshObsConfig();
  }, [refreshObsConfig]);

  /**
   * Fetches the tenant list via platformFetch (which carries the JWT)
   * and pushes the result into local state. Warroom round 7: replaces
   * the prior `window.open` path that stripped the JWT in a new tab.
   */
  const fetchTenantList = useCallback(async () => {
    setTenantListLoading(true);
    setTenantListError(null);
    try {
      const resp = await platformFetch('/api/v1/tenants', { method: 'GET' });
      if (resp.ok) {
        const data = (await resp.json()) as TenantSummary[];
        setTenantList(data);
      } else {
        setTenantListError(`Could not load tenants (status ${resp.status}).`);
        // Open the panel even on error so the operator sees the message
        // instead of the click silently doing nothing.
        setTenantList([]);
      }
    } catch {
      setTenantListError('Network error. Try Refresh.');
      setTenantList([]);
    } finally {
      setTenantListLoading(false);
    }
  }, []);

  const handleActivate = async (action: PlatformAction) => {
    // Warroom round 6 (2026-05-03): observability actions now use the
    // ObservabilityActionCard inline-edit form — they never reach this
    // handler. Anything that does is either a GET (new-tab open) or a
    // tenant-lifecycle action (Slice E ships the typed-confirm modal).
    if (action.category === 'observability') {
      // Defensive: if a code path ever wires an obs action to this
      // handler again, fail loudly rather than fall through to the
      // bygone window.prompt flow.
      const msg = `PlatformDashboard: observability action "${action.id}" reached handleActivate; should be handled by ObservabilityActionCard.`;
      if (typeof console !== 'undefined') console.error(msg);
      throw new Error(msg);
    }

    // Round 8 N1 (warroom round 4 restore): defensive guard for any
    // destructive action whose endpoint is NOT yet wired in this slice.
    // Tenant-lifecycle destructive actions remain flag-gated and unhandled
    // here. Without this guard, a future flag-flip would issue a
    // navigation GET via window.open against a destructive POST/DELETE
    // endpoint, returning 405 with no audit trail.
    if (action.dangerLevel === 'destructive' && action.flagGate === 'fabt.tenant.lifecycle.enabled') {
      const msg =
        `PlatformDashboard: destructive lifecycle action "${action.id}" is not wired ` +
        `for activation in this slice. The flag-gate must remain false until ` +
        `Slice E ships the typed-confirmation modal + POST handler.`;
      if (typeof console !== 'undefined') {
        console.error(msg);
      }
      throw new Error(msg);
    }

    // Same defense for safe actions whose endpoint requires auth — the
    // new-tab navigation strips the Authorization header and the operator
    // would see a 401 JSON body in the new tab. Refuse early.
    if (action.flagGate && actionEnabled(action) === false) {
      const msg =
        `PlatformDashboard: action "${action.id}" has a flagGate but reached ` +
        `handleActivate. Flag-gated actions should remain disabled.`;
      if (typeof console !== 'undefined') {
        console.error(msg);
      }
      throw new Error(msg);
    }

    // Warroom round 7 (2026-05-03): tenant-list opens an inline panel
    // below the Lifecycle category instead of `window.open`-ing the
    // auth-required JSON endpoint into a new tab. Toggles open/closed.
    if (action.id === 'tenant-list') {
      if (tenantList === null) {
        await fetchTenantList();
      } else {
        setTenantList(null);
        setTenantListError(null);
      }
      return;
    }

    if (action.method === 'GET') {
      window.open(action.endpoint, '_blank', 'noopener,noreferrer');
      return;
    }

    // Anything else falling through here is a category we don't yet wire
    // (no destructive lifecycle POST handler in v0.54). Fail loudly.
    const msg = `PlatformDashboard: action "${action.id}" has no inline handler — Slice E pending.`;
    if (typeof console !== 'undefined') console.error(msg);
    setResult({ type: 'error', message: msg });
  };

  // Per-card observability current value (boolean | number | string).
  const obsCurrentValue = (fieldKey: string | undefined): boolean | number | string | undefined => {
    if (!fieldKey || !obsConfig) return undefined;
    switch (fieldKey) {
      case 'prometheus_enabled': return obsConfig.prometheusEnabled;
      case 'tracing_enabled': return obsConfig.tracingEnabled;
      case 'tracing_endpoint': return obsConfig.tracingEndpoint;
      case 'monitor_stale_interval_minutes': return obsConfig.monitorStaleIntervalMinutes;
      case 'monitor_dv_canary_interval_minutes': return obsConfig.monitorDvCanaryIntervalMinutes;
      case 'monitor_temperature_interval_minutes': return obsConfig.monitorTemperatureIntervalMinutes;
      default: return undefined;
    }
  };

  const backupCodes = operator?.backupCodesRemaining ?? null;
  const urgency = backupCodes !== null ? backupCodesUrgency(backupCodes) : null;

  return (
    <main
      style={{
        maxWidth: '960px',
        margin: '2rem auto',
        padding: '0 1.5rem',
      }}
      data-testid="platform-dashboard-main"
    >
      <h1 style={{ fontSize: '1.5rem' }}>Platform Operator Dashboard</h1>
      {/* §7.3 — first-time help link to the user guide. URL pinned in
          constants.ts so a future doc-tree move only needs one edit. */}
      <p
        style={{
          margin: '0.25rem 0 1rem',
          fontSize: '0.875rem',
          color: color.textSecondary,
        }}
      >
        First time? See the{' '}
        <a
          href={PLATFORM_OPERATOR_USER_GUIDE_URL}
          target="_blank"
          rel="noopener noreferrer"
          data-testid="platform-dashboard-user-guide-link"
          style={{ color: color.primaryText }}
        >
          Platform Operator User Guide
        </a>
        .
      </p>

      {result && (
        <div
          role="alert"
          data-testid="platform-action-result"
          style={{
            padding: '1rem',
            marginBottom: '1.5rem',
            borderRadius: '8px',
            backgroundColor: result.type === 'success' ? color.successBg : color.errorBg,
            color: result.type === 'success' ? color.successMid : color.error,
            border: `1px solid ${result.type === 'success' ? color.successBorder : color.errorBorder}`,
            fontWeight: 600,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          <span>{result.message}</span>
          <button
            onClick={() => setResult(null)}
            style={{
              background: 'transparent',
              border: 'none',
              color: 'inherit',
              cursor: 'pointer',
              fontSize: '1.25rem',
              lineHeight: 1,
            }}
            aria-label="Dismiss"
          >
            ×
          </button>
        </div>
      )}

      {/* Header: operator metadata. Loading and 410 are handled by the
          banner above; this section just renders what we have. */}
      {loading ? (
        <p
          role="status"
          aria-live="polite"
          style={{ color: color.textSecondary }}
        >
          Loading operator metadata…
        </p>
      ) : (
        <section
          aria-labelledby="platform-dashboard-meta-heading"
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
            gap: '0.75rem',
            margin: '1rem 0 2rem',
            padding: '1rem',
            backgroundColor: color.bgSecondary,
            borderRadius: '8px',
            border: `1px solid ${color.border}`,
          }}
        >
          <h2
            id="platform-dashboard-meta-heading"
            style={{
              gridColumn: '1 / -1',
              margin: 0,
              fontSize: '0.875rem',
              color: color.textSecondary,
              textTransform: 'uppercase',
              letterSpacing: '0.05em',
            }}
          >
            Operator
          </h2>
          <MetaItem label="Email" value={operator?.email ?? '—'} testid="platform-dashboard-email" />
          <MetaItem
            label="Last login"
            value={formatTimestamp(operator?.lastLoginAt)}
            testid="platform-dashboard-last-login"
          />
          <MetaItem
            label="MFA enrolled"
            value={formatTimestamp(operator?.mfaEnrolledAt)}
            testid="platform-dashboard-mfa-enrolled"
          />
          <div style={{ minWidth: '140px' }}>
            <div style={{ fontSize: '0.75rem', color: color.textSecondary, marginBottom: '0.25rem' }}>
              Backup codes
            </div>
            <span
              data-testid="platform-dashboard-backup-codes-badge"
              style={{
                display: 'inline-block',
                padding: '0.25rem 0.5rem',
                borderRadius: '4px',
                fontWeight: 600,
                color: color.textInverse,
                backgroundColor: urgency?.background ?? color.successMid,
              }}
            >
              {backupCodes ?? '—'} remaining
              {urgency && (
                /* WCAG 1.4.1 — backup-codes urgency must not be
                   conveyed by color alone. The text label
                   (Healthy / Low / Critical) is the non-color signal. */
                <span data-testid="platform-dashboard-backup-codes-urgency">
                  {' · '}
                  {urgency.label}
                </span>
              )}
            </span>
          </div>
        </section>
      )}

      {CATEGORY_ORDER.map((category) => (
        <Fragment key={category}>
          <CategorySection
            category={category}
            onActivate={handleActivate}
            obsConfig={obsConfig}
            obsCurrentValue={obsCurrentValue}
            refreshObsConfig={refreshObsConfig}
          />
          {/* Tenant-list inline panel renders directly under the Lifecycle
              category section so the operator sees the data adjacent to the
              card that triggered it. */}
          {category === 'lifecycle' && tenantList !== null && (
            <TenantListPanel
              tenants={tenantList}
              loading={tenantListLoading}
              error={tenantListError}
              onRefresh={fetchTenantList}
              onClose={() => { setTenantList(null); setTenantListError(null); }}
            />
          )}
        </Fragment>
      ))}
    </main>
  );
}

function MetaItem({ label, value, testid }: { label: string; value: string; testid: string }) {
  return (
    <div>
      <div style={{ fontSize: '0.75rem', color: color.textSecondary, marginBottom: '0.25rem' }}>
        {label}
      </div>
      <div data-testid={testid} style={{ fontSize: '0.875rem', wordBreak: 'break-all' }}>
        {value}
      </div>
    </div>
  );
}

function CategorySection({
  category,
  onActivate,
  obsConfig,
  obsCurrentValue,
  refreshObsConfig,
}: {
  category: ActionCategory;
  onActivate: (action: PlatformAction) => void;
  /** Latest GET /api/v1/platform/observability snapshot, or null while
   *  the initial fetch is in flight. Drives the "Current: …" line on
   *  observability cards. */
  obsConfig: ObservabilityConfig | null;
  /** Resolves an action's fieldKey to its current persisted value. */
  obsCurrentValue: (fieldKey: string | undefined) => boolean | number | string | undefined;
  /** Re-fetches obsConfig after the inline-edit form saves. */
  refreshObsConfig: () => void;
}) {
  const actions = PLATFORM_ACTIONS.filter((a) => a.category === category);

  // Operator Management is empty in v0.54 — render the placeholder
  // card per warroom round 1 Sam recommendation (sets expectation
  // about what's coming).
  if (category === 'operator' && actions.length === 0) {
    return (
      <section
        aria-labelledby={`platform-category-${category}-heading`}
        style={{ marginBottom: '2rem' }}
      >
        <h2
          id={`platform-category-${category}-heading`}
          style={{ fontSize: '1.125rem', marginBottom: '0.75rem' }}
        >
          {CATEGORY_LABELS[category]}
        </h2>
        <p
          data-testid="platform-operator-management-placeholder"
          style={{
            padding: '1rem',
            backgroundColor: color.bgSecondary,
            borderRadius: '8px',
            border: `1px solid ${color.border}`,
            color: color.textSecondary,
            fontStyle: 'italic',
          }}
        >
          Operator self-management coming v0.55 — until then, operator
          additions go through the documented psql bootstrap procedure
          in the platform-operator runbook.
        </p>
      </section>
    );
  }

  return (
    <section
      aria-labelledby={`platform-category-${category}-heading`}
      style={{ marginBottom: '2rem' }}
    >
      <h2
        id={`platform-category-${category}-heading`}
        style={{ fontSize: '1.125rem', marginBottom: '0.75rem' }}
      >
        {CATEGORY_LABELS[category]}
      </h2>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
          gap: '1rem',
        }}
      >
        {actions.map((action) => (
          action.category === 'observability' ? (
            <ObservabilityActionCard
              key={action.id}
              action={action}
              currentValue={obsCurrentValue(action.fieldKey)}
              onSaved={refreshObsConfig}
            />
          ) : (
            <PlatformActionCard
              key={action.id}
              action={action}
              enabled={actionEnabled(action)}
              onActivate={onActivate}
              currentValueDisplay={formatCurrentValue(action.id, obsConfig)}
            />
          )
        ))}
      </div>
    </section>
  );
}

/**
 * Inline tenant-list panel that renders directly under the Lifecycle
 * category section when the operator clicks the "View" button on the
 * `tenant-list` action card. Read-only for v0.55; Slice E adds sort/filter.
 */
function TenantListPanel({
  tenants,
  loading,
  error,
  onRefresh,
  onClose,
}: {
  tenants: TenantSummary[];
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
  onClose: () => void;
}) {
  return (
    <section
      aria-label="Tenant list"
      data-testid="platform-tenant-list-panel"
      style={{
        marginTop: '-1rem',
        marginBottom: '2rem',
        padding: '1rem',
        border: `1px solid ${color.border}`,
        borderRadius: '8px',
        backgroundColor: color.bgSecondary,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: '0.75rem',
          gap: 8,
          flexWrap: 'wrap',
        }}
      >
        <h3 style={{ margin: 0, fontSize: '1rem' }}>
          Tenants{tenants.length > 0 ? ` (${tenants.length})` : ''}
        </h3>
        <div style={{ display: 'flex', gap: 8 }}>
          <button
            type="button"
            onClick={onRefresh}
            disabled={loading}
            data-testid="platform-tenant-list-refresh"
            style={{
              padding: '0.5rem 0.875rem',
              fontSize: '0.875rem',
              fontWeight: 600,
              borderRadius: '4px',
              border: `2px solid ${color.border}`,
              cursor: loading ? 'wait' : 'pointer',
              backgroundColor: color.bg,
              color: color.text,
            }}
          >
            {loading ? 'Loading…' : 'Refresh'}
          </button>
          <button
            type="button"
            onClick={onClose}
            data-testid="platform-tenant-list-close"
            style={{
              padding: '0.5rem 0.875rem',
              fontSize: '0.875rem',
              fontWeight: 600,
              borderRadius: '4px',
              border: `2px solid ${color.border}`,
              cursor: 'pointer',
              backgroundColor: color.bg,
              color: color.text,
            }}
          >
            Close
          </button>
        </div>
      </div>

      {error && (
        <p
          role="alert"
          data-testid="platform-tenant-list-error"
          style={{
            margin: 0,
            marginBottom: '0.75rem',
            padding: '8px 12px',
            fontSize: '0.875rem',
            color: color.error,
            backgroundColor: color.errorBg,
            border: `1px solid ${color.errorBorder}`,
            borderRadius: 8,
          }}
        >
          {error}
        </p>
      )}

      {tenants.length === 0 && !loading && !error && (
        <p
          data-testid="platform-tenant-list-empty"
          style={{ margin: 0, fontSize: '0.875rem', color: color.textSecondary }}
        >
          No tenants yet.
        </p>
      )}

      {tenants.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              fontSize: '0.875rem',
            }}
          >
            <thead>
              <tr style={{ textAlign: 'left', borderBottom: `1px solid ${color.border}` }}>
                <th style={{ padding: '6px 8px', fontWeight: 600 }}>Name</th>
                <th style={{ padding: '6px 8px', fontWeight: 600 }}>Slug</th>
                <th style={{ padding: '6px 8px', fontWeight: 600 }}>Created</th>
                <th style={{ padding: '6px 8px', fontWeight: 600 }}>Updated</th>
              </tr>
            </thead>
            <tbody>
              {tenants.map((t) => (
                <tr
                  key={t.id}
                  data-testid={`platform-tenant-row-${t.slug}`}
                  style={{ borderBottom: `1px solid ${color.border}` }}
                >
                  <td style={{ padding: '6px 8px', fontWeight: 600 }}>{t.name}</td>
                  <td style={{ padding: '6px 8px', fontFamily: 'monospace' }}>{t.slug}</td>
                  <td style={{ padding: '6px 8px', color: color.textSecondary }}>
                    {formatTimestamp(t.createdAt)}
                  </td>
                  <td style={{ padding: '6px 8px', color: color.textSecondary }}>
                    {formatTimestamp(t.updatedAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
