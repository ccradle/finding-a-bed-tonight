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
import { PLATFORM_OPERATOR_USER_GUIDE_URL } from './constants';

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

export default function PlatformDashboard() {
  const { data: operator, loading } = usePlatformMetadata();

  const handleActivate = (action: PlatformAction) => {
    // v0.54 ground truth: the only enabled cards in this deployment are
    // safe + permitAll (`/actuator/health`, `/api/v1/version`) — both
    // open cleanly in a new tab without an Authorization header. All
    // other actions are flag-gated disabled, so this branch is the only
    // one that ever fires. Slice E adds an in-page result viewer +
    // destructive-action POST handler.
    //
    // Round 8 N1: defensive guard so a future flag-flip on Slice E
    // can't silently mis-fire. `window.open` would issue a navigation
    // GET against a destructive POST/DELETE endpoint and return 405
    // in a new tab with no audit trail. Throw loudly so the broken
    // state is visible in monitoring + console immediately rather
    // than 3am-paged.
    if (action.dangerLevel === 'destructive') {
      const msg =
        `PlatformDashboard: destructive action "${action.id}" is not wired ` +
        `for activation in v0.54. The flag-gate must remain false until ` +
        `Slice E ships the typed-confirmation modal + POST handler.`;
      if (typeof console !== 'undefined') {
        console.error(msg);
      }
      throw new Error(msg);
    }
    // Same defense for safe actions whose endpoint requires auth — the
    // new-tab navigation strips the Authorization header and the
    // operator would see a 401 JSON body in the new tab. Today no
    // such action is enabled (tenant-list is flag-gated; system-* are
    // permitAll), but Slice E may add new entries; refuse early.
    if (action.flagGate) {
      const msg =
        `PlatformDashboard: action "${action.id}" has a flagGate but reached ` +
        `handleActivate. Flag-gated actions should remain disabled in v0.54.`;
      if (typeof console !== 'undefined') {
        console.error(msg);
      }
      throw new Error(msg);
    }
    window.open(action.endpoint, '_blank', 'noopener,noreferrer');
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
        <CategorySection
          key={category}
          category={category}
          onActivate={handleActivate}
        />
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
}: {
  category: ActionCategory;
  onActivate: (action: PlatformAction) => void;
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
          <PlatformActionCard
            key={action.id}
            action={action}
            enabled={actionEnabled(action)}
            onActivate={onActivate}
          />
        ))}
      </div>
    </section>
  );
}
