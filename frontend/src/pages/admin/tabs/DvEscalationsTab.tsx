import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { api } from '../../../services/api';
import { ErrorBox, NoData, Spinner } from '../components';
import { useAuth } from '../../../auth/useAuth';
import { useDvEscalationQueue, type EscalatedReferral } from '../../../hooks/useDvEscalationQueue';
import { useDeepLink, type DeepLinkIntent, type ResolvedTarget } from '../../../hooks/useDeepLink';
import { useHashSearchParams } from '../../../hooks/useHashSearchParams';
import { classifyDeepLinkOutcome, reportDeepLinkClick } from '../../../services/notificationDeepLinkMetrics';
import { markNotificationsActedByPayload } from '../../../services/notificationMarkActed';
import { EscalatedQueueTable } from './dvEscalations/EscalatedQueueTable';
import { EscalatedQueueCardList } from './dvEscalations/EscalatedQueueCardList';
import { EscalatedReferralDetailModal } from './dvEscalations/EscalatedReferralDetailModal';
import { EscalationPolicyEditor } from './dvEscalations/EscalationPolicyEditor';

type Segment = 'queue' | 'policy';

const MOBILE_BREAKPOINT = 768;

/**
 * Detect mobile viewport via window.matchMedia. Updates on resize via the
 * MediaQueryList change event — same pattern used by the existing
 * accessibility audit recommendations for responsive admin views.
 */
function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState(() =>
    typeof window !== 'undefined' && window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`).matches
  );
  useEffect(() => {
    if (typeof window === 'undefined') return;
    const mql = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`);
    const handler = (e: MediaQueryListEvent) => setIsMobile(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, []);
  return isMobile;
}

/**
 * T-33 — DV Escalations admin tab orchestrator.
 *
 * <p>Two segmented sections: "Pending Queue" (the work surface) and
 * "Escalation Policy" (the per-tenant configuration editor). Mobile
 * (&lt;768px) hides the segment switch and shows the queue only — the
 * policy editor renders a read-only message on small screens because
 * the form is desktop-only by design (D7).</p>
 *
 * <p>The orchestrator owns the modal state — `EscalatedReferralDetailModal`
 * is a singleton rendered here, and the table/card-list call `onOpenDetail`
 * to populate it. This avoids two competing modal stacks (table and card
 * list each owning their own).</p>
 *
 * <p>SSE-driven refresh comes through `useDvEscalationQueue`, which extends
 * the existing `useNotifications` hook (D20 — single SSE connection per
 * session). No parallel EventSource here.</p>
 */
