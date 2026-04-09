import { useState, useEffect, useCallback, useRef } from 'react';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { useAuth } from '../auth/useAuth';
import { api } from '../services/api';

export interface Notification {
  id: string;
  eventType: string;
  data: Record<string, unknown>;
  timestamp: number;
  read: boolean;
}

interface UseNotificationsReturn {
  notifications: Notification[];
  unreadCount: number;
  markRead: (id: string) => Promise<void>;
  markAllRead: () => Promise<void>;
  dismiss: (id: string) => void;
  connected: boolean;
}

const MAX_NOTIFICATIONS = 50;

/** Custom events dispatched on window for page-level refresh triggers */
export const SSE_REFERRAL_UPDATE = 'fabt:referral-update';
export const SSE_REFERRAL_EXPIRED = 'fabt:referral-expired';
export const SSE_AVAILABILITY_UPDATE = 'fabt:availability-update';

/**
 * SSE notification stream with persistent DB-backed badge count.
 *
 * T-37:  Badge count initialized from REST on mount (source of truth).
 * T-37a: Handles "notification" SSE event type for persistent notifications.
 * T-38:  SSE events increment/decrement count from REST baseline.
 * T-39:  Deduplicates by notification ID — catch-up + real-time no-ops.
 *
 * Reconciliation strategy (Alex/Design D4):
 * - REST count is the source of truth on mount.
 * - SSE events that arrive BEFORE the REST response are buffered.
 * - After REST baseline is set, buffered events are reconciled.
 * - SSE events that arrive AFTER REST baseline increment/decrement.
 */
