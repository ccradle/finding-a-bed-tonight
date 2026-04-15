import { useEffect, useState, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';
import { useDeepLink, type DeepLinkIntent, type ResolvedTarget } from '../hooks/useDeepLink';
import { markNotificationsActedByPayload } from '../services/notificationMarkActed';
import { classifyDeepLinkOutcome, reportDeepLinkClick } from '../services/notificationDeepLinkMetrics';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import { getPopulationTypeLabel } from '../utils/populationTypeLabels';
import { isActive, statusLabelId, statusBadgeColors } from './myPastHoldsHelpers';

/**
 * My Past Holds page (notification-deep-linking Phase 3 tasks 6.1–6.3).
 *
 * <p>Outreach workers need a place to review their recent bed holds —
 * both HELD reservations awaiting confirmation and the terminal states
 * (CONFIRMED / CANCELLED / EXPIRED / CANCELLED_SHELTER_DEACTIVATED).
 * It's also the deep-link target for hold-cancellation notifications:
 * when a shelter deactivation cascades and cancels a worker's HELD
 * reservation, the notification's {@code reservationId} deep-links
 * here with the row highlighted (task 6.4a — wired in a later session).</p>
 *
 * <p><b>14-day window</b> per D-1 (Devon): casual weekend workers whose
 * holds expired 8+ days prior still find them. The "Show older" button
 * (task 6.6) extends the range 14-60 days — also deferred.</p>
 *
 * <p>Rows display shelter name + population + status badge + timestamp
 * + a status-specific primary action. HELD rows wire through to the
 * existing confirm/cancel endpoints. Terminal rows offer a "Find another
 * bed" link back to search. The tel: link per row (D-2) and the offline
 * toast (D-3) land in task 6.4 / 6.10.</p>
 */

interface Reservation {
  id: string;
  shelterId: string;
  shelterName: string | null;
  shelterPhone: string | null;
  populationType: string;
  status: string;
  expiresAt: string | null;
  remainingSeconds: number;
  createdAt: string;
  confirmedAt: string | null;
  cancelledAt: string | null;
  notes: string | null;
}

/** Statuses the page fetches. HELD first for the "Active" group, then terminals. */
const DISPLAY_STATUSES = [
  'HELD',
  'CONFIRMED',
  'CANCELLED',
  'EXPIRED',
  'CANCELLED_SHELTER_DEACTIVATED',
] as const;

const DEFAULT_WINDOW_DAYS = 14;
const EXTENDED_WINDOW_DAYS = 60;

export function MyPastHoldsPage() {
  const intl = useIntl();
  const [searchParams] = useSearchParams();
  const [reservations, setReservations] = useState<Reservation[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionBusyId, setActionBusyId] = useState<string | null>(null);
  // Phase 3 task 6.4a — deep-link highlight + toast state owned by the host;
  // useDeepLink owns the state machine (D-12).
  const [highlightedRowId, setHighlightedRowId] = useState<string | null>(null);
  const [deepLinkToast, setDeepLinkToast] = useState<string | null>(null);
  // Ref-gate so SSE / list refetches don't re-fire scroll/focus/highlight
  // after the user has moved on from the deep-link. Same pattern as Phase 2
  // DvEscalationsTab H-1 fix.
  const lastHandledDlKindRef = useRef<string>('idle');
  // Phase 3 task 6.6 — "Show older" toggle. Default 14 days (D-1). Clicking
  // extends the window to 60 days and refetches; clicking again doesn't
  // un-extend (irreversible per UAT expectation — collapsing would be
  // confusing when the user just scrolled through older rows).
  const [showOlder, setShowOlder] = useState(false);
  const windowDays = showOlder ? EXTENDED_WINDOW_DAYS : DEFAULT_WINDOW_DAYS;
  // Phase 3 task 6.5 — first-ever vs no-recent distinction (D-2 Devon).
  // Tri-state: null = unknown (not yet probed), true = user has at least
  // one reservation all-time, false = user has never held a bed.
  // Probe fires only when the current 14/60-day fetch comes back empty,
  // so it's a single extra API call exactly once per session.
  const [hasAnyReservation, setHasAnyReservation] = useState<boolean | null>(null);

  const fetchReservations = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const statusParam = DISPLAY_STATUSES.join(',');
      const data = await api.get<Reservation[]>(
        `/api/v1/reservations?status=${statusParam}&sinceDays=${windowDays}`,
      );
      setReservations(data ?? []);
    } catch (err: unknown) {
      // Phase 3 task 6.10 (D-3) — when the user is offline, show a
      // connection-specific message instead of the generic "couldn't
      // load." The SW cache fallback is a separate concern not plumbed
      // here (Phase 4 task 8.4 handles the REST polling + cache strategy
      // app-wide). For now we at least give the user accurate feedback.
      const apiErr = err as { message?: string };
      const offlineKey = typeof navigator !== 'undefined' && !navigator.onLine
        ? 'myHolds.error.offline'
        : 'myHolds.error.loadFailed';
      setError(apiErr.message || intl.formatMessage({ id: offlineKey }));
    } finally {
      setLoading(false);
    }
  }, [intl, windowDays]);

  useEffect(() => {
    fetchReservations();
  }, [fetchReservations]);

  // Phase 3 task 6.5 — one-shot probe when the windowed fetch came back
  // empty AND we haven't yet resolved the first-ever question. Fetches
  // the same status set WITHOUT sinceDays so terminal rows older than the
  // window count. One extra API call per session in the "empty list" case;
  // zero extra calls when the user has rows in-window. Short-circuits once
  // resolved (hasAnyReservation !== null).
  useEffect(() => {
    if (loading) return;
    if (reservations.length > 0) {
      if (hasAnyReservation !== true) setHasAnyReservation(true);
      return;
    }
    if (hasAnyReservation !== null) return; // already probed
    let cancelled = false;
    (async () => {
      try {
        const statusParam = DISPLAY_STATUSES.join(',');
        const data = await api.get<Reservation[]>(
          `/api/v1/reservations?status=${statusParam}`,
        );
        if (cancelled) return;
        setHasAnyReservation((data ?? []).length > 0);
      } catch {
        // Probe failure defaults to the less-presumptuous message ("no recent").
        // Not worth surfacing as a blocking error — the page already rendered.
        if (!cancelled) setHasAnyReservation(true);
      }
    })();
    return () => { cancelled = true; };
  }, [loading, reservations.length, hasAnyReservation]);

  // ---------------------------------------------------------------------------
  // notification-deep-linking Phase 3 task 6.4a — useDeepLink wiring
  // ---------------------------------------------------------------------------
  //
  // Callbacks follow the admin-queue pattern (Phase 2): the reservation
  // list fetched above is our data source, so resolveTarget is a
  // pass-through and isTargetReady polls the list until the deep-linked
  // row appears (or the awaiting-target deadline fires → stale toast).
  //
  // URL source: standard useSearchParams — my-past-holds lives at the flat
  // /outreach/my-holds route, unlike the admin queue which nests under
  // /admin#tab and needs useHashSearchParams.

  const resolveTarget = useCallback(async (
    intent: DeepLinkIntent,
  ): Promise<ResolvedTarget<Reservation>> => {
    // Pass through — reservations array is the data source.
    return { intent, resolvedShelterId: null, detail: null };
  }, []);

  const needsUnsavedConfirm = useCallback(() => false, []);
  const expandNoop = useCallback(async () => { /* list is always visible */ }, []);

  const isTargetReady = useCallback((
    resolved: ResolvedTarget<Reservation>,
  ): boolean => {
    if (loading) return false;
    const reservationId = resolved.intent.reservationId;
    // Defensive guard: /outreach/my-holds only accepts reservationId
    // deep-links. A referralId or shelterId intent reaching this page is
    // a URL-routing bug — return false so the hook's awaiting-target
    // deadline surfaces it as a stale toast rather than silent 'done'.
    if (!reservationId) return false;
    return reservations.some((r) => r.id === reservationId);
  }, [loading, reservations]);

  const { state: dlState } = useDeepLink<Reservation>({
    searchParams,
    resolveTarget,
    needsUnsavedConfirm,
    expand: expandNoop,
    isTargetReady,
  });

  // React to state transitions — D11 says my-past-holds focuses the primary
  // action button (not a row heading) because there's no DV-accept safety
  // risk here. The highlight, scroll, and focus all fire on the same
  // 'done' transition.
  useEffect(() => {
    if (lastHandledDlKindRef.current === dlState.kind) return;
    lastHandledDlKindRef.current = dlState.kind;
    if (dlState.kind === 'done') {
      const reservationId = dlState.resolved.intent.reservationId;
      if (!reservationId) return;
      // Phase 4 task 9a.1 — report the deep-link outcome to the metrics
      // counter. tag = 'reservation-deeplink' (intent shape) since the
      // notification type isn't carried through the URL.
      reportDeepLinkClick('reservation-deeplink', classifyDeepLinkOutcome('done', undefined, false));
      setHighlightedRowId(reservationId);
      const rowEl = document.querySelector<HTMLElement>(
        `[data-testid="my-holds-row-${reservationId}"]`,
      );
      const actionEl = document.querySelector<HTMLElement>(
        `[data-testid="my-holds-action-${reservationId}"]`,
      );
      rowEl?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      actionEl?.focus();
    } else if (dlState.kind === 'stale') {
      // D-3 (task 6.10): pick offline-specific wording when we know the
      // stale was caused by a connection issue. useDeepLink reports
      // reason='error' for non-404/403 rejections, which includes network
      // failures — pair with navigator.onLine === false for the offline case.
      const isOffline = typeof navigator !== 'undefined' && !navigator.onLine;
      // Phase 4 task 9a.1 — same metric report on the stale path. The
      // classifier folds isOffline + dlState.reason into the right outcome
      // tag (offline if network failure + offline browser, stale otherwise).
      reportDeepLinkClick('reservation-deeplink', classifyDeepLinkOutcome('stale', dlState.reason, isOffline));
      const toastKey = isOffline && dlState.reason === 'error'
        ? 'myHolds.deepLink.offline'
        : 'myHolds.deepLink.stale';
      setDeepLinkToast(intl.formatMessage({ id: toastKey }));
      // Phase 3 D3 + Phase 4 task 11.13 fix — mark the notification READ
      // via the stale-fallback (see CoordinatorDashboard.tsx for the full
      // rationale; same contract across all 3 deep-link hosts).
      if (dlState.intent.reservationId) {
        markNotificationsActedByPayload('reservationId', dlState.intent.reservationId, 'stale').catch(() => { /* best-effort */ });
      }
      const t = setTimeout(() => setDeepLinkToast(null), 5000);
      return () => clearTimeout(t);
    }
  }, [dlState, intl]);

  const handleConfirm = async (reservationId: string) => {
    setActionBusyId(reservationId);
    try {
      await api.patch(`/api/v1/reservations/${reservationId}/confirm`, {});
      await fetchReservations();
      // Phase 3 task 7.3 — confirm is the terminal "arrival happened"
      // action. Fan-out markActed to every notification with this
      // reservationId (typically the HOLD_CANCELLED_SHELTER_DEACTIVATED
      // that deep-linked the user here, but also any related reminders
      // that may land here later). Best-effort — confirm IS recorded;
      // lifecycle catches up on next bell refresh if this fails.
      markNotificationsActedByPayload('reservationId', reservationId, 'acted').catch(() => { /* best-effort */ });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'myHolds.error.confirmFailed' }));
    } finally {
      setActionBusyId(null);
    }
  };

  const handleCancel = async (reservationId: string) => {
    setActionBusyId(reservationId);
    try {
      await api.patch(`/api/v1/reservations/${reservationId}/cancel`, {});
      await fetchReservations();
      // Phase 3 task 7.3 — cancel is also terminal (the worker explicitly
      // released the bed). Same markActed fan-out as confirm.
      markNotificationsActedByPayload('reservationId', reservationId, 'acted').catch(() => { /* best-effort */ });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'myHolds.error.cancelFailed' }));
    } finally {
      setActionBusyId(null);
    }
  };

  const active = reservations.filter((r) => isActive(r.status));
  const recent = reservations.filter((r) => !isActive(r.status));

  const renderRow = (r: Reservation) => {
    const badge = statusBadgeColors(r.status);
    const createdAgeSeconds = Math.floor(
      (Date.now() - new Date(r.createdAt).getTime()) / 1000,
    );
    const isHighlighted = highlightedRowId === r.id;
    return (
      <div
        key={r.id}
        data-testid={`my-holds-row-${r.id}`}
        tabIndex={isHighlighted ? -1 : undefined}
        style={{
          padding: '14px 16px',
          borderBottom: `1px solid ${color.borderLight}`,
          // D11 + 6.4a: deep-linked row gets a visible left-border accent
          // in the primary-text color. Transition keeps it calm when the
          // page renders after a deep-link navigation.
          borderLeft: isHighlighted ? `4px solid ${color.primaryText}` : '4px solid transparent',
          backgroundColor: isHighlighted ? color.bgHighlight : 'transparent',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          transition: 'background-color 0.2s, border-left-color 0.2s',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
          <div style={{ flex: '1 1 auto', minWidth: 0 }}>
            <div style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text }}>
              {r.shelterName ?? intl.formatMessage({ id: 'myHolds.shelterNameMissing' })}
            </div>
            <div style={{ fontSize: text.xs, color: color.textTertiary, marginTop: 2 }}>
              {getPopulationTypeLabel(r.populationType, intl)}
            </div>
          </div>
          <span
            data-testid={`my-holds-status-${r.id}`}
            style={{
              padding: '3px 10px',
              borderRadius: 6,
              fontSize: text['2xs'],
              fontWeight: weight.bold,
              backgroundColor: badge.bg,
              color: badge.fg,
              whiteSpace: 'nowrap',
            }}
          >
            <FormattedMessage id={statusLabelId(r.status)} />
          </span>
        </div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <DataAge dataAgeSeconds={createdAgeSeconds} />
          {r.status === 'HELD' && (
            <>
              <button
                type="button"
                data-testid={`my-holds-action-${r.id}`}
                onClick={() => handleConfirm(r.id)}
                disabled={actionBusyId === r.id}
                style={{
                  padding: '6px 14px',
                  minHeight: 40,
                  border: 'none',
                  borderRadius: 8,
                  backgroundColor: color.success,
                  color: color.textInverse,
                  fontSize: text.xs,
                  fontWeight: weight.bold,
                  cursor: actionBusyId === r.id ? 'wait' : 'pointer',
                }}
              >
                <FormattedMessage id="myHolds.action.confirm" />
              </button>
              <button
                type="button"
                data-testid={`my-holds-cancel-${r.id}`}
                onClick={() => handleCancel(r.id)}
                disabled={actionBusyId === r.id}
                style={{
                  padding: '6px 14px',
                  minHeight: 40,
                  border: `1px solid ${color.errorMid}`,
                  borderRadius: 8,
                  backgroundColor: color.bg,
                  color: color.errorMid,
                  fontSize: text.xs,
                  fontWeight: weight.bold,
                  cursor: actionBusyId === r.id ? 'wait' : 'pointer',
                }}
              >
                <FormattedMessage id="myHolds.action.cancel" />
              </button>
            </>
          )}
          {(r.status === 'CANCELLED' || r.status === 'EXPIRED' || r.status === 'CANCELLED_SHELTER_DEACTIVATED') && (
            <Link
              to="/outreach"
              data-testid={`my-holds-action-${r.id}`}
              style={{
                padding: '6px 14px',
                minHeight: 40,
                display: 'inline-flex',
                alignItems: 'center',
                borderRadius: 8,
                border: `1px solid ${color.primary}`,
                color: color.primaryText,
                textDecoration: 'none',
                fontSize: text.xs,
                fontWeight: weight.bold,
              }}
            >
              <FormattedMessage id="myHolds.action.findAnother" />
            </Link>
          )}
          {r.status === 'CONFIRMED' && (
            <span style={{ fontSize: text.xs, color: color.textTertiary }}>
              <FormattedMessage id="myHolds.action.confirmedLabel" />
            </span>
          )}
          {/* D-2 (Devon) — tel: link so outreach workers can call the shelter
              directly from the row when they're on a phone. Rendered after
              the primary action so it doesn't compete for attention. Hidden
              when we don't have a phone number (pre-Phase-3 reservations at
              shelters whose row came back without a phone via the batch
              lookup — acceptable fallback). */}
          {r.shelterPhone && (
            <a
              href={`tel:${r.shelterPhone}`}
              data-testid={`my-holds-call-${r.id}`}
              style={{
                padding: '6px 12px',
                minHeight: 40,
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                borderRadius: 8,
                border: `1px solid ${color.borderMedium}`,
                color: color.text,
                textDecoration: 'none',
                fontSize: text.xs,
                fontWeight: weight.semibold,
                backgroundColor: color.bg,
              }}
            >
              <FormattedMessage id="myHolds.action.callShelter" />
            </a>
          )}
        </div>
      </div>
    );
  };

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      {/* Header — matches coordinator dashboard styling */}
      <div
        style={{
          background: `linear-gradient(135deg, ${color.headerGradientStart} 0%, ${color.headerGradientMid} 50%, ${color.headerGradientEnd} 100%)`,
          borderRadius: 16,
          padding: '28px 24px',
          marginBottom: 20,
          color: color.textInverse,
          boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
        }}
      >
        <h1
          data-testid="my-holds-heading"
          style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}
        >
          <FormattedMessage id="myHolds.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: color.headerText }}>
          <FormattedMessage id="myHolds.subtitle" values={{ days: windowDays }} />
        </p>
      </div>

      {/* Deep-link stale toast (D10 unified shape) — 404/403/timeout all
          land here. role="alert" announces to screen readers; auto-dismisses
          after 5s via the effect that sets it. */}
      {deepLinkToast && (
        <div
          role="alert"
          data-testid="my-holds-deep-link-toast"
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

      {error && (
        <div
          role="alert"
          data-testid="my-holds-error"
          style={{
            backgroundColor: color.errorBg,
            color: color.error,
            padding: '12px 16px',
            borderRadius: 10,
            marginBottom: 16,
            fontSize: text.sm,
            fontWeight: weight.medium,
          }}
        >
          {error}
        </div>
      )}

      {loading && (
        <div style={{ textAlign: 'center', padding: 24, color: color.textMuted, fontSize: text.sm }}>
          <FormattedMessage id="myHolds.loading" />
        </div>
      )}

      {!loading && reservations.length === 0 && (
        <div
          data-testid="my-holds-empty"
          style={{
            padding: 32,
            textAlign: 'center',
            color: color.textTertiary,
            fontSize: text.sm,
            border: `1px dashed ${color.border}`,
            borderRadius: 12,
          }}
        >
          {/* Phase 3 task 6.5 — two distinct empty messages:
              - hasAnyReservation === false → user has NEVER held a bed (first-ever)
              - otherwise (true | null) → user had rows but none in the window
              Tri-state default-false-on-probe keeps the less-presumptuous
              message while the probe is in flight. */}
          {hasAnyReservation === false ? (
            <FormattedMessage id="myHolds.emptyFirstEver" />
          ) : (
            <FormattedMessage id="myHolds.empty" />
          )}
          <div style={{ marginTop: 12 }}>
            <Link
              to="/outreach"
              style={{
                color: color.primaryText,
                fontWeight: weight.semibold,
                textDecoration: 'none',
              }}
            >
              <FormattedMessage id="myHolds.emptyCta" />
            </Link>
          </div>
        </div>
      )}

      {!loading && active.length > 0 && (
        <section data-testid="my-holds-active-section" style={{ marginBottom: 20 }}>
          <h2
            style={{
              fontSize: text.sm,
              fontWeight: weight.bold,
              color: color.primaryText,
              margin: '0 0 8px',
              textTransform: 'uppercase',
              letterSpacing: '0.04em',
            }}
          >
            <FormattedMessage id="myHolds.section.active" values={{ count: active.length }} />
          </h2>
          <div
            style={{
              backgroundColor: color.bg,
              border: `1px solid ${color.border}`,
              borderRadius: 12,
              overflow: 'hidden',
            }}
          >
            {active.map(renderRow)}
          </div>
        </section>
      )}

      {!loading && recent.length > 0 && (
        <section data-testid="my-holds-recent-section">
          <h2
            style={{
              fontSize: text.sm,
              fontWeight: weight.bold,
              color: color.textSecondary,
              margin: '0 0 8px',
              textTransform: 'uppercase',
              letterSpacing: '0.04em',
            }}
          >
            <FormattedMessage id="myHolds.section.recent" values={{ count: recent.length }} />
          </h2>
          <div
            style={{
              backgroundColor: color.bg,
              border: `1px solid ${color.border}`,
              borderRadius: 12,
              overflow: 'hidden',
            }}
          >
            {recent.map(renderRow)}
          </div>
        </section>
      )}

      {/* Phase 3 task 6.6 — "Show older" button extends the window from 14
          to 60 days. Hidden once the user has opted into the extended view
          (they can still see the broader range in the subtitle). */}
      {!loading && !showOlder && (
        <div style={{ textAlign: 'center', marginTop: 20 }}>
          <button
            type="button"
            data-testid="my-holds-load-older"
            onClick={() => setShowOlder(true)}
            style={{
              padding: '10px 20px',
              minHeight: 44,
              backgroundColor: color.bgSecondary,
              color: color.primaryText,
              border: `1px solid ${color.borderMedium}`,
              borderRadius: 10,
              fontSize: text.sm,
              fontWeight: weight.semibold,
              cursor: 'pointer',
            }}
          >
            <FormattedMessage id="myHolds.showOlder" values={{ days: EXTENDED_WINDOW_DAYS }} />
          </button>
        </div>
      )}
    </div>
  );
}
