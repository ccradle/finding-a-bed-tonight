import { useCallback, useEffect, useReducer, useRef } from 'react';

/**
 * Deep-link state machine for notification-deep-linking (Issue #106).
 *
 * <p>Owns the orchestration of: extracting intent from URL search params,
 * resolving it to a concrete target (typically via a backend lookup),
 * gating on host-managed unsaved state, expanding the host's UI to the
 * target, waiting for the target row to render, and falling back to a
 * stale state on any failure or timeout.</p>
 *
 * <p>The reducer + helper functions are pure and independently testable
 * (see {@code useDeepLink.test.ts}). The hook layers React's
 * {@code useReducer} + side-effect {@code useEffect}s on top to drive the
 * machine through its transitions.</p>
 *
 * <p>Why a state machine: prior ad-hoc patches kept introducing new
 * stuck-state defects (war-room rounds 1-3 in the OpenSpec history).
 * With explicit states and timeouts, every non-terminal state has a
 * defined exit — stuck states are impossible by construction.</p>
 *
 * <p>Why this lives in a hook (not Redux/context): state is local to one
 * page view and is not shared across components. A hook keeps the API
 * surface small and the state colocated with its consumer.</p>
 *
 * <p>See OpenSpec design.md D-12 for the full state machine table.</p>
 */

export interface DeepLinkIntent {
  referralId?: string;
  shelterId?: string;
  reservationId?: string;
}

/**
 * Output of {@link UseDeepLinkOptions#resolveTarget}. Carries the original
 * intent plus what the host needs to act:
 * - {@code resolvedShelterId}: present for both referralId and shelterId
 *   intents (the referral fetch resolves to its shelter).
 * - {@code detail}: the host-defined detail object (e.g.,
 *   {@code ReferralDetailResponse} for the coordinator dashboard, escalation
 *   row for the admin queue, reservation for my-past-holds). May be null
 *   for shelterId-only intents that don't fetch additional data.
 */
export interface ResolvedTarget<T> {
  intent: DeepLinkIntent;
  resolvedShelterId: string | null;
  detail: T | null;
}

export type StaleReason = 'not-found' | 'race' | 'error' | 'timeout';

export type DeepLinkState<T> =
  | { kind: 'idle' }
  | { kind: 'resolving'; intent: DeepLinkIntent }
  | { kind: 'awaiting-confirm'; resolved: ResolvedTarget<T> }
  | { kind: 'expanding'; resolved: ResolvedTarget<T> }
  | { kind: 'awaiting-target'; resolved: ResolvedTarget<T>; deadlineAt: number }
  | { kind: 'done'; resolved: ResolvedTarget<T> }
  | { kind: 'stale'; intent: DeepLinkIntent; reason: StaleReason };

export type DeepLinkAction<T> =
  | { type: 'INTENT'; intent: DeepLinkIntent }
  | { type: 'RESOLVED'; resolved: ResolvedTarget<T>; needsConfirm: boolean }
  | { type: 'CONFIRM_CONTINUE' }
  | { type: 'CONFIRM_ABORT' }
  | { type: 'EXPAND_DONE'; deadlineAt: number }
  | { type: 'TARGET_READY' }
  | { type: 'STALE'; intent: DeepLinkIntent; reason: StaleReason }
  | { type: 'RESET' };

export interface UseDeepLinkOptions<T> {
  /** Current URL search params (typically from {@code useSearchParams}). */
  searchParams: URLSearchParams;
  /**
   * Resolve the intent into a target the host can act on. Throws on
   * 404/403 (mapped to {@code stale: not-found}) or other errors (mapped
   * to {@code stale: error}). Receives an {@code AbortSignal} that is
   * fired when a newer URL change supersedes this resolve.
   */
  resolveTarget: (
    intent: DeepLinkIntent,
    signal: AbortSignal,
  ) => Promise<ResolvedTarget<T>>;
  /**
   * Should the user be prompted to confirm before the deep-link proceeds
   * (e.g., they have unsaved edits on a different shelter)? Called once
   * after {@code resolveTarget} returns; if true, the machine pauses in
   * {@code awaiting-confirm} until the host calls {@code confirm}.
   */
  needsUnsavedConfirm: (resolved: ResolvedTarget<T>) => boolean;
  /**
   * Expand or navigate to the resolved target. May be a no-op if the
   * host's UI is already at the target. Throws to signal a hard failure
   * (transitions to {@code stale: error}).
   */
  expand: (resolved: ResolvedTarget<T>) => Promise<void>;
  /**
   * Whether the resolved target is now visible / actionable in the host's
   * data model (e.g., the screening row is in {@code pendingReferrals}).
   * Polled on every render while in {@code awaiting-target}; the machine
   * transitions to {@code done} the first time this returns true.
   */
  isTargetReady: (resolved: ResolvedTarget<T>) => boolean;
  /**
   * Maximum time (ms) to wait for {@code isTargetReady} after expand
   * resolves. On timeout, the machine transitions to {@code stale: timeout}.
   * Default: 5000ms.
   */
  targetTimeoutMs?: number;
}

