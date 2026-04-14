import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl, type IntlShape } from 'react-intl';
import { api, ApiError } from '../services/api';
import { enqueueAction, type ReplayResult } from '../services/offlineQueue';
import { markNotificationsActedByPayload } from '../services/notificationMarkActed';
import { DataAge } from '../components/DataAge';
import { text, weight, leading } from '../theme/typography';
import { color } from '../theme/colors';
import { getPopulationTypeLabel } from '../utils/populationTypeLabels';
import { SSE_REFERRAL_UPDATE, SSE_AVAILABILITY_UPDATE } from '../hooks/useNotifications';
import { useOnlineStatus } from '../hooks/useOnlineStatus';
import { useAuth } from '../auth/useAuth';

const POPULATION_TYPES = [
  { value: '', labelId: 'search.allTypes' },
  { value: 'SINGLE_ADULT', labelId: 'search.singleAdult' },
  { value: 'FAMILY_WITH_CHILDREN', labelId: 'search.family' },
  { value: 'WOMEN_ONLY', labelId: 'search.womenOnly' },
  { value: 'VETERAN', labelId: 'search.veteran' },
  { value: 'YOUTH_18_24', labelId: 'search.youth1824' },
  { value: 'YOUTH_UNDER_18', labelId: 'search.youthUnder18' },
  { value: 'DV_SURVIVOR', labelId: 'search.dvSurvivor' },
];

interface PopulationAvailability {
  populationType: string;
  bedsTotal: number;
  bedsOccupied: number;
  bedsOnHold: number;
  bedsAvailable: number;
  acceptingNewGuests: boolean;
  overflowBeds: number;
}

interface ConstraintsSummary {
  petsAllowed: boolean;
  wheelchairAccessible: boolean;
  sobrietyRequired: boolean;
  idRequired: boolean;
  referralRequired: boolean;
}

interface BedSearchResult {
  shelterId: string;
  shelterName: string;
  address: string;
  phone: string;
  latitude: number;
  longitude: number;
  availability: PopulationAvailability[];
  dataAgeSeconds: number | null;
  dataFreshness: string;
  distanceMiles: number | null;
  constraints: ConstraintsSummary;
  surgeActive: boolean;
  dvShelter: boolean;
}

interface ReferralToken {
  id: string;
  shelterId: string;
  /** Snapshotted at token creation — survives shelter rename/offline refresh (Darius persona). */
  shelterName?: string;
  status: string;
  urgency: string;
  householdSize: number;
  populationType: string;
  createdAt: string;
  expiresAt: string;
  remainingSeconds: number | null;
  rejectionReason: string | null;
  shelterPhone: string | null;
}

function formatReferralListTime(iso: string, intl: IntlShape): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return intl.formatTime(d, { hour: 'numeric', minute: '2-digit' });
}

function referralStatusLabel(status: string, intl: IntlShape): string {
  switch (status) {
    case 'ACCEPTED':
      return intl.formatMessage({ id: 'referral.statusAccepted' });
    case 'REJECTED':
      return intl.formatMessage({ id: 'referral.statusRejected' });
    case 'PENDING':
      return intl.formatMessage({ id: 'referral.statusPending' });
    case 'EXPIRED':
      return intl.formatMessage({ id: 'referral.expired' });
    case 'SHELTER_CLOSED':
      return intl.formatMessage({ id: 'referral.statusShelterClosed' });
    default:
      return status;
  }
}

interface BedSearchResponse {
  results: BedSearchResult[];
  totalCount: number;
}

interface ShelterConstraints {
  sobrietyRequired: boolean;
  idRequired: boolean;
  referralRequired: boolean;
  petsAllowed: boolean;
  wheelchairAccessible: boolean;
  curfewTime: string | null;
  maxStayDays: number | null;
  populationTypesServed: string[];
}

interface ShelterCapacity {
  populationType: string;
  bedsTotal: number;
}

interface AvailabilityDto {
  populationType: string;
  bedsTotal: number;
  bedsOccupied: number;
  bedsOnHold: number;
  bedsAvailable: number;
  acceptingNewGuests: boolean;
  snapshotTs: string;
  dataAgeSeconds: number;
  dataFreshness: string;
}

interface ShelterDetail {
  shelter: {
    id: string;
    name: string;
    addressStreet: string;
    addressCity: string;
    addressState: string;
    addressZip: string;
    phone: string;
    latitude: number;
    longitude: number;
    dvShelter: boolean;
    updatedAt: string;
  };
  constraints: ShelterConstraints | null;
  capacities: ShelterCapacity[];
  availability: AvailabilityDto[];
  data_age_seconds?: number;
  data_freshness?: string;
}

interface SurgeEventResponse {
  id: string;
  status: string;
  reason: string;
  activatedAt: string;
}

interface ReservationResponse {
  id: string;
  shelterId: string;
  populationType: string;
  status: string;
  expiresAt: string;
  remainingSeconds: number;
  createdAt: string;
  notes: string | null;
}

