import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useSearchParams } from 'react-router-dom';
import { api } from '../services/api';
import { enqueueAction } from '../services/offlineQueue';
import { CoordinatorReferralBanner } from '../components/CoordinatorReferralBanner';
import { DataAge } from '../components/DataAge';
import { useDeepLink, type DeepLinkIntent, type ResolvedTarget } from '../hooks/useDeepLink';
import { markNotificationsActedByPayload } from '../services/notificationMarkActed';
import { classifyDeepLinkOutcome, reportDeepLinkClick } from '../services/notificationDeepLinkMetrics';
import { text, weight, leading } from '../theme/typography';
import { color } from '../theme/colors';
import { getPopulationTypeLabel } from '../utils/populationTypeLabels';

const POPULATION_TYPES = [
  'SINGLE_ADULT',
  'FAMILY_WITH_CHILDREN',
  'WOMEN_ONLY',
  'VETERAN',
  'YOUTH_18_24',
  'YOUTH_UNDER_18',
  'DV_SURVIVOR',
];

interface AvailabilitySummary {
  totalBedsAvailable: number | null;
  populationTypesServed: number;
  lastUpdated: string | null;
  dataAgeSeconds: number | null;
  dataFreshness: string;
}

interface Shelter {
  id: string;
  name: string;
  addressStreet: string;
  addressCity: string;
  addressState: string;
  addressZip: string;
  updatedAt: string;
  dvShelter?: boolean;
  active?: boolean;
  deactivatedAt?: string | null;
}

interface PendingReferral {
  id: string;
  householdSize: number;
  populationType: string;
  urgency: string;
  specialNeeds: string | null;
  callbackNumber: string;
  status: string;
  createdAt: string;
  expiresAt: string;
  remainingSeconds: number | null;
}

interface ShelterListItem {
  shelter: Shelter;
  availabilitySummary: AvailabilitySummary | null;
}

interface ShelterDetail {
  shelter: Shelter;
  availability?: AvailabilityDto[];
}

interface AvailabilityDto {
  populationType: string;
  bedsTotal: number;
  bedsOccupied: number;
  bedsOnHold: number;
  bedsAvailable: number;
  acceptingNewGuests: boolean;
  overflowBeds: number;
  snapshotTs: string;
  dataAgeSeconds: number;
  dataFreshness: string;
}

interface AvailabilityEdit {
  populationType: string;
  bedsTotal: number;
  bedsOccupied: number;
  bedsOnHold: number;
  overflowBeds: number;
}

/**
 * Response shape of {@code GET /api/v1/dv-referrals/{id}}, mirroring the
 * Java {@code ReferralTokenResponse} record. Used by the deep-link processor
 * to resolve a referralId from a notification payload to its containing
 * shelter so the dashboard can auto-expand the right card.
 *
 * M-4 fix from the war-room review: replaces the unsafe
 * {@code <{ shelterId: string } & PendingReferral>} cast that hid the
 * missing endpoint bug behind a TypeScript lie.
 */
interface ReferralDetailResponse {
  id: string;
  shelterId: string;
  shelterName: string;
  householdSize: number;
  populationType: string;
  urgency: string;
  specialNeeds: string | null;
  callbackNumber: string;
  status: string;
  createdAt: string;
  expiresAt: string | null;
  remainingSeconds: number | null;
}

