import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';

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
      setResults(data.results);
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
      setReservations(data);
    } catch { /* silent — reservations panel is optional */ }
  }, []);

  useEffect(() => { fetchReservations(); }, [fetchReservations]);

  // Fetch active surge
  useEffect(() => {
    (async () => {
      try {
        const surges = await api.get<SurgeEventResponse[]>('/api/v1/surge-events');
        const active = surges.find(s => s.status === 'ACTIVE');
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
      UNKNOWN: { bg: '#f1f5f9', color: '#64748b' },
    };
    const style = colors[freshness] || colors.UNKNOWN;
    return (
      <span style={{
        padding: '2px 8px', borderRadius: 6, fontSize: 11, fontWeight: 700,
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
        <h1 style={{ margin: 0, fontSize: 24, fontWeight: 800, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="search.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: 14, color: '#94b8d8' }}>
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
            <div style={{ fontSize: 13, fontWeight: 800, letterSpacing: '0.06em', marginBottom: 2 }}>
              <FormattedMessage id="surge.banner" />
            </div>
            <div style={{ fontSize: 14, fontWeight: 500 }}>{activeSurge.reason}</div>
          </div>
          <div style={{ fontSize: 12, opacity: 0.85 }}>
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
        style={{
          width: '100%', padding: '15px 18px', borderRadius: 14,
          border: '2px solid #e2e8f0', fontSize: 16, minHeight: 50,
          boxSizing: 'border-box', marginBottom: 14, outline: 'none',
          fontWeight: 500, color: '#0f172a',
        }}
      />

      {/* Filters */}
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 20 }}>
        <select
          value={populationType}
          onChange={(e) => setPopulationType(e.target.value)}
          style={{
            padding: '11px 14px', borderRadius: 10, border: '2px solid #e2e8f0',
            fontSize: 14, minHeight: 44, backgroundColor: populationType ? '#eff6ff' : '#fff',
            color: populationType ? '#1a56db' : '#475569', fontWeight: 500, cursor: 'pointer',
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
      <div style={{ fontSize: 13, color: '#64748b', marginBottom: 10, fontWeight: 600, letterSpacing: '0.02em' }}>
        {loading
          ? <FormattedMessage id="search.loading" />
          : <FormattedMessage id="search.resultCount" values={{ count: filtered.length }} />}
      </div>

      {error && (
        <div style={{
          backgroundColor: '#fef2f2', color: '#991b1b', padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: 14, fontWeight: 500,
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
              color: '#1a56db', fontSize: 14, fontWeight: 700, cursor: 'pointer',
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
                      <div style={{ fontWeight: 700, fontSize: 14, color: '#0f172a' }}>
                        {shelterResult?.shelterName || res.shelterId.substring(0, 8)}
                      </div>
                      <div style={{ fontSize: 12, color: '#64748b' }}>
                        {res.populationType.replace(/_/g, ' ')}
                      </div>
                      <div style={{
                        fontSize: 13, fontWeight: 700, marginTop: 4,
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
                          backgroundColor: '#059669', color: '#fff', fontSize: 13, fontWeight: 700, cursor: 'pointer',
                        }}
                      ><FormattedMessage id="reservations.confirm" /></button>
                      <button
                        onClick={() => cancelReservation(res.id)}
                        style={{
                          padding: '8px 14px', borderRadius: 8, border: '2px solid #e2e8f0',
                          backgroundColor: '#fff', color: '#64748b', fontSize: 13, fontWeight: 600, cursor: 'pointer',
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
            onClick={() => openDetail(r.shelterId)}
            style={{
              display: 'block', width: '100%', textAlign: 'left', padding: '18px 20px',
              marginBottom: 10, borderRadius: 14, border: `2px solid ${isFull ? '#fecaca' : '#e2e8f0'}`,
              backgroundColor: isFull ? '#fefefe' : '#fff', cursor: 'pointer',
              transition: 'border-color 0.12s, box-shadow 0.12s',
              opacity: isFull ? 0.75 : 1,
            }}
            onMouseEnter={(e) => { e.currentTarget.style.borderColor = '#3b82f6'; e.currentTarget.style.boxShadow = '0 2px 12px rgba(59,130,246,0.1)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.borderColor = isFull ? '#fecaca' : '#e2e8f0'; e.currentTarget.style.boxShadow = 'none'; }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 3 }}>
              <div style={{ fontSize: 17, fontWeight: 700, color: '#0f172a' }}>{r.shelterName}</div>
              {/* Beds available badge */}
              {isFull ? (
                <span style={{
                  padding: '4px 10px', borderRadius: 8, fontSize: 12, fontWeight: 700,
                  backgroundColor: '#fef2f2', color: '#991b1b',
                }}><FormattedMessage id="search.currentlyFull" /></span>
              ) : (
                <span style={{
                  padding: '4px 10px', borderRadius: 8, fontSize: 14, fontWeight: 800,
                  backgroundColor: '#f0fdf4', color: '#166534',
                }}>{avail}</span>
              )}
            </div>
            <div style={{ fontSize: 14, color: '#64748b', marginBottom: 6 }}>{r.address}</div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                {r.phone && <span style={{ fontSize: 14, color: '#1a56db', fontWeight: 600 }}>📞 {r.phone}</span>}
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
                      padding: '3px 8px', borderRadius: 6, fontSize: 11, fontWeight: 600,
                      backgroundColor: a.bedsAvailable > 0 ? '#f0fdf4' : '#fef2f2',
                      color: a.bedsAvailable > 0 ? '#166534' : '#991b1b',
                    }}>
                      {a.populationType.replace(/_/g, ' ')}: {a.bedsAvailable}
                      {a.bedsOnHold > 0 && <span style={{ color: '#854d0e' }}> ({a.bedsOnHold} held)</span>}
                      {a.overflowBeds > 0 && <span style={{ color: '#dc2626' }}> +{a.overflowBeds} overflow</span>}
                    </span>
                    {a.bedsAvailable > 0 && (
                      <button
                        onClick={(e) => { e.stopPropagation(); holdBed(r.shelterId, a.populationType); }}
                        disabled={holdingShelterId === r.shelterId && holdPopType === a.populationType}
                        style={{
                          padding: '2px 8px', borderRadius: 6, border: 'none',
                          backgroundColor: '#1a56db', color: '#fff', fontSize: 10, fontWeight: 700,
                          cursor: 'pointer', opacity: holdingShelterId === r.shelterId ? 0.6 : 1,
                        }}
                      >
                        {holdingShelterId === r.shelterId && holdPopType === a.populationType
                          ? intl.formatMessage({ id: 'search.holding' })
                          : intl.formatMessage({ id: 'search.holdBed' })}
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
        <div style={{ textAlign: 'center', padding: 48, color: '#94a3b8' }}>
          <div style={{ fontSize: 48, marginBottom: 12 }}>🏠</div>
          <div style={{ fontSize: 16, fontWeight: 500 }}><FormattedMessage id="search.noResults" /></div>
          <div style={{ fontSize: 14, marginTop: 6 }}><FormattedMessage id="search.tryDifferent" /></div>
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

            <h2 style={{ margin: '0 0 4px', fontSize: 22, fontWeight: 800, color: '#0f172a' }}>{selectedShelter.shelter.name}</h2>
            <p style={{ margin: '0 0 16px', fontSize: 14, color: '#64748b' }}>
              {[selectedShelter.shelter.addressStreet, selectedShelter.shelter.addressCity,
                selectedShelter.shelter.addressState, selectedShelter.shelter.addressZip].filter(Boolean).join(', ')}
            </p>

            {selectedShelter.data_age_seconds !== undefined && (
              <div style={{ marginBottom: 18 }}><DataAge dataAgeSeconds={selectedShelter.data_age_seconds} /></div>
            )}

            {/* Action row */}
            <div style={{ display: 'flex', gap: 10, marginBottom: 24 }}>
              {selectedShelter.shelter.phone && (
                <a href={`tel:${selectedShelter.shelter.phone}`} style={{
                  flex: 1, padding: 14, backgroundColor: '#059669', color: '#fff', borderRadius: 12,
                  textAlign: 'center', textDecoration: 'none', fontSize: 16, fontWeight: 700, minHeight: 50,
                  display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                }}>📞 <FormattedMessage id="search.call" /></a>
              )}
              <a href={mapsUrl({
                shelterId: selectedShelter.shelter.id, shelterName: selectedShelter.shelter.name,
                address: [selectedShelter.shelter.addressStreet, selectedShelter.shelter.addressCity].filter(Boolean).join(', '),
                phone: selectedShelter.shelter.phone, latitude: selectedShelter.shelter.latitude,
                longitude: selectedShelter.shelter.longitude, availability: [], dataAgeSeconds: null,
                dataFreshness: 'UNKNOWN', distanceMiles: null,
                constraints: { petsAllowed: false, wheelchairAccessible: false, sobrietyRequired: false, idRequired: false, referralRequired: false },
                surgeActive: false,
              })} target="_blank" rel="noopener noreferrer" style={{
                flex: 1, padding: 14, backgroundColor: '#1a56db', color: '#fff', borderRadius: 12,
                textAlign: 'center', textDecoration: 'none', fontSize: 16, fontWeight: 700, minHeight: 50,
                display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
              }}>🗺️ <FormattedMessage id="search.directions" /></a>
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
                        fontWeight: 800, color: a.bedsAvailable > 0 ? '#166534' : '#991b1b',
                        fontSize: 24, lineHeight: 1,
                      }}>{a.bedsAvailable}</div>
                      <div style={{
                        color: a.bedsAvailable > 0 ? '#15803d' : '#991b1b',
                        fontSize: 11, fontWeight: 600, marginTop: 4,
                        textTransform: 'uppercase', letterSpacing: '0.04em',
                      }}>
                        {a.populationType.replace(/_/g, ' ')}
                      </div>
                      <div style={{ fontSize: 10, color: '#64748b', marginTop: 4 }}>
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
                      <div style={{ fontWeight: 800, color: '#166534', fontSize: 24, lineHeight: 1 }}>{cap.bedsTotal}</div>
                      <div style={{ color: '#15803d', fontSize: 11, fontWeight: 600, marginTop: 4, textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                        {cap.populationType.replace(/_/g, ' ')}
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
                  <div style={{ marginTop: 12, fontSize: 14, color: '#475569', fontWeight: 500 }}>
                    ⏰ <FormattedMessage id="search.curfew" />: {selectedShelter.constraints.curfewTime}
                  </div>
                )}
                {selectedShelter.constraints.maxStayDays && (
                  <div style={{ marginTop: 4, fontSize: 14, color: '#475569', fontWeight: 500 }}>
                    📅 <FormattedMessage id="search.maxStay" />: {selectedShelter.constraints.maxStayDays} <FormattedMessage id="search.days" />
                  </div>
                )}
                {selectedShelter.constraints.populationTypesServed?.length > 0 && (
                  <div style={{ marginTop: 12 }}>
                    <div style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 6, textTransform: 'uppercase' }}>
                      <FormattedMessage id="search.serves" />
                    </div>
                    <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                      {selectedShelter.constraints.populationTypesServed.map((pt) => (
                        <span key={pt} style={{
                          padding: '4px 10px', borderRadius: 6, fontSize: 12, fontWeight: 600,
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
              border: 'none', borderRadius: 12, fontSize: 15, fontWeight: 600, cursor: 'pointer', minHeight: 50,
            }}><FormattedMessage id="search.close" /></button>
          </div>
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
      backgroundColor: active ? '#eff6ff' : '#fff', color: active ? '#1a56db' : '#64748b',
      cursor: 'pointer', fontSize: 14, fontWeight: active ? 600 : 500, minHeight: 44,
      display: 'flex', alignItems: 'center', gap: 4, transition: 'all 0.12s',
    }}>{label}</button>
  );
}

function Badge({ ok, yes, no }: { ok: boolean; yes: string; no: string }) {
  return (
    <span style={{
      padding: '6px 12px', borderRadius: 8, fontSize: 13, fontWeight: 600,
      backgroundColor: ok ? '#f0fdf4' : '#fef2f2', color: ok ? '#166534' : '#991b1b',
      border: `1px solid ${ok ? '#bbf7d0' : '#fecaca'}`,
    }}>{ok ? yes : no}</span>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <h3 style={{ fontSize: 12, fontWeight: 700, color: '#64748b', marginBottom: 10, textTransform: 'uppercase', letterSpacing: '0.06em' }}>{title}</h3>
      {children}
    </div>
  );
}

function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: '#94a3b8' }}>
      <div style={{
        width: 32, height: 32, border: '3px solid #e2e8f0', borderTopColor: '#1a56db',
        borderRadius: '50%', animation: 'fabt-spin 0.7s linear infinite', margin: '0 auto 10px',
      }} />
      <style>{`@keyframes fabt-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
