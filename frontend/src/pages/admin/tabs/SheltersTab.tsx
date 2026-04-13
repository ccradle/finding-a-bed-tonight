import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../../../services/api';
import { DataAge } from '../../../components/DataAge';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { ErrorBox, NoData, Spinner } from '../components';
import { StatusBadge } from '../components/StatusBadge';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle } from '../styles';
import type { ShelterListItem } from '../types';

const DEACTIVATION_REASONS = [
  'TEMPORARY_CLOSURE',
  'SEASONAL_END',
  'PERMANENT_CLOSURE',
  'CODE_VIOLATION',
  'FUNDING_LOSS',
  'OTHER',
] as const;

function SheltersTab() {
  const intl = useIntl();
  const [shelters, setShelters] = useState<ShelterListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Deactivation dialog state
  const [deactivatingId, setDeactivatingId] = useState<string | null>(null);
  const [deactReason, setDeactReason] = useState<string>(DEACTIVATION_REASONS[0]);
  const [deactSubmitting, setDeactSubmitting] = useState(false);
  const [dvConfirmNeeded, setDvConfirmNeeded] = useState(false);
  const deactDialogRef = useRef<HTMLDivElement>(null);

  // Reactivation dialog state
  const [reactivatingId, setReactivatingId] = useState<string | null>(null);
  const [reactSubmitting, setReactSubmitting] = useState(false);
  const reactDialogRef = useRef<HTMLDivElement>(null);

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ShelterListItem[]>('/api/v1/shelters');
      setShelters(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchShelters(); }, [fetchShelters]);

  // C-5 fix: focus dialog on open
  useEffect(() => {
    if (deactivatingId && deactDialogRef.current) deactDialogRef.current.focus();
  }, [deactivatingId]);
  useEffect(() => {
    if (reactivatingId && reactDialogRef.current) reactDialogRef.current.focus();
  }, [reactivatingId]);

  const closeDeactivateDialog = () => {
    if (deactSubmitting) return;
    setDeactivatingId(null);
    setDvConfirmNeeded(false);
    setDeactReason(DEACTIVATION_REASONS[0]);
  };

  // ---- Deactivation flow ----

  const handleDeactivate = async (shelterId: string, confirmDv: boolean) => {
    setDeactSubmitting(true);
    setError(null);
    try {
      await api.patch(`/api/v1/shelters/${shelterId}/deactivate`, {
        reason: deactReason,
        confirmDv,
      });
      setDeactivatingId(null);
      setDvConfirmNeeded(false);
      setDeactReason(DEACTIVATION_REASONS[0]);
      await fetchShelters();
    } catch (err: unknown) {
      // C-8 fix: use err.error (not JSON.parse of err.message) to detect DV gate
      if (err instanceof ApiError && err.status === 409 && err.error === 'DV_CONFIRMATION_REQUIRED') {
        setDvConfirmNeeded(true);
        return;
      }
      const apiErr = err as { message?: string };
      setError(apiErr.message || 'Deactivation failed');
    } finally {
      setDeactSubmitting(false);
    }
  };

  // ---- Reactivation flow ----

  const handleReactivate = async (shelterId: string) => {
    setReactSubmitting(true);
    setError(null);
    try {
      await api.patch(`/api/v1/shelters/${shelterId}/reactivate`, {});
      setReactivatingId(null);
      await fetchShelters();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || 'Reactivation failed');
    } finally {
      setReactSubmitting(false);
    }
  };

  if (loading) return <Spinner />;

  const deactivatingShelter = shelters.find(s => s.shelter.id === deactivatingId);

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <a href="/coordinator/shelters/new" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>
          <FormattedMessage id="admin.addShelter" />
        </a>
      </div>

      {shelters.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                {/* C-1 fix: i18n column headers */}
                <th style={thStyle}><FormattedMessage id="shelter.name" /></th>
                <th style={thStyle}><FormattedMessage id="admin.shelters.col.city" /></th>
                <th style={thStyle}><FormattedMessage id="admin.shelters.col.status" /></th>
                <th style={thStyle}><FormattedMessage id="admin.shelters.col.bedsAvailable" /></th>
                <th style={thStyle}><FormattedMessage id="admin.shelters.col.updated" /></th>
                <th style={thStyle}></th>
              </tr>
            </thead>
            <tbody>
              {shelters.map((item, i) => {
                const isInactive = !item.shelter.active;
                return (
                  <tr key={item.shelter.id} style={isInactive ? { backgroundColor: color.bgTertiary } : undefined}>
                    <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>
                      {item.shelter.name}
                      {item.shelter.dvShelter && (
                        <span style={{
                          marginLeft: 6, padding: '2px 6px', borderRadius: 4,
                          fontSize: text['2xs'], fontWeight: weight.bold,
                          backgroundColor: color.dvBg, color: color.dvText,
                          border: `1px solid ${color.dvBorder}`,
                        }}>DV</span>
                      )}
                    </td>
                    <td style={tdStyle(i)}>{item.shelter.addressCity}</td>
                    <td style={tdStyle(i)}>
                      <span data-testid={`shelter-status-badge-${item.shelter.id}`}>
                        <StatusBadge
                          active={item.shelter.active}
                          yesId="shelter.statusActive"
                          noId="shelter.statusInactive"
                        />
                      </span>
                    </td>
                    <td style={tdStyle(i)}>
                      {item.availabilitySummary?.totalBedsAvailable != null
                        ? <span style={{ fontWeight: weight.bold, color: item.availabilitySummary.totalBedsAvailable > 0 ? color.success : color.error }}>
                            {item.availabilitySummary.totalBedsAvailable}
                          </span>
                        : <span style={{ color: color.textMuted }}>—</span>}
                    </td>
                    <td style={tdStyle(i)}>
                      <DataAge dataAgeSeconds={item.availabilitySummary?.dataAgeSeconds ?? null} />
                    </td>
                    <td style={{ ...tdStyle(i), whiteSpace: 'nowrap' }}>
                      <a
                        href={`/coordinator/shelters/${item.shelter.id}/edit?from=/admin`}
                        data-testid={`edit-shelter-${item.shelter.id}`}
                        style={{
                          color: color.primaryText, fontSize: text.sm,
                          fontWeight: weight.semibold, textDecoration: 'none', marginRight: 12,
                        }}
                      >
                        <FormattedMessage id="shelter.editBtn" />
                      </a>
                      {item.shelter.active ? (
                        <button
                          data-testid={`shelter-deactivate-btn-${item.shelter.id}`}
                          aria-label={intl.formatMessage({ id: 'shelter.deactivate' }) + ' ' + item.shelter.name}
                          onClick={() => { setDeactivatingId(item.shelter.id); setDvConfirmNeeded(false); }}
                          style={{
                            padding: '4px 10px', borderRadius: 6, fontSize: text.xs,
                            fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
                            backgroundColor: color.errorBg, color: color.error,
                            border: `1px solid ${color.errorBorder}`,
                          }}
                        >
                          <FormattedMessage id="shelter.deactivate" />
                        </button>
                      ) : (
                        <button
                          data-testid={`shelter-reactivate-btn-${item.shelter.id}`}
                          aria-label={intl.formatMessage({ id: 'shelter.reactivate' }) + ' ' + item.shelter.name}
                          onClick={() => setReactivatingId(item.shelter.id)}
                          style={{
                            padding: '4px 10px', borderRadius: 6, fontSize: text.xs,
                            fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
                            backgroundColor: color.successBg, color: color.success,
                            border: `1px solid ${color.successBorder}`,
                          }}
                        >
                          <FormattedMessage id="shelter.reactivate" />
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* ---- Deactivation Confirmation Dialog ---- */}
      {deactivatingId && deactivatingShelter && (
        <>
          <div
            onClick={closeDeactivateDialog}
            style={{
              position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
              backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1000,
            }}
          />
          <div
            ref={deactDialogRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby="deactivate-dialog-title"
            data-testid="deactivation-dialog"
            tabIndex={-1}
            onKeyDown={(e) => { if (e.key === 'Escape') closeDeactivateDialog(); }}
            style={{
              position: 'fixed', top: '50%', left: '50%',
              transform: 'translate(-50%, -50%)',
              backgroundColor: color.bg, borderRadius: 12, padding: 24,
              maxWidth: 480, width: '90%', zIndex: 1001,
              border: `1px solid ${color.border}`,
              boxShadow: '0 8px 32px rgba(0,0,0,0.15)',
              outline: 'none',
            }}
          >
            <h3 id="deactivate-dialog-title" style={{
              fontSize: text.lg, fontWeight: weight.bold, color: color.text, margin: '0 0 12px',
            }}>
              <FormattedMessage id="shelter.deactivateConfirmTitle" />
            </h3>

            <p style={{ fontSize: text.base, color: color.textSecondary, marginBottom: 16 }}>
              <FormattedMessage id="shelter.deactivateConfirmMessage" />
            </p>

            {/* DV safety warning — shown for DV shelters, enhanced when confirmation required */}
            {deactivatingShelter.shelter.dvShelter && (
              <div data-testid="dv-deactivation-warning" style={{
                padding: 12, marginBottom: 16,
                backgroundColor: color.warningBg,
                border: `1px solid ${color.warningMid}`,
                borderRadius: 8, fontSize: text.sm, color: color.text, lineHeight: 1.5,
              }}>
                <FormattedMessage id="shelter.deactivateDvWarning" />
              </div>
            )}

            {/* Reason selector — C-7 fix: htmlFor + id linking */}
            <label htmlFor="deactivation-reason" style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 6 }}>
              <FormattedMessage id="shelter.deactivateReason" />
            </label>
            <select
              id="deactivation-reason"
              data-testid="deactivation-reason-select"
              value={deactReason}
              onChange={e => setDeactReason(e.target.value)}
              style={{
                width: '100%', padding: '10px 12px', borderRadius: 8,
                border: `2px solid ${color.border}`, fontSize: text.base,
                color: color.text, backgroundColor: color.bg, marginBottom: 20,
                minHeight: 44,
              }}
            >
              {DEACTIVATION_REASONS.map(r => (
                <option key={r} value={r}>
                  {intl.formatMessage({ id: `shelter.reason.${r}` })}
                </option>
              ))}
            </select>

            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button
                onClick={closeDeactivateDialog}
                disabled={deactSubmitting}
                style={{
                  padding: '10px 20px', borderRadius: 8, fontSize: text.base,
                  fontWeight: weight.medium, cursor: 'pointer', minHeight: 44,
                  backgroundColor: color.bg, color: color.textSecondary,
                  border: `1px solid ${color.borderMedium}`,
                }}
              >
                <FormattedMessage id="common.cancel" />
              </button>
              <button
                data-testid="deactivation-confirm-btn"
                onClick={() => handleDeactivate(
                  deactivatingId,
                  deactivatingShelter.shelter.dvShelter && dvConfirmNeeded
                )}
                disabled={deactSubmitting}
                style={{
                  padding: '10px 20px', borderRadius: 8, fontSize: text.base,
                  fontWeight: weight.bold, cursor: deactSubmitting ? 'not-allowed' : 'pointer',
                  minHeight: 44, backgroundColor: color.error, color: color.textInverse,
                  border: 'none', opacity: deactSubmitting ? 0.6 : 1,
                }}
              >
                {deactSubmitting ? '...' : <FormattedMessage id="shelter.deactivateConfirmBtn" />}
              </button>
            </div>
          </div>
        </>
      )}

      {/* ---- Reactivation Confirmation Dialog ---- */}
      {reactivatingId && (
        <>
          <div
            onClick={() => { if (!reactSubmitting) setReactivatingId(null); }}
            style={{
              position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
              backgroundColor: 'rgba(0,0,0,0.5)', zIndex: 1000,
            }}
          />
          <div
            ref={reactDialogRef}
            role="dialog"
            aria-modal="true"
            aria-labelledby="reactivate-dialog-title"
            data-testid="reactivation-dialog"
            tabIndex={-1}
            onKeyDown={(e) => { if (e.key === 'Escape' && !reactSubmitting) setReactivatingId(null); }}
            style={{
              position: 'fixed', top: '50%', left: '50%',
              transform: 'translate(-50%, -50%)',
              backgroundColor: color.bg, borderRadius: 12, padding: 24,
              maxWidth: 420, width: '90%', zIndex: 1001,
              border: `1px solid ${color.border}`,
              boxShadow: '0 8px 32px rgba(0,0,0,0.15)',
              outline: 'none',
            }}
          >
            <h3 id="reactivate-dialog-title" style={{
              fontSize: text.lg, fontWeight: weight.bold, color: color.text, margin: '0 0 12px',
            }}>
              <FormattedMessage id="shelter.reactivateConfirmTitle" />
            </h3>

            <p style={{ fontSize: text.base, color: color.textSecondary, marginBottom: 20 }}>
              <FormattedMessage id="shelter.reactivateConfirmMessage" />
            </p>

            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button
                onClick={() => setReactivatingId(null)}
                disabled={reactSubmitting}
                style={{
                  padding: '10px 20px', borderRadius: 8, fontSize: text.base,
                  fontWeight: weight.medium, cursor: 'pointer', minHeight: 44,
                  backgroundColor: color.bg, color: color.textSecondary,
                  border: `1px solid ${color.borderMedium}`,
                }}
              >
                <FormattedMessage id="common.cancel" />
              </button>
              <button
                data-testid="reactivation-confirm-btn"
                onClick={() => handleReactivate(reactivatingId)}
                disabled={reactSubmitting}
                style={{
                  padding: '10px 20px', borderRadius: 8, fontSize: text.base,
                  fontWeight: weight.bold, cursor: reactSubmitting ? 'not-allowed' : 'pointer',
                  minHeight: 44, backgroundColor: color.success, color: color.textInverse,
                  border: 'none', opacity: reactSubmitting ? 0.6 : 1,
                }}
              >
                {reactSubmitting ? '...' : <FormattedMessage id="shelter.reactivateConfirmBtn" />}
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

export default SheltersTab;
