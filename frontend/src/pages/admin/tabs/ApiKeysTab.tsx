import { useState, useEffect, useCallback, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight, font } from '../../../theme/typography';
import { ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';
import type { ApiKeyRow, ApiKeyCreateResponse } from '../types';

/** Derive display status from backend fields */
function getKeyStatus(k: ApiKeyRow): 'active' | 'grace' | 'revoked' {
  if (!k.active) return 'revoked';
  if (k.oldKeyExpiresAt && new Date(k.oldKeyExpiresAt) > new Date()) return 'grace';
  return 'active';
}

function KeyStatusBadge({ status }: { status: 'active' | 'grace' | 'revoked' }) {
  const intl = useIntl();
  const styles: Record<string, { bg: string; text: string; label: string }> = {
    active: { bg: color.successBg, text: color.successBright, label: intl.formatMessage({ id: 'apiKey.status.active' }) },
    grace: { bg: color.warningBg, text: color.warning, label: intl.formatMessage({ id: 'apiKey.status.grace' }) },
    revoked: { bg: color.errorBg, text: color.errorMid, label: intl.formatMessage({ id: 'apiKey.status.revoked' }) },
  };
  const s = styles[status];
  return (
    <span
      aria-label={s.label}
      style={{
        padding: '4px 10px', borderRadius: 8, fontSize: text.xs, fontWeight: weight.bold,
        backgroundColor: s.bg, color: s.text,
      }}
    >
      {s.label}
    </span>
  );
}

function ApiKeysTab() {
  const intl = useIntl();
  const [keys, setKeys] = useState<ApiKeyRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formLabel, setFormLabel] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [newKeyResult, setNewKeyResult] = useState<ApiKeyCreateResponse | null>(null);
  const [copied, setCopied] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState<ApiKeyRow | null>(null);
  const [confirmRotate, setConfirmRotate] = useState<ApiKeyRow | null>(null);
  const [rotatingId, setRotatingId] = useState<string | null>(null);
  const confirmRef = useRef<HTMLDivElement>(null);
  const rotateConfirmRef = useRef<HTMLDivElement>(null);

  const fetchKeys = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ApiKeyRow[]>('/api/v1/api-keys');
      setKeys(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchKeys(); }, [fetchKeys]);

  // Focus confirm button when dialog opens
  useEffect(() => {
    if (confirmRevoke && confirmRef.current) {
      const btn = confirmRef.current.querySelector<HTMLElement>('[data-testid="revoke-confirm-btn"]');
      btn?.focus();
    }
  }, [confirmRevoke]);

  useEffect(() => {
    if (confirmRotate && rotateConfirmRef.current) {
      const btn = rotateConfirmRef.current.querySelector<HTMLElement>('[data-testid="rotate-confirm-btn"]');
      btn?.focus();
    }
  }, [confirmRotate]);

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await api.post<ApiKeyCreateResponse>('/api/v1/api-keys', { label: formLabel });
      setNewKeyResult(result);
      setShowForm(false);
      setFormLabel('');
      await fetchKeys();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSubmitting(false);
    }
  };

  const handleRevoke = async (id: string) => {
    setError(null);
    try {
      await api.delete(`/api/v1/api-keys/${id}`);
      setConfirmRevoke(null);
      await fetchKeys();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
      setConfirmRevoke(null);
    }
  };

  const handleRotate = async (id: string) => {
    setRotatingId(id);
    setConfirmRotate(null);
    setError(null);
    try {
      const result = await api.post<ApiKeyCreateResponse>(`/api/v1/api-keys/${id}/rotate`);
      setNewKeyResult(result);
      await fetchKeys();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setRotatingId(null);
    }
  };

  const copyKey = async () => {
    if (newKeyResult) {
      await navigator.clipboard.writeText(newKeyResult.plaintextKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      {/* New key reveal */}
      {newKeyResult && (
        <div style={{
          padding: 20, backgroundColor: color.warningBg, border: `2px solid ${color.warningBright}`,
          borderRadius: 14, marginBottom: 20,
        }}>
          <div style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.warning, marginBottom: 8 }}>
            <FormattedMessage id="admin.keyWarning" />
          </div>
          <div data-testid="api-key-reveal" style={{
            padding: '12px 14px', backgroundColor: color.bg, borderRadius: 8,
            fontFamily: font.mono, fontSize: text.base, color: color.text, wordBreak: 'break-all',
            marginBottom: 10, border: `1px solid ${color.border}`,
          }}>
            {newKeyResult.plaintextKey}
          </div>
          <button onClick={copyKey} style={{
            ...primaryBtnStyle,
            backgroundColor: copied ? color.successBright : color.primary,
          }}>
            {copied ? intl.formatMessage({ id: 'apiKey.copied' }) : intl.formatMessage({ id: 'apiKey.copy' })}
          </button>
          <button onClick={() => setNewKeyResult(null)} style={{
            marginLeft: 8, padding: '12px 20px', backgroundColor: color.borderLight,
            color: color.textTertiary, border: 'none', borderRadius: 10, fontSize: text.base,
            fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
          }}>
            <FormattedMessage id="apiKey.dismiss" />
          </button>
        </div>
      )}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <button onClick={() => setShowForm(!showForm)} style={primaryBtnStyle}>
          <FormattedMessage id="admin.createKey" />
        </button>
      </div>

      {showForm && (
        <div style={{
          padding: 20, border: `2px solid ${color.border}`, borderRadius: 14,
          marginBottom: 20, backgroundColor: color.bgSecondary,
        }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <label htmlFor="api-key-label" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="apiKey.label" />
              </label>
              <input id="api-key-label" value={formLabel} onChange={(e) => setFormLabel(e.target.value)}
                style={inputStyle} placeholder={intl.formatMessage({ id: 'apiKey.labelPlaceholder' })} />
            </div>
            <button onClick={handleCreate} disabled={submitting || !formLabel}
              style={{ ...primaryBtnStyle, opacity: submitting || !formLabel ? 0.6 : 1 }}>
              {submitting ? '...' : intl.formatMessage({ id: 'apiKey.create' })}
            </button>
          </div>
        </div>
      )}

      {keys.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}><FormattedMessage id="apiKey.col.key" /></th>
                <th style={thStyle}><FormattedMessage id="apiKey.col.label" /></th>
                <th style={thStyle}><FormattedMessage id="apiKey.col.status" /></th>
                <th style={thStyle}><FormattedMessage id="apiKey.col.lastUsed" /></th>
                <th style={thStyle}><FormattedMessage id="apiKey.col.created" /></th>
                <th style={thStyle}><FormattedMessage id="apiKey.col.actions" /></th>
              </tr>
            </thead>
            <tbody>
              {keys.map((k, i) => {
                const status = getKeyStatus(k);
                return (
                  <tr key={k.id}>
                    <td style={{ ...tdStyle(i), fontFamily: font.mono }}>****{k.suffix}</td>
                    <td style={tdStyle(i)}>{k.label}</td>
                    <td style={tdStyle(i)}>
                      <KeyStatusBadge status={status} />
                      {status === 'grace' && k.oldKeyExpiresAt && (
                        <div style={{ fontSize: text.xs, color: color.textMuted, marginTop: 4 }}>
                          <FormattedMessage id="apiKey.graceUntil" /> {new Date(k.oldKeyExpiresAt).toLocaleString()}
                        </div>
                      )}
                    </td>
                    <td style={{ ...tdStyle(i), fontSize: text.sm, color: color.textTertiary }}>
                      {k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'}
                    </td>
                    <td style={tdStyle(i)}>{new Date(k.createdAt).toLocaleDateString()}</td>
                    <td style={tdStyle(i)}>
                      {k.active && (
                        <div style={{ display: 'flex', gap: 6 }}>
                          <button
                            data-testid={`rotate-key-${k.id}`}
                            onClick={() => setConfirmRotate(k)}
                            disabled={rotatingId === k.id}
                            aria-label={intl.formatMessage({ id: 'apiKey.rotateLabel' }, { label: k.label })}
                            style={{
                              padding: '6px 12px', borderRadius: 6, border: `1px solid ${color.borderMedium}`,
                              backgroundColor: color.bg, color: color.primaryText, fontSize: text.xs,
                              fontWeight: weight.semibold, cursor: 'pointer', minHeight: 36,
                              opacity: rotatingId === k.id ? 0.6 : 1,
                            }}
                          >
                            {rotatingId === k.id ? '...' : intl.formatMessage({ id: 'apiKey.rotate' })}
                          </button>
                          <button
                            data-testid={`revoke-key-${k.id}`}
                            onClick={() => setConfirmRevoke(k)}
                            aria-label={intl.formatMessage({ id: 'apiKey.revokeLabel' }, { label: k.label })}
                            style={{
                              padding: '6px 12px', borderRadius: 6, border: `1px solid ${color.errorBorder}`,
                              backgroundColor: color.errorBg, color: color.errorMid, fontSize: text.xs,
                              fontWeight: weight.semibold, cursor: 'pointer', minHeight: 36,
                            }}
                          >
                            <FormattedMessage id="apiKey.revoke" />
                          </button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Revoke confirmation dialog — WCAG alertdialog pattern */}
      {confirmRevoke && (
        <div
          ref={confirmRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="revoke-confirm-title"
          aria-describedby="revoke-confirm-desc"
          data-testid="revoke-confirm-dialog"
          style={{
            position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex',
            alignItems: 'center', justifyContent: 'center', zIndex: 1000,
          }}
          onKeyDown={(e) => { if (e.key === 'Escape') setConfirmRevoke(null); }}
        >
          <div style={{
            backgroundColor: color.bg, borderRadius: 12, padding: 24,
            maxWidth: 480, width: '90%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
          }}>
            <h3 id="revoke-confirm-title" style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.error, marginTop: 0 }}>
              <FormattedMessage id="apiKey.revokeConfirmTitle" />
            </h3>
            <p id="revoke-confirm-desc" style={{ fontSize: text.base, color: color.textSecondary, lineHeight: 1.6 }}>
              <FormattedMessage id="apiKey.revokeConfirmMessage" values={{ label: confirmRevoke.label, suffix: confirmRevoke.suffix }} />
            </p>
            <div style={{ display: 'flex', gap: 12, justifyContent: 'flex-end', marginTop: 20 }}>
              <button
                data-testid="revoke-cancel-btn"
                onClick={() => setConfirmRevoke(null)}
                style={{
                  padding: '10px 20px', borderRadius: 8, border: `1px solid ${color.borderMedium}`,
                  backgroundColor: color.bg, color: color.textSecondary, cursor: 'pointer',
                  fontSize: text.base, minHeight: 44,
                }}
              >
                <FormattedMessage id="apiKey.cancel" />
              </button>
              <button
                data-testid="revoke-confirm-btn"
                onClick={() => handleRevoke(confirmRevoke.id)}
                style={{
                  padding: '10px 20px', borderRadius: 8, border: 'none',
                  backgroundColor: color.errorMid, color: color.textInverse, cursor: 'pointer',
                  fontSize: text.base, fontWeight: weight.semibold, minHeight: 44,
                }}
              >
                <FormattedMessage id="apiKey.revokeConfirm" />
              </button>
            </div>
          </div>
        </div>
      )}
      {/* Rotate confirmation dialog — WCAG alertdialog pattern */}
      {confirmRotate && (
        <div
          ref={rotateConfirmRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="rotate-confirm-title"
          aria-describedby="rotate-confirm-desc"
          data-testid="rotate-confirm-dialog"
          style={{
            position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
            backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex',
            alignItems: 'center', justifyContent: 'center', zIndex: 1000,
          }}
          onKeyDown={(e) => { if (e.key === 'Escape') setConfirmRotate(null); }}
        >
          <div style={{
            backgroundColor: color.bg, borderRadius: 12, padding: 24,
            maxWidth: 480, width: '90%', boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
          }}>
            <h3 id="rotate-confirm-title" style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.warning, marginTop: 0 }}>
              <FormattedMessage id="apiKey.rotateConfirmTitle" />
            </h3>
            <p id="rotate-confirm-desc" style={{ fontSize: text.base, color: color.textSecondary, lineHeight: 1.6 }}>
              <FormattedMessage id="apiKey.rotateConfirmMessage" values={{ label: confirmRotate.label, suffix: confirmRotate.suffix }} />
            </p>
            <div style={{ display: 'flex', gap: 12, justifyContent: 'flex-end', marginTop: 20 }}>
              <button
                data-testid="rotate-cancel-btn"
                onClick={() => setConfirmRotate(null)}
                style={{
                  padding: '10px 20px', borderRadius: 8, border: `1px solid ${color.borderMedium}`,
                  backgroundColor: color.bg, color: color.textSecondary, cursor: 'pointer',
                  fontSize: text.base, minHeight: 44,
                }}
              >
                <FormattedMessage id="apiKey.cancel" />
              </button>
              <button
                data-testid="rotate-confirm-btn"
                onClick={() => handleRotate(confirmRotate.id)}
                style={{
                  padding: '10px 20px', borderRadius: 8, border: 'none',
                  backgroundColor: color.warning, color: color.textInverse, cursor: 'pointer',
                  fontSize: text.base, fontWeight: weight.semibold, minHeight: 44,
                }}
              >
                <FormattedMessage id="apiKey.rotateConfirm" />
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default ApiKeysTab;