function DvEscalationsTab() {
  const intl = useIntl();
  const { user } = useAuth();
  const isMobile = useIsMobile();
  const { queue, loading, error, refresh } = useDvEscalationQueue();

  const [segment, setSegment] = useState<Segment>('queue');
  const [openReferral, setOpenReferral] = useState<EscalatedReferral | null>(null);
  const [actionSubmitting, setActionSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  // notification-deep-linking Phase 2 — transient toast shown when a
  // deep-link target is no longer in the escalation queue (D10 unified
  // stale shape). Auto-dismisses after 5s.
  const [deepLinkToast, setDeepLinkToast] = useState<string | null>(null);

  // Sync the open modal's referral to the latest queue version when the
  // queue refreshes (initial load OR SSE-driven refetch). The deep field
  // comparison is the LOAD-BEARING bailout that prevents an infinite
  // re-render loop:
  //
  //   1. SSE arrives → queue is replaced with a new array (new references).
  //   2. This effect fires because `queue` changed.
  //   3. We compare `fresh` field-by-field against `openReferral`.
  //   4. If nothing material changed → no setState → no re-render → loop closes.
  //   5. If something DID change → setState(fresh) → re-render → effect fires
  //      AGAIN with the new `openReferral` as the new `current`. This time
  //      `fresh.X === current.X` for every field → no setState → loop closes.
  //
  // The earlier ref-pattern attempt (war room round 6) had a stale-read race
  // when actions interleaved with SSE; the ref lagged the state by one render.
  // Putting `openReferral` back in deps + relying on the comparison as the
  // bailout is simpler AND race-free (war room round 7 — Riley Cho).
  //
  // **MAINTENANCE NOTE:** the comparison list below covers the fields that
  // change AFTER referral creation. When adding a new field to
  // EscalatedReferral that can change post-creation (e.g. a future
  // `dispatchedToShelterAt` timestamp), ADD IT HERE — otherwise the open
  // modal will show a stale value. The other fields (id, shelterId,
  // shelterName, populationType, householdSize, urgency, createdAt,
  // expiresAt) are immutable after creation and don't need comparison.
  useEffect(() => {
    if (!openReferral) return;
    const fresh = queue.find((r) => r.id === openReferral.id);
    if (!fresh) {
      // Referral is no longer in the queue (accepted/rejected/expired) —
      // close the modal so the admin sees the queue again.
      setOpenReferral(null);
      return;
    }
    if (
      fresh.claimedByAdminId !== openReferral.claimedByAdminId ||
      fresh.claimedByAdminName !== openReferral.claimedByAdminName ||
      fresh.escalationChainBroken !== openReferral.escalationChainBroken ||
      fresh.remainingMinutes !== openReferral.remainingMinutes ||
      fresh.assignedCoordinatorName !== openReferral.assignedCoordinatorName
    ) {
      setOpenReferral(fresh);
    }
  }, [queue, openReferral]);

  // (The 30-second live-countdown interval lives inside useDvEscalationQueue
  // as of war room round 7 — it now uses the debounced scheduleRefresh AND
  // gates on Page Visibility so background tabs don't burn REST calls.)

  // -------------------------------------------------------------------------
  // notification-deep-linking Phase 2 (tasks 4.1–4.3) — useDeepLink wiring
  // -------------------------------------------------------------------------
  //
  // The admin queue's host callbacks:
  //   resolveTarget: pass the intent through unchanged — the queue is our
  //     data source, so we don't fetch anything in the resolve step.
  //   needsUnsavedConfirm: false — no per-row edits on this tab.
  //   expand: no-op — the queue is always visible once the tab is active.
  //   isTargetReady: true once the queue has loaded AND contains the target.
  //     If the queue loads and the target isn't there, the hook's
  //     awaiting-target deadline fires → STALE: timeout → toast.
  //
  // URL source: hash-embedded (#dvEscalations?referralId=X) per D1. We use
  // useHashSearchParams rather than useSearchParams because useSearchParams
  // reads location.search, which doesn't see params after the hash.
  const hashSearchParams = useHashSearchParams();

  const resolveTarget = useCallback(async (
    intent: DeepLinkIntent,
  ): Promise<ResolvedTarget<EscalatedReferral>> => {
    // Unusual pattern for useDeepLink: the admin queue's data source is the
    // in-memory `queue` array that's already being fetched by
    // useDvEscalationQueue. There's no separate per-referral fetch to
    // perform at the resolve step — the row either is or will be in the
    // queue once it loads. So resolveTarget is a trivial pass-through, and
    // the hook's awaiting-target state (polling isTargetReady against the
    // queue, with a 5s deadline) is what actually waits for the data.
    //
    // If you're consuming useDeepLink for a host that needs a backend
    // lookup to resolve the intent (coordinator dashboard does this via
    // GET /api/v1/dv-referrals/{id}), put that fetch HERE — not in
    // isTargetReady.
    return { intent, resolvedShelterId: null, detail: null };
  }, []);

  const needsUnsavedConfirm = useCallback(() => false, []);
  const expandNoop = useCallback(async () => { /* queue already visible */ }, []);

  const isTargetReady = useCallback((
    resolved: ResolvedTarget<EscalatedReferral>,
  ): boolean => {
    if (loading) return false;
    const referralId = resolved.intent.referralId;
    // L-2 defensive guard: the admin queue only accepts referralId deep-links.
    // A shelterId or reservationId intent reaching this tab is an error in
    // the URL routing layer — don't silently reach 'done' with no action.
    // Returning false here lets the hook's awaiting-target timeout surface
    // the misrouted intent as a stale toast after 5s rather than failing
    // silently.
    if (!referralId) return false;
    return queue.some((r) => r.id === referralId);
  }, [loading, queue]);

  const { state: dlState } = useDeepLink<EscalatedReferral>({
    searchParams: hashSearchParams,
    resolveTarget,
    needsUnsavedConfirm,
    expand: expandNoop,
    isTargetReady,
  });

  // Task 4.2 / 4.3 — react to state TRANSITIONS (not re-renders).
  //
  // 'done'  → open the detail modal for the matching queue row.
  // 'stale' → non-blocking toast (D10 unified stale shape). markActed-stale
  //           wiring waits for Phase 3 task 7.1 when markNotificationsActedByPayload
  //           ships (X-1 pattern).
  //
  // War-room round 2 H-1 fix: the effect's deps include `queue` (because the
  // 'done' branch reads from it to find the row). Without this ref-gated
  // guard, every SSE queue refresh would re-fire the 'done' branch and
  // re-open the detail modal that the admin just closed. The ref tracks
  // the last-handled `dlState.kind` so the effect only ACTS when the kind
  // has actually changed, not on every unrelated re-render.
  const lastHandledDlKindRef = useRef<string>('idle');
  useEffect(() => {
    if (lastHandledDlKindRef.current === dlState.kind) return;
    lastHandledDlKindRef.current = dlState.kind;
    if (dlState.kind === 'done') {
      const referralId = dlState.resolved.intent.referralId;
      if (!referralId) return;
      // Phase 4 task 9a.1 — admin queue uses 'admin-escalation-deeplink'
      // tag to distinguish from the coordinator dashboard's
      // 'referral-deeplink' (same intent shape, different host treatment).
      reportDeepLinkClick('admin-escalation-deeplink', classifyDeepLinkOutcome('done', undefined, false));
      const row = queue.find((r) => r.id === referralId);
      // We only reach 'done' when isTargetReady returned true, so row is
      // guaranteed to be present — belt and suspenders.
      if (row) setOpenReferral(row);
    } else if (dlState.kind === 'stale') {
      const isOffline = typeof navigator !== 'undefined' && !navigator.onLine;
      reportDeepLinkClick('admin-escalation-deeplink', classifyDeepLinkOutcome('stale', dlState.reason, isOffline));
      setDeepLinkToast(intl.formatMessage({ id: 'notifications.deepLink.escalationStale' }));
      // Phase 3 D3 + Phase 4 task 11.13 fix — mark the notification READ
      // via the stale-fallback (see CoordinatorDashboard.tsx for the full
      // rationale; same contract across all 3 deep-link hosts).
      if (dlState.intent.referralId) {
        markNotificationsActedByPayload('referralId', dlState.intent.referralId, 'stale').catch(() => { /* best-effort */ });
      }
      const t = setTimeout(() => setDeepLinkToast(null), 5000);
      return () => clearTimeout(t);
    }
  }, [dlState, queue, intl]);

  const handleClaim = useCallback(async (referral: EscalatedReferral) => {
    setActionSubmitting(true);
    setActionError(null);
    try {
      await api.post(`/api/v1/dv-referrals/${referral.id}/claim`);
      await refresh();
    } catch (err: unknown) {
      const apiErr = err as { message?: string; status?: number };
      if (apiErr.status === 409) {
        setActionError(intl.formatMessage({ id: 'dvEscalations.error.claimedByOther' }));
      } else {
        setActionError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
      }
    } finally {
      setActionSubmitting(false);
    }
  }, [refresh, intl]);

  const handleModalChanged = useCallback(() => {
    refresh();
  }, [refresh]);

  // Hide the segment switch on mobile — queue is the only meaningful surface.
  const showSegments = !isMobile;

  return (
    <div data-testid="dv-escalations-tab">
      {/* D10 stale-deep-link toast. role="alert" announces to screen
          readers; auto-dismisses after 5s. Same contract as coordinator
          dashboard's toast (copy differs for admin queue context). */}
      {deepLinkToast && (
        <div
          role="alert"
          data-testid="dv-escalations-deep-link-toast"
          style={{
            position: 'fixed', top: 16, right: 16, maxWidth: 360, zIndex: 2000,
            backgroundColor: color.warningBg, color: color.text,
            border: `1px solid ${color.warningMid}`,
            padding: '12px 16px', borderRadius: 8, fontSize: text.sm,
            fontWeight: weight.medium,
            boxShadow: '0 4px 16px rgba(0,0,0,0.15)',
          }}
        >
          {deepLinkToast}
        </div>
      )}

      {/* Subtitle */}
      <p style={{
        margin: 0, marginBottom: 16,
        fontSize: text.sm,
        color: color.textSecondary,
      }}>
        <FormattedMessage id="dvEscalations.subtitle" />
      </p>

      {/* Segment switch (desktop only) */}
      {showSegments && (
        <div
          role="tablist"
          aria-label="DV Escalations sections"
          style={{
            display: 'inline-flex',
            gap: 0,
            marginBottom: 20,
            border: `1px solid ${color.border}`,
            borderRadius: 10,
            overflow: 'hidden',
          }}
        >
          <button
            role="tab"
            aria-selected={segment === 'queue'}
            data-testid="dv-escalations-segment-queue"
            onClick={() => setSegment('queue')}
            style={{
              padding: '10px 18px',
              minHeight: 44,
              border: 'none',
              background: segment === 'queue' ? color.primary : 'transparent',
              color: segment === 'queue' ? color.textInverse : color.text,
              fontSize: text.sm,
              fontWeight: segment === 'queue' ? weight.bold : weight.semibold,
              cursor: 'pointer',
            }}
          >
            <FormattedMessage id="dvEscalations.segment.queue" />
          </button>
          <button
            role="tab"
            aria-selected={segment === 'policy'}
            data-testid="dv-escalations-segment-policy"
            onClick={() => setSegment('policy')}
            style={{
              padding: '10px 18px',
              minHeight: 44,
              border: 'none',
              borderLeft: `1px solid ${color.border}`,
              background: segment === 'policy' ? color.primary : 'transparent',
              color: segment === 'policy' ? color.textInverse : color.text,
              fontSize: text.sm,
              fontWeight: segment === 'policy' ? weight.bold : weight.semibold,
              cursor: 'pointer',
            }}
          >
            <FormattedMessage id="dvEscalations.segment.policy" />
          </button>
        </div>
      )}

      {/* Queue segment */}
      {(segment === 'queue' || isMobile) && (
        <div data-testid="dv-escalations-queue-section">
          {/* Live count for screen readers + visible label */}
          <div
            aria-live="polite"
            data-testid="dv-escalation-queue-count"
            style={{
              fontSize: text.sm,
              fontWeight: weight.semibold,
              color: color.textSecondary,
              marginBottom: 12,
            }}
          >
            <FormattedMessage
              id="dvEscalations.queue.count"
              values={{ count: queue.length }}
            />
          </div>

          {actionError && <ErrorBox message={actionError} />}
          {error && <ErrorBox message={intl.formatMessage({ id: 'dvEscalations.error.loadFailed' })} />}

          {loading ? (
            <Spinner />
          ) : queue.length === 0 ? (
            <NoData />
          ) : isMobile ? (
            <EscalatedQueueCardList
              queue={queue}
              onOpenDetail={setOpenReferral}
              onClaim={handleClaim}
              submitting={actionSubmitting}
            />
          ) : (
            <EscalatedQueueTable
              queue={queue}
              onOpenDetail={setOpenReferral}
              onClaim={handleClaim}
              submitting={actionSubmitting}
            />
          )}
        </div>
      )}

      {/* Policy segment (desktop only) */}
      {segment === 'policy' && !isMobile && (
        <div data-testid="dv-escalations-policy-section">
          <EscalationPolicyEditor isMobile={false} />
        </div>
      )}

      {/* Singleton detail modal — owned by the orchestrator */}
      {openReferral && (
        <EscalatedReferralDetailModal
          referral={openReferral}
          onChanged={handleModalChanged}
          onClose={() => setOpenReferral(null)}
          currentUserId={user?.userId}
        />
      )}
    </div>
  );
}

export default DvEscalationsTab;