export function useNotifications(): UseNotificationsReturn {
  const { token, isAuthenticated } = useAuth();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [connected, setConnected] = useState(false);
  // T-37: REST-sourced unread count — source of truth for badge
  const [restUnreadCount, setRestUnreadCount] = useState(0);
  // T-38: SSE-driven delta from REST baseline
  const [sseDelta, setSseDelta] = useState(0);
  const restCountLoaded = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const retryCountRef = useRef(0);
  // T-39: Set of notification IDs already seen (dedup catch-up + real-time)
  const seenIdsRef = useRef<Set<string>>(new Set());

  // Badge count = REST baseline + SSE delta
  const unreadCount = Math.max(0, restUnreadCount + sseDelta);

  // T-37: Clear dedup state on auth change (prevents cross-user state leak).
  // Uses a ref to track the previous auth state and reset on transition.
  const prevAuthRef = useRef(isAuthenticated);
  useEffect(() => {
    if (prevAuthRef.current !== isAuthenticated) {
      prevAuthRef.current = isAuthenticated;
      seenIdsRef.current.clear();
      restCountLoaded.current = false;
    }
  });

  // T-37 + T-40: Fetch unread count AND notification list from REST on mount.
  // REST is the source of truth for initial state. SSE adds real-time updates on top.
  // Alex: "Badge count and notification list must both come from REST. SSE is the bonus."
  useEffect(() => {
    if (!isAuthenticated || !token) return;

    seenIdsRef.current.clear();
    restCountLoaded.current = false;

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    const baseUrl = import.meta.env.VITE_API_URL || '';
    const headers = { 'Authorization': `Bearer ${token}` };

    // Fetch count + list in parallel
    Promise.all([
      fetch(`${baseUrl}/api/v1/notifications/count`, { headers, signal: controller.signal })
        .then((r) => r.ok ? r.json() : Promise.reject()),
      fetch(`${baseUrl}/api/v1/notifications?unread=true`, { headers, signal: controller.signal })
        .then((r) => r.ok ? r.json() : Promise.reject()),
    ])
      .then(([countData, listData]: [{ unread: number }, Array<Record<string, unknown>>]) => {
        setRestUnreadCount(countData.unread);
        restCountLoaded.current = true;
        setSseDelta(0);

        // Populate notifications from REST — each item has id, type, severity, payload, createdAt
        const restNotifications: Notification[] = (listData || []).map((item) => {
          const id = String(item.id || '');
          seenIdsRef.current.add(id); // Mark as seen for SSE dedup
          return {
            id,
            eventType: String(item.type || 'notification'),
            data: item as Record<string, unknown>,
            timestamp: item.createdAt ? new Date(String(item.createdAt)).getTime() : Date.now(),
            read: !!item.readAt,
          };
        });
        setNotifications(restNotifications);
      })
      .catch(() => {
        // REST failed or timed out — unlock SSE delta so badge still works
        restCountLoaded.current = true;
      })
      .finally(() => clearTimeout(timeout));

    return () => { controller.abort(); clearTimeout(timeout); };
  }, [isAuthenticated, token]);

  // T-41: Mark as read — calls REST, updates local state, decrements count
  const markRead = useCallback(async (id: string) => {
    setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, read: true } : n)));
    setSseDelta((d) => d - 1);
    try {
      await api.patch<void>(`/api/v1/notifications/${id}/read`);
    } catch { /* best-effort — local state already updated */ }
  }, []);

  // T-42: Mark all as read — calls REST, updates local state (excludes CRITICAL per D3)
  const markAllRead = useCallback(async () => {
    setNotifications((prev) => prev.map((n) =>
      n.data?.severity === 'CRITICAL' ? n : { ...n, read: true }
    ));
    // Count how many non-CRITICAL were unread
    setNotifications((prev) => {
      const criticalUnread = prev.filter((n) => !n.read && n.data?.severity === 'CRITICAL').length;
      setRestUnreadCount(criticalUnread);
      setSseDelta(0);
      return prev;
    });
    try {
      await api.post<void>('/api/v1/notifications/read-all');
    } catch { /* best-effort */ }
  }, []);

  const dismiss = useCallback((id: string) => {
    setNotifications((prev) => prev.filter((n) => n.id !== id));
  }, []);

  // T-39: Deduplicated add — ignores catch-up notifications already seen via SSE
  const addNotification = useCallback((eventType: string, data: Record<string, unknown>, eventId: string) => {
    const id = eventId || String(Date.now());
    if (seenIdsRef.current.has(id)) return; // T-39: dedup
    seenIdsRef.current.add(id);
    // Cap dedup set to prevent unbounded growth in long-lived sessions
    if (seenIdsRef.current.size > MAX_NOTIFICATIONS * 2) {
      const entries = Array.from(seenIdsRef.current);
      seenIdsRef.current = new Set(entries.slice(-MAX_NOTIFICATIONS));
    }

    setNotifications((prev) => {
      // Also check by ID in existing array (belt and suspenders)
      if (prev.some((n) => n.id === id)) return prev;
      const notification: Notification = {
        id,
        eventType,
        data,
        timestamp: Date.now(),
        read: false,
      };
      return [notification, ...prev].slice(0, MAX_NOTIFICATIONS);
    });

    // T-38: Increment SSE delta (only after REST baseline loaded)
    if (restCountLoaded.current) {
      setSseDelta((d) => d + 1);
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated || !token) return;

    const abortController = new AbortController();
    abortRef.current = abortController;

    const baseUrl = import.meta.env.VITE_API_URL || '';
    const url = `${baseUrl}/api/v1/notifications/stream`;

    fetchEventSource(url, {
      headers: {
        'Authorization': `Bearer ${token}`,
      },
      signal: abortController.signal,
      openWhenHidden: false,

      async onopen(response) {
        if (response.ok) {
          setConnected(true);
          retryCountRef.current = 0;
        } else {
          throw new Error(`SSE connection failed: ${response.status}`);
        }
      },

      onmessage(ev) {
        retryCountRef.current = 0;

        switch (ev.event) {
          case 'connected':
            break;

          case 'heartbeat':
            break;

          case 'refresh':
            // Server says gap too large — refetch REST count and trigger page refreshes
            api.get<{ unread: number }>('/api/v1/notifications/count')
              .then((data) => {
                setRestUnreadCount(data.unread);
                setSseDelta(0);
              })
              .catch(() => {});
            window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
            window.dispatchEvent(new Event(SSE_AVAILABILITY_UPDATE));
            break;

          // T-37a: Persistent notification events from NotificationPersistenceService
          case 'notification':
            try {
              const notifData = JSON.parse(ev.data);
              addNotification(notifData.type || 'notification', notifData, ev.id);
            } catch { /* malformed event */ }
            break;

          // Existing domain events (SSE push from NotificationService.onDomainEvent)
          case 'dv-referral.responded':
          case 'dv-referral.requested':
            try {
              const referralData = JSON.parse(ev.data);
              addNotification(ev.event, referralData, ev.id);
              window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
            } catch { /* malformed event */ }
            break;

          case 'dv-referral.expired':
            try {
              const expiredData = JSON.parse(ev.data);
              addNotification(ev.event, expiredData, ev.id);
              window.dispatchEvent(new CustomEvent(SSE_REFERRAL_EXPIRED, { detail: expiredData }));
            } catch { /* malformed event */ }
            break;

          case 'availability.updated':
            try {
              const availData = JSON.parse(ev.data);
              addNotification(ev.event, availData, ev.id);
              window.dispatchEvent(new Event(SSE_AVAILABILITY_UPDATE));
            } catch { /* malformed event */ }
            break;

          default:
            break;
        }
      },

      onerror() {
        setConnected(false);
        retryCountRef.current++;
        const baseDelay = Math.min(1000 * Math.pow(2, retryCountRef.current - 1), 30000);
        const jitter = baseDelay * 0.3 * Math.random();
        return baseDelay + jitter;
      },

      onclose() {
        setConnected(false);
      },
    });

    return () => {
      abortController.abort();
      abortRef.current = null;
      setConnected(false);
    };
  }, [isAuthenticated, token, addNotification]);

  return {
    notifications,
    unreadCount,
    markRead,
    markAllRead,
    dismiss,
    connected,
  };
}