export function OutreachSearch() {
  const intl = useIntl();
  const { isOnline } = useOnlineStatus();
  const { isAuthenticated } = useAuth();
  const [results, setResults] = useState<BedSearchResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [populationType, setPopulationType] = useState('');
  const [petsAllowed, setPetsAllowed] = useState(false);
  const [wheelchairAccessible, setWheelchairAccessible] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [selectedShelter, setSelectedShelter] = useState<ShelterDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [activeSurge, setActiveSurge] = useState<SurgeEventResponse | null>(null);
  const [reservations, setReservations] = useState<ReservationResponse[]>([]);
  const [holdingShelterId, setHoldingShelterId] = useState<string | null>(null);
  const [holdPopType, setHoldPopType] = useState<string | null>(null);
  const [showReservations, setShowReservations] = useState(false);
  const countdownRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const detailModalRef = useRef<HTMLDivElement>(null);
  const referralModalRef = useRef<HTMLDivElement>(null);

  // Queued holds state (offline)
  interface QueuedHold {
    shelterId: string;
    populationType: string;
    idempotencyKey: string;
    timestamp: number;
    status: 'QUEUED' | 'SENDING' | 'CONFIRMED' | 'CONFLICTED' | 'EXPIRED' | 'FAILED';
  }
  const [queuedHolds, setQueuedHolds] = useState<QueuedHold[]>([]);

  // DV referral state
  const [referralModal, setReferralModal] = useState<{ shelterId: string; popType: string } | null>(null);
  const [referralForm, setReferralForm] = useState({ householdSize: 1, urgency: 'STANDARD', specialNeeds: '', callbackNumber: '' });
  const [referralSubmitting, setReferralSubmitting] = useState(false);
  const [myReferrals, setMyReferrals] = useState<ReferralToken[]>([]);
  const [referralListError, setReferralListError] = useState<string | null>(null);
  const [showReferrals, setShowReferrals] = useState(false);
  const [offlineReferralShelterId, setOfflineReferralShelterId] = useState<string | null>(null);
  const [referralError, setReferralError] = useState<string | null>(null);

  const fetchBeds = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const body: Record<string, unknown> = {};
      if (populationType) body.populationType = populationType;
      const constraints: Record<string, boolean> = {};
      if (petsAllowed) constraints.petsAllowed = true;
      if (wheelchairAccessible) constraints.wheelchairAccessible = true;
      if (Object.keys(constraints).length > 0) body.constraints = constraints;

      const data = await api.post<BedSearchResponse>('/api/v1/queries/beds', body);
      setResults(data?.results || []);
    } catch {
      setError(intl.formatMessage({ id: 'search.error' }));
    } finally {
      setLoading(false);
    }
  }, [populationType, petsAllowed, wheelchairAccessible, intl]);

  useEffect(() => { fetchBeds(); }, [fetchBeds]);

  const fetchReservations = useCallback(async () => {
    try {
      const data = await api.get<ReservationResponse[]>('/api/v1/reservations');
      setReservations(data || []);
    } catch { /* silent — reservations panel is optional */ }
  }, []);

  useEffect(() => { fetchReservations(); }, [fetchReservations]);

  // Listen for replay lifecycle events from Layout
  useEffect(() => {
    const handleReplaying = () => {
      setQueuedHolds((prev) => prev.map((h) =>
        h.status === 'QUEUED' ? { ...h, status: 'SENDING' as const } : h
      ));
    };
    window.addEventListener('fabt-queue-replaying', handleReplaying);
    return () => window.removeEventListener('fabt-queue-replaying', handleReplaying);
  }, []);

  useEffect(() => {
    const handleReplay = (e: Event) => {
      const result = (e as CustomEvent<ReplayResult>).detail;

      setQueuedHolds((prev) => {
        let updated = [...prev];

        for (const action of result.succeededActions) {
          if (action.type === 'HOLD_BED') {
            updated = updated.map((h) =>
              h.idempotencyKey === action.idempotencyKey ? { ...h, status: 'CONFIRMED' as const } : h
            );
          }
        }
        for (const action of result.expired) {
          if (action.type === 'HOLD_BED') {
            updated = updated.map((h) =>
              h.idempotencyKey === action.idempotencyKey ? { ...h, status: 'EXPIRED' as const } : h
            );
          }
        }
        for (const action of result.conflicts) {
          if (action.type === 'HOLD_BED') {
            updated = updated.map((h) =>
              h.idempotencyKey === action.idempotencyKey ? { ...h, status: 'CONFLICTED' as const } : h
            );
          }
        }
        for (const action of result.failedActions) {
          if (action.type === 'HOLD_BED') {
            updated = updated.map((h) =>
              h.idempotencyKey === action.idempotencyKey ? { ...h, status: 'FAILED' as const } : h
            );
          }
        }

        return updated.filter((h) => h.status !== 'CONFIRMED');
      });

      fetchReservations();
      fetchBeds();
    };

    window.addEventListener('fabt-queue-replayed', handleReplay);
    return () => window.removeEventListener('fabt-queue-replayed', handleReplay);
  }, [fetchReservations, fetchBeds]);

  // Fetch DV referrals
  const fetchReferrals = useCallback(async () => {
    try {
      const data = await api.get<ReferralToken[]>('/api/v1/dv-referrals/mine');
      setMyReferrals(data || []);
      setReferralListError(null);
    } catch (err) {
      // Expected for users without DV access — keep panel hidden without alarming copy.
      if (err instanceof ApiError && err.status === 403) {
        setMyReferrals([]);
        setReferralListError(null);
        return;
      }
      setReferralListError(intl.formatMessage({ id: 'referral.myReferralsLoadError' }));
    }
  }, [intl]);

  useEffect(() => { fetchReferrals(); }, [fetchReferrals]);

  // Elena Vasquez / design: clear in-session referral list on logout (no IndexedDB cache for /mine).
  useEffect(() => {
    if (!isAuthenticated) {
      setMyReferrals([]);
      setReferralListError(null);
      setShowReferrals(false);
    }
  }, [isAuthenticated]);

  const submitReferral = async () => {
    if (!referralModal) return;
    setReferralSubmitting(true);
    setReferralError(null);
    try {
      await api.post('/api/v1/dv-referrals', {
        shelterId: referralModal.shelterId,
        householdSize: referralForm.householdSize,
        populationType: referralModal.popType,
        urgency: referralForm.urgency,
        specialNeeds: referralForm.specialNeeds,
        callbackNumber: referralForm.callbackNumber,
      });
      setReferralModal(null);
      setReferralForm({ householdSize: 1, urgency: 'STANDARD', specialNeeds: '', callbackNumber: '' });
      await fetchReferrals();
    } catch (err) {
      if (err instanceof TypeError) {
        // Network error (offline, captive portal, DNS failure) — show inside modal
        setReferralError(intl.formatMessage({ id: 'referral.networkError' }));
      } else {
        // API error (server-side) — show server message if available (e.g., duplicate referral)
        const apiErr = err as { message?: string };
        setReferralError(apiErr.message || intl.formatMessage({ id: 'search.error' }));
      }
    } finally {
      setReferralSubmitting(false);
    }
  };

  // Clear offline referral message when connectivity returns
  useEffect(() => {
    if (isOnline) {
      setOfflineReferralShelterId(null);
    }
  }, [isOnline]);

  // Fetch active surge
  useEffect(() => {
    (async () => {
      try {
        const surges = await api.get<SurgeEventResponse[]>('/api/v1/surge-events');
        const active = (surges || []).find(s => s.status === 'ACTIVE');
        setActiveSurge(active || null);
      } catch { /* silent */ }
    })();
  }, []);

  // Auto-refresh on SSE notifications
  useEffect(() => {
    const handleReferralUpdate = () => { fetchReferrals(); };
    const handleAvailabilityUpdate = () => { fetchBeds(); };
    window.addEventListener(SSE_REFERRAL_UPDATE, handleReferralUpdate);
    window.addEventListener(SSE_AVAILABILITY_UPDATE, handleAvailabilityUpdate);
    return () => {
      window.removeEventListener(SSE_REFERRAL_UPDATE, handleReferralUpdate);
      window.removeEventListener(SSE_AVAILABILITY_UPDATE, handleAvailabilityUpdate);
    };
  }, [fetchReferrals, fetchBeds]);

  // Countdown timer for active reservations
  useEffect(() => {
    if (reservations.some(r => r.status === 'HELD')) {
      countdownRef.current = setInterval(() => {
        setReservations(prev => prev.map(r => ({
          ...r,
          remainingSeconds: Math.max(0, r.remainingSeconds - 1),
        })));
      }, 1000);
    }
    return () => { if (countdownRef.current) clearInterval(countdownRef.current); };
  // eslint-disable-next-line react-hooks/exhaustive-deps -- Intentionally depends on .length, not the full array (avoids infinite re-renders from the interval update)
  }, [reservations.length]);

  // Auto-focus modal content on open — required for onKeyDown (Escape) to work.
  // WAI-ARIA APG: dialog content receives focus, tabIndex={-1} makes it programmatically focusable.
  useEffect(() => {
    if (selectedShelter) detailModalRef.current?.focus();
  }, [selectedShelter]);

  useEffect(() => {
    if (referralModal) referralModalRef.current?.focus();
  }, [referralModal]);

  const holdBed = async (shelterId: string, popType: string) => {
    setHoldingShelterId(shelterId);
    setHoldPopType(popType);
    try {
      if (!navigator.onLine) {
        const key = await enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {
          shelterId,
          populationType: popType,
        });
        setQueuedHolds(prev => [...prev, {
          shelterId,
          populationType: popType,
          idempotencyKey: key,
          timestamp: Date.now(),
          status: 'QUEUED',
        }]);
        setShowReservations(true);
        return;
      }
      await api.post('/api/v1/reservations', {
        shelterId,
        populationType: popType,
      });
      await fetchReservations();
      await fetchBeds();
      setShowReservations(true);
    } catch {
      // Online but request failed (navigator.onLine lied, or network is flaky).
      // Enqueue as fallback rather than showing error — replay will handle it.
      try {
        const key = await enqueueAction('HOLD_BED', '/api/v1/reservations', 'POST', {
          shelterId,
          populationType: popType,
        });
        setQueuedHolds(prev => [...prev, {
          shelterId,
          populationType: popType,
          idempotencyKey: key,
          timestamp: Date.now(),
          status: 'QUEUED',
        }]);
        setShowReservations(true);
      } catch {
        // IndexedDB also failed — show error as last resort
        setError(intl.formatMessage({ id: 'search.holdFailed' }));
      }
    } finally {
      setHoldingShelterId(null);
      setHoldPopType(null);
    }
  };

  const confirmReservation = async (id: string) => {
    try {
      await api.patch(`/api/v1/reservations/${id}/confirm`);
      await fetchReservations();
      await fetchBeds();
      // Phase 3 task 7.3 — terminal action; fan-out markActed to every
      // notification carrying this reservationId (same contract as
      // MyPastHoldsPage). Best-effort — the confirm IS recorded.
      markNotificationsActedByPayload('reservationId', id, 'acted').catch(() => { /* best-effort */ });
    } catch {
      setError(intl.formatMessage({ id: 'search.error' }));
    }
  };

  const cancelReservation = async (id: string) => {
    try {
      await api.patch(`/api/v1/reservations/${id}/cancel`);
      await fetchReservations();
      await fetchBeds();
      // Phase 3 task 7.3 — cancel is terminal too.
      markNotificationsActedByPayload('reservationId', id, 'acted').catch(() => { /* best-effort */ });
    } catch {
      setError(intl.formatMessage({ id: 'search.error' }));
    }
  };

  const openDetail = async (shelterId: string) => {
    setDetailLoading(true);
    try {
      const detail = await api.get<ShelterDetail>(`/api/v1/shelters/${shelterId}`);
      setSelectedShelter(detail);
    } catch {
      setError(intl.formatMessage({ id: 'search.error' }));
    } finally {
      setDetailLoading(false);
    }
  };

  const filtered = results.filter((r) => {
    if (!searchText) return true;
    const q = searchText.toLowerCase();
    return r.shelterName.toLowerCase().includes(q) ||
      (r.address && r.address.toLowerCase().includes(q));
  });

  const mapsUrl = (r: BedSearchResult) =>
    r.latitude && r.longitude
      ? `https://maps.google.com/?q=${r.latitude},${r.longitude}`
      : `https://maps.google.com/?q=${encodeURIComponent(r.address)}`;

  const totalBedsAvailable = (r: BedSearchResult) =>
    r.availability.reduce((sum, a) => sum + a.bedsAvailable, 0);

  const freshnessLabel = (freshness: string) => {
    const colors: Record<string, { bg: string; color: string }> = {
      FRESH: { bg: color.successBg, color: color.success },
      AGING: { bg: color.warningBg, color: color.warning },
      STALE: { bg: color.errorBg, color: color.error },
      UNKNOWN: { bg: color.borderLight, color: color.textTertiary },
    };
    const style = colors[freshness] || colors.UNKNOWN;
    return (
      <span style={{
        padding: '2px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.bold,
        backgroundColor: style.bg, color: style.color, textTransform: 'uppercase',
        letterSpacing: '0.04em',
      }}>{freshness}</span>
    );
  };

  return (
    <div style={{ maxWidth: 720, margin: '0 auto' }}>
      {/* Header */}
      <div style={{
        background: `linear-gradient(135deg, ${color.headerGradientStart} 0%, ${color.headerGradientMid} 50%, ${color.headerGradientEnd} 100%)`,
        borderRadius: 16, padding: '28px 24px', marginBottom: 20, color: color.textInverse,
        boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
      }}>
        <h1 style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="search.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: color.headerText }}>
          <FormattedMessage id="search.subtitle" />
        </p>
      </div>

      {/* Surge banner */}
      {activeSurge && (
        <div style={{
          padding: '14px 20px', borderRadius: 12, marginBottom: 14,
          background: `linear-gradient(135deg, ${color.errorMid} 0%, ${color.error} 100%)`,
          color: color.textInverse, display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          boxShadow: '0 2px 12px rgba(220,38,38,0.3)',
        }}>
          <div>
            <div style={{ fontSize: text.sm, fontWeight: weight.extrabold, letterSpacing: '0.06em', marginBottom: 2 }}>
              <FormattedMessage id="surge.banner" />
            </div>
            <div style={{ fontSize: text.base, fontWeight: weight.medium }}>{activeSurge.reason}</div>
          </div>
          <div style={{ fontSize: text.xs, color: color.textInverse }}>
            <FormattedMessage id="surge.since" />: {new Date(activeSurge.activatedAt).toLocaleString()}
          </div>
        </div>
      )}

      {/* Search input */}
      <input
        type="search"
        value={searchText}
        onChange={(e) => setSearchText(e.target.value)}
        placeholder={intl.formatMessage({ id: 'search.placeholder' })}
        aria-label={intl.formatMessage({ id: 'search.placeholder' })}
        style={{
          width: '100%', padding: '15px 18px', borderRadius: 14,
          border: `2px solid ${color.border}`, fontSize: text.md, minHeight: 50,
          boxSizing: 'border-box', marginBottom: 14,
          fontWeight: weight.medium, color: color.text,
        }}
      />

      {/* Filters */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 20 }}>
        <select
          data-testid="population-type-filter"
          value={populationType}
          onChange={(e) => setPopulationType(e.target.value)}
          aria-label="Filter by population type"
          style={{
            padding: '11px 14px', borderRadius: 10, border: `2px solid ${color.border}`,
            fontSize: text.base, minHeight: 44, backgroundColor: populationType ? color.bgHighlight : color.bg,
            color: populationType ? color.primary : color.textTertiary, fontWeight: weight.medium, cursor: 'pointer',
          }}
        >
          {POPULATION_TYPES.map((pt) => (
            <option key={pt.value} value={pt.value}>{intl.formatMessage({ id: pt.labelId })}</option>
          ))}
        </select>
        <ToggleChip active={petsAllowed} onClick={() => setPetsAllowed(!petsAllowed)} label={`🐕 ${intl.formatMessage({ id: 'search.pets' })}`} />
        <ToggleChip active={wheelchairAccessible} onClick={() => setWheelchairAccessible(!wheelchairAccessible)} label={`♿ ${intl.formatMessage({ id: 'search.wheelchair' })}`} />
      </div>

      {/* Count */}
      <div style={{ fontSize: text.sm, color: color.textTertiary, marginBottom: 10, fontWeight: weight.semibold, letterSpacing: '0.02em' }}>
        {loading
          ? <FormattedMessage id="search.loading" />
          : <FormattedMessage id="search.resultCount" values={{ count: filtered.length }} />}
      </div>

      {error && (
        <div style={{
          backgroundColor: color.errorBg, color: color.error, padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium,
        }}>{error}</div>
      )}

      {loading && <Spinner />}

      {/* Active Reservations Panel */}
      {(reservations.filter(r => r.status === 'HELD').length > 0 || queuedHolds.length > 0) && (
        <div style={{ marginBottom: 16 }}>
          <button
            onClick={() => setShowReservations(!showReservations)}
            style={{
              width: '100%', padding: '14px 18px', borderRadius: 12,
              border: `2px solid ${color.primary}`, backgroundColor: color.bgHighlight,
              color: color.primaryText, fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}
          >
            <span><FormattedMessage id="reservations.title" /> ({reservations.filter(r => r.status === 'HELD').length + queuedHolds.length})</span>
            <span>{showReservations ? '▲' : '▼'}</span>
          </button>
          {showReservations && (
            <div style={{ border: `2px solid ${color.border}`, borderTop: 'none', borderRadius: '0 0 12px 12px', padding: '12px 16px' }}>
              {/* Queued holds (offline) */}
              {queuedHolds.map((qh) => {
                const shelterResult = results.find(r => r.shelterId === qh.shelterId);
                const minutesAgo = Math.floor((Date.now() - qh.timestamp) / 60000);
                return (
                  <div key={qh.idempotencyKey} data-testid={`queued-hold-${qh.shelterId}`} style={{
                    padding: '12px 0', borderBottom: `1px solid ${color.borderLight}`,
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8,
                  }}>
                    <div>
                      <div style={{ fontWeight: weight.bold, fontSize: text.base, color: color.text }}>
                        {shelterResult?.shelterName || qh.shelterId.substring(0, 8)}
                      </div>
                      <div style={{ fontSize: text.xs, color: color.textTertiary }}>
                        {getPopulationTypeLabel(qh.populationType, intl)}
                      </div>
                      <div aria-live="polite" style={{ fontSize: text.sm, fontWeight: weight.bold, marginTop: 4 }}>
                        {qh.status === 'QUEUED' && (
                          <span style={{ color: color.warning }}>
                            &#128336; <FormattedMessage id="search.holdQueued" />
                          </span>
                        )}
                        {qh.status === 'SENDING' && (
                          <span style={{ color: color.primaryText }}>
                            &#8635; Syncing...
                          </span>
                        )}
                        {qh.status === 'CONFLICTED' && (
                          <span style={{ color: color.error }}>
                            <FormattedMessage id="search.holdConflict" />
                          </span>
                        )}
                        {qh.status === 'EXPIRED' && (
                          <span style={{ color: color.textMuted }}>
                            <FormattedMessage id="search.holdExpired" values={{ minutes: minutesAgo }} />
                          </span>
                        )}
                        {qh.status === 'FAILED' && (
                          <span style={{ color: color.warning }}>
                            &#8635; <FormattedMessage id="search.holdFailed.retry" defaultMessage="Could not reach server. Will retry automatically." />
                          </span>
                        )}
                      </div>
                    </div>
                    {(qh.status === 'CONFLICTED' || qh.status === 'EXPIRED') && (
                      <button
                        onClick={() => {
                          setQueuedHolds(prev => prev.filter(h => h.idempotencyKey !== qh.idempotencyKey));
                          window.scrollTo({ top: 0, behavior: 'smooth' });
                        }}
                        style={{
                          padding: '8px 14px', borderRadius: 8, border: `2px solid ${color.border}`,
                          backgroundColor: color.bg, color: color.primaryText, fontSize: text.sm, fontWeight: weight.bold, cursor: 'pointer',
                        }}
                      >
                        <FormattedMessage id="search.title" />
                      </button>
                    )}
                  </div>
                );
              })}
              {reservations.filter(r => r.status === 'HELD').map((res) => {
                const mins = Math.floor(res.remainingSeconds / 60);
                const secs = res.remainingSeconds % 60;
                const isExpiring = res.remainingSeconds < 300;
                const shelterResult = results.find(r => r.shelterId === res.shelterId);
                return (
                  <div key={res.id} style={{
                    padding: '12px 0', borderBottom: `1px solid ${color.borderLight}`,
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8,
                  }}>
                    <div>
                      <button
                        data-testid={`reservation-shelter-link-${res.shelterId}`}
                        onClick={(e) => { e.stopPropagation(); openDetail(res.shelterId); }}
                        style={{
                          fontWeight: weight.bold, fontSize: text.base, color: color.primaryText,
                          background: 'none', border: 'none', padding: 0, cursor: 'pointer',
                          textAlign: 'left', textDecoration: 'underline', textUnderlineOffset: '2px',
                        }}
                        aria-label={intl.formatMessage(
                          { id: 'reservations.viewShelter', defaultMessage: 'View details for {shelter}' },
                          { shelter: shelterResult?.shelterName || res.shelterId.substring(0, 8) }
                        )}
                      >
                        {shelterResult?.shelterName || res.shelterId.substring(0, 8)}
                      </button>
                      <div style={{ fontSize: text.xs, color: color.textTertiary }}>
                        {getPopulationTypeLabel(res.populationType, intl)}
                      </div>
                      <div style={{
                        fontSize: text.sm, fontWeight: weight.bold, marginTop: 4,
                        color: isExpiring ? color.error : color.primary,
                      }}>
                        {res.remainingSeconds > 0
                          ? intl.formatMessage({ id: 'reservations.expiresIn' }, { minutes: mins, seconds: secs })
                          : intl.formatMessage({ id: 'reservations.expired' })}
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <button
                        onClick={() => confirmReservation(res.id)}
                        style={{
                          padding: '8px 14px', borderRadius: 8, border: 'none',
                          backgroundColor: color.success, color: color.textInverse, fontSize: text.sm, fontWeight: weight.bold, cursor: 'pointer',
                        }}
                      ><FormattedMessage id="reservations.confirm" /></button>
                      <button
                        onClick={() => cancelReservation(res.id)}
                        style={{
                          padding: '8px 14px', borderRadius: 8, border: `2px solid ${color.border}`,
                          backgroundColor: color.bg, color: color.textTertiary, fontSize: text.sm, fontWeight: weight.semibold, cursor: 'pointer',
                        }}
                      ><FormattedMessage id="reservations.cancel" /></button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* Results */}
      {!loading && filtered.map((r) => {
        const avail = totalBedsAvailable(r);
        const isFull = avail === 0;

        return (
          <div
            key={r.shelterId}
            data-testid={`shelter-card-${r.shelterName.toLowerCase().replace(/\s+/g, '-')}`}
            onClick={() => openDetail(r.shelterId)}
            style={{
              display: 'block', width: '100%', textAlign: 'left', padding: '18px 20px',
              marginBottom: 10, borderRadius: 14, border: `2px solid ${isFull ? color.errorBorder : color.border}`,
              backgroundColor: isFull ? color.bg : color.bg, cursor: 'pointer',
              transition: 'border-color 0.12s, box-shadow 0.12s',
              // No opacity reduction — WCAG 1.4.3 contrast requirement
            }}
            onMouseEnter={(e) => { e.currentTarget.style.borderColor = color.borderFocus; e.currentTarget.style.boxShadow = '0 2px 12px rgba(59,130,246,0.1)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.borderColor = isFull ? color.errorBorder : color.border; e.currentTarget.style.boxShadow = 'none'; }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 3 }}>
              <div style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.text }}>{r.shelterName}</div>
              {/* Beds available badge */}
              {isFull ? (
                <span style={{
                  padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.bold,
                  backgroundColor: color.errorBg, color: color.error,
                }}><FormattedMessage id="search.currentlyFull" /></span>
              ) : (
                <span style={{
                  padding: '4px 10px', borderRadius: 8, fontSize: text.base, fontWeight: weight.extrabold,
                  backgroundColor: color.successBg, color: color.success,
                }}>{avail}</span>
              )}
            </div>
            <div style={{ fontSize: text.base, color: r.dvShelter ? color.dvText : color.textTertiary, fontStyle: r.dvShelter ? 'italic' : 'normal', marginBottom: 6 }}>
              {r.dvShelter ? intl.formatMessage({ id: 'search.dvAddressHidden' }) : r.address}
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {r.phone && <span style={{ fontSize: text.base, color: color.primaryText, fontWeight: weight.semibold }}>📞 {r.phone}</span>}
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {freshnessLabel(r.dataFreshness)}
                <DataAge dataAgeSeconds={r.dataAgeSeconds} />
              </div>
            </div>
            {/* Per-population availability pills with hold buttons */}
            {r.availability.length > 0 && (
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10, alignItems: 'center' }}>
                {r.availability.map((a) => {
                  const effectiveAvail = activeSurge ? a.bedsAvailable + a.overflowBeds : a.bedsAvailable;
                  return (
                  <div key={a.populationType} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <span style={{
                      padding: '3px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.semibold,
                      backgroundColor: effectiveAvail > 0 ? color.successBg : color.errorBg,
                      color: effectiveAvail > 0 ? color.success : color.error,
                    }}>
                      {getPopulationTypeLabel(a.populationType, intl)}: {effectiveAvail}
                      {a.bedsOnHold > 0 && <span style={{ color: color.warning }}> ({a.bedsOnHold} held)</span>}
                      {activeSurge && a.overflowBeds > 0 && (
                        <span style={{ color: color.textTertiary, fontWeight: weight.normal }}>
                          {' '}<FormattedMessage id="search.includesTemporary" values={{ count: a.overflowBeds }} />
                        </span>
                      )}
                    </span>
                    {effectiveAvail > 0 && !r.dvShelter && (
                      <button
                        data-testid={`hold-bed-${r.shelterId}-${a.populationType}`}
                        onClick={(e) => { e.stopPropagation(); holdBed(r.shelterId, a.populationType); }}
                        disabled={holdingShelterId === r.shelterId && holdPopType === a.populationType}
                        style={{
                          padding: '6px 12px', borderRadius: 6, border: 'none',
                          backgroundColor: color.primary, color: color.textInverse, fontSize: text.xs, fontWeight: weight.bold,
                          cursor: 'pointer', minHeight: 44, minWidth: 44,
                        }}
                      >
                        {holdingShelterId === r.shelterId && holdPopType === a.populationType
                          ? intl.formatMessage({ id: 'search.holding' })
                          : intl.formatMessage({ id: 'search.holdBed' })}
                      </button>
                    )}
                    {effectiveAvail > 0 && r.dvShelter && (
                      <button
                        data-testid={`request-referral-${r.shelterId}-${a.populationType}`}
                        aria-disabled={!isOnline}
                        onClick={(e) => {
                          e.stopPropagation();
                          if (!isOnline) {
                            setOfflineReferralShelterId(r.shelterId);
                            return;
                          }
                          setOfflineReferralShelterId(null);
                          setReferralError(null);
                          setReferralModal({ shelterId: r.shelterId, popType: a.populationType });
                        }}
                        style={{
                          padding: '6px 12px', borderRadius: 6, border: 'none',
                          backgroundColor: color.dv, color: color.textInverse, fontSize: text.xs, fontWeight: weight.bold,
                          cursor: isOnline ? 'pointer' : 'default', minHeight: 44, minWidth: 44,
                          ...(isOnline ? {} : { backgroundColor: color.borderLight, color: color.textMuted }),
                        }}
                      >
                        <FormattedMessage id="search.requestReferral" />
                      </button>
                    )}
                  </div>
                  );
                })}
              </div>
            )}
            {offlineReferralShelterId === r.shelterId && !isOnline && (
              <div
                data-testid={`offline-referral-msg-${r.shelterId}`}
                role="alert"
                aria-live="polite"
                style={{
                  marginTop: 8, padding: '10px 14px', borderRadius: 8,
                  backgroundColor: color.warningBg, color: color.warning,
                  fontSize: text.xs, fontWeight: weight.medium,
                }}
              >
                {r.phone ? (
                  <FormattedMessage
                    id="search.referralOffline"
                    values={{
                      phone: <a href={`tel:${r.phone}`} style={{ color: color.warning, fontWeight: weight.bold }}>{r.phone}</a>,
                    }}
                  />
                ) : (
                  <FormattedMessage id="search.referralOfflineNoPhone" />
                )}
              </div>
            )}
          </div>
        );
      })}

      {!loading && filtered.length === 0 && (
        <div style={{ textAlign: 'center', padding: 48, color: color.textMuted }}>
          <div style={{ fontSize: text['4xl'], marginBottom: 12 }}>🏠</div>
          <div style={{ fontSize: text.md, fontWeight: weight.medium }}><FormattedMessage id="search.noResults" /></div>
          <div style={{ fontSize: text.base, marginTop: 6 }}><FormattedMessage id="search.tryDifferent" /></div>
        </div>
      )}

      {/* Detail modal */}
      {selectedShelter && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'flex-end', justifyContent: 'center' }}
          onClick={() => setSelectedShelter(null)}>
          <div
            ref={detailModalRef}
            role="dialog"
            aria-modal="true"
            aria-label={selectedShelter.shelter.name}
            tabIndex={-1}
            onKeyDown={(e) => { if (e.key === 'Escape') setSelectedShelter(null); }}
            style={{
              backgroundColor: color.bg, borderRadius: '24px 24px 0 0', width: '100%', maxWidth: 600,
              maxHeight: '88vh', overflowY: 'auto', padding: '28px 24px 36px', outline: 'none',
            }} onClick={(e) => e.stopPropagation()}>
            <div style={{ width: 40, height: 4, backgroundColor: color.borderMedium, borderRadius: 2, margin: '0 auto 22px' }} />

            <h2 style={{ margin: '0 0 4px', fontSize: text['2xl'], fontWeight: weight.extrabold, color: color.text }}>{selectedShelter.shelter.name}</h2>
            {selectedShelter.shelter.dvShelter ? (
              <p style={{ margin: '0 0 16px', fontSize: text.base, color: color.dvText, fontStyle: 'italic' }}>
                <FormattedMessage id="search.dvAddressHidden" />
              </p>
            ) : (
              <p style={{ margin: '0 0 16px', fontSize: text.base, color: color.textTertiary }}>
                {[selectedShelter.shelter.addressStreet, selectedShelter.shelter.addressCity,
                  selectedShelter.shelter.addressState, selectedShelter.shelter.addressZip].filter(Boolean).join(', ')}
              </p>
            )}

            {selectedShelter.data_age_seconds !== undefined && (
              <div style={{ marginBottom: 18 }}><DataAge dataAgeSeconds={selectedShelter.data_age_seconds} /></div>
            )}

            {/* Action row */}
            <div style={{ display: 'flex', gap: 10, marginBottom: 24 }}>
              {selectedShelter.shelter.phone && (
                <a href={`tel:${selectedShelter.shelter.phone}`} style={{
                  flex: 1, padding: 14, backgroundColor: color.success, color: color.textInverse, borderRadius: 12,
                  textAlign: 'center', textDecoration: 'none', fontSize: text.md, fontWeight: weight.bold, minHeight: 50,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                }}>📞 <FormattedMessage id="search.call" /></a>
              )}
              {/* Hide directions for DV shelters — address shared verbally only (FVPSA) */}
              {!selectedShelter.shelter.dvShelter && (
                <a href={mapsUrl({
                  shelterId: selectedShelter.shelter.id, shelterName: selectedShelter.shelter.name,
                  address: [selectedShelter.shelter.addressStreet, selectedShelter.shelter.addressCity].filter(Boolean).join(', '),
                  phone: selectedShelter.shelter.phone, latitude: selectedShelter.shelter.latitude,
                  longitude: selectedShelter.shelter.longitude, availability: [], dataAgeSeconds: null,
                  dataFreshness: 'UNKNOWN', distanceMiles: null,
                  constraints: { petsAllowed: false, wheelchairAccessible: false, sobrietyRequired: false, idRequired: false, referralRequired: false },
                  surgeActive: false,
                  dvShelter: false,
                })} target="_blank" rel="noopener noreferrer" style={{
                  flex: 1, padding: 14, backgroundColor: color.primary, color: color.textInverse, borderRadius: 12,
                  textAlign: 'center', textDecoration: 'none', fontSize: text.md, fontWeight: weight.bold, minHeight: 50,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                }}>🗺️ <FormattedMessage id="search.directions" /></a>
              )}
            </div>

            {/* Availability */}
            {selectedShelter.availability?.length > 0 && (
              <Section title={intl.formatMessage({ id: 'search.availability' })}>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  {selectedShelter.availability.map((a) => (
                    <div key={a.populationType} style={{
                      padding: '12px 18px', backgroundColor: a.bedsAvailable > 0 ? color.successBg : color.errorBg,
                      border: `1px solid ${a.bedsAvailable > 0 ? color.successBorder : color.errorBorder}`,
                      borderRadius: 12, textAlign: 'center', minWidth: 100,
                    }}>
                      <div style={{
                        fontWeight: weight.extrabold, color: a.bedsAvailable > 0 ? color.success : color.error,
                        fontSize: text['2xl'], lineHeight: leading.tight,
                      }}>{a.bedsAvailable}</div>
                      <div style={{
                        color: a.bedsAvailable > 0 ? color.success : color.error,
                        fontSize: text['2xs'], fontWeight: weight.semibold, marginTop: 4,
                        textTransform: 'uppercase', letterSpacing: '0.04em',
                      }}>
                        {getPopulationTypeLabel(a.populationType, intl)}
                      </div>
                      <div style={{ fontSize: text['2xs'], color: color.textTertiary, marginTop: 4 }}>
                        {a.bedsTotal} total / {a.bedsOccupied} occ
                      </div>
                    </div>
                  ))}
                </div>
              </Section>
            )}

            {/* Capacity (static) */}
            {selectedShelter.capacities?.length > 0 && (
              <Section title={intl.formatMessage({ id: 'search.capacity' })}>
                <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                  {selectedShelter.capacities.map((cap) => (
                    <div key={cap.populationType} style={{
                      padding: '12px 18px', backgroundColor: color.successBg, border: `1px solid ${color.successBorder}`,
                      borderRadius: 12, textAlign: 'center', minWidth: 90,
                    }}>
                      <div style={{ fontWeight: weight.extrabold, color: color.success, fontSize: text['2xl'], lineHeight: leading.tight }}>{cap.bedsTotal}</div>
                      <div style={{ color: color.success, fontSize: text['2xs'], fontWeight: weight.semibold, marginTop: 4, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                        {getPopulationTypeLabel(cap.populationType, intl)}
                      </div>
                    </div>
                  ))}
                </div>
              </Section>
            )}

            {/* Constraints */}
            {selectedShelter.constraints && (
              <Section title={intl.formatMessage({ id: 'search.requirements' })}>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <Badge ok={selectedShelter.constraints.petsAllowed} yes="🐕 Pets OK" no="🚫 No Pets" />
                  <Badge ok={selectedShelter.constraints.wheelchairAccessible} yes="♿ Accessible" no="⚠️ Not Accessible" />
                  <Badge ok={!selectedShelter.constraints.sobrietyRequired} yes="✅ No Sobriety Req" no="🔒 Sobriety Required" />
                  <Badge ok={!selectedShelter.constraints.idRequired} yes="✅ No ID Needed" no="🪪 ID Required" />
                  <Badge ok={!selectedShelter.constraints.referralRequired} yes="✅ Walk-in OK" no="📋 Referral Required" />
                </div>
                {selectedShelter.constraints.curfewTime && (
                  <div style={{ marginTop: 12, fontSize: text.base, color: color.textTertiary, fontWeight: weight.medium }}>
                    ⏰ <FormattedMessage id="search.curfew" />: {selectedShelter.constraints.curfewTime}
                  </div>
                )}
                {selectedShelter.constraints.maxStayDays && (
                  <div style={{ marginTop: 4, fontSize: text.base, color: color.textTertiary, fontWeight: weight.medium }}>
                    📅 <FormattedMessage id="search.maxStay" />: {selectedShelter.constraints.maxStayDays} <FormattedMessage id="search.days" />
                  </div>
                )}
                {selectedShelter.constraints.populationTypesServed?.length > 0 && (
                  <div style={{ marginTop: 12 }}>
                    <div style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 6, textTransform: 'uppercase' }}>
                      <FormattedMessage id="search.serves" />
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {selectedShelter.constraints.populationTypesServed.map((pt) => (
                        <span key={pt} style={{
                          padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
                          backgroundColor: color.bgHighlight, color: color.primaryText,
                        }}>{pt.replace(/_/g, ' ')}</span>
                      ))}
                    </div>
                  </div>
                )}
              </Section>
            )}

            <button onClick={() => setSelectedShelter(null)} style={{
              width: '100%', padding: 16, backgroundColor: color.borderLight, color: color.textTertiary,
              border: 'none', borderRadius: 12, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 50,
            }}><FormattedMessage id="search.close" /></button>
          </div>
        </div>
      )}

      {/* DV Referral Request Modal */}
      {referralModal && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1002, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          onClick={() => setReferralModal(null)}>
          <div
            ref={referralModalRef}
            role="dialog"
            aria-modal="true"
            aria-label={intl.formatMessage({ id: 'referral.requestTitle' })}
            tabIndex={-1}
            onKeyDown={(e) => { if (e.key === 'Escape') setReferralModal(null); }}
            data-testid="referral-modal"
            style={{
              backgroundColor: color.bg, borderRadius: 16, width: '90%', maxWidth: 440, padding: '28px 24px',
              boxShadow: '0 8px 32px rgba(0,0,0,0.2)', outline: 'none',
            }} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 4px', fontSize: text.lg, fontWeight: weight.extrabold, color: color.dvText }}>
              <FormattedMessage id="referral.title" />
            </h3>
            <p style={{ margin: '0 0 16px', fontSize: text.sm, color: color.textTertiary }}>
              <FormattedMessage id="referral.subtitle" />
            </p>

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.householdSize" />
            </label>
            <input data-testid="referral-household-size" type="number" min={1} max={20} value={referralForm.householdSize}
              onChange={(e) => setReferralForm(f => ({ ...f, householdSize: parseInt(e.target.value) || 1 }))}
              style={{ width: '100%', padding: 10, borderRadius: 8, border: `2px solid ${color.border}`, fontSize: text.base, marginBottom: 12 }} />

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.urgency" />
            </label>
            <div data-testid="referral-urgency" style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              {['STANDARD', 'URGENT', 'EMERGENCY'].map(u => (
                <button key={u} onClick={() => setReferralForm(f => ({ ...f, urgency: u }))}
                  style={{
                    flex: 1, padding: 8, borderRadius: 8, border: `2px solid ${referralForm.urgency === u ? color.dv : color.border}`,
                    backgroundColor: referralForm.urgency === u ? color.dvBg : color.bg,
                    color: referralForm.urgency === u ? color.dvText : color.textTertiary,
                    fontSize: text.xs, fontWeight: weight.bold, cursor: 'pointer',
                  }}>{u}</button>
              ))}
            </div>

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.specialNeeds" />
            </label>
            <textarea data-testid="referral-special-needs" value={referralForm.specialNeeds}
              onChange={(e) => setReferralForm(f => ({ ...f, specialNeeds: e.target.value }))}
              placeholder={intl.formatMessage({ id: 'referral.specialNeedsPlaceholder' })}
              style={{ width: '100%', padding: 10, borderRadius: 8, border: `2px solid ${color.border}`, fontSize: text.sm, minHeight: 60, resize: 'vertical', marginBottom: 12 }} />

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.callbackNumber" />
            </label>
            <input data-testid="referral-callback" type="tel" value={referralForm.callbackNumber}
              onChange={(e) => setReferralForm(f => ({ ...f, callbackNumber: e.target.value }))}
              placeholder="919-555-0000"
              style={{ width: '100%', padding: 10, borderRadius: 8, border: `2px solid ${color.border}`, fontSize: text.base, marginBottom: 16 }} />

            {referralError && (
              <div data-testid="referral-error" role="alert" style={{
                padding: '10px 14px', borderRadius: 8, marginBottom: 12,
                backgroundColor: color.errorBg, color: color.error,
                fontSize: text.xs, fontWeight: weight.medium,
              }}>
                {referralError}
              </div>
            )}

            <div style={{ display: 'flex', gap: 8 }}>
              <button onClick={() => { setReferralModal(null); setReferralError(null); }}
                style={{ flex: 1, padding: 12, borderRadius: 10, border: `2px solid ${color.border}`, backgroundColor: color.bg, color: color.textTertiary, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer' }}>
                <FormattedMessage id="referral.cancel" />
              </button>
              <button data-testid="referral-submit" onClick={submitReferral} disabled={referralSubmitting || !referralForm.callbackNumber}
                style={{
                  flex: 1, padding: 12, borderRadius: 10, border: 'none',
                  backgroundColor: referralSubmitting ? color.dv : color.dv, color: color.textInverse,
                  fontSize: text.base, fontWeight: weight.bold, cursor: referralSubmitting ? 'default' : 'pointer',
                }}>
                {referralSubmitting ? '...' : intl.formatMessage({ id: 'referral.submit' })}
              </button>
            </div>
          </div>
        </div>
      )}

      {referralListError && (
        <div
          role="alert"
          data-testid="referral-list-error"
          style={{
            marginTop: 12,
            marginBottom: 8,
            padding: '12px 14px',
            borderRadius: 10,
            backgroundColor: color.errorBg,
            color: color.error,
            fontSize: text.sm,
            fontWeight: weight.medium,
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'center',
            gap: 10,
          }}
        >
          <span style={{ flex: '1 1 200px' }}>{referralListError}</span>
          <button
            type="button"
            data-testid="referral-list-retry"
            onClick={() => { void fetchReferrals(); }}
            style={{
              padding: '8px 14px',
              borderRadius: 8,
              border: `1px solid ${color.error}`,
              backgroundColor: color.bg,
              color: color.error,
              fontSize: text.xs,
              fontWeight: weight.bold,
              cursor: 'pointer',
            }}
          >
            <FormattedMessage id="referral.myReferralsRetry" />
          </button>
        </div>
      )}

      {/* My DV Referrals section */}
      {myReferrals.length > 0 && (
        <div style={{ marginTop: 16, marginBottom: 16 }}>
          <button onClick={() => setShowReferrals(!showReferrals)}
            style={{
              width: '100%', padding: '14px 18px', borderRadius: 12,
              border: `2px solid ${color.dv}`, backgroundColor: color.dvBg,
              color: color.dvText, fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
            <span><FormattedMessage id="referral.myReferrals" /> ({myReferrals.filter(r => r.status === 'PENDING').length} <FormattedMessage id="referral.pending" />)</span>
            <span>{showReferrals ? '▲' : '▼'}</span>
          </button>
          {showReferrals && (
            <div data-testid="my-referrals" role="list" aria-label={intl.formatMessage({ id: 'referral.myReferrals' })} style={{ border: `2px solid ${color.border}`, borderTop: 'none', borderRadius: '0 0 12px 12px', padding: '12px 16px' }}>
              {myReferrals.map((ref) => {
                const populationLabel = getPopulationTypeLabel(ref.populationType, intl);
                const timeStr = formatReferralListTime(ref.createdAt, intl)
                  || intl.formatMessage({ id: 'referral.timeUnknown' });
                const statusHuman = referralStatusLabel(ref.status, intl);
                const shelterDisp = (ref.shelterName && ref.shelterName.trim())
                  ? ref.shelterName.trim()
                  : intl.formatMessage({ id: 'referral.shelterUnknown' });
                const headline = intl.formatMessage(
                  { id: 'referral.myReferralHeadline' },
                  { status: statusHuman, shelter: shelterDisp, population: populationLabel, time: timeStr }
                );
                const aria = intl.formatMessage(
                  { id: 'referral.listItemAriaLabel' },
                  { status: statusHuman, shelter: shelterDisp, population: populationLabel, time: timeStr }
                );
                const badgeBg = ref.status === 'ACCEPTED' ? color.successBg
                  : ref.status === 'REJECTED' || ref.status === 'EXPIRED' ? color.errorBg
                  : ref.status === 'SHELTER_CLOSED' ? color.errorBg
                  : color.warningBg;
                const badgeFg = ref.status === 'ACCEPTED' ? color.success
                  : ref.status === 'REJECTED' || ref.status === 'EXPIRED' || ref.status === 'SHELTER_CLOSED' ? color.error
                  : color.warning;
                // Tooltip: expiry time + remaining (Devon Kessler: "created for identity,
                // expiry on hover"). Follows the DataAge.tsx native title= pattern.
                const expiryTooltip = ref.expiresAt
                  ? intl.formatMessage(
                      { id: 'referral.expiryTooltip' },
                      {
                        expiresAt: new Date(ref.expiresAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }),
                        remaining: ref.remainingSeconds != null && ref.remainingSeconds > 0
                          ? Math.ceil(ref.remainingSeconds / 60).toString()
                          : '0',
                      }
                    )
                  : undefined;

                return (
                <div key={ref.id} data-testid={`referral-${ref.id}`} role="listitem" aria-label={aria} style={{
                  padding: '10px 0', borderBottom: `1px solid ${color.borderLight}`,
                  display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8,
                }}>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <span
                      data-testid={`referral-primary-line-${ref.id}`}
                      title={expiryTooltip}
                      style={{ fontSize: text.sm, fontWeight: weight.semibold, color: color.text, display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', cursor: expiryTooltip ? 'help' : undefined }}
                    >
                      {headline}
                    </span>
                    <span style={{ fontSize: text['2xs'], color: color.textTertiary, marginTop: 2, display: 'block' }}>
                      {populationLabel} — <FormattedMessage id="referral.householdCount" values={{ count: ref.householdSize }} />
                    </span>
                    <div style={{ fontSize: text['2xs'], color: color.textTertiary, marginTop: 4 }}>
                      {ref.status === 'SHELTER_CLOSED' && (
                        <span data-testid={`referral-shelter-closed-${ref.id}`} style={{ color: color.error, display: 'block' }}>
                          <FormattedMessage id="referral.shelterClosedGuidance" />
                        </span>
                      )}
                      {ref.status === 'ACCEPTED' && ref.shelterPhone && (
                        <span data-testid={`referral-phone-${ref.id}`} style={{ color: color.success, fontWeight: weight.bold }}>
                          <FormattedMessage id="referral.callShelter" /> {ref.shelterPhone}
                        </span>
                      )}
                      {ref.status === 'REJECTED' && ref.rejectionReason && (
                        <span style={{ color: color.error }}>
                          <FormattedMessage id="referral.declined" />: {ref.rejectionReason}
                        </span>
                      )}
                      {ref.status === 'PENDING' && ref.remainingSeconds != null && (
                        <span style={{ color: color.warning }}>
                          <FormattedMessage id="referral.waiting" /> — {Math.floor(ref.remainingSeconds / 60)}m remaining
                        </span>
                      )}
                      {ref.status === 'EXPIRED' && (
                        <span style={{ color: color.error }}><FormattedMessage id="referral.expired" /></span>
                      )}
                    </div>
                  </div>
                  <span style={{
                    padding: '3px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.bold, flexShrink: 0,
                    backgroundColor: badgeBg,
                    color: badgeFg,
                  }}>{statusHuman}</span>
                </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {detailLoading && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.3)', zIndex: 1001, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ backgroundColor: color.bg, borderRadius: 16, padding: 32 }}><Spinner /></div>
        </div>
      )}
    </div>
  );
}

function ToggleChip({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button onClick={onClick} style={{
      padding: '10px 14px', borderRadius: 10, border: `2px solid ${active ? color.primary : color.border}`,
      backgroundColor: active ? color.bgHighlight : color.bg, color: active ? color.primary : color.textTertiary,
      cursor: 'pointer', fontSize: text.base, fontWeight: active ? weight.semibold : weight.medium, minHeight: 44,
      display: 'flex', alignItems: 'center', gap: 4, transition: 'all 0.12s',
    }}>{label}</button>
  );
}

function Badge({ ok, yes, no }: { ok: boolean; yes: string; no: string }) {
  return (
    <span style={{
      padding: '6px 12px', borderRadius: 8, fontSize: text.sm, fontWeight: weight.semibold,
      backgroundColor: ok ? color.successBg : color.errorBg, color: ok ? color.success : color.error,
      border: `1px solid ${ok ? color.successBorder : color.errorBorder}`,
    }}>{ok ? yes : no}</span>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <h3 style={{ fontSize: text.xs, fontWeight: weight.bold, color: color.textTertiary, marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.06em' }}>{title}</h3>
      {children}
    </div>
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
