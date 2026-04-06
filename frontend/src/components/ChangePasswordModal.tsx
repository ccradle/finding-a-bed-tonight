import { useState, useRef, useEffect } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

interface ChangePasswordModalProps {
  open: boolean;
  onClose: () => void;
  onSuccess: () => void;
}

export function ChangePasswordModal({ open, onClose, onSuccess }: ChangePasswordModalProps) {
  const intl = useIntl();
  const dialogRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      previousFocusRef.current = document.activeElement as HTMLElement;
      setTimeout(() => dialogRef.current?.focus(), 50);
    } else {
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      setError(null);
      previousFocusRef.current?.focus();
    }
  }, [open]);

  if (!open) return null;

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      onClose();
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (newPassword !== confirmPassword) {
      setError(intl.formatMessage({ id: 'password.change.mismatch' }));
      return;
    }

    if (newPassword.length < 12) {
      setError(intl.formatMessage({ id: 'password.change.tooShort' }));
      return;
    }

    setSubmitting(true);
    try {
      await api.put('/api/v1/auth/password', { currentPassword, newPassword });
      onSuccess();
    } catch (err: unknown) {
      const apiErr = err as { status?: number; message?: string };
      if (apiErr.status === 409) {
        setError(intl.formatMessage({ id: 'password.change.ssoOnly' }));
      } else if (apiErr.status === 401) {
        setError(intl.formatMessage({ id: 'password.change.wrongCurrent' }));
      } else {
        setError(apiErr.message || intl.formatMessage({ id: 'password.change.error' }));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const inputStyle: React.CSSProperties = {
    width: '100%',
    padding: '12px 14px',
    borderRadius: 10,
    border: `2px solid ${color.border}`,
    fontSize: text.base,
    boxSizing: 'border-box',
    color: color.text,
    fontWeight: weight.medium,
  };

  const labelStyle: React.CSSProperties = {
    fontSize: text.xs,
    fontWeight: weight.semibold,
    color: color.textTertiary,
    marginBottom: 4,
    display: 'block',
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 9000,
      }}
      onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="change-password-title"
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        style={{
          background: color.bg,
          borderRadius: 16,
          padding: 32,
          maxWidth: 420,
          width: '90%',
          boxShadow: '0 8px 32px rgba(0,0,0,0.2)',
        }}
      >
        <h2
          id="change-password-title"
          style={{ margin: '0 0 20px', fontSize: text.xl, fontWeight: weight.bold, color: color.text }}
        >
          <FormattedMessage id="password.change.title" />
        </h2>

        {error && (
          <div
            role="alert"
            style={{
              backgroundColor: color.errorBg,
              color: color.error,
              padding: '12px 16px',
              borderRadius: 10,
              marginBottom: 16,
              fontSize: text.base,
              fontWeight: weight.medium,
            }}
          >
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div>
            <label htmlFor="current-password" style={labelStyle}>
              <FormattedMessage id="password.change.current" />
            </label>
            <input
              id="current-password"
              type="password"
              autoComplete="current-password"
              required
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              style={inputStyle}
              data-testid="current-password-input"
            />
          </div>

          <div>
            <label htmlFor="new-password" style={labelStyle}>
              <FormattedMessage id="password.change.new" />
            </label>
            <input
              id="new-password"
              type="password"
              autoComplete="new-password"
              required
              minLength={12}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              style={inputStyle}
              data-testid="new-password-input"
            />
            <span style={{ fontSize: text.xs, color: color.textMuted, marginTop: 2, display: 'block' }}>
              <FormattedMessage id="password.change.minLength" />
            </span>
          </div>

          <div>
            <label htmlFor="confirm-password" style={labelStyle}>
              <FormattedMessage id="password.change.confirm" />
            </label>
            <input
              id="confirm-password"
              type="password"
              autoComplete="new-password"
              required
              minLength={12}
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              style={inputStyle}
              data-testid="confirm-password-input"
            />
          </div>

          <div style={{ display: 'flex', gap: 10, marginTop: 8 }}>
            <button
              type="button"
              onClick={onClose}
              style={{
                flex: 1,
                padding: '12px 16px',
                backgroundColor: color.bgTertiary,
                color: color.textSecondary,
                border: `1px solid ${color.border}`,
                borderRadius: 10,
                fontSize: text.base,
                fontWeight: weight.semibold,
                cursor: 'pointer',
                minHeight: 44,
              }}
            >
              <FormattedMessage id="password.cancel" />
            </button>
            <button
              type="submit"
              disabled={submitting}
              style={{
                flex: 1,
                padding: '12px 16px',
                backgroundColor: color.primary,
                color: color.textInverse,
                border: 'none',
                borderRadius: 10,
                fontSize: text.base,
                fontWeight: weight.bold,
                cursor: submitting ? 'not-allowed' : 'pointer',
                minHeight: 44,
                opacity: submitting ? 0.7 : 1,
              }}
              data-testid="change-password-submit"
            >
              {submitting
                ? <FormattedMessage id="password.submitting" />
                : <FormattedMessage id="password.change.submit" />}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
