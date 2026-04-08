import { useState, useEffect, useCallback, useRef, Fragment } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight, font } from '../../../theme/typography';
import { ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';
import type { SubscriptionRow, SubscriptionStatus } from '../types';

interface DeliveryLog {
  id: string;
  eventType: string;
  statusCode: number | null;
  responseTimeMs: number | null;
  attemptedAt: string;
  attemptNumber: number;
  responseBody: string | null;
}

interface TestResult {
  statusCode: number;
  responseTimeMs: number;
  responseBody: string | null;
}

const STATUS_COLORS: Record<SubscriptionStatus, { bg: string; text: string }> = {
  ACTIVE: { bg: color.successBg, text: color.successBright },
  PAUSED: { bg: color.warningBg, text: color.warning },
  FAILING: { bg: color.errorBg, text: color.errorMid },
  DEACTIVATED: { bg: color.errorBg, text: color.error },
  CANCELLED: { bg: color.bgSecondary, text: color.textMuted },
};

function SubStatusBadge({ status }: { status: SubscriptionStatus }) {
  const intl = useIntl();
  const c = STATUS_COLORS[status] || STATUS_COLORS.CANCELLED;
  const label = intl.formatMessage({ id: `subscription.status.${status}` });
  return (
    <span aria-label={label} style={{
      padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.bold,
      backgroundColor: c.bg, color: c.text,
    }}>
      {label}
    </span>
  );
}

function SubscriptionsTab() {
  const intl = useIntl();
  const [subs, setSubs] = useState<SubscriptionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formEventType, setFormEventType] = useState('');
  const [formCallbackUrl, setFormCallbackUrl] = useState('');
  const [formCallbackSecret, setFormCallbackSecret] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<SubscriptionRow | null>(null);
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ id: string; result: TestResult } | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [deliveries, setDeliveries] = useState<DeliveryLog[]>([]);
  const [loadingDeliveries, setLoadingDeliveries] = useState(false);
  const confirmRef = useRef<HTMLDivElement>(null);

  const fetchSubs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<SubscriptionRow[]>('/api/v1/subscriptions');
      setSubs(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchSubs(); }, [fetchSubs]);

  useEffect(() => {
    if (confirmDelete && confirmRef.current) {
      const btn = confirmRef.current.querySelector<HTMLElement>('[data-testid="delete-confirm-btn"]');
      btn?.focus();
    }
  }, [confirmDelete]);

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post('/api/v1/subscriptions', {
        eventType: formEventType,
        callbackUrl: formCallbackUrl,
        callbackSecret: formCallbackSecret,
      });
      setShowForm(false);
      setFormEventType('');
      setFormCallbackUrl('');
      setFormCallbackSecret('');
      await fetchSubs();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: string) => {
    setError(null);
    try {
      await api.delete(`/api/v1/subscriptions/${id}`);
      setConfirmDelete(null);
      await fetchSubs();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
      setConfirmDelete(null);
    }
  };

  const handleToggle = async (sub: SubscriptionRow) => {
    setTogglingId(sub.id);
    setError(null);
    const newStatus = sub.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE';
    try {
      await api.patch(`/api/v1/subscriptions/${sub.id}/status`, { status: newStatus });
      await fetchSubs();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setTogglingId(null);
    }
  };

  const handleTest = async (sub: SubscriptionRow) => {
    setTestingId(sub.id);
    setTestResult(null);
    setError(null);
    try {
      const result = await api.post<TestResult>(`/api/v1/subscriptions/${sub.id}/test`, {
        eventType: sub.eventType,
      });
      setTestResult({ id: sub.id, result });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setTestingId(null);
    }
  };

  const toggleDeliveries = async (id: string) => {
    if (expandedId === id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(id);
    setLoadingDeliveries(true);
    try {
      const data = await api.get<DeliveryLog[]>(`/api/v1/subscriptions/${id}/deliveries`);
      setDeliveries(data || []);
    } catch {
      setDeliveries([]);
    } finally {
      setLoadingDeliveries(false);
    }
  };

  const canToggle = (s: SubscriptionRow) => s.status === 'ACTIVE' || s.status === 'PAUSED';
  const canTest = (s: SubscriptionRow) => s.status === 'ACTIVE';

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <button onClick={() => setShowForm(!showForm)} style={primaryBtnStyle}>
          <FormattedMessage id="admin.newSubscription" />
        </button>
      </div>

      {showForm && (
        <div style={{
          padding: 20, border: `2px solid ${color.border}`, borderRadius: 14,
          marginBottom: 20, backgroundColor: color.bgSecondary,
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <label htmlFor="sub-event-type" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="subscription.eventType" />
              </label>
              <input id="sub-event-type" value={formEventType} onChange={(e) => setFormEventType(e.target.value)}
                style={inputStyle} placeholder={intl.formatMessage({ id: 'subscription.eventTypePlaceholder' })} />
            </div>
            <div>
              <label htmlFor="sub-callback-url" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="subscription.callbackUrl" />
              </label>
              <input id="sub-callback-url" value={formCallbackUrl} onChange={(e) => setFormCallbackUrl(e.target.value)}
                type="url" style={inputStyle} placeholder="https://..." />
            </div>
            <div>
              <label htmlFor="sub-callback-secret" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="subscription.callbackSecret" />
              </label>
              <input id="sub-callback-secret" value={formCallbackSecret} onChange={(e) => setFormCallbackSecret(e.target.value)}
                type="password" style={inputStyle} />
            </div>
            <button onClick={handleCreate} disabled={submitting || !formEventType || !formCallbackUrl}
              style={{ ...primaryBtnStyle, width: '100%', opacity: submitting || !formEventType || !formCallbackUrl ? 0.6 : 1 }}>
              {submitting ? '...' : intl.formatMessage({ id: 'admin.newSubscription' })}
            </button>
          </div>
        </div>
      )}

      {subs.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}><FormattedMessage id="subscription.col.eventType" /></th>
                <th style={thStyle}><FormattedMessage id="subscription.col.callbackUrl" /></th>
                <th style={thStyle}><FormattedMessage id="subscription.col.status" /></th>
                <th style={thStyle}><FormattedMessage id="subscription.col.created" /></th>
                <th style={thStyle}><FormattedMessage id="subscription.col.actions" /></th>
              </tr>
            </thead>
            <tbody>
              {subs.map((s, i) => (
                <Fragment key={s.id}>
                  <tr>
                    <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{s.eventType}</td>
                    <td style={{ ...tdStyle(i), fontFamily: font.mono, fontSize: text.sm }}>{s.callbackUrl}</td>
                    <td style={tdStyle(i)}>
                      <SubStatusBadge status={s.status} />
                      {s.consecutiveFailures > 0 && (
                        <div style={{ fontSize: text.xs, color: color.errorMid, marginTop: 4 }}>
                          {s.consecutiveFailures} <FormattedMessage id="subscription.failures" />
                        </div>
                      )}
                      {s.lastError && (
                        <div style={{ fontSize: text.xs, color: color.textMuted, marginTop: 2, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {s.lastError}
                        </div>
                      )}
                    </td>
                    <td style={tdStyle(i)}>{new Date(s.createdAt).toLocaleDateString()}</td>
                    <td style={tdStyle(i)}>
                      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                        {canToggle(s) && (
                          <button
                            data-testid={`toggle-sub-${s.id}`}
                            onClick={() => handleToggle(s)}
                            disabled={togglingId === s.id}
                            aria-label={intl.formatMessage(
                              { id: s.status === 'ACTIVE' ? 'subscription.pauseLabel' : 'subscription.resumeLabel' },
                              { eventType: s.eventType }
                            )}
                            style={{
                              padding: '6px 12px', borderRadius: 6, border: `1px solid ${color.borderMedium}`,
                              backgroundColor: color.bg, color: s.status === 'ACTIVE' ? color.warning : color.successBright,
                              fontSize: text.xs, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 36,
                              opacity: togglingId === s.id ? 0.6 : 1,
                            }}
                          >
                            {togglingId === s.id ? '...' : (
                              s.status === 'ACTIVE'
                                ? intl.formatMessage({ id: 'subscription.pause' })
                                : intl.formatMessage({ id: 'subscription.resume' })
                            )}
                          </button>
                        )}
                        {canTest(s) && (
                          <button
                            data-testid={`test-sub-${s.id}`}
                            onClick={() => handleTest(s)}
                            disabled={testingId === s.id}
                            aria-label={intl.formatMessage({ id: 'subscription.testLabel' }, { eventType: s.eventType })}
                            style={{
                              padding: '6px 12px', borderRadius: 6, border: `1px solid ${color.borderMedium}`,
                              backgroundColor: color.bg, color: color.primaryText, fontSize: text.xs,
                              fontWeight: weight.semibold, cursor: 'pointer', minHeight: 36,
                              opacity: testingId === s.id ? 0.6 : 1,
                            }}
                          >
                            {testingId === s.id ? '...' : intl.formatMessage({ id: 'subscription.test' })}
                          </button>
                        )}
                        <button
                          data-testid={`deliveries-sub-${s.id}`}
                          onClick={() => toggleDeliveries(s.id)}
                          aria-expanded={expandedId === s.id}
                          aria-label={intl.formatMessage({ id: 'subscription.deliveriesLabel' }, { eventType: s.eventType })}
                          style={{
                            padding: '6px 12px', borderRadius: 6, border: `1px solid ${color.borderMedium}`,
                            backgroundColor: expandedId === s.id ? color.bgSecondary : color.bg,
                            color: color.textTertiary, fontSize: text.xs, fontWeight: weight.semibold,
                            cursor: 'pointer', minHeight: 36,
                          }}
                        >
                          <FormattedMessage id="subscription.deliveries" />
                        </button>
                        {s.status !== 'CANCELLED' && (
                          <button
                            data-testid={`delete-sub-${s.id}`}
                            onClick={() => setConfirmDelete(s)}
                            aria-label={intl.formatMessage({ id: 'subscription.deleteLabel' }, { eventType: s.eventType })}
                            style={{
                              padding: '6px 12px', borderRadius: 6, border: `1px solid ${color.errorBorder}`,
                              backgroundColor: color.errorBg, color: color.errorMid, fontSize: text.xs,
                              fontWeight: weight.semibold, cursor: 'pointer', minHeight: 36,
                            }}
                          >
                            <FormattedMessage id="subscription.delete" />
                          </button>
                        )}
                      </div>
                      {/* Inline test result */}
                      {testResult && testResult.id === s.id && (
                        <div data-testid={`test-result-${s.id}`} aria-live="polite" style={{
                          marginTop: 8, padding: '8px 12px', borderRadius: 8, fontSize: text.xs,
                          backgroundColor: testResult.result.statusCode < 300 ? color.successBg : color.errorBg,
                          color: testResult.result.statusCode < 300 ? color.successBright : color.errorMid,
                          fontWeight: weight.semibold,
                        }}>
                          {testResult.result.statusCode} — {testResult.result.responseTimeMs}ms
                        </div>
                      )}
                    </td>
                  </tr>
                  {/* Expandable delivery log */}
                  {expandedId === s.id && (
                    <tr>
                      <td colSpan={5} style={{ padding: '12px 16px', backgroundColor: color.bgSecondary }}>
                        {loadingDeliveries ? <Spinner /> : deliveries.length === 0 ? (
                          <div style={{ fontSize: text.sm, color: color.textMuted, textAlign: 'center', padding: 12 }}>
                            <FormattedMessage id="subscription.noDeliveries" />
                          </div>
                        ) : (
                          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.xs }}>
                            <thead>
                              <tr>
                                <th style={{ ...thStyle, fontSize: text.xs, padding: '6px 8px' }}><FormattedMessage id="subscription.delivery.time" /></th>
                                <th style={{ ...thStyle, fontSize: text.xs, padding: '6px 8px' }}><FormattedMessage id="subscription.delivery.event" /></th>
                                <th style={{ ...thStyle, fontSize: text.xs, padding: '6px 8px' }}><FormattedMessage id="subscription.delivery.status" /></th>
                                <th style={{ ...thStyle, fontSize: text.xs, padding: '6px 8px' }}><FormattedMessage id="subscription.delivery.time_ms" /></th>
                              </tr>
                            </thead>
                            <tbody>
                              {deliveries.map((d) => (
                                <tr key={d.id}>
                                  <td style={{ padding: '6px 8px', borderBottom: `1px solid ${color.border}` }}>
                                    {new Date(d.attemptedAt).toLocaleString()}
                                  </td>
                                  <td style={{ padding: '6px 8px', borderBottom: `1px solid ${color.border}` }}>
                                    {d.eventType}
                                  </td>
                                  <td style={{ padding: '6px 8px', borderBottom: `1px solid ${color.border}` }}>
                                    <span style={{
                                      padding: '2px 6px', borderRadius: 4, fontWeight: weight.bold,
                                      backgroundColor: d.statusCode && d.statusCode < 300 ? color.successBg : color.errorBg,
                                      color: d.statusCode && d.statusCode < 300 ? color.successBright : color.errorMid,
                                    }}>
                                      {d.statusCode ?? '—'}
                                    </span>
                                  </td>
                                  <td style={{ padding: '6px 8px', borderBottom: `1px solid ${color.border}` }}>
                                    {d.responseTimeMs != null ? `${d.responseTimeMs}ms` : '—'}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Delete confirmation dialog — WCAG alertdialog pattern */}
      {confirmDelete && (
        <div
          ref={confirmRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="delete-confirm-title"
          aria-describedby="delete-confirm-desc"
          data-testid="delete-confirm-dialog"
          style={{
            position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex',
            alignItems: 'center', justifyContent: 'center', zIndex: 1000,
          }}
          onKeyDown={(e) => { if (e.key === 'Escape') setConfirmDelete(null); }}
        >
          <div style={{
            backgroundColor: color.bg, borderRadius: 12, padding: 24,
            maxWidth: 480, width: '90%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
          }}>
            <h3 id="delete-confirm-title" style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.error, marginTop: 0 }}>
              <FormattedMessage id="subscription.deleteConfirmTitle" />
            </h3>
            <p id="delete-confirm-desc" style={{ fontSize: text.base, color: color.textSecondary, lineHeight: 1.6 }}>
              <FormattedMessage id="subscription.deleteConfirmMessage" values={{ eventType: confirmDelete.eventType, url: confirmDelete.callbackUrl }} />
            </p>
            <div style={{ display: 'flex', gap: 12, justifyContent: 'flex-end', marginTop: 20 }}>
              <button
                data-testid="delete-cancel-btn"
                onClick={() => setConfirmDelete(null)}
                style={{
                  padding: '10px 20px', borderRadius: 8, border: `1px solid ${color.borderMedium}`,
                  backgroundColor: color.bg, color: color.textSecondary, cursor: 'pointer',
                  fontSize: text.base, minHeight: 44,
                }}
              >
                <FormattedMessage id="subscription.cancel" />
              </button>
              <button
                data-testid="delete-confirm-btn"
                onClick={() => handleDelete(confirmDelete.id)}
                style={{
                  padding: '10px 20px', borderRadius: 8, border: 'none',
                  backgroundColor: color.errorMid, color: color.textInverse, cursor: 'pointer',
                  fontSize: text.base, fontWeight: weight.semibold, minHeight: 44,
                }}
              >
                <FormattedMessage id="subscription.deleteConfirm" />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default SubscriptionsTab;
