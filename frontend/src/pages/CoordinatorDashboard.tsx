import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';
import { text, weight, leading } from '../theme/typography';
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
  const [pendingReferrals, setPendingReferrals] = useState<PendingReferral[]>([]);
  const [rejectingId, setRejectingId] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ShelterListItem[]>('/api/v1/shelters');
      setShelters(data || []);
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

      // Fetch pending DV referrals for this shelter (silent fail if not DV or no access)
      try {
        const refs = await api.get<PendingReferral[]>(`/api/v1/dv-referrals/pending?shelterId=${id}`);
        setPendingReferrals(refs || []);
      } catch { setPendingReferrals([]); }
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setDetailLoading(false);
    }
  };

  const acceptReferral = async (tokenId: string) => {
    try {
      await api.patch(`/api/v1/dv-referrals/${tokenId}/accept`, {});
      setPendingReferrals(prev => prev.filter(r => r.id !== tokenId));
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    }
  };

  const rejectReferral = async (tokenId: string) => {
    if (!rejectReason.trim()) return;
    try {
      await api.patch(`/api/v1/dv-referrals/${tokenId}/reject`, { reason: rejectReason });
      setPendingReferrals(prev => prev.filter(r => r.id !== tokenId));
      setRejectingId(null);
      setRejectReason('');
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
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
        <h1 data-testid="coordinator-heading" style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="coord.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: '#94b8d8' }}>
          <FormattedMessage id="coord.subtitle" />
        </p>
      </div>

      {/* Status */}
      <div style={{ fontSize: text.sm, color: '#475569', marginBottom: 10, fontWeight: weight.semibold, letterSpacing: '0.02em' }}>
        {loading
          ? <FormattedMessage id="coord.loading" />
          : <FormattedMessage id="coord.bedsTotal" values={{ count: shelters.length }} />}
      </div>

      {error && (
        <div style={{
          backgroundColor: '#fef2f2', color: '#991b1b', padding: '14px 18px',
          borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium,
        }}>{error}</div>
      )}

      {loading && <Spinner />}

      {detailLoading && (
        <div style={{
          backgroundColor: '#eff6ff', color: '#1a56db', padding: '14px 18px',
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
              data-testid={`shelter-card-${s.id}`}
              onClick={() => openShelter(s.id)}
              style={{
                display: 'block', width: '100%', textAlign: 'left', padding: '18px 20px',
                backgroundColor: 'transparent', border: 'none', cursor: 'pointer',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div style={{ fontSize: text.lg, fontWeight: weight.bold, color: '#0f172a', marginBottom: 3 }}>{s.name}</div>
                  <div style={{ fontSize: text.base, color: '#475569', marginBottom: 6 }}>{fmtAddr(s)}</div>
                </div>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                  {summary && summary.totalBedsAvailable != null && (
                    <span data-testid={`avail-badge-${s.id}`} style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: text.base, fontWeight: weight.extrabold,
                      backgroundColor: summary.totalBedsAvailable > 0 ? '#f0fdf4' : '#fef2f2',
                      color: summary.totalBedsAvailable > 0 ? '#166534' : '#991b1b',
                    }}>{summary.totalBedsAvailable} avail</span>
                  )}
                  {s.dvShelter && isExpanded && pendingReferrals.length > 0 && (
                    <span data-testid={`referral-badge-${s.id}`} style={{
                      padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.extrabold,
                      backgroundColor: '#f5f3ff', color: '#7c3aed',
                    }}>{pendingReferrals.length} referral{pendingReferrals.length > 1 ? 's' : ''}</span>
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
                    <span style={{ fontSize: text['2xs'], color: '#6b7280' }}>
                      <FormattedMessage id="coord.lastAvailUpdate" />: {new Date(summary.lastUpdated).toLocaleString()}
                    </span>
                  )}
                </div>
                {isExpanded
                  ? <span style={{ fontSize: text.xs, color: '#1a56db', fontWeight: weight.semibold }}>▲</span>
                  : <span style={{ fontSize: text.xs, color: '#6b7280', fontWeight: weight.semibold }}>▼</span>}
              </div>
            </button>

            {/* Expanded editor */}
            {isExpanded && (
              <div style={{ padding: '0 20px 20px' }}>
                <div style={{ height: 1, backgroundColor: '#e2e8f0', marginBottom: 16 }} />

                {/* Availability update section */}
                <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: '#1a56db', margin: '0 0 12px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
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
                        <span style={{ fontSize: text.base, fontWeight: weight.semibold, color: '#0f172a', textTransform: 'capitalize' }}>
                          {getPopulationTypeLabel(avail.populationType, intl)}
                        </span>
                        <span data-testid={`available-value-${avail.populationType}`} style={{
                          fontSize: text.md, fontWeight: weight.extrabold,
                          color: bedsAvailable > 0 ? '#166534' : '#991b1b',
                        }}>
                          {bedsAvailable} <span style={{ fontSize: text['2xs'], fontWeight: weight.semibold }}><FormattedMessage id="coord.bedsAvail" /></span>
                        </span>
                      </div>
                      <div style={{ display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap', marginBottom: 8 }}>
                        {/* Total beds stepper */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: text.xs, color: '#475569', fontWeight: weight.semibold, minWidth: 40 }}>
                            <FormattedMessage id="coord.bedsTotal" />
                          </span>
                          <StepperButton label="−" data-testid={`total-minus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsTotal', -1)} disabled={avail.bedsTotal <= avail.bedsOccupied + avail.bedsOnHold} />
                          <span data-testid={`total-value-${avail.populationType}`} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center' }}>{avail.bedsTotal}</span>
                          <StepperButton label="+" data-testid={`total-plus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsTotal', 1)} />
                        </div>
                        {/* Occupied stepper */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: text.xs, color: '#475569', fontWeight: weight.semibold, minWidth: 60 }}>
                            <FormattedMessage id="coord.bedsOccupied" />
                          </span>
                          <StepperButton label="−" data-testid={`occupied-minus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsOccupied', -1)} disabled={avail.bedsOccupied <= 0} />
                          <span data-testid={`occupied-value-${avail.populationType}`} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center' }}>{avail.bedsOccupied}</span>
                          <StepperButton label="+" data-testid={`occupied-plus-${avail.populationType}`} onClick={() => updateAvailField(avail.populationType, 'bedsOccupied', 1)} disabled={avail.bedsOccupied >= avail.bedsTotal - avail.bedsOnHold} />
                        </div>
                        {/* On-hold display (read-only — holds are managed by the reservation system) */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                          <span style={{ fontSize: text.xs, color: '#475569', fontWeight: weight.semibold, minWidth: 50 }}>
                            <FormattedMessage id="coord.bedsOnHold" />
                          </span>
                          <span data-testid={`onhold-value-${avail.populationType}`} style={{ fontSize: text.lg, fontWeight: weight.extrabold, minWidth: 32, textAlign: 'center', color: avail.bedsOnHold > 0 ? '#1a56db' : '#6b7280' }}>{avail.bedsOnHold}</span>
                          {avail.bedsOnHold > 0 && (
                            <span style={{ fontSize: text['2xs'], color: '#6b7280' }}>(system)</span>
                          )}
                        </div>
                      </div>
                      <button
                        data-testid={`save-avail-${avail.populationType}`}
                        onClick={() => submitAvailability(s.id, avail.populationType)}
                        disabled={isSavingThis}
                        aria-live="polite"
                        style={{
                          padding: '8px 16px', backgroundColor: isSavedThis ? '#22c55e' : '#1a56db', color: '#fff',
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

                {/* Pending DV Referrals (screening view) */}
                {s.dvShelter && pendingReferrals.length > 0 && (
                  <div data-testid="referral-screening" style={{
                    padding: '12px 16px', backgroundColor: '#f5f3ff', borderRadius: 10,
                    border: '1px solid #ddd6fe', marginBottom: 16,
                  }}>
                    <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: '#7c3aed', margin: '0 0 10px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      <FormattedMessage id="referral.pendingReferrals" /> ({pendingReferrals.length})
                    </h4>
                    {pendingReferrals.map(ref => (
                      <div key={ref.id} data-testid={`screening-${ref.id}`} style={{
                        padding: '10px 0', borderBottom: '1px solid #ede9fe',
                      }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 6 }}>
                          <div>
                            <span style={{ fontSize: text.sm, fontWeight: weight.bold, color: '#0f172a' }}>
                              {getPopulationTypeLabel(ref.populationType, intl)} — {ref.householdSize} person{ref.householdSize > 1 ? 's' : ''}
                            </span>
                            <span style={{
                              marginLeft: 8, padding: '2px 6px', borderRadius: 4, fontSize: text['2xs'], fontWeight: weight.bold,
                              backgroundColor: ref.urgency === 'EMERGENCY' ? '#fef2f2' : ref.urgency === 'URGENT' ? '#fefce8' : '#f0fdf4',
                              color: ref.urgency === 'EMERGENCY' ? '#991b1b' : ref.urgency === 'URGENT' ? '#854d0e' : '#166534',
                            }}>{ref.urgency}</span>
                          </div>
                          {ref.remainingSeconds != null && (
                            <span style={{ fontSize: text['2xs'], color: '#475569' }}>
                              {Math.floor(ref.remainingSeconds / 60)}m remaining
                            </span>
                          )}
                        </div>
                        {ref.specialNeeds && (
                          <div style={{ fontSize: text.xs, color: '#475569', marginBottom: 4 }}>
                            <FormattedMessage id="referral.specialNeedsLabel" />: {ref.specialNeeds}
                          </div>
                        )}
                        <div style={{ fontSize: text.xs, color: '#475569', marginBottom: 8 }}>
                          <FormattedMessage id="referral.callbackLabel" />: {ref.callbackNumber}
                        </div>

                        {rejectingId === ref.id ? (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <input data-testid={`reject-reason-${ref.id}`}
                              type="text" value={rejectReason}
                              onChange={(e) => setRejectReason(e.target.value)}
                              placeholder={intl.formatMessage({ id: 'referral.rejectReason' })}
                              style={{ flex: 1, padding: 6, borderRadius: 6, border: '1px solid #ddd6fe', fontSize: text.xs }} />
                            <button data-testid={`reject-confirm-${ref.id}`}
                              onClick={() => rejectReferral(ref.id)} disabled={!rejectReason.trim()}
                              style={{ padding: '6px 10px', borderRadius: 6, border: 'none', backgroundColor: '#dc2626', color: '#fff', fontSize: text['2xs'], fontWeight: weight.bold, cursor: 'pointer' }}>
                              <FormattedMessage id="referral.reject" />
                            </button>
                            <button onClick={() => { setRejectingId(null); setRejectReason(''); }}
                              style={{ padding: '6px 10px', borderRadius: 6, border: '1px solid #e2e8f0', backgroundColor: '#fff', color: '#475569', fontSize: text['2xs'], cursor: 'pointer' }}>
                              <FormattedMessage id="referral.cancel" />
                            </button>
                          </div>
                        ) : (
                          <div style={{ display: 'flex', gap: 6 }}>
                            <button data-testid={`accept-referral-${ref.id}`}
                              onClick={() => acceptReferral(ref.id)}
                              style={{ padding: '6px 12px', borderRadius: 6, border: 'none', backgroundColor: '#166534', color: '#fff', fontSize: text['2xs'], fontWeight: weight.bold, cursor: 'pointer' }}>
                              <FormattedMessage id="referral.accept" />
                            </button>
                            <button data-testid={`reject-referral-${ref.id}`}
                              onClick={() => setRejectingId(ref.id)}
                              style={{ padding: '6px 12px', borderRadius: 6, border: '1px solid #dc2626', backgroundColor: '#fff', color: '#dc2626', fontSize: text['2xs'], fontWeight: weight.bold, cursor: 'pointer' }}>
                              <FormattedMessage id="referral.reject" />
                            </button>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}

                {/* Active holds indicator */}
                {editAvailability.some(a => a.bedsOnHold > 0) && (
                  <div style={{
                    padding: '10px 14px', backgroundColor: '#eff6ff', borderRadius: 10,
                    border: '1px solid #bfdbfe', marginBottom: 16,
                  }}>
                    <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: '#1a56db', margin: '0 0 8px', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
                      <FormattedMessage id="coord.activeHolds" />
                    </h4>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      {editAvailability.filter(a => a.bedsOnHold > 0).map(a => (
                        <span key={a.populationType} style={{
                          padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
                          backgroundColor: '#dbeafe', color: '#1e40af',
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
        <div style={{ textAlign: 'center', padding: 48, color: '#6b7280' }}>
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
        border: '2px solid #e2e8f0', backgroundColor: '#fff',
        fontSize, fontWeight: weight.bold, color: disabled ? '#d1d5db' : '#0f172a',
        cursor: disabled ? 'default' : 'pointer',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        lineHeight: leading.tight,
      }}
    >{label}</button>
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
