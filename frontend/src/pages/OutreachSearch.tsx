import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';
import { text, weight, leading } from '../theme/typography';
import { getPopulationTypeLabel } from '../utils/populationTypeLabels';

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

  // DV referral state
  const [referralModal, setReferralModal] = useState<{ shelterId: string; popType: string } | null>(null);
  const [referralForm, setReferralForm] = useState({ householdSize: 1, urgency: 'STANDARD', specialNeeds: '', callbackNumber: '' });
  const [referralSubmitting, setReferralSubmitting] = useState(false);
  const [myReferrals, setMyReferrals] = useState<ReferralToken[]>([]);
  const [showReferrals, setShowReferrals] = useState(false);

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

  // Fetch DV referrals
  const fetchReferrals = useCallback(async () => {
    try {
      const data = await api.get<ReferralToken[]>('/api/v1/dv-referrals/mine');
      setMyReferrals(data || []);
    } catch { /* silent — referrals panel is optional (user may not have dvAccess) */ }
  }, []);

  useEffect(() => { fetchReferrals(); }, [fetchReferrals]);

  const submitReferral = async () => {
    if (!referralModal) return;
    setReferralSubmitting(true);
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
      fetchReferrals();
    } catch {
      setError(intl.formatMessage({ id: 'search.error' }));
    } finally {
      setReferralSubmitting(false);
    }
  };

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

  const holdBed = async (shelterId: string, popType: string) => {
    setHoldingShelterId(shelterId);
    setHoldPopType(popType);
    try {
      await api.post('/api/v1/reservations', {
        shelterId,
        populationType: popType,
      });
      await fetchReservations();
      await fetchBeds();
      setShowReservations(true);
    } catch {
      setError(intl.formatMessage({ id: 'search.holdFailed' }));
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
    } catch {
      setError(intl.formatMessage({ id: 'search.error' }));
    }
  };

  const cancelReservation = async (id: string) => {
    try {
      await api.patch(`/api/v1/reservations/${id}/cancel`);
      await fetchReservations();
      await fetchBeds();
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
      FRESH: { bg: '#f0fdf4', color: '#166534' },
      AGING: { bg: '#fefce8', color: '#854d0e' },
      STALE: { bg: '#fef2f2', color: '#991b1b' },
      UNKNOWN: { bg: '#f1f5f9', color: '#475569' },
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
        background: 'linear-gradient(135deg, #0c1929 0%, #1a3a5c 50%, #0f2940 100%)',
        borderRadius: 16, padding: '28px 24px', marginBottom: 20, color: '#fff',
        boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
      }}>
        <h1 style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="search.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: '#94b8d8' }}>
          <FormattedMessage id="search.subtitle" />
        </p>
      </div>

      {/* Surge banner */}
      {activeSurge && (
        <div style={{
          padding: '14px 20px', borderRadius: 12, marginBottom: 14,
          background: 'linear-gradient(135deg, #dc2626 0%, #b91c1c 100%)',
          color: '#fff', display: 'flex', justifyContent: 'space-between', alignItems: 'center',
          boxShadow: '0 2px 12px rgba(220,38,38,0.3)',
        }}>
          <div>
            <div style={{ fontSize: text.sm, fontWeight: weight.extrabold, letterSpacing: '0.06em', marginBottom: 2 }}>
              <FormattedMessage id="surge.banner" />
            </div>
            <div style={{ fontSize: text.base, fontWeight: weight.medium }}>{activeSurge.reason}</div>
          </div>
          <div style={{ fontSize: text.xs, color: '#475569' }}>
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
          border: '2px solid #e2e8f0', fontSize: text.md, minHeight: 50,
          boxSizing: 'border-box', marginBottom: 14, outline: 'none',
          fontWeight: weight.medium, color: '#0f172a',
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
            padding: '11px 14px', borderRadius: 10, border: '2px solid #e2e8f0',
            fontSize: text.base, minHeight: 44, backgroundColor: populationType ? '#eff6ff' : '#fff',
            color: populationType ? '#1a56db' : '#475569', fontWeight: weight.medium, cursor: 'pointer',
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
      <div style={{ fontSize: text.sm, color: '#475569', marginBottom: 10, fontWeight: weight.semibold, letterSpacing: '0.02em' }}>
        {loading
          ? <FormattedMessage id="search.loading" />
          : <FormattedMessage id="search.resultCount" values={{ count: filtered.length }} />}
      </div>

      {error && (
        <div style={{
          backgroundColor: '#fef2f2', color: '#991b1b', padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium,
        }}>{error}</div>
      )}

      {loading && <Spinner />}

      {/* Active Reservations Panel */}
      {reservations.filter(r => r.status === 'HELD').length > 0 && (
        <div style={{ marginBottom: 16 }}>
          <button
            onClick={() => setShowReservations(!showReservations)}
            style={{
              width: '100%', padding: '14px 18px', borderRadius: 12,
              border: '2px solid #1a56db', backgroundColor: '#eff6ff',
              color: '#1a56db', fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}
          >
            <span><FormattedMessage id="reservations.title" /> ({reservations.filter(r => r.status === 'HELD').length})</span>
            <span>{showReservations ? '▲' : '▼'}</span>
          </button>
          {showReservations && (
            <div style={{ border: '2px solid #e2e8f0', borderTop: 'none', borderRadius: '0 0 12px 12px', padding: '12px 16px' }}>
              {reservations.filter(r => r.status === 'HELD').map((res) => {
                const mins = Math.floor(res.remainingSeconds / 60);
                const secs = res.remainingSeconds % 60;
                const isExpiring = res.remainingSeconds < 300;
                const shelterResult = results.find(r => r.shelterId === res.shelterId);
                return (
                  <div key={res.id} style={{
                    padding: '12px 0', borderBottom: '1px solid #f1f5f9',
                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8,
                  }}>
                    <div>
                      <div style={{ fontWeight: weight.bold, fontSize: text.base, color: '#0f172a' }}>
                        {shelterResult?.shelterName || res.shelterId.substring(0, 8)}
                      </div>
                      <div style={{ fontSize: text.xs, color: '#475569' }}>
                        {getPopulationTypeLabel(res.populationType, intl)}
                      </div>
                      <div style={{
                        fontSize: text.sm, fontWeight: weight.bold, marginTop: 4,
                        color: isExpiring ? '#991b1b' : '#1a56db',
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
                          backgroundColor: '#047857', color: '#fff', fontSize: text.sm, fontWeight: weight.bold, cursor: 'pointer',
                        }}
                      ><FormattedMessage id="reservations.confirm" /></button>
                      <button
                        onClick={() => cancelReservation(res.id)}
                        style={{
                          padding: '8px 14px', borderRadius: 8, border: '2px solid #e2e8f0',
                          backgroundColor: '#fff', color: '#475569', fontSize: text.sm, fontWeight: weight.semibold, cursor: 'pointer',
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
              marginBottom: 10, borderRadius: 14, border: `2px solid ${isFull ? '#fecaca' : '#e2e8f0'}`,
              backgroundColor: isFull ? '#fefefe' : '#fff', cursor: 'pointer',
              transition: 'border-color 0.12s, box-shadow 0.12s',
              // No opacity reduction — WCAG 1.4.3 contrast requirement
            }}
            onMouseEnter={(e) => { e.currentTarget.style.borderColor = '#3b82f6'; e.currentTarget.style.boxShadow = '0 2px 12px rgba(59,130,246,0.1)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.borderColor = isFull ? '#fecaca' : '#e2e8f0'; e.currentTarget.style.boxShadow = 'none'; }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 3 }}>
              <div style={{ fontSize: text.lg, fontWeight: weight.bold, color: '#0f172a' }}>{r.shelterName}</div>
              {/* Beds available badge */}
              {isFull ? (
                <span style={{
                  padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.bold,
                  backgroundColor: '#fef2f2', color: '#991b1b',
                }}><FormattedMessage id="search.currentlyFull" /></span>
              ) : (
                <span style={{
                  padding: '4px 10px', borderRadius: 8, fontSize: text.base, fontWeight: weight.extrabold,
                  backgroundColor: '#f0fdf4', color: '#166534',
                }}>{avail}</span>
              )}
            </div>
            <div style={{ fontSize: text.base, color: r.dvShelter ? '#7c3aed' : '#475569', fontStyle: r.dvShelter ? 'italic' : 'normal', marginBottom: 6 }}>
              {r.dvShelter ? intl.formatMessage({ id: 'search.dvAddressHidden' }) : r.address}
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {r.phone && <span style={{ fontSize: text.base, color: '#1a56db', fontWeight: weight.semibold }}>📞 {r.phone}</span>}
              </div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {freshnessLabel(r.dataFreshness)}
                <DataAge dataAgeSeconds={r.dataAgeSeconds} />
              </div>
            </div>
            {/* Per-population availability pills with hold buttons */}
            {r.availability.length > 0 && (
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10, alignItems: 'center' }}>
                {r.availability.map((a) => (
                  <div key={a.populationType} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                    <span style={{
                      padding: '3px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.semibold,
                      backgroundColor: a.bedsAvailable > 0 ? '#f0fdf4' : '#fef2f2',
                      color: a.bedsAvailable > 0 ? '#166534' : '#991b1b',
                    }}>
                      {getPopulationTypeLabel(a.populationType, intl)}: {a.bedsAvailable}
                      {a.bedsOnHold > 0 && <span style={{ color: '#854d0e' }}> ({a.bedsOnHold} held)</span>}
                      {a.overflowBeds > 0 && <span style={{ color: '#dc2626' }}> +{a.overflowBeds} overflow</span>}
                    </span>
                    {a.bedsAvailable > 0 && !r.dvShelter && (
                      <button
                        data-testid={`hold-bed-${r.shelterId}-${a.populationType}`}
                        onClick={(e) => { e.stopPropagation(); holdBed(r.shelterId, a.populationType); }}
                        disabled={holdingShelterId === r.shelterId && holdPopType === a.populationType}
                        style={{
                          padding: '6px 12px', borderRadius: 6, border: 'none',
                          backgroundColor: '#1a56db', color: '#fff', fontSize: text.xs, fontWeight: weight.bold,
                          cursor: 'pointer', minHeight: 44, minWidth: 44,
                        }}
                      >
                        {holdingShelterId === r.shelterId && holdPopType === a.populationType
                          ? intl.formatMessage({ id: 'search.holding' })
                          : intl.formatMessage({ id: 'search.holdBed' })}
                      </button>
                    )}
                    {a.bedsAvailable > 0 && r.dvShelter && (
                      <button
                        data-testid={`request-referral-${r.shelterId}-${a.populationType}`}
                        onClick={(e) => { e.stopPropagation(); setReferralModal({ shelterId: r.shelterId, popType: a.populationType }); }}
                        style={{
                          padding: '6px 12px', borderRadius: 6, border: 'none',
                          backgroundColor: '#7c3aed', color: '#fff', fontSize: text.xs, fontWeight: weight.bold,
                          cursor: 'pointer', minHeight: 44, minWidth: 44,
                        }}
                      >
                        <FormattedMessage id="search.requestReferral" />
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}

      {!loading && filtered.length === 0 && (
        <div style={{ textAlign: 'center', padding: 48, color: '#6b7280' }}>
          <div style={{ fontSize: text['4xl'], marginBottom: 12 }}>🏠</div>
          <div style={{ fontSize: text.md, fontWeight: weight.medium }}><FormattedMessage id="search.noResults" /></div>
          <div style={{ fontSize: text.base, marginTop: 6 }}><FormattedMessage id="search.tryDifferent" /></div>
        </div>
      )}

      {/* Detail modal */}
      {selectedShelter && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1000, display: 'flex', alignItems: 'flex-end', justifyContent: 'center' }}
          onClick={() => setSelectedShelter(null)}>
          <div style={{
            backgroundColor: '#fff', borderRadius: '24px 24px 0 0', width: '100%', maxWidth: 600,
            maxHeight: '88vh', overflowY: 'auto', padding: '28px 24px 36px',
          }} onClick={(e) => e.stopPropagation()}>
            <div style={{ width: 40, height: 4, backgroundColor: '#d1d5db', borderRadius: 2, margin: '0 auto 22px' }} />

            <h2 style={{ margin: '0 0 4px', fontSize: text['2xl'], fontWeight: weight.extrabold, color: '#0f172a' }}>{selectedShelter.shelter.name}</h2>
            {selectedShelter.shelter.dvShelter ? (
              <p style={{ margin: '0 0 16px', fontSize: text.base, color: '#7c3aed', fontStyle: 'italic' }}>
                <FormattedMessage id="search.dvAddressHidden" />
              </p>
            ) : (
              <p style={{ margin: '0 0 16px', fontSize: text.base, color: '#475569' }}>
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
                  flex: 1, padding: 14, backgroundColor: '#047857', color: '#fff', borderRadius: 12,
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
                  flex: 1, padding: 14, backgroundColor: '#1a56db', color: '#fff', borderRadius: 12,
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
                      padding: '12px 18px', backgroundColor: a.bedsAvailable > 0 ? '#f0fdf4' : '#fef2f2',
                      border: `1px solid ${a.bedsAvailable > 0 ? '#bbf7d0' : '#fecaca'}`,
                      borderRadius: 12, textAlign: 'center', minWidth: 100,
                    }}>
                      <div style={{
                        fontWeight: weight.extrabold, color: a.bedsAvailable > 0 ? '#166534' : '#991b1b',
                        fontSize: text['2xl'], lineHeight: leading.tight,
                      }}>{a.bedsAvailable}</div>
                      <div style={{
                        color: a.bedsAvailable > 0 ? '#15803d' : '#991b1b',
                        fontSize: text['2xs'], fontWeight: weight.semibold, marginTop: 4,
                        textTransform: 'uppercase', letterSpacing: '0.04em',
                      }}>
                        {getPopulationTypeLabel(a.populationType, intl)}
                      </div>
                      <div style={{ fontSize: text['2xs'], color: '#475569', marginTop: 4 }}>
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
                      padding: '12px 18px', backgroundColor: '#f0fdf4', border: '1px solid #bbf7d0',
                      borderRadius: 12, textAlign: 'center', minWidth: 90,
                    }}>
                      <div style={{ fontWeight: weight.extrabold, color: '#166534', fontSize: text['2xl'], lineHeight: leading.tight }}>{cap.bedsTotal}</div>
                      <div style={{ color: '#15803d', fontSize: text['2xs'], fontWeight: weight.semibold, marginTop: 4, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
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
                  <div style={{ marginTop: 12, fontSize: text.base, color: '#475569', fontWeight: weight.medium }}>
                    ⏰ <FormattedMessage id="search.curfew" />: {selectedShelter.constraints.curfewTime}
                  </div>
                )}
                {selectedShelter.constraints.maxStayDays && (
                  <div style={{ marginTop: 4, fontSize: text.base, color: '#475569', fontWeight: weight.medium }}>
                    📅 <FormattedMessage id="search.maxStay" />: {selectedShelter.constraints.maxStayDays} <FormattedMessage id="search.days" />
                  </div>
                )}
                {selectedShelter.constraints.populationTypesServed?.length > 0 && (
                  <div style={{ marginTop: 12 }}>
                    <div style={{ fontSize: text.xs, fontWeight: weight.semibold, color: '#475569', marginBottom: 6, textTransform: 'uppercase' }}>
                      <FormattedMessage id="search.serves" />
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {selectedShelter.constraints.populationTypesServed.map((pt) => (
                        <span key={pt} style={{
                          padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
                          backgroundColor: '#eff6ff', color: '#1e40af',
                        }}>{pt.replace(/_/g, ' ')}</span>
                      ))}
                    </div>
                  </div>
                )}
              </Section>
            )}

            <button onClick={() => setSelectedShelter(null)} style={{
              width: '100%', padding: 16, backgroundColor: '#f1f5f9', color: '#475569',
              border: 'none', borderRadius: 12, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 50,
            }}><FormattedMessage id="search.close" /></button>
          </div>
        </div>
      )}

      {/* DV Referral Request Modal */}
      {referralModal && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1002, display: 'flex', alignItems: 'center', justifyContent: 'center' }}
          onClick={() => setReferralModal(null)}>
          <div data-testid="referral-modal" style={{
            backgroundColor: '#fff', borderRadius: 16, width: '90%', maxWidth: 440, padding: '28px 24px',
            boxShadow: '0 8px 32px rgba(0,0,0,0.2)',
          }} onClick={(e) => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 4px', fontSize: text.lg, fontWeight: weight.extrabold, color: '#7c3aed' }}>
              <FormattedMessage id="referral.title" />
            </h3>
            <p style={{ margin: '0 0 16px', fontSize: text.sm, color: '#475569' }}>
              <FormattedMessage id="referral.subtitle" />
            </p>

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: '#475569', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.householdSize" />
            </label>
            <input data-testid="referral-household-size" type="number" min={1} max={20} value={referralForm.householdSize}
              onChange={(e) => setReferralForm(f => ({ ...f, householdSize: parseInt(e.target.value) || 1 }))}
              style={{ width: '100%', padding: 10, borderRadius: 8, border: '2px solid #e2e8f0', fontSize: text.base, marginBottom: 12 }} />

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: '#475569', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.urgency" />
            </label>
            <div data-testid="referral-urgency" style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              {['STANDARD', 'URGENT', 'EMERGENCY'].map(u => (
                <button key={u} onClick={() => setReferralForm(f => ({ ...f, urgency: u }))}
                  style={{
                    flex: 1, padding: 8, borderRadius: 8, border: `2px solid ${referralForm.urgency === u ? '#7c3aed' : '#e2e8f0'}`,
                    backgroundColor: referralForm.urgency === u ? '#f5f3ff' : '#fff',
                    color: referralForm.urgency === u ? '#7c3aed' : '#475569',
                    fontSize: text.xs, fontWeight: weight.bold, cursor: 'pointer',
                  }}>{u}</button>
              ))}
            </div>

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: '#475569', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.specialNeeds" />
            </label>
            <textarea data-testid="referral-special-needs" value={referralForm.specialNeeds}
              onChange={(e) => setReferralForm(f => ({ ...f, specialNeeds: e.target.value }))}
              placeholder={intl.formatMessage({ id: 'referral.specialNeedsPlaceholder' })}
              style={{ width: '100%', padding: 10, borderRadius: 8, border: '2px solid #e2e8f0', fontSize: text.sm, minHeight: 60, resize: 'vertical', marginBottom: 12 }} />

            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: '#475569', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="referral.callbackNumber" />
            </label>
            <input data-testid="referral-callback" type="tel" value={referralForm.callbackNumber}
              onChange={(e) => setReferralForm(f => ({ ...f, callbackNumber: e.target.value }))}
              placeholder="919-555-0000"
              style={{ width: '100%', padding: 10, borderRadius: 8, border: '2px solid #e2e8f0', fontSize: text.base, marginBottom: 16 }} />

            <div style={{ display: 'flex', gap: 8 }}>
              <button onClick={() => setReferralModal(null)}
                style={{ flex: 1, padding: 12, borderRadius: 10, border: '2px solid #e2e8f0', backgroundColor: '#fff', color: '#475569', fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer' }}>
                <FormattedMessage id="referral.cancel" />
              </button>
              <button data-testid="referral-submit" onClick={submitReferral} disabled={referralSubmitting || !referralForm.callbackNumber}
                style={{
                  flex: 1, padding: 12, borderRadius: 10, border: 'none',
                  backgroundColor: referralSubmitting ? '#a78bfa' : '#7c3aed', color: '#fff',
                  fontSize: text.base, fontWeight: weight.bold, cursor: referralSubmitting ? 'default' : 'pointer',
                }}>
                {referralSubmitting ? '...' : intl.formatMessage({ id: 'referral.submit' })}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* My DV Referrals section */}
      {myReferrals.length > 0 && (
        <div style={{ marginTop: 16, marginBottom: 16 }}>
          <button onClick={() => setShowReferrals(!showReferrals)}
            style={{
              width: '100%', padding: '14px 18px', borderRadius: 12,
              border: '2px solid #7c3aed', backgroundColor: '#f5f3ff',
              color: '#7c3aed', fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer',
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            }}>
            <span><FormattedMessage id="referral.myReferrals" /> ({myReferrals.filter(r => r.status === 'PENDING').length} <FormattedMessage id="referral.pending" />)</span>
            <span>{showReferrals ? '▲' : '▼'}</span>
          </button>
          {showReferrals && (
            <div data-testid="my-referrals" style={{ border: '2px solid #e2e8f0', borderTop: 'none', borderRadius: '0 0 12px 12px', padding: '12px 16px' }}>
              {myReferrals.map((ref) => (
                <div key={ref.id} data-testid={`referral-${ref.id}`} style={{
                  padding: '10px 0', borderBottom: '1px solid #f1f5f9',
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                }}>
                  <div>
                    <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: '#0f172a' }}>
                      {getPopulationTypeLabel(ref.populationType, intl)} — {ref.householdSize} person{ref.householdSize > 1 ? 's' : ''}
                    </span>
                    <div style={{ fontSize: text['2xs'], color: '#475569', marginTop: 2 }}>
                      {ref.status === 'ACCEPTED' && ref.shelterPhone && (
                        <span data-testid={`referral-phone-${ref.id}`} style={{ color: '#166534', fontWeight: weight.bold }}>
                          <FormattedMessage id="referral.callShelter" /> {ref.shelterPhone}
                        </span>
                      )}
                      {ref.status === 'REJECTED' && ref.rejectionReason && (
                        <span style={{ color: '#991b1b' }}>
                          <FormattedMessage id="referral.declined" />: {ref.rejectionReason}
                        </span>
                      )}
                      {ref.status === 'PENDING' && ref.remainingSeconds != null && (
                        <span style={{ color: '#854d0e' }}>
                          <FormattedMessage id="referral.waiting" /> — {Math.floor(ref.remainingSeconds / 60)}m remaining
                        </span>
                      )}
                      {ref.status === 'EXPIRED' && (
                        <span style={{ color: '#991b1b' }}><FormattedMessage id="referral.expired" /></span>
                      )}
                    </div>
                  </div>
                  <span style={{
                    padding: '3px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.bold,
                    backgroundColor: ref.status === 'ACCEPTED' ? '#f0fdf4' : ref.status === 'REJECTED' ? '#fef2f2' : ref.status === 'EXPIRED' ? '#fef2f2' : '#fefce8',
                    color: ref.status === 'ACCEPTED' ? '#166534' : ref.status === 'REJECTED' || ref.status === 'EXPIRED' ? '#991b1b' : '#854d0e',
                  }}>{ref.status}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {detailLoading && (
        <div style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.3)', zIndex: 1001, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div style={{ backgroundColor: '#fff', borderRadius: 16, padding: 32 }}><Spinner /></div>
        </div>
      )}
    </div>
  );
}

function ToggleChip({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button onClick={onClick} style={{
      padding: '10px 14px', borderRadius: 10, border: `2px solid ${active ? '#1a56db' : '#e2e8f0'}`,
      backgroundColor: active ? '#eff6ff' : '#fff', color: active ? '#1a56db' : '#475569',
      cursor: 'pointer', fontSize: text.base, fontWeight: active ? weight.semibold : weight.medium, minHeight: 44,
      display: 'flex', alignItems: 'center', gap: 4, transition: 'all 0.12s',
    }}>{label}</button>
  );
}

function Badge({ ok, yes, no }: { ok: boolean; yes: string; no: string }) {
  return (
    <span style={{
      padding: '6px 12px', borderRadius: 8, fontSize: text.sm, fontWeight: weight.semibold,
      backgroundColor: ok ? '#f0fdf4' : '#fef2f2', color: ok ? '#166534' : '#991b1b',
      border: `1px solid ${ok ? '#bbf7d0' : '#fecaca'}`,
    }}>{ok ? yes : no}</span>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <h3 style={{ fontSize: text.xs, fontWeight: weight.bold, color: '#475569', marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.06em' }}>{title}</h3>
      {children}
    </div>
  );
}

function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: '#6b7280' }}>
      <div style={{
        width: 32, height: 32, border: '3px solid #e2e8f0', borderTopColor: '#1a56db',
        borderRadius: '50%', animation: 'fabt-spin 0.7s linear infinite', margin: '0 auto 10px',
      }} />
      <style>{`@keyframes fabt-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
