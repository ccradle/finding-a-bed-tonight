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

interface ShelterCapacity {
  populationType: string;
  bedsTotal: number;
}

interface Shelter {
  id: string;
  name: string;
  addressStreet: string;
  addressCity: string;
  addressState: string;
  addressZip: string;
  updatedAt: string;
  capacities?: ShelterCapacity[];
}

interface ShelterDetail {
  shelter: Shelter;
  capacities: ShelterCapacity[];
}

export function CoordinatorDashboard() {
  const intl = useIntl();
  const [shelters, setShelters] = useState<Shelter[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [editCapacities, setEditCapacities] = useState<ShelterCapacity[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedId, setSavedId] = useState<string | null>(null);

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<Shelter[]>('/api/v1/shelters');
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

  const totalBeds = (caps: ShelterCapacity[]) =>
    caps.reduce((sum, c) => sum + c.bedsTotal, 0);

  const openShelter = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null);
      return;
    }
    setDetailLoading(true);
    setError(null);
    try {
      const detail = await api.get<ShelterDetail>(`/api/v1/shelters/${id}`);
      const existing = detail.capacities || [];
      // Ensure all population types have an entry
      const capacityMap = new Map(existing.map((c) => [c.populationType, c.bedsTotal]));
      const full = POPULATION_TYPES.map((pt) => ({
        populationType: pt,
        bedsTotal: capacityMap.get(pt) ?? 0,
      }));
      setEditCapacities(full);
      setExpandedId(id);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setDetailLoading(false);
    }
  };

  const adjustCount = (popType: string, delta: number) => {
    setEditCapacities((prev) =>
      prev.map((c) =>
        c.populationType === popType
          ? { ...c, bedsTotal: Math.max(0, c.bedsTotal + delta) }
          : c
      )
    );
  };

  const saveShelter = async (id: string) => {
    setSaving(true);
    setError(null);
    try {
      await api.put(`/api/v1/shelters/${id}`, { capacities: editCapacities });
      setSavedId(id);
      // Update shelter's updatedAt locally
      setShelters((prev) =>
        prev.map((s) => s.id === id ? { ...s, updatedAt: new Date().toISOString() } : s)
      );
      setTimeout(() => {
        setSavedId(null);
        setExpandedId(null);
      }, 1200);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSaving(false);
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
      {!loading && shelters.map((s) => {
        const isExpanded = expandedId === s.id;
        const isSaved = savedId === s.id;

        return (
          <div
            key={s.id}
            style={{
              marginBottom: 10, borderRadius: 14,
              border: `2px solid ${isSaved ? '#22c55e' : isExpanded ? '#1a56db' : '#e2e8f0'}`,
              backgroundColor: isSaved ? '#f0fdf4' : '#fff',
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
              <div style={{ fontSize: 17, fontWeight: 700, color: '#0f172a', marginBottom: 3 }}>{s.name}</div>
              <div style={{ fontSize: 14, color: '#64748b', marginBottom: 6 }}>{fmtAddr(s)}</div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <DataAge dataAgeSeconds={s.updatedAt ? Math.floor((Date.now() - new Date(s.updatedAt).getTime()) / 1000) : null} />
                {isExpanded
                  ? <span style={{ fontSize: 12, color: '#1a56db', fontWeight: 600 }}>▲</span>
                  : <span style={{ fontSize: 12, color: '#94a3b8', fontWeight: 600 }}>▼</span>}
              </div>
            </button>

            {/* Expanded bed count editor */}
            {isExpanded && (
              <div style={{ padding: '0 20px 20px' }}>
                <div style={{
                  height: 1, backgroundColor: '#e2e8f0', marginBottom: 16,
                }} />

                {editCapacities.map((cap) => (
                  <div
                    key={cap.populationType}
                    style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                      padding: '10px 0', borderBottom: '1px solid #f1f5f9',
                    }}
                  >
                    <span style={{
                      fontSize: 14, fontWeight: 600, color: '#0f172a', flex: 1,
                      textTransform: 'capitalize',
                    }}>
                      {cap.populationType.replace(/_/g, ' ').toLowerCase()}
                    </span>

                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <button
                        onClick={() => adjustCount(cap.populationType, -1)}
                        disabled={cap.bedsTotal <= 0}
                        style={{
                          width: 44, height: 44, borderRadius: '50%',
                          border: '2px solid #e2e8f0', backgroundColor: '#fff',
                          fontSize: 22, fontWeight: 700, color: cap.bedsTotal > 0 ? '#0f172a' : '#d1d5db',
                          cursor: cap.bedsTotal > 0 ? 'pointer' : 'default',
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                          lineHeight: 1,
                        }}
                      >−</button>

                      <span style={{
                        fontSize: 28, fontWeight: 800, color: '#0f172a',
                        minWidth: 48, textAlign: 'center', lineHeight: 1,
                      }}>
                        {cap.bedsTotal}
                      </span>

                      <button
                        onClick={() => adjustCount(cap.populationType, 1)}
                        style={{
                          width: 44, height: 44, borderRadius: '50%',
                          border: '2px solid #e2e8f0', backgroundColor: '#fff',
                          fontSize: 22, fontWeight: 700, color: '#0f172a',
                          cursor: 'pointer',
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                          lineHeight: 1,
                        }}
                      >+</button>
                    </div>
                  </div>
                ))}

                {/* Total */}
                <div style={{
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: '14px 0 18px', fontWeight: 800, fontSize: 16, color: '#0f172a',
                }}>
                  <span><FormattedMessage id="coord.bedsTotal" values={{ count: totalBeds(editCapacities) }} /></span>
                </div>

                {/* Save button */}
                <button
                  onClick={() => saveShelter(s.id)}
                  disabled={saving}
                  style={{
                    width: '100%', padding: 16, backgroundColor: '#059669', color: '#fff',
                    border: 'none', borderRadius: 12, fontSize: 16, fontWeight: 700,
                    cursor: saving ? 'default' : 'pointer', minHeight: 50,
                    opacity: saving ? 0.7 : 1, transition: 'opacity 0.15s',
                  }}
                >
                  {saving
                    ? <FormattedMessage id="coord.loading" />
                    : <FormattedMessage id="coord.save" />}
                </button>
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
