import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

interface HmisInventoryRecord {
  projectId: string | null;
  projectName: string;
  householdType: string;
  bedInventory: number;
  bedsOccupied: number;
  utilizationPercent: number;
  isDvAggregated: boolean;
}

interface HmisAuditEntry {
  vendorType: string;
  pushTimestamp: string;
  status: string;
  recordCount: number;
  errorMessage: string | null;
}

interface HmisVendorStatus {
  type: string;
  enabled: boolean;
  pushIntervalHours?: number;
}

interface HmisStatus {
  vendors: HmisVendorStatus[];
  deadLetterCount: number;
}

function HmisExportTab() {
  const intl = useIntl();
  const [preview, setPreview] = useState<HmisInventoryRecord[]>([]);
  const [history, setHistory] = useState<HmisAuditEntry[]>([]);
  const [status, setStatus] = useState<HmisStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [pushing, setPushing] = useState(false);
  const [dvFilter, setDvFilter] = useState<boolean | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [statusData, previewData, historyData] = await Promise.all([
        api.get<HmisStatus>('/api/v1/hmis/status'),
        api.get<HmisInventoryRecord[]>('/api/v1/hmis/preview'),
        api.get<HmisAuditEntry[]>('/api/v1/hmis/history?limit=20'),
      ]);
      setStatus(statusData);
      setPreview(previewData || []);
      setHistory(historyData || []);
    } catch { /* silent */ }
    setLoading(false);
  }, []);

  // eslint-disable-next-line react-hooks/set-state-in-effect -- Initial data fetch on mount; setState in async callback is the standard pattern
  useEffect(() => { fetchData(); }, [fetchData]);

  const handlePush = async () => {
    setPushing(true);
    try {
      await api.post('/api/v1/hmis/push', {});
      await fetchData();
    } catch { /* silent */ }
    setPushing(false);
  };

  const filteredPreview = dvFilter === null ? preview
    : preview.filter(r => r.isDvAggregated === dvFilter);

  if (loading) return <div style={{ padding: 20, color: color.textTertiary }}><FormattedMessage id="coord.loading" /></div>;

  return (
    <div>
      {/* Export Status */}
      <div data-testid="hmis-status" style={{ marginBottom: 20 }}>
        <h3 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginBottom: 10 }}>
          <FormattedMessage id="hmis.exportStatus" />
        </h3>
        {status && status.vendors?.length > 0 ? (
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            {status.vendors.map((v: HmisVendorStatus, i: number) => (
              <div key={i} style={{
                padding: '10px 16px', borderRadius: 10, border: `1px solid ${color.border}`,
                backgroundColor: v.enabled ? color.successBg : color.errorBg,
              }}>
                <span style={{ fontWeight: weight.bold, fontSize: text.sm }}>{v.type}</span>
                <span style={{ marginLeft: 8, fontSize: text.xs, color: v.enabled ? color.success : color.error }}>
                  {v.enabled ? 'Enabled' : 'Disabled'}
                </span>
                <span style={{ marginLeft: 8, fontSize: text['2xs'], color: color.textTertiary }}>
                  every {v.pushIntervalHours}h
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p style={{ fontSize: text.base, color: color.textTertiary }}><FormattedMessage id="hmis.noVendors" /></p>
        )}
        {status && status.deadLetterCount != null && status.deadLetterCount > 0 && (
          <div style={{ marginTop: 8, padding: '6px 12px', backgroundColor: color.errorBg, borderRadius: 8, display: 'inline-block' }}>
            <span style={{ fontSize: text.xs, fontWeight: weight.bold, color: color.error }}>
              {status.deadLetterCount} dead letter{status.deadLetterCount > 1 ? 's' : ''}
            </span>
          </div>
        )}
      </div>

      {/* Manual Push */}
      <div style={{ marginBottom: 20 }}>
        <button data-testid="hmis-push-now" onClick={handlePush} disabled={pushing}
          style={{
            padding: '10px 20px', borderRadius: 10, border: 'none',
            backgroundColor: pushing ? color.textMuted : color.primary, color: color.textInverse,
            fontSize: text.base, fontWeight: weight.bold, cursor: pushing ? 'default' : 'pointer',
          }}>
          {pushing ? '...' : intl.formatMessage({ id: 'hmis.pushNow' })}
        </button>
      </div>

      {/* Data Preview */}
      <div data-testid="hmis-preview" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <h3 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text }}>
            <FormattedMessage id="hmis.dataPreview" />
          </h3>
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => setDvFilter(null)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === null ? color.primary : color.border}`,
              backgroundColor: dvFilter === null ? color.bgHighlight : color.bg, fontSize: text['2xs'], fontWeight: weight.semibold, cursor: 'pointer',
              color: dvFilter === null ? color.primaryText : color.textTertiary,
            }}>All</button>
            <button onClick={() => setDvFilter(false)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === false ? color.primary : color.border}`,
              backgroundColor: dvFilter === false ? color.bgHighlight : color.bg, fontSize: text['2xs'], fontWeight: weight.semibold, cursor: 'pointer',
              color: dvFilter === false ? color.primaryText : color.textTertiary,
            }}>Non-DV</button>
            <button onClick={() => setDvFilter(true)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === true ? color.dv : color.border}`,
              backgroundColor: dvFilter === true ? color.dvBg : color.bg, fontSize: text['2xs'], fontWeight: weight.semibold, cursor: 'pointer',
              color: dvFilter === true ? color.dv : color.textTertiary,
            }}>DV (Aggregated)</button>
          </div>
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.sm }}>
          <thead>
            <tr style={{ borderBottom: `2px solid ${color.border}`, textAlign: 'left' }}>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Shelter</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Population</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary, textAlign: 'right' }}>Total</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary, textAlign: 'right' }}>Occupied</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary, textAlign: 'right' }}>Util %</th>
            </tr>
          </thead>
          <tbody>
            {filteredPreview.map((r, i) => (
              <tr key={i} data-testid={`hmis-preview-row-${i}`} style={{
                borderBottom: `1px solid ${color.borderLight}`,
                backgroundColor: r.isDvAggregated ? color.dvBg : 'transparent',
              }}>
                <td style={{ padding: '8px 12px', fontWeight: r.isDvAggregated ? weight.bold : weight.normal, color: r.isDvAggregated ? color.dv : color.text }}>
                  {r.projectName}
                </td>
                <td style={{ padding: '8px 12px', color: color.textTertiary, textTransform: 'capitalize' }}>
                  {r.householdType.replace(/_/g, ' ').toLowerCase()}
                </td>
                <td style={{ padding: '8px 12px', textAlign: 'right', fontWeight: weight.semibold }}>{r.bedInventory}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right' }}>{r.bedsOccupied}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right', color: r.utilizationPercent > 100 ? color.error : color.textTertiary }}>
                  {(r.utilizationPercent ?? 0).toFixed(1)}%
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Export History */}
      <div data-testid="hmis-history">
        <h3 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginBottom: 10 }}>
          <FormattedMessage id="hmis.exportHistory" />
        </h3>
        {history.length === 0 ? (
          <p style={{ fontSize: text.base, color: color.textTertiary }}><FormattedMessage id="hmis.noHistory" /></p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.sm }}>
            <thead>
              <tr style={{ borderBottom: `2px solid ${color.border}`, textAlign: 'left' }}>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Time</th>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Vendor</th>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Records</th>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {history.map((h, i) => (
                <tr key={i} style={{ borderBottom: `1px solid ${color.borderLight}` }}>
                  <td style={{ padding: '8px 12px' }}>{new Date(h.pushTimestamp).toLocaleString()}</td>
                  <td style={{ padding: '8px 12px' }}>{h.vendorType}</td>
                  <td style={{ padding: '8px 12px' }}>{h.recordCount}</td>
                  <td style={{ padding: '8px 12px' }}>
                    <span style={{
                      padding: '2px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.bold,
                      backgroundColor: h.status === 'SUCCESS' ? color.successBg : color.errorBg,
                      color: h.status === 'SUCCESS' ? color.success : color.error,
                    }}>{h.status}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

export default HmisExportTab;
