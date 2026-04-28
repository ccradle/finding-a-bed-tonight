/**
 * Platform-operator dashboard (F11 task 4.7 / spec Requirement: Platform
 * dashboard renders operator metadata + Action cards grouped by
 * category).
 *
 * Reached after successful login + MFA verify. Renders:
 *   - Page header with operator email + last-login + MFA-enrolled date
 *     + backup-codes-remaining badge (color-coded amber@3 / red@1)
 *   - Action cards grouped by category (Tenant Lifecycle, Operator
 *     Management, System Status), driven by the `platformActions.ts`
 *     config so adding new actions is a one-line config change
 *   - Lifecycle actions disabled with tooltip when the
 *     `fabt.tenant.lifecycle.enabled` flag is off (per design D3)
 *   - Destructive actions go through ConfirmActionModal with
 *     typed-slug confirmation (per design D10)
 *
 * Heading hierarchy h1 / h2 / h3 per warroom round 2 (Sam) — supports
 * screen-reader heading navigation.
 */

import { useState } from 'react';
import { color } from '../../theme/colors';
import { useOperatorMetadata } from './helpers/useOperatorMetadata';
import {
  CATEGORY_LABELS,
  CATEGORY_ORDER,
  PLATFORM_ACTIONS,
  type ActionCategory,
  type PlatformAction,
} from './platformActions';
import { PlatformActionCard } from './components/PlatformActionCard';
import { ConfirmActionModal } from './components/ConfirmActionModal';

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

function backupCodesBadgeColor(remaining: number): string {
  if (remaining <= 1) return color.error;
  if (remaining <= 3) return color.warning;
  return color.successMid;
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
  const { data: operator, loading } = useOperatorMetadata();
  const [pendingDestructive, setPendingDestructive] = useState<PlatformAction | null>(null);

  const handleActivate = (action: PlatformAction) => {
    if (action.dangerLevel === 'destructive') {
      setPendingDestructive(action);
    } else {
      // Safe actions just open the endpoint in a new tab. v0.54 doesn't
      // ship inline result rendering — operator clicks "List tenants"
      // and gets the JSON in a new tab. Slice E adds a real result UI.
      window.open(action.endpoint, '_blank', 'noopener,noreferrer');
    }
  };

  const handleConfirmDestructive = () => {
    // v0.54 ships the dashboard with destructive cards DISABLED via the
    // flag gate (lifecycle endpoints land behind the flag in v0.55+).
    // The confirm flow exists for when operators have tenant-id-bound
    // tooling running OR for the manual psql break-glass that Phase H+
    // operator runbook references. For now the modal closes without
    // submitting; the F43 follow-up wires the actual POST.
    setPendingDestructive(null);
  };

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
                backgroundColor: backupCodesBadgeColor(operator?.backupCodesRemaining ?? 10),
              }}
            >
              {operator?.backupCodesRemaining ?? '—'} remaining
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

      <ConfirmActionModal
        open={!!pendingDestructive}
        variant={
          pendingDestructive
            ? {
                kind: 'destructive',
                expectedSlug: '',
                actionLabel: pendingDestructive.title,
              }
            : { kind: 'destructive', expectedSlug: '', actionLabel: '' }
        }
        body={
          pendingDestructive
            ? `Confirm: ${pendingDestructive.title}. ${pendingDestructive.description}`
            : undefined
        }
        onCancel={() => setPendingDestructive(null)}
        onConfirm={handleConfirmDestructive}
      />
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
          additions go through the documented psql bootstrap procedure.
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