export interface UseDeepLinkResult<T> {
  state: DeepLinkState<T>;
  /**
   * Called by the host's unsaved-state dialog buttons. {@code 'continue'}
   * proceeds to the {@code expanding} state; {@code 'abort'} returns to
   * {@code idle}. No-op if the machine isn't in {@code awaiting-confirm}.
   */
  confirm: (action: 'continue' | 'abort') => void;
}

const DEFAULT_TARGET_TIMEOUT_MS = 5000;

// ---------------------------------------------------------------------------
// Pure helpers — exported so they can be unit-tested without React
// ---------------------------------------------------------------------------

/**
 * Extract a deep-link intent from URL search params. Returns null when no
 * deep-link parameter is present so the caller can short-circuit.
 */
export function extractIntent(searchParams: URLSearchParams): DeepLinkIntent | null {
  const referralId = searchParams.get('referralId') || undefined;
  const shelterId = searchParams.get('shelterId') || undefined;
  const reservationId = searchParams.get('reservationId') || undefined;
  if (!referralId && !shelterId && !reservationId) return null;
  return { referralId, shelterId, reservationId };
}

/** Deep-equality check for two intents (or undefined). */
export function intentsEqual(
  a: DeepLinkIntent | undefined,
  b: DeepLinkIntent | undefined,
): boolean {
  if (a === b) return true;
  if (!a || !b) return false;
  return (
    a.referralId === b.referralId
    && a.shelterId === b.shelterId
    && a.reservationId === b.reservationId
  );
}

/** Return the intent associated with the current state (or undefined for idle). */
export function currentIntent<T>(state: DeepLinkState<T>): DeepLinkIntent | undefined {
  switch (state.kind) {
    case 'idle':
      return undefined;
    case 'resolving':
      return state.intent;
    case 'awaiting-confirm':
    case 'expanding':
    case 'awaiting-target':
    case 'done':
      return state.resolved.intent;
    case 'stale':
      return state.intent;
  }
}

// ---------------------------------------------------------------------------
// Reducer — the state machine
// ---------------------------------------------------------------------------

export function deepLinkReducer<T>(
  state: DeepLinkState<T>,
  action: DeepLinkAction<T>,
): DeepLinkState<T> {
  switch (action.type) {
    case 'INTENT':
      // Always restart on a new intent. Caller is responsible for not
      // dispatching INTENT for an unchanged intent (intentsEqual check).
      return { kind: 'resolving', intent: action.intent };
    case 'RESOLVED':
      // Ignore late resolves from a superseded request.
      if (state.kind !== 'resolving') return state;
      if (!intentsEqual(state.intent, action.resolved.intent)) return state;
      return action.needsConfirm
        ? { kind: 'awaiting-confirm', resolved: action.resolved }
        : { kind: 'expanding', resolved: action.resolved };
    case 'CONFIRM_CONTINUE':
      if (state.kind !== 'awaiting-confirm') return state;
      return { kind: 'expanding', resolved: state.resolved };
    case 'CONFIRM_ABORT':
      if (state.kind !== 'awaiting-confirm') return state;
      return { kind: 'idle' };
    case 'EXPAND_DONE':
      if (state.kind !== 'expanding') return state;
      return {
        kind: 'awaiting-target',
        resolved: state.resolved,
        deadlineAt: action.deadlineAt,
      };
    case 'TARGET_READY':
      if (state.kind !== 'awaiting-target') return state;
      return { kind: 'done', resolved: state.resolved };
    case 'STALE':
      // Ignore stale dispatches that don't match the current intent
      // (e.g., a late timeout firing after the URL already changed).
      if (state.kind === 'idle') return state;
      if (!intentsEqual(currentIntent(state), action.intent)) return state;
      return { kind: 'stale', intent: action.intent, reason: action.reason };
    case 'RESET':
      return { kind: 'idle' };
  }
}

