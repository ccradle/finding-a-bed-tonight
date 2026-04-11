import { useState, useEffect, useCallback, useRef } from 'react';
import { api } from '../services/api';
import { SSE_DV_QUEUE_UPDATE, SSE_DV_POLICY_UPDATE } from './useNotifications';

/**
 * Mirrors backend `EscalatedReferralDto` (org.fabt.referral.api.EscalatedReferralDto).
 * Field-for-field including the round-5 `escalationChainBroken` boolean
 * (Marcus Okafor surface — admin queue UI can render "Owned by [name]").
 */
export interface EscalatedReferral {
  id: string;
  shelterId: string;
  shelterName: string;
  populationType: string;
  householdSize: number;
  urgency: string;
  createdAt: string;
  expiresAt: string;
  remainingMinutes: number;
  assignedCoordinatorId: string | null;
  assignedCoordinatorName: string | null;
  claimedByAdminId: string | null;
  claimedByAdminName: string | null;
  claimExpiresAt: string | null;
  escalationChainBroken: boolean;
}

interface UseDvEscalationQueueReturn {
  queue: EscalatedReferral[];
  /** True only on the very first load. Background SSE-driven refreshes do not flip this. */
  loading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
}

/** Debounce window for SSE-driven refreshes — collapses bursty events into one REST call. */
const SSE_REFRESH_DEBOUNCE_MS = 250;

/**
 * Live-countdown interval — periodic refetch so the `remainingMinutes` field
 * (computed at request time on the backend) stays accurate even when no SSE
 * events arrive (Marcus Okafor war-room round 6). 30 seconds is a balance
 * between accurate countdowns and REST chatter.
 */
const LIVE_COUNTDOWN_INTERVAL_MS = 30_000;

/**
 * React hook for the CoC admin escalated DV referral queue (T-39).
 *
 * <p><b>SSE strategy:</b> this hook does NOT open a parallel SSE connection.
 * It listens for `fabt:dv-queue-update` and `fabt:dv-policy-update` window
 * custom events that are dispatched by the existing `useNotifications` hook
 * when its single SSE connection receives the four new event types
 * (`referral.claimed`, `referral.released`, `referral.queue-changed`,
 * `referral.policy-updated`). One SSE connection per session — D20
 * conformance with the archived sse-stability spec.</p>
 *
 * <p><b>Reconciliation:</b> on any window event, the hook schedules a
 * debounced refetch (250ms) so a burst of SSE events collapses into one
 * REST call (Sam Okafor war-room round 6). The queue is bounded (~10-20
 * items at peak per the spec) so a full refetch is cheaper than
 * maintaining optimistic local state with conflict resolution.</p>
 *
 * <p><b>Spinner discipline:</b> {@code loading} is TRUE only on the very
 * first load. Background SSE-driven refreshes silently replace the queue
 * without flipping the spinner so the admin's view doesn't flicker on
 * every claim/release event in a busy CoC (Sam Okafor war-room round 6).</p>
 *
 * <p><b>Live countdown + Page Visibility:</b> a 30-second interval inside
 * the hook calls the debounced {@code scheduleRefresh} so {@code
 * remainingMinutes} stays honest even when no SSE events arrive. The
 * interval is gated by {@code document.visibilityState === 'visible'} so
 * background tabs don't burn REST calls while the user is elsewhere
 * (Sam Okafor war-room round 7). When the tab is hidden, refetches stop;
 * when it becomes visible again, an immediate refetch fires to catch up
 * any state changes that happened during the absence.</p>
 */
export function useDvEscalationQueue(): UseDvEscalationQueueReturn {
  const [queue, setQueue] = useState<EscalatedReferral[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Mounted flag — guard against setting state after unmount when a refetch
  // resolves late (Sandra Kim closes the tab during a slow network).
  const mountedRef = useRef(true);
  // Initial-load flag — flips false after the first refetch resolves so
  // subsequent refetches stay silent (no spinner flicker).
  const initialLoadRef = useRef(true);
  // Debounce timer for SSE-driven refetches.
  const debounceTimerRef = useRef<number | null>(null);

  const refresh = useCallback(async () => {
    if (initialLoadRef.current) {
      setLoading(true);
    }
    setError(null);
    try {
      const data = await api.get<EscalatedReferral[]>('/api/v1/dv-referrals/escalated');
      if (mountedRef.current) {
        setQueue(data || []);
      }
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      if (mountedRef.current) {
        setError(apiErr.message || 'load_failed');
      }
    } finally {
      if (mountedRef.current) {
        if (initialLoadRef.current) {
          setLoading(false);
          initialLoadRef.current = false;
        }
      }
    }
  }, []);

  /**
   * Debounced refresh — collapses a burst of SSE events into one REST call.
   * Used by the window event listeners only; the explicit `refresh()` API
   * (called by the orchestrator's claim/reassign action handlers) bypasses
   * the debouncer for immediacy.
   */
  const scheduleRefresh = useCallback(() => {
    if (debounceTimerRef.current !== null) {
      window.clearTimeout(debounceTimerRef.current);
    }
    debounceTimerRef.current = window.setTimeout(() => {
      debounceTimerRef.current = null;
      refresh();
    }, SSE_REFRESH_DEBOUNCE_MS);
  }, [refresh]);

  // Initial load + cleanup
  useEffect(() => {
    mountedRef.current = true;
    refresh();
    return () => {
      mountedRef.current = false;
      if (debounceTimerRef.current !== null) {
        window.clearTimeout(debounceTimerRef.current);
        debounceTimerRef.current = null;
      }
    };
  }, [refresh]);

  // Subscribe to the SSE-driven window events.
  useEffect(() => {
    function handleQueueUpdate() {
      scheduleRefresh();
    }
    function handlePolicyUpdate() {
      // Policy updates don't change the queue contents directly, but they
      // can affect what NEW referrals will look like — refresh anyway so
      // any stale rendering of policy version metadata refreshes.
      scheduleRefresh();
    }

    window.addEventListener(SSE_DV_QUEUE_UPDATE, handleQueueUpdate);
    window.addEventListener(SSE_DV_POLICY_UPDATE, handlePolicyUpdate);

    return () => {
      window.removeEventListener(SSE_DV_QUEUE_UPDATE, handleQueueUpdate);
      window.removeEventListener(SSE_DV_POLICY_UPDATE, handlePolicyUpdate);
    };
  }, [scheduleRefresh]);

  // Live countdown interval with Page Visibility gating.
  // - 30s interval keeps `remainingMinutes` honest when no SSE events arrive.
  // - Skips the refetch when the tab is hidden (Sam Okafor war-room round 7
  //   — at 18 partner agencies × admins idle in background tabs, the
  //   unconditional version was burning ~1080 REST calls/hour to a single
  //   endpoint for nothing).
  // - When the tab becomes visible again, fires an immediate catch-up
  //   refetch so the admin sees current state without waiting for the
  //   next 30s tick.
  // - Uses scheduleRefresh (debounced) so the timer collapses cleanly
  //   with any in-flight SSE events instead of double-firing.
  useEffect(() => {
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        scheduleRefresh();
      }
    }, LIVE_COUNTDOWN_INTERVAL_MS);

    function handleVisibilityChange() {
      if (document.visibilityState === 'visible') {
        scheduleRefresh();
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [scheduleRefresh]);

  return { queue, loading, error, refresh };
}