export function CoordinatorDashboard() {
  const intl = useIntl();
  const [searchParams, setSearchParams] = useSearchParams();
  const [shelters, setShelters] = useState<ShelterListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editAvailability, setEditAvailability] = useState<AvailabilityEdit[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [availSaving, setAvailSaving] = useState<string | null>(null);
  const [availSaved, setAvailSaved] = useState<string | null>(null);
  const [pendingReferrals, setPendingReferrals] = useState<PendingReferral[]>([]);
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [surgeActive, setSurgeActive] = useState(false);

  // notification-deep-linking (Issue #106) — host-owned UI state.
  //
  // deepLinkToast: transient message shown when a deep-link target is stale
  //   (referral no longer pending, 404, 403, expand timeout). Per D10 the
  //   same text covers all four reasons so the response never leaks whether
  //   the referral exists vs the user lacks access.
  // deepLinkAnnouncement: text rendered into the role="status" aria-live
  //   region so screen readers announce "Opened pending DV referral: ..."
  //   after focus lands.
  //
  // The deep-link state machine itself lives in useDeepLink (D-12). The
  // dialog-gate, idempotency tracking, focus-target stash, and error
  // routing that previously needed four parallel state/ref trackers
  // (pendingDeepLink, pendingFocus, processedRef, plus the two effects
  // wiring them) are all owned by the hook now.
  //
  // originalAvailRef survives because it's an unsaved-state concern that
  // belongs to the host — the hook only asks "should we confirm?" via
  // needsUnsavedConfirm, and isDirty() answers using this snapshot.
  const [deepLinkToast, setDeepLinkToast] = useState<string | null>(null);
  const [deepLinkAnnouncement, setDeepLinkAnnouncement] = useState('');
  const originalAvailRef = useRef<AvailabilityEdit[]>([]);

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ShelterListItem[]>('/api/v1/shelters');
      setShelters(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchShelters(); }, [fetchShelters]);

  useEffect(() => {
    api.get<{ id: string; status: string }[]>('/api/v1/surge-events')
      .then(surges => setSurgeActive((surges || []).some(s => s.status === 'ACTIVE')))
      .catch(() => {});
  }, []);

  // Countdown timer for pending referrals (Design D1: client-side timer)
  const countdownRef = useRef<ReturnType<typeof setInterval> | null>(null);
  useEffect(() => {
    if (pendingReferrals.some(r => r.remainingSeconds != null && r.remainingSeconds > 0)) {
      countdownRef.current = setInterval(() => {
        setPendingReferrals(prev => prev.map(r => ({
          ...r,
          remainingSeconds: r.remainingSeconds != null ? Math.max(0, r.remainingSeconds - 1) : null,
        })));
      }, 1000);
    }
    return () => { if (countdownRef.current) clearInterval(countdownRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingReferrals.length]);

  // SSE listener for dv-referral.expired events (Design D6)
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      const expiredIds: string[] = detail?.tokenIds || [];
      if (expiredIds.length > 0) {
        setPendingReferrals(prev => prev.map(r =>
          expiredIds.includes(r.id) ? { ...r, remainingSeconds: 0 } : r
        ));
      }
    };
    window.addEventListener('fabt:referral-expired', handler);
    return () => window.removeEventListener('fabt:referral-expired', handler);
  }, []);

  const fmtAddr = (s: Shelter) =>
    [s.addressStreet, s.addressCity, s.addressState, s.addressZip].filter(Boolean).join(', ');

  // S-1 fix: detect unsaved bed count edits on the currently expanded shelter.
  // Compares editAvailability against the server-snapshot captured in openShelter.
  // Returns false when nothing is expanded (no possible unsaved state).
  // Consumed by needsUnsavedConfirm in the useDeepLink wiring below.
  const isDirty = useCallback((): boolean => {
    if (!expandedId || originalAvailRef.current.length === 0) return false;
    if (editAvailability.length !== originalAvailRef.current.length) return true;
    return editAvailability.some((current, i) => {
      const orig = originalAvailRef.current[i];
      return !orig
        || current.populationType !== orig.populationType
        || current.bedsTotal !== orig.bedsTotal
        || current.bedsOccupied !== orig.bedsOccupied
        || current.bedsOnHold !== orig.bedsOnHold
        || current.overflowBeds !== orig.overflowBeds;
    });
  }, [expandedId, editAvailability]);

  // openShelter is consumed both by direct user clicks on the shelter card
  // header and by the useDeepLink hook (via expandForDeepLink below).
  // Wrapped in useCallback so the hook's expandForDeepLink dep is stable.
  const openShelter = useCallback(async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null);
      originalAvailRef.current = [];
      return;
    }
    setDetailLoading(true);
    setError(null);
    try {
      const detail = await api.get<ShelterDetail>(`/api/v1/shelters/${id}`);

      // Initialize availability edit from current snapshots (single source of truth — D10)
      const availMap = new Map((detail.availability || []).map((a) => [a.populationType, a]));
      const availEdit = POPULATION_TYPES.map((pt) => {
        const a = availMap.get(pt);
        return {
          populationType: pt,
          bedsTotal: a?.bedsTotal ?? 0,
          bedsOccupied: a?.bedsOccupied ?? 0,
          bedsOnHold: a?.bedsOnHold ?? 0,
          overflowBeds: a?.overflowBeds ?? 0,
        };
      });
      setEditAvailability(availEdit);
      // S-1 (deep-linking): snapshot the server state so isDirty() can detect
      // unsaved bed count edits before auto-collapsing in a deep-link flow.
      // Deep-copy via JSON round-trip — AvailabilityEdit objects are flat,
      // so this is safe and avoids accidental shared references.
      originalAvailRef.current = JSON.parse(JSON.stringify(availEdit));
      setExpandedId(id);

      // Fetch pending DV referrals for this shelter (silent fail if not DV or no access)
      try {
        const refs = await api.get<PendingReferral[]>(`/api/v1/dv-referrals/pending?shelterId=${id}`);
        setPendingReferrals(refs || []);
      } catch { setPendingReferrals([]); /* DV referral fetch — silent fail for non-DV shelters */ }
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setDetailLoading(false);
    }
  }, [expandedId, intl]);

  // ---------------------------------------------------------------------------
  // notification-deep-linking — useDeepLink callbacks (D-12 state machine)
  // ---------------------------------------------------------------------------
  //
  // Four small host-callbacks define the host-specific behavior; the rest is
  // owned by useDeepLink (URL → resolve → optional confirm → expand → wait
  // for target → done | stale, with timeouts that prevent stuck states).
  //
  // resolveTarget: referralId → fetch ReferralDetailResponse → return its
  //   shelterId. shelterId-only intents skip the fetch.
  // needsUnsavedConfirm: true when current card has unsaved edits AND the
  //   target is a different shelter (S-1).
  // expand: defer to openShelter, but only when actually switching shelters.
  // isTargetReady: detail-bearing intents need the screening row to be in
  //   pendingReferrals; shelterId-only intents are ready as soon as the
  //   correct shelter is expanded and detailLoading is false.

  const resolveTarget = useCallback(async (
    intent: DeepLinkIntent,
  ): Promise<ResolvedTarget<ReferralDetailResponse>> => {
    if (intent.referralId) {
      const detail = await api.get<ReferralDetailResponse>(
        `/api/v1/dv-referrals/${intent.referralId}`,
      );
      return { intent, resolvedShelterId: detail.shelterId, detail };
    }
    if (intent.shelterId) {
      return { intent, resolvedShelterId: intent.shelterId, detail: null };
    }
    // Reservation-only intent: not handled by the coordinator dashboard
    // (that lives on /outreach/my-holds in Phase 3). Treat as no-op.
    return { intent, resolvedShelterId: null, detail: null };
  }, []);

  const needsUnsavedConfirm = useCallback((
    resolved: ResolvedTarget<ReferralDetailResponse>,
  ): boolean => {
    return Boolean(
      isDirty()
      && resolved.resolvedShelterId
      && expandedId
      && resolved.resolvedShelterId !== expandedId,
    );
  }, [isDirty, expandedId]);

  const expandForDeepLink = useCallback(async (
    resolved: ResolvedTarget<ReferralDetailResponse>,
  ): Promise<void> => {
    if (!resolved.resolvedShelterId) return;
    if (expandedId === resolved.resolvedShelterId) return; // already expanded
    await openShelter(resolved.resolvedShelterId);
  }, [expandedId, openShelter]);

  const isTargetReady = useCallback((
    resolved: ResolvedTarget<ReferralDetailResponse>,
  ): boolean => {
    if (detailLoading) return false;
    if (!resolved.resolvedShelterId) return false;
    if (expandedId !== resolved.resolvedShelterId) return false;
    if (resolved.detail) {
      // referralId path — the screening row must exist in the fetched list.
      return pendingReferrals.some((r) => r.id === resolved.detail!.id);
    }
    // shelterId-only path — expansion alone is sufficient.
    return true;
  }, [detailLoading, expandedId, pendingReferrals]);

  const { state: dlState, confirm: dlConfirm } = useDeepLink<ReferralDetailResponse>({
    searchParams,
    resolveTarget,
    needsUnsavedConfirm,
    expand: expandForDeepLink,
    isTargetReady,
  });

  // Side effects from state transitions — host owns DOM/UI concerns,
  // hook owns state correctness. One useEffect, keyed on dlState.kind.
  //
  // Phase 4 task 9a.1: also reports the deep-link click outcome to
  // /api/v1/metrics/notification-deeplink-click. Tag derived from the
  // intent shape (referral-deeplink for ?referralId, shelter-deeplink
  // for ?shelterId) — coarser than per-notification-type but doesn't
  // require an extra lookup. Phase 5 polish: pass the originating
  // notification type through the URL or via a context if finer
  // granularity is needed.
  useEffect(() => {
    if (dlState.kind === 'done') {
      const { resolved } = dlState;
      const metricType = resolved.detail ? 'referral-deeplink' : 'shelter-deeplink';
      reportDeepLinkClick(metricType, classifyDeepLinkOutcome('done', undefined, false));
      if (resolved.detail) {
        const rowEl = document.querySelector<HTMLElement>(
          `[data-testid="screening-${resolved.detail.id}"]`,
        );
        if (rowEl) {
          rowEl.scrollIntoView({ block: 'center', behavior: 'smooth' });
          // S-2 — focus the row heading, not Accept. tabIndex={-1} on the row
          // makes programmatic focus work without adding a natural tab stop.
          rowEl.focus();
        }
        // T-1 — aria-live announcement (population type + size + urgency only;
        // no PII).
        setDeepLinkAnnouncement(intl.formatMessage(
          { id: 'notifications.deepLink.referralOpened' },
          {
            populationType: getPopulationTypeLabel(resolved.detail.populationType, intl),
            size: resolved.detail.householdSize,
            urgency: resolved.detail.urgency,
          },
        ));
      } else if (resolved.resolvedShelterId) {
        const cardEl = document.querySelector<HTMLElement>(
          `[data-testid="shelter-card-${resolved.resolvedShelterId}"]`,
        );
        cardEl?.scrollIntoView({ block: 'center', behavior: 'smooth' });
        cardEl?.focus();
        setDeepLinkAnnouncement(intl.formatMessage({ id: 'notifications.deepLink.shelterOpened' }));
      }
    } else if (dlState.kind === 'stale') {
      // D10 — single stale toast for not-found, race, error, and timeout.
      // role="alert" on the toast announces on its own; no aria-live update
      // needed (avoids the H-3 double-announce regression).
      const isOffline = typeof navigator !== 'undefined' && !navigator.onLine;
      const metricType = dlState.intent.referralId ? 'referral-deeplink' : 'shelter-deeplink';
      reportDeepLinkClick(metricType, classifyDeepLinkOutcome('stale', dlState.reason, isOffline));
      setDeepLinkToast(intl.formatMessage({ id: 'notifications.deepLink.stale' }));
      // Phase 3 D3 + Phase 4 task 11.13 fix — the stale outcome marks the
      // notification READ (via /read) without marking it acted. This
      // preserves the "I saw too late" vs "I acted" lifecycle distinction.
      // The backend contract is pinned by task 9.4
      // (task_9_4_markRead_doesNotSetActedAt). Without this frontend call,
      // stale-resolved notifications stayed unread forever — surfaced by
      // Playwright task 11.13.
      if (dlState.intent.referralId) {
        markNotificationsActedByPayload('referralId', dlState.intent.referralId, 'stale').catch(() => { /* best-effort */ });
      }
      const t = setTimeout(() => setDeepLinkToast(null), 5000);
      return () => clearTimeout(t);
    }
  }, [dlState, intl]);

  const acceptReferral = async (tokenId: string) => {
    try {
      await api.patch(`/api/v1/dv-referrals/${tokenId}/accept`, {});
      setPendingReferrals(prev => prev.filter(r => r.id !== tokenId));
      // Phase 3 task 7.2 — after a successful accept, mark every
      // notification carrying this referralId as acted. D3: markActed
      // fires only on SUCCESSFUL terminal actions. If the markActed call
      // itself fails we swallow — the referral IS accepted; lifecycle
      // state catches up on next bell refresh.
      markNotificationsActedByPayload('referralId', tokenId, 'acted').catch(() => { /* best-effort */ });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      if (apiErr.message?.includes('expired')) {
        setError(intl.formatMessage({ id: 'referral.expiredError' }));
        setPendingReferrals(prev => prev.map(r => r.id === tokenId ? { ...r, remainingSeconds: 0 } : r));
      } else {
        setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
      }
    }
  };

  const rejectReferral = async (tokenId: string) => {
    if (!rejectReason.trim()) return;
    try {
      await api.patch(`/api/v1/dv-referrals/${tokenId}/reject`, { reason: rejectReason });
      setPendingReferrals(prev => prev.filter(r => r.id !== tokenId));
      setRejectingId(null);
      setRejectReason('');
      // Phase 3 task 7.2 — reject is a terminal action too (coordinator
      // decided NOT to shelter this survivor). markActed applies.
      markNotificationsActedByPayload('referralId', tokenId, 'acted').catch(() => { /* best-effort */ });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      if (apiErr.message?.includes('expired')) {
        setError(intl.formatMessage({ id: 'referral.expiredError' }));
        setPendingReferrals(prev => prev.map(r => r.id === tokenId ? { ...r, remainingSeconds: 0 } : r));
        setRejectingId(null);
        setRejectReason('');
      } else {
        setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
      }
    }
  };

  const updateOverflow = (popType: string, delta: number) => {
    setEditAvailability(prev =>
      prev.map(a => a.populationType !== popType ? a : { ...a, overflowBeds: Math.max(0, a.overflowBeds + delta) })
    );
  };

  const updateAvailField = (popType: string, field: 'bedsTotal' | 'bedsOccupied' | 'bedsOnHold', delta: number) => {
    setEditAvailability((prev) =>
      prev.map((a) => {
        if (a.populationType !== popType) return a;
        const newVal = Math.max(0, a[field] + delta);
        if (field === 'bedsTotal') {
          // When total changes, clamp occupied and on_hold to not exceed new total
          const newTotal = newVal;
          const clampedOccupied = Math.min(a.bedsOccupied, newTotal);
          const clampedOnHold = Math.min(a.bedsOnHold, newTotal - clampedOccupied);
          return { ...a, bedsTotal: newTotal, bedsOccupied: clampedOccupied, bedsOnHold: clampedOnHold };
        }
        // Enforce INV-5: occupied + on_hold <= total
        if (field === 'bedsOccupied') {
          return { ...a, bedsOccupied: Math.min(newVal, a.bedsTotal - a.bedsOnHold) };
        } else {
          return { ...a, bedsOnHold: Math.min(newVal, a.bedsTotal - a.bedsOccupied) };
        }
      })
    );
  };

  /**
   * Submit a single population's availability edit.
   *
   * Returns 'saved' on a successful PATCH, 'queued' when offline (or after a
   * network failure that the offline queue accepted), and 'failed' when both
   * the network and IndexedDB rejected the write. Callers that need to chain
   * additional work after a save (e.g., the unsaved-state dialog's
   * Save-and-proceed flow — H-4 from the war-room review) inspect the return
   * value. Existing fire-and-forget callers (the per-row Save buttons) ignore
   * it and the previous behavior is preserved.
   *
   * H-1 fix: on a 'saved' result the originalAvailRef snapshot for the
   * affected population is updated in place so {@code isDirty()} no longer
   * reports the post-save state as dirty. Without this, a coordinator who
   * saved via the dialog would hit the unsaved-state confirm again on every
   * subsequent deep-link.
   */
  // H-5 fix: useCallback so handleSaveAndProceed (in dialog flow) and the
  // per-row Save buttons share a stable identity. Closes over editAvailability
  // and intl + fetchShelters; all listed in deps.
  const submitAvailability = useCallback(async (
    shelterId: string,
    popType: string,
  ): Promise<'saved' | 'queued' | 'failed'> => {
    const avail = editAvailability.find((a) => a.populationType === popType);
    if (!avail) return 'failed';
    setAvailSaving(popType);
    setError(null);
    const payload = {
      populationType: popType,
      bedsTotal: avail.bedsTotal,
      bedsOccupied: avail.bedsOccupied,
      bedsOnHold: avail.bedsOnHold,
      acceptingNewGuests: true,
      overflowBeds: avail.overflowBeds,
    };
    try {
      if (!navigator.onLine) {
        await enqueueAction('UPDATE_AVAILABILITY', `/api/v1/shelters/${shelterId}/availability`, 'PATCH', payload);
        setAvailSaved(popType);
        setError(intl.formatMessage({ id: 'coord.updateQueued', defaultMessage: 'Update queued — will send when online' }));
        setTimeout(() => setAvailSaved(null), 1500);
        return 'queued';
      }
      await api.patch(`/api/v1/shelters/${shelterId}/availability`, payload);
      setAvailSaved(popType);
      // H-1: refresh the snapshot for this population so isDirty() returns
      // false until the user makes a NEW edit. Mutate in place — the ref
      // identity stays the same; only the row's fields change.
      const idx = originalAvailRef.current.findIndex((a) => a.populationType === popType);
      if (idx >= 0) {
        originalAvailRef.current[idx] = {
          populationType: popType,
          bedsTotal: avail.bedsTotal,
          bedsOccupied: avail.bedsOccupied,
          bedsOnHold: avail.bedsOnHold,
          overflowBeds: avail.overflowBeds,
        };
      }
      // Refresh shelter list for updated summary
      fetchShelters();
      setTimeout(() => setAvailSaved(null), 1500);
      return 'saved';
    } catch (err: unknown) {
      // Online but request failed — enqueue as fallback
      const apiErr = err as { message?: string };
      try {
        await enqueueAction('UPDATE_AVAILABILITY', `/api/v1/shelters/${shelterId}/availability`, 'PATCH', payload);
        setAvailSaved(popType);
        setError(intl.formatMessage({ id: 'coord.updateQueued', defaultMessage: 'Update queued — will send when online' }));
        setTimeout(() => setAvailSaved(null), 1500);
        return 'queued';
      } catch {
        // IndexedDB also failed — show API error as last resort
        setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
        return 'failed';
      }
    } finally {
      setAvailSaving(null);
    }
  }, [editAvailability, intl, fetchShelters]);

  // S-1 dialog handlers — drive the useDeepLink state machine via dlConfirm.
  //
  // handleCancelDialog: confirm('abort') → state machine returns to idle.
  //   The deep-link won't re-fire for the SAME URL (intent equality blocks
  //   re-dispatch), but a fresh URL or a banner click WILL. The hook's
  //   intent-equality replaces the prior processedRef leak whereby Cancel
  //   silently blocked future re-triggers for the same notification.
  //
  // handleDiscardAndProceed: restore the server snapshot locally (server
  //   was never changed), then confirm('continue') → state machine moves
  //   to expanding.
  //
  // handleSaveAndProceed: submit every dirty population. If ALL saves
  //   succeed or queue for offline retry, confirm('continue'). On any
  //   'failed' result, leave the dialog open so the user doesn't lose
  //   edits (the caller already set the error banner).
  const handleCancelDialog = () => dlConfirm('abort');
  const handleDiscardAndProceed = () => {
    setEditAvailability(JSON.parse(JSON.stringify(originalAvailRef.current)));
    dlConfirm('continue');
  };
  const handleSaveAndProceed = async () => {
    if (dlState.kind !== 'awaiting-confirm') return;
    if (!expandedId) { dlConfirm('abort'); return; }
    const dirtyPops = editAvailability.filter((current, i) => {
      const orig = originalAvailRef.current[i];
      return !orig
        || current.bedsTotal !== orig.bedsTotal
        || current.bedsOccupied !== orig.bedsOccupied
        || current.bedsOnHold !== orig.bedsOnHold
        || current.overflowBeds !== orig.overflowBeds;
    });
    const results = await Promise.all(
      dirtyPops.map((avail) => submitAvailability(expandedId, avail.populationType)),
    );
    if (results.some((r) => r === 'failed')) return; // dialog stays open
    dlConfirm('continue');
  };

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      {/* T-1: aria-live status region for deep-link announcements. Visually
          hidden (off-screen) so it doesn't clutter the UI, but screen readers
          announce changes to its text content. No PII in announcements. */}
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="deep-link-announcement"
        style={{
          position: 'absolute', width: 1, height: 1, padding: 0,
          margin: -1, overflow: 'hidden', clip: 'rect(0,0,0,0)',
          whiteSpace: 'nowrap', border: 0,
        }}
      >
        {deepLinkAnnouncement}
      </div>

      {/* D10: stale/unauthorized-referral toast. Same text for 404 and 403
          to avoid leaking whether the referral exists. Non-blocking; auto-
          dismisses after 5s. */}
      {deepLinkToast && (
        <div
          role="alert"
          data-testid="deep-link-toast"
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

      {/* S-1: unsaved bed count edits confirmation. Modal dialog; blocks
          the deep-link until the user chooses. Save commits, Discard blows
          away edits, Cancel preserves the current view. The dialog renders
          whenever the deep-link state machine is in awaiting-confirm. */}
      {dlState.kind === 'awaiting-confirm' && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="unsaved-dialog-title"
          data-testid="unsaved-changes-dialog"
          style={{
            position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            zIndex: 3000,
          }}
        >
          <div style={{
            backgroundColor: color.bg, padding: 24, borderRadius: 12,
            maxWidth: 440, width: '90%', boxShadow: '0 8px 32px rgba(0,0,0,0.25)',
          }}>
            <h2
              id="unsaved-dialog-title"
              style={{ margin: '0 0 12px', fontSize: text.lg, fontWeight: weight.bold, color: color.text }}
            >
              <FormattedMessage id="notifications.deepLink.unsavedTitle" />
            </h2>
            <p style={{ margin: '0 0 20px', fontSize: text.base, color: color.text, lineHeight: 1.5 }}>
              <FormattedMessage id="notifications.deepLink.unsavedMessage" />
            </p>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
              <button
                data-testid="unsaved-dialog-cancel"
                onClick={handleCancelDialog}
                // L-2 fix: Cancel is the safest default — a coordinator who
                // reflexively hits Enter shouldn't accidentally save bad data
                // OR discard real edits. Cancel preserves the current view so
                // the user can review what they had before deciding.
                autoFocus
                style={{
                  padding: '10px 16px', minHeight: 44, borderRadius: 8,
                  border: `1px solid ${color.border}`, backgroundColor: color.bg,
                  color: color.text, fontSize: text.sm, fontWeight: weight.semibold,
                  cursor: 'pointer',
                }}
              >
                <FormattedMessage id="notifications.deepLink.unsavedCancel" />
              </button>
              <button
                data-testid="unsaved-dialog-discard"
                onClick={handleDiscardAndProceed}
                style={{
                  padding: '10px 16px', minHeight: 44, borderRadius: 8,
                  border: `1px solid ${color.errorMid}`, backgroundColor: color.bg,
                  color: color.errorMid, fontSize: text.sm, fontWeight: weight.semibold,
                  cursor: 'pointer',
                }}
              >
                <FormattedMessage id="notifications.deepLink.unsavedDiscard" />
              </button>
              <button
                data-testid="unsaved-dialog-save"
                onClick={handleSaveAndProceed}
                style={{
                  padding: '10px 16px', minHeight: 44, borderRadius: 8,
                  border: 'none', backgroundColor: color.primary,
                  color: color.textInverse, fontSize: text.sm, fontWeight: weight.bold,
                  cursor: 'pointer',
                }}
              >
                <FormattedMessage id="notifications.deepLink.unsavedSave" />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Header */}
      <div style={{
        background: `linear-gradient(135deg, ${color.headerGradientStart} 0%, ${color.headerGradientMid} 50%, ${color.headerGradientEnd} 100%)`,
        borderRadius: 16, padding: '28px 24px', marginBottom: 20, color: color.textInverse,
        boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
      }}>
        <h1 data-testid="coordinator-heading" style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="coord.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: color.headerText }}>
          <FormattedMessage id="coord.subtitle" />
        </p>
      </div>

      {/* T-43: Persistent referral banner — not dismissable, resolves when actioned.
          notification-deep-linking Issue #106 Section 16 (D-BP): the banner
          routes to the oldest pending referral regardless of how the user
          arrived. Two cases, one code path:
           1. URL already carries ?referralId=X (notification deep-link in
              flight) — {@code useDeepLink} already processed it. Banner
              click is a no-op (target.source === 'url' and the URL is
              already there; re-navigating to the same URL adds no info).
           2. URL has no ?referralId — banner got a {@code firstPending}
              routing hint from {@code GET /pending/count}. Click navigates
              to {@code /coordinator?referralId=<hint>}; URL change triggers
              {@code useDeepLink} which resolves → expands → focuses the row.
          The prior "first DV shelter" fallback is GONE — it picked the
          alphabetically-first DV shelter regardless of where the pending
          referral actually lived, which is the original user story that
          motivated this entire change. */}
      <CoordinatorReferralBanner
        referralId={searchParams.get('referralId') || undefined}
        onBannerClick={(target) => {
          if (!target) return; // defensive — banner only renders when count > 0
          if (target.source === 'url') {
            // useDeepLink already processed this referralId. Re-clicking the
            // same URL adds no information.
            return;
          }
          // source === 'hint': navigate to the pending referral. useDeepLink
          // picks up the URL change via the searchParams effect and drives
          // the resolve → expand → scroll → focus sequence. If the referral
          // is no longer pending by the time the fetch lands, useDeepLink
          // transitions to 'stale' and surfaces the stale toast (Scenario 3
          // of banner-click-navigation).
          setSearchParams({ referralId: target.referralId });
        }}
      />

      {/* Status */}
      <div style={{ fontSize: text.sm, color: color.textTertiary, marginBottom: 10, fontWeight: weight.semibold, letterSpacing: '0.02em' }}>
        {loading
          ? <FormattedMessage id="coord.loading" />
          : <FormattedMessage id="coord.shelterCount" values={{ count: shelters.length }} />}
      </div>

      {error && (
        <div style={{
          backgroundColor: color.errorBg, color: color.error, padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium,
        }}>{error}</div>
      )}

      {loading && <Spinner />}

      {detailLoading && (
        <div style={{
          backgroundColor: color.bgHighlight, color: color.primaryText, padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium, textAlign: 'center',
        }}>
          <FormattedMessage id="coord.loading" />
        </div>
      )}

      {/* Shelter cards */}
      {!loading && shelters.map((item) => {
        const s = item.shelter;
        const summary = item.availabilitySummary;
        const isExpanded = expandedId === s.id;
        const isInactive = s.active === false;

        return (
          <div
            key={s.id}
            style={{
              marginBottom: 10, borderRadius: 14,
              border: `2px solid ${isExpanded ? color.primary : color.border}`,
              backgroundColor: isInactive ? color.bgSecondary : color.bg,
              transition: 'border-color 0.2s, background-color 0.3s',
              overflow: 'hidden',
            }}
          >
            {/* Card header - tappable */}
            <button
              data-testid={`shelter-card-${s.id}`}
              onClick={() => openShelter(s.id)}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '18px 20px',
                backgroundColor: 'transparent', border: 'none', cursor: 'pointer',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.text, marginBottom: 3 }}>
                    {s.name}
                    {isInactive && (
                      <span data-testid={`inactive-badge-${s.id}`} style={{
                        marginLeft: 8, padding: '3px 8px', borderRadius: 6,
                        fontSize: text['2xs'], fontWeight: weight.bold,
                        backgroundColor: color.errorBg, color: color.error,
                        border: `1px solid ${color.errorBorder}`,
                      }}>
                        <FormattedMessage id="shelter.statusInactive" />
                      </span>
                    )}
                    {s.dvShelter && <span data-testid={`dv-indicator-${s.id}`} style={{ display: 'none' }} />}
                  </div>
                  <div style={{ fontSize: text.base, color: color.textTertiary, marginBottom: 6 }}>{fmtAddr(s)}</div>
                </div>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  {summary && summary.totalBedsAvailable != null && (
                    <span data-testid={`avail-badge-${s.id}`} style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: text.base, fontWeight: weight.extrabold,
                      backgroundColor: summary.totalBedsAvailable > 0 ? color.successBg : color.errorBg,
                      color: summary.totalBedsAvailable > 0 ? color.success : color.error,
                    }}>{summary.totalBedsAvailable} avail</span>
                  )}
                  {s.dvShelter && isExpanded && pendingReferrals.length > 0 && (
                    <span data-testid={`referral-badge-${s.id}`} style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.extrabold,
                      backgroundColor: color.dvBg, color: color.dvText,
                    }}>{pendingReferrals.length} referral{pendingReferrals.length > 1 ? 's' : ''}</span>
                  )}
                  {/* T-47: DV shelter indicator on collapsed cards — expands to show details */}
                  {s.dvShelter && !isExpanded && (
                    <span data-testid={`dv-badge-${s.id}`} style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.extrabold,
                      backgroundColor: color.dvBg, color: color.dvText,
                    }}>DV</span>
                  )}
                </div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  {summary?.lastUpdated ? (
                    <DataAge dataAgeSeconds={summary.dataAgeSeconds} />
                  ) : (
                    <DataAge dataAgeSeconds={s.updatedAt ? Math.floor((Date.now() - new Date(s.updatedAt).getTime()) / 1000) : null} />
                  )}
                  {summary?.lastUpdated && (
                    <span style={{ fontSize: text['2xs'], color: color.textMuted }}>
                      <FormattedMessage id="coord.lastAvailUpdate" />: {new Date(summary.lastUpdated).toLocaleString()}
                    </span>
                  )}
                </div>
                {isExpanded
                  ? <span style={{ fontSize: text.xs, color: color.primaryText, fontWeight: weight.semibold }}>▲</span>
                  : <span style={{ fontSize: text.xs, color: color.textMuted, fontWeight: weight.semibold }}>▼</span>}
              </div>
            </button>

            {/* Expanded editor */}
            {isExpanded && (
              <div style={{ padding: '0 20px 20px' }}>
                <div style={{ height: 1, backgroundColor: color.border, marginBottom: 16 }} />

                {/* Inactive shelter message */}
                {isInactive && (
                  <div
                    data-testid={`inactive-message-${s.id}`}
                    style={{
                      padding: 12, marginBottom: 16,
                      backgroundColor: color.warningBg,
                      border: `1px solid ${color.warningMid}`,
                      borderRadius: 8, fontSize: text.sm, color: color.text, lineHeight: 1.5,
                    }}
                  >
                    <FormattedMessage
                      id="shelter.deactivatedOn"
                      values={{ date: s.deactivatedAt ? new Date(s.deactivatedAt).toLocaleDateString() : '—' }}
                    />
                  </div>
                )}

                {/* Edit Details button */}
                <div style={{ marginBottom: 16 }}>
                  <a
                    href={`/coordinator/shelters/${s.id}/edit?from=/coordinator`}
                    data-testid={`edit-details-${s.id}`}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      padding: '8px 16px',
                      backgroundColor: color.bgSecondary,
                      color: color.primaryText,
                      border: `1px solid ${color.borderMedium}`,
                      borderRadius: 8,
                      fontSize: text.sm,
                      fontWeight: weight.semibold,
                      textDecoration: 'none',
                      minHeight: 44,
                      cursor: 'pointer',
                    }}
                  >
                    <FormattedMessage id="shelter.editDetails" />
                  </a>
                </div>

                {/* Availability update section — disabled for inactive shelters (C-6: keyboard + pointer blocked) */}
                <div aria-disabled={isInactive ? 'true' : undefined}
                     tabIndex={isInactive ? -1 : undefined}
                     onClickCapture={isInactive ? (e) => e.stopPropagation() : undefined}
                     onKeyDownCapture={isInactive ? (e) => { if (e.key === 'Enter' || e.key === ' ') e.preventDefault(); } : undefined}
                     style={isInactive ? { pointerEvents: 'none' as const } : undefined}>
                <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.primaryText, margin: '0 0 12px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                  <FormattedMessage id="coord.availability" />
                </h4>

                {editAvailability.filter(a => a.bedsTotal > 0 || a.bedsOccupied > 0).map((avail) => {
                  const bedsAvailable = avail.bedsTotal - avail.bedsOccupied - avail.bedsOnHold;
                  const isSavingThis = availSaving === avail.populationType;
                  const isSavedThis = availSaved === avail.populationType;

                  return (
                    <div key={avail.populationType} data-testid={`avail-row-${avail.populationType}`} style={{
                      padding: '12px 0', borderBottom: `1px solid ${color.borderLight}`,
                    }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                        <span style={{ fontSize: text.base, fontWeight: weight.semibold, color: color.text, textTransform: 'capitalize' }}>
                          {getPopulationTypeLabel(avail.populationType, intl)}
                        </span>
                        <span data-testid={`available-value-${avail.populationType}`} style={{
                          fontSize: text.md, fontWeight: weight.extrabold,
                          color: bedsAvailable > 0 ? color.success : color.error,
                        }}>
                          {bedsAvailable} <span style={{ fontSize: text['2xs'], fontWeight: weight.semibold }}><FormattedMessage id="coord.bedsAvail" /></span>
                        </span>
                      </div>
                      <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
                        {/* Total beds stepper */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: text.xs, color: color.textTertiary, fontWeight: weight.semibold, minWidth: 40 }}>
                            <FormattedMessage id="coord.bedsTotal" />
                          </span>
                          <StepperButton label="−" data-testid={`total-minus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsTotal', -1)} disabled={avail.bedsTotal <= avail.bedsOccupied + avail.bedsOnHold} />
                          <span data-testid={`total-value-${avail.populationType}`} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center' }}>{avail.bedsTotal}</span>
                          <StepperButton label="+" data-testid={`total-plus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsTotal', 1)} />
                        </div>
                        {/* Occupied stepper */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: text.xs, color: color.textTertiary, fontWeight: weight.semibold, minWidth: 60 }}>
                            <FormattedMessage id="coord.bedsOccupied" />
                          </span>
                          <StepperButton label="−" data-testid={`occupied-minus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsOccupied', -1)} disabled={avail.bedsOccupied <= 0} />
                          <span data-testid={`occupied-value-${avail.populationType}`} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center' }}>{avail.bedsOccupied}</span>
                          <StepperButton label="+" data-testid={`occupied-plus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsOccupied', 1)} disabled={avail.bedsOccupied >= avail.bedsTotal - avail.bedsOnHold} />
                        </div>
                        {/* On-hold display (read-only — holds are managed by the reservation system) */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: text.xs, color: color.textTertiary, fontWeight: weight.semibold, minWidth: 50 }}>
                            <FormattedMessage id="coord.bedsOnHold" />
                          </span>
                          <span data-testid={`onhold-value-${avail.populationType}`} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center', color: avail.bedsOnHold > 0 ? color.primary : color.textMuted }}>{avail.bedsOnHold}</span>
                          {avail.bedsOnHold > 0 && (
                            <span style={{ fontSize: text['2xs'], color: color.textMuted }}>(system)</span>
                          )}
                        </div>
                        {/* Overflow / temporary beds stepper — only during active surge */}
                        {surgeActive && (
                          <div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                              <span style={{ fontSize: text.xs, color: color.textTertiary, fontWeight: weight.semibold, minWidth: 70 }}>
                                <FormattedMessage id="surge.overflowBeds" />
                              </span>
                              <StepperButton label="−" data-testid={`overflow-minus-${avail.populationType}`} onClick={() => updateOverflow(avail.populationType, -1)} disabled={avail.overflowBeds <= 0} />
                              <span data-testid={`overflow-value-${avail.populationType}`} aria-label={intl.formatMessage({ id: 'surge.overflowBeds' }) + ': ' + avail.overflowBeds} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center' }}>{avail.overflowBeds}</span>
                              <StepperButton label="+" data-testid={`overflow-plus-${avail.populationType}`} onClick={() => updateOverflow(avail.populationType, 1)} />
                            </div>
                            <div style={{ fontSize: text['2xs'], color: color.textMuted, marginTop: 2 }}>
                              <FormattedMessage id="surge.overflowHint" />
                            </div>
                          </div>
                        )}
                      </div>
                      <button
                        data-testid={`save-avail-${avail.populationType}`}
                        onClick={() => submitAvailability(s.id, avail.populationType)}
                        disabled={isSavingThis}
                        aria-live="polite"
                        style={{
                          padding: '8px 16px', backgroundColor: isSavedThis ? color.successBright : color.primary, color: color.textInverse,
                          border: 'none', borderRadius: 8, fontSize: text.sm, fontWeight: weight.bold,
                          cursor: isSavingThis ? 'default' : 'pointer',
                          minHeight: 44, transition: 'all 0.15s',
                        }}
                      >
                        {isSavedThis ? intl.formatMessage({ id: 'coord.availabilityUpdated' })
                          : isSavingThis ? '...'
                          : intl.formatMessage({ id: 'coord.updateAvailability' })}
                      </button>
                    </div>
                  );
                })}

                </div>{/* end availability disabled wrapper */}

                {/* Pending DV Referrals (screening view) */}
                {s.dvShelter && pendingReferrals.length > 0 && (
                  <div data-testid="referral-screening" style={{
                    padding: '12px 16px', backgroundColor: color.dvBg, borderRadius: 10,
                    border: `1px solid ${color.dvBorder}`, marginBottom: 16,
                  }}>
                    <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.dvText, margin: '0 0 10px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      <FormattedMessage id="referral.pendingReferrals" /> ({pendingReferrals.length})
                    </h4>
                    {pendingReferrals.map(ref => (
                      <div
                        key={ref.id}
                        data-testid={`screening-${ref.id}`}
                        // S-2 — programmatically focusable for deep-link handoff,
                        // but NOT in the natural tab order. A coordinator reaching
                        // the row via deep-link is one Tab from the Accept button.
                        tabIndex={-1}
                        style={{
                          padding: '10px 0', borderBottom: `1px solid ${color.dvBorder}`,
                          outline: 'none',
                          // Visible focus ring so the coordinator can see where
                          // focus landed after a deep-link jump (WCAG 2.4.7).
                          boxShadow: 'none',
                        }}
                        onFocus={(e) => {
                          e.currentTarget.style.boxShadow = `0 0 0 3px ${color.primary}`;
                          e.currentTarget.style.borderRadius = '8px';
                        }}
                        onBlur={(e) => {
                          e.currentTarget.style.boxShadow = 'none';
                        }}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 6 }}>
                          <div>
                            <span style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.text }}>
                              {getPopulationTypeLabel(ref.populationType, intl)} — {ref.householdSize} person{ref.householdSize > 1 ? 's' : ''}
                            </span>
                            <span style={{
                              marginLeft: 8, padding: '2px 6px', borderRadius: 4, fontSize: text['2xs'], fontWeight: weight.bold,
                              backgroundColor: ref.urgency === 'EMERGENCY' ? color.errorBg : ref.urgency === 'URGENT' ? color.warningBg : color.successBg,
                              color: ref.urgency === 'EMERGENCY' ? color.error : ref.urgency === 'URGENT' ? color.warning : color.success,
                            }}>{ref.urgency}</span>
                          </div>
                          {ref.remainingSeconds != null && (
                            ref.remainingSeconds <= 0 ? (
                              <span data-testid={`referral-expired-badge-${ref.id}`} style={{
                                padding: '2px 8px', borderRadius: 4, fontSize: text['2xs'], fontWeight: weight.bold,
                                backgroundColor: color.errorBg, color: color.error,
                              }}>
                                <FormattedMessage id="referral.expired" />
                              </span>
                            ) : (
                              <span data-testid={`referral-countdown-${ref.id}`} style={{ fontSize: text['2xs'], color: color.textTertiary }}>
                                {ref.remainingSeconds < 300
                                  ? intl.formatMessage({ id: 'referral.remainingMinutesSeconds' }, { minutes: Math.floor(ref.remainingSeconds / 60), seconds: ref.remainingSeconds % 60 })
                                  : intl.formatMessage({ id: 'referral.remainingMinutes' }, { minutes: Math.floor(ref.remainingSeconds / 60) })}
                              </span>
                            )
                          )}
                        </div>
                        {ref.specialNeeds && (
                          <div style={{ fontSize: text.xs, color: color.textTertiary, marginBottom: 4 }}>
                            <FormattedMessage id="referral.specialNeedsLabel" />: {ref.specialNeeds}
                          </div>
                        )}
                        <div style={{ fontSize: text.xs, color: color.textTertiary, marginBottom: 8 }}>
                          <FormattedMessage id="referral.callbackLabel" />: {ref.callbackNumber}
                        </div>

                        {(() => {
                          const isExpired = ref.remainingSeconds != null && ref.remainingSeconds <= 0;
                          return rejectingId === ref.id && !isExpired ? (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <input data-testid={`reject-reason-${ref.id}`}
                              type="text" value={rejectReason}
                              onChange={(e) => setRejectReason(e.target.value)}
                              placeholder={intl.formatMessage({ id: 'referral.rejectReason' })}
                              style={{ flex: 1, padding: 6, borderRadius: 6, border: `1px solid ${color.dvBorder}`, fontSize: text.xs }} />
                            <button data-testid={`reject-confirm-${ref.id}`}
                              onClick={() => rejectReferral(ref.id)} disabled={!rejectReason.trim()}
                              style={{ padding: '6px 10px', borderRadius: 6, border: 'none', backgroundColor: color.errorMid, color: color.textInverse, fontSize: text['2xs'], fontWeight: weight.bold, cursor: 'pointer' }}>
                              <FormattedMessage id="referral.reject" />
                            </button>
                            <button onClick={() => { setRejectingId(null); setRejectReason(''); }}
                              style={{ padding: '6px 10px', borderRadius: 6, border: `1px solid ${color.border}`, backgroundColor: color.bg, color: color.textTertiary, fontSize: text['2xs'], cursor: 'pointer' }}>
                              <FormattedMessage id="referral.cancel" />
                            </button>
                          </div>
                        ) : (
                          <div style={{ display: 'flex', gap: 6 }}>
                            {/* Carbon split (axe scan 12.2 dark): button-fill
                                with white text uses *Mid (success/error). The
                                outlined Reject button's text uses the *text*
                                variant (color.error in dark mode = #ff8389,
                                6.3:1 on the bg). Bare color.success/errorMid
                                shipped originally gave 2.39:1 / 2.75:1. */}
                            <button data-testid={`accept-referral-${ref.id}`}
                              onClick={() => acceptReferral(ref.id)}
                              disabled={isExpired}
                              style={{ padding: '6px 12px', borderRadius: 6, border: 'none', backgroundColor: isExpired ? color.textTertiary : color.successMid, color: color.textInverse, fontSize: text['2xs'], fontWeight: weight.bold, cursor: isExpired ? 'not-allowed' : 'pointer', opacity: isExpired ? 0.5 : 1 }}>
                              <FormattedMessage id="referral.accept" />
                            </button>
                            <button data-testid={`reject-referral-${ref.id}`}
                              onClick={() => setRejectingId(ref.id)}
                              disabled={isExpired}
                              style={{ padding: '6px 12px', borderRadius: 6, border: `1px solid ${isExpired ? color.textTertiary : color.errorMid}`, backgroundColor: color.bg, color: isExpired ? color.textTertiary : color.error, fontSize: text['2xs'], fontWeight: weight.bold, cursor: isExpired ? 'not-allowed' : 'pointer', opacity: isExpired ? 0.5 : 1 }}>
                              <FormattedMessage id="referral.reject" />
                            </button>
                          </div>
                        );
                        })()}
                      </div>
                    ))}
                  </div>
                )}

                {/* Active holds indicator */}
                {editAvailability.some(a => a.bedsOnHold > 0) && (
                  <div style={{
                    padding: '10px 14px', backgroundColor: color.bgHighlight, borderRadius: 10,
                    border: `1px solid ${color.primaryLight}`, marginBottom: 16,
                  }}>
                    <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.primaryText, margin: '0 0 8px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      <FormattedMessage id="coord.activeHolds" />
                    </h4>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      {editAvailability.filter(a => a.bedsOnHold > 0).map(a => (
                        <span key={a.populationType} style={{
                          padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
                          backgroundColor: color.primaryLight, color: color.primaryText,
                        }}>
                          {getPopulationTypeLabel(a.populationType, intl)}: {a.bedsOnHold} held
                        </span>
                      ))}
                    </div>
                  </div>
                )}

                {/* NOTE: Capacity editing is unified into the availability section above (D10).
                   Total beds stepper is part of each population type row. Single save per population. */}
              </div>
            )}
          </div>
        );
      })}

      {!loading && shelters.length === 0 && (
        <div style={{ textAlign: 'center', padding: 48, color: color.textMuted }}>
          <div style={{ fontSize: text['4xl'], marginBottom: 12 }}>🏠</div>
          <div style={{ fontSize: text.md, fontWeight: weight.medium }}><FormattedMessage id="coord.error" /></div>
        </div>
      )}
    </div>
  );
}

function StepperButton({ label, onClick, disabled, size = 44, fontSize = 18, 'data-testid': testId, 'aria-label': ariaLabel }: {
  label: string; onClick: () => void; disabled?: boolean; size?: number; fontSize?: number; 'data-testid'?: string; 'aria-label'?: string
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      data-testid={testId}
      aria-label={ariaLabel || (label === '+' ? 'Increase' : label === '−' ? 'Decrease' : label)}
      style={{
        width: size, height: size, borderRadius: '50%',
        border: `2px solid ${color.border}`, backgroundColor: color.bg,
        fontSize, fontWeight: weight.bold, color: disabled ? color.borderMedium : color.text,
        cursor: disabled ? 'default' : 'pointer',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        lineHeight: leading.tight,
      }}
    >{label}</button>
  );
}

function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: color.textMuted }}>
      <div style={{
        width: 32, height: 32, border: `3px solid ${color.border}`, borderTopColor: color.primary,
        borderRadius: '50%', animation: 'fabt-spin 0.7s linear infinite', margin: '0 auto 10px',
      }} />
      <style>{`@keyframes fabt-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