// ---------------------------------------------------------------------------
// The hook itself — thin wiring layer over the pure pieces above
// ---------------------------------------------------------------------------

export function useDeepLink<T>(options: UseDeepLinkOptions<T>): UseDeepLinkResult<T> {
  const {
    searchParams,
    resolveTarget,
    needsUnsavedConfirm,
    expand,
    isTargetReady,
    targetTimeoutMs = DEFAULT_TARGET_TIMEOUT_MS,
  } = options;

  // Generic-aware initial state can't be inferred without a hint.
  const initialState: DeepLinkState<T> = { kind: 'idle' };
  const [state, dispatch] = useReducer(deepLinkReducer<T>, initialState);

  // Stash the latest callback refs so async chains see the freshest closure
  // without re-running effects on every render.
  const resolveRef = useRef(resolveTarget);
  const needsConfirmRef = useRef(needsUnsavedConfirm);
  const expandRef = useRef(expand);
  resolveRef.current = resolveTarget;
  needsConfirmRef.current = needsUnsavedConfirm;
  expandRef.current = expand;

  // 1. URL → intent. Compare by intent equality, not searchParams identity,
  //    so router rerenders that don't change the intent are no-ops.
  const lastIntentJsonRef = useRef<string>('');
  useEffect(() => {
    const intent = extractIntent(searchParams);
    const intentJson = intent ? JSON.stringify(intent) : '';
    if (intentJson === lastIntentJsonRef.current) return;
    lastIntentJsonRef.current = intentJson;
    if (!intent) {
      dispatch({ type: 'RESET' });
    } else {
      dispatch({ type: 'INTENT', intent });
    }
  }, [searchParams]);

  // 2. resolving → resolveTarget → RESOLVED or STALE.
  //    Cancellation: AbortController + cancelled flag handle URL changes
  //    that supersede an in-flight resolve.
  const resolvingIntentJson = state.kind === 'resolving' ? JSON.stringify(state.intent) : null;
  useEffect(() => {
    if (state.kind !== 'resolving') return;
    const intent = state.intent;
    const controller = new AbortController();
    let cancelled = false;
    (async () => {
      try {
        const resolved = await resolveRef.current(intent, controller.signal);
        if (cancelled) return;
        const needsConfirm = needsConfirmRef.current(resolved);
        dispatch({ type: 'RESOLVED', resolved, needsConfirm });
      } catch (err) {
        if (cancelled) return;
        const status = (err as { status?: number })?.status;
        const reason: StaleReason = status === 404 || status === 403 ? 'not-found' : 'error';
        dispatch({ type: 'STALE', intent, reason });
      }
    })();
    return () => {
      cancelled = true;
      controller.abort();
    };
    // intentsEqual change is captured by the JSON key.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolvingIntentJson]);

  // 3. expanding → expand → EXPAND_DONE or STALE.
  const expandingIntentJson = state.kind === 'expanding' ? JSON.stringify(state.resolved.intent) : null;
  useEffect(() => {
    if (state.kind !== 'expanding') return;
    const resolved = state.resolved;
    let cancelled = false;
    (async () => {
      try {
        await expandRef.current(resolved);
        if (cancelled) return;
        dispatch({ type: 'EXPAND_DONE', deadlineAt: Date.now() + targetTimeoutMs });
      } catch {
        if (cancelled) return;
        dispatch({ type: 'STALE', intent: resolved.intent, reason: 'error' });
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expandingIntentJson, targetTimeoutMs]);

  // 4. awaiting-target → poll isTargetReady on every render; fall back
  //    to STALE: timeout if deadline passes.
  useEffect(() => {
    if (state.kind !== 'awaiting-target') return;
    if (isTargetReady(state.resolved)) {
      dispatch({ type: 'TARGET_READY' });
      return;
    }
    const remaining = Math.max(0, state.deadlineAt - Date.now());
    const timer = setTimeout(() => {
      dispatch({ type: 'STALE', intent: state.resolved.intent, reason: 'timeout' });
    }, remaining);
    return () => clearTimeout(timer);
  }, [state, isTargetReady]);

  const confirm = useCallback((action: 'continue' | 'abort') => {
    dispatch({ type: action === 'continue' ? 'CONFIRM_CONTINUE' : 'CONFIRM_ABORT' });
  }, []);

  return { state, confirm };
}
