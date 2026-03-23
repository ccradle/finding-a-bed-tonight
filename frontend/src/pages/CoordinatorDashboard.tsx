import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';

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

export function CoordinatorDashboard() {
  const intl = useIntl();
  const [shelters, setShelters] = useState<ShelterListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editAvailability, setEditAvailability] = useState<AvailabilityEdit[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [availSaving, setAvailSaving] = useState<string | null>(null);
  const [availSaved, setAvailSaved] = useState<string | null>(null);

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ShelterListItem[]>('/api/v1/shelters');
      setShelters(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchShelters(); }, [fetchShelters]);

  const fmtAddr = (s: Shelter) =>
    [s.addressStreet, s.addressCity, s.addressState, s.addressZip].filter(Boolean).join(', ');

  const openShelter = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null);
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
          overflowBeds: 0,
        };
      });
      setEditAvailability(availEdit);
      setExpandedId(id);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setDetailLoading(false);
    }
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

  const submitAvailability = async (shelterId: string, popType: string) => {
    const avail = editAvailability.find((a) => a.populationType === popType);
    if (!avail) return;
    setAvailSaving(popType);
    setError(null);
    try {
      await api.patch(`/api/v1/shelters/${shelterId}/availability`, {
        populationType: popType,
        bedsTotal: avail.bedsTotal,
        bedsOccupied: avail.bedsOccupied,
        bedsOnHold: avail.bedsOnHold,
        acceptingNewGuests: true,
        overflowBeds: avail.overflowBeds,
      });
      setAvailSaved(popType);
      // Refresh shelter list for updated summary
      fetchShelters();
      setTimeout(() => setAvailSaved(null), 1500);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setAvailSaving(null);
    }
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
          <FormattedMessage id="coord.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: 14, color: '#94b8d8' }}>
          <FormattedMessage id="coord.subtitle" />
        </p>
      </div>

      {/* Status */}
      <div style={{ fontSize: 13, color: '#64748b', marginBottom: 10, fontWeight: 600, letterSpacing: '0.02em' }}>
        {loading
          ? <FormattedMessage id="coord.loading" />
          : <FormattedMessage id="coord.bedsTotal" values={{ count: shelters.length }} />}
      </div>

      {error && (
        <div style={{
          backgroundColor: '#fef2f2', color: '#991b1b', padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: 14, fontWeight: 500,
        }}>{error}</div>
      )}

      {loading && <Spinner />}

      {detailLoading && (
        <div style={{
          backgroundColor: '#eff6ff', color: '#1a56db', padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: 14, fontWeight: 500, textAlign: 'center',
        }}>
          <FormattedMessage id="coord.loading" />
        </div>
      )}

      {/* Shelter cards */}
      {!loading && shelters.map((item) => {
        const s = item.shelter;
        const summary = item.availabilitySummary;
        const isExpanded = expandedId === s.id;

        return (
          <div
            key={s.id}
            style={{
              marginBottom: 10, borderRadius: 14,
              border: `2px solid ${isExpanded ? '#1a56db' : '#e2e8f0'}`,
              backgroundColor: '#fff',
              transition: 'border-color 0.2s, background-color 0.3s',
              overflow: 'hidden',
            }}
          >
            {/* Card header - tappable */}
            <button
              onClick={() => openShelter(s.id)}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '18px 20px',
                backgroundColor: 'transparent', border: 'none', cursor: 'pointer',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div style={{ fontSize: 17, fontWeight: 700, color: '#0f172a', marginBottom: 3 }}>{s.name}</div>
                  <div style={{ fontSize: 14, color: '#64748b', marginBottom: 6 }}>{fmtAddr(s)}</div>
                </div>
                {summary && summary.totalBedsAvailable != null && (
                  <span style={{
                    padding: '4px 10px', borderRadius: 8, fontSize: 14, fontWeight: 800,
                    backgroundColor: summary.totalBedsAvailable > 0 ? '#f0fdf4' : '#fef2f2',
                    color: summary.totalBedsAvailable > 0 ? '#166534' : '#991b1b',
                  }}>{summary.totalBedsAvailable} avail</span>
                )}
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  {summary?.lastUpdated ? (
                    <DataAge dataAgeSeconds={summary.dataAgeSeconds} />
                  ) : (
                    <DataAge dataAgeSeconds={s.updatedAt ? Math.floor((Date.now() - new Date(s.updatedAt).getTime()) / 1000) : null} />
                  )}
                  {summary?.lastUpdated && (
                    <span style={{ fontSize: 11, color: '#94a3b8' }}>
                      <FormattedMessage id="coord.lastAvailUpdate" />: {new Date(summary.lastUpdated).toLocaleString()}
                    </span>
                  )}
                </div>
                {isExpanded
                  ? <span style={{ fontSize: 12, color: '#1a56db', fontWeight: 600 }}>▲</span>
                  : <span style={{ fontSize: 12, color: '#94a3b8', fontWeight: 600 }}>▼</span>}
              </div>
            </button>

            {/* Expanded editor */}
            {isExpanded && (
              <div style={{ padding: '0 20px 20px' }}>
                <div style={{ height: 1, backgroundColor: '#e2e8f0', marginBottom: 16 }} />

                {/* Availability update section */}
                <h4 style={{ fontSize: 13, fontWeight: 700, color: '#1a56db', margin: '0 0 12px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                  <FormattedMessage id="coord.availability" />
                </h4>

                {editAvailability.filter(a => a.bedsTotal > 0 || a.bedsOccupied > 0).map((avail) => {
                  const bedsAvailable = avail.bedsTotal - avail.bedsOccupied - avail.bedsOnHold;
                  const isSavingThis = availSaving === avail.populationType;
                  const isSavedThis = availSaved === avail.populationType;

                  return (
                    <div key={avail.populationType} data-testid={`avail-row-${avail.populationType}`} style={{
                      padding: '12px 0', borderBottom: '1px solid #f1f5f9',
                    }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                        <span style={{ fontSize: 14, fontWeight: 600, color: '#0f172a', textTransform: 'capitalize' }}>
                          {avail.populationType.replace(/_/g, ' ').toLowerCase()}
                        </span>
                        <span data-testid={`available-value-${avail.populationType}`} style={{
                          fontSize: 16, fontWeight: 800,
                          color: bedsAvailable > 0 ? '#166534' : '#991b1b',
                        }}>
                          {bedsAvailable} <span style={{ fontSize: 11, fontWeight: 600 }}><FormattedMessage id="coord.bedsAvail" /></span>
                        </span>
                      </div>
                      <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
                        {/* Total beds stepper */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: 12, color: '#64748b', fontWeight: 600, minWidth: 40 }}>
                            <FormattedMessage id="coord.bedsTotal" />
                          </span>
                          <StepperButton label="−" data-testid={`total-minus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsTotal', -1)} disabled={avail.bedsTotal <= avail.bedsOccupied + avail.bedsOnHold} />
                          <span data-testid={`total-value-${avail.populationType}`} style={{ fontSize: 18, fontWeight: 800, minWidth: 32, textAlign: 'center' }}>{avail.bedsTotal}</span>
                          <StepperButton label="+" data-testid={`total-plus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsTotal', 1)} />
                        </div>
                        {/* Occupied stepper */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: 12, color: '#64748b', fontWeight: 600, minWidth: 60 }}>
                            <FormattedMessage id="coord.bedsOccupied" />
                          </span>
                          <StepperButton label="−" data-testid={`occupied-minus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsOccupied', -1)} disabled={avail.bedsOccupied <= 0} />
                          <span data-testid={`occupied-value-${avail.populationType}`} style={{ fontSize: 18, fontWeight: 800, minWidth: 32, textAlign: 'center' }}>{avail.bedsOccupied}</span>
                          <StepperButton label="+" data-testid={`occupied-plus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsOccupied', 1)} disabled={avail.bedsOccupied >= avail.bedsTotal - avail.bedsOnHold} />
                        </div>
                        {/* On-hold display (read-only — holds are managed by the reservation system) */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: 12, color: '#64748b', fontWeight: 600, minWidth: 50 }}>
                            <FormattedMessage id="coord.bedsOnHold" />
                          </span>
                          <span data-testid={`onhold-value-${avail.populationType}`} style={{ fontSize: 18, fontWeight: 800, minWidth: 32, textAlign: 'center', color: avail.bedsOnHold > 0 ? '#1a56db' : '#94a3b8' }}>{avail.bedsOnHold}</span>
                          {avail.bedsOnHold > 0 && (
                            <span style={{ fontSize: 10, color: '#94a3b8' }}>(system)</span>
                          )}
                        </div>
                      </div>
                      <button
                        data-testid={`save-avail-${avail.populationType}`}
                        onClick={() => submitAvailability(s.id, avail.populationType)}
                        disabled={isSavingThis}
                        style={{
                          padding: '8px 16px', backgroundColor: isSavedThis ? '#22c55e' : '#1a56db', color: '#fff',
                          border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 700,
                          cursor: isSavingThis ? 'default' : 'pointer',
                          opacity: isSavingThis ? 0.7 : 1, transition: 'all 0.15s',
                        }}
                      >
                        {isSavedThis ? intl.formatMessage({ id: 'coord.availabilityUpdated' })
                          : isSavingThis ? '...'
                          : intl.formatMessage({ id: 'coord.updateAvailability' })}
                      </button>
                    </div>
                  );
                })}

                {/* Active holds indicator */}
                {editAvailability.some(a => a.bedsOnHold > 0) && (
                  <div style={{
                    padding: '10px 14px', backgroundColor: '#eff6ff', borderRadius: 10,
                    border: '1px solid #bfdbfe', marginBottom: 16,
                  }}>
                    <h4 style={{ fontSize: 13, fontWeight: 700, color: '#1a56db', margin: '0 0 8px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      <FormattedMessage id="coord.activeHolds" />
                    </h4>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      {editAvailability.filter(a => a.bedsOnHold > 0).map(a => (
                        <span key={a.populationType} style={{
                          padding: '4px 10px', borderRadius: 6, fontSize: 12, fontWeight: 600,
                          backgroundColor: '#dbeafe', color: '#1e40af',
                        }}>
                          {a.populationType.replace(/_/g, ' ').toLowerCase()}: {a.bedsOnHold} held
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
        <div style={{ textAlign: 'center', padding: 48, color: '#94a3b8' }}>
          <div style={{ fontSize: 48, marginBottom: 12 }}>🏠</div>
          <div style={{ fontSize: 16, fontWeight: 500 }}><FormattedMessage id="coord.error" /></div>
        </div>
      )}
    </div>
  );
}

function StepperButton({ label, onClick, disabled, size = 36, fontSize = 18, 'data-testid': testId }: {
  label: string; onClick: () => void; disabled?: boolean; size?: number; fontSize?: number; 'data-testid'?: string
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      data-testid={testId}
      style={{
        width: size, height: size, borderRadius: '50%',
        border: '2px solid #e2e8f0', backgroundColor: '#fff',
        fontSize, fontWeight: 700, color: disabled ? '#d1d5db' : '#0f172a',
        cursor: disabled ? 'default' : 'pointer',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        lineHeight: 1,
      }}
    >{label}</button>
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
