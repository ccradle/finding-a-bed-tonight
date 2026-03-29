import { useState, useEffect, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';

interface User {
  id: string;
  email: string;
  displayName: string;
  roles: string[];
  dvAccess: boolean;
  status: string;
}

interface UserEditDrawerProps {
  user: User | null;
  onClose: () => void;
  onSaved: () => void;
}

const AVAILABLE_ROLES = ['OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN'];

export function UserEditDrawer({ user, onClose, onSaved }: UserEditDrawerProps) {
  const intl = useIntl();
  const drawerRef = useRef<HTMLDivElement>(null);
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [roles, setRoles] = useState<string[]>([]);
  const [dvAccess, setDvAccess] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Confirmation dialog for deactivation
  const [showDeactivateConfirm, setShowDeactivateConfirm] = useState(false);
  const [statusChanging, setStatusChanging] = useState(false);

  // Populate form when user changes
  useEffect(() => {
    if (user) {
      setDisplayName(user.displayName || '');
      setEmail(user.email || '');
      setRoles([...user.roles]);
      setDvAccess(user.dvAccess);
      setError(null);
      setSuccess(null);
      setShowDeactivateConfirm(false);
    }
  }, [user]);

  // Escape to close
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape' && user) onClose();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [user, onClose]);

  if (!user) return null;

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    setSuccess(null);
    try {
      await api.put(`/api/v1/users/${user.id}`, {
        displayName,
        email,
        roles,
        dvAccess,
      });
      setSuccess(intl.formatMessage({ id: 'admin.user.saved' }));
      onSaved();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'admin.user.saveError' }));
    } finally {
      setSaving(false);
    }
  };

  const handleStatusChange = async (newStatus: string) => {
    setStatusChanging(true);
    setError(null);
    try {
      await api.patch(`/api/v1/users/${user.id}/status`, { status: newStatus });
      setShowDeactivateConfirm(false);
      onSaved();
      onClose();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'admin.user.saveError' }));
    } finally {
      setStatusChanging(false);
    }
  };

  const toggleRole = (role: string) => {
    setRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role]
    );
  };

  const isDeactivated = user.status === 'DEACTIVATED';

  return (
    <>
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{
          position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.3)', zIndex: 1000,
        }}
      />
      {/* Drawer */}
      <div
        ref={drawerRef}
        role="dialog"
        aria-label={intl.formatMessage({ id: 'admin.user.editTitle' })}
        data-testid="user-edit-drawer"
        style={{
          position: 'fixed', top: 0, right: 0, bottom: 0, width: '400px', maxWidth: '90vw',
          backgroundColor: '#ffffff', boxShadow: '-4px 0 20px rgba(0,0,0,0.15)',
          zIndex: 1001, display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        {/* Header */}
        <div style={{
          padding: '16px 20px', borderBottom: '1px solid #e5e7eb',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <h2 style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold }}>
            <FormattedMessage id="admin.user.editTitle" />
          </h2>
          <button onClick={onClose} aria-label="Close" style={{
            background: 'none', border: 'none', fontSize: '20px', cursor: 'pointer', color: '#6b7280',
          }}>×</button>
        </div>

        {/* Status badge */}
        <div style={{ padding: '12px 20px', borderBottom: '1px solid #f3f4f6' }}>
          <span style={{
            display: 'inline-block', padding: '4px 10px', borderRadius: 12,
            fontSize: text.xs, fontWeight: weight.semibold,
            backgroundColor: isDeactivated ? '#fef2f2' : '#f0fdf4',
            color: isDeactivated ? '#dc2626' : '#166534',
          }}>
            {isDeactivated
              ? intl.formatMessage({ id: 'admin.user.statusDeactivated' })
              : intl.formatMessage({ id: 'admin.user.statusActive' })}
          </span>
        </div>

        {/* Form */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px' }}>
          {/* Display Name */}
          <label style={{ display: 'block', marginBottom: 16 }}>
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: '#374151', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="admin.displayName" />
            </span>
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              data-testid="user-edit-displayName"
              style={{
                width: '100%', padding: '10px 12px', border: '1px solid #d1d5db',
                borderRadius: 6, fontSize: text.base, boxSizing: 'border-box',
              }}
            />
          </label>

          {/* Email */}
          <label style={{ display: 'block', marginBottom: 16 }}>
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: '#374151', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="admin.email" />
            </span>
            <input
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              data-testid="user-edit-email"
              style={{
                width: '100%', padding: '10px 12px', border: '1px solid #d1d5db',
                borderRadius: 6, fontSize: text.base, boxSizing: 'border-box',
              }}
            />
          </label>

          {/* Roles */}
          <fieldset style={{ border: 'none', padding: 0, marginBottom: 16 }}>
            <legend style={{ fontSize: text.sm, fontWeight: weight.semibold, color: '#374151', marginBottom: 8 }}>
              <FormattedMessage id="admin.roles" />
            </legend>
            {AVAILABLE_ROLES.map((role) => (
              <label key={role} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={roles.includes(role)}
                  onChange={() => toggleRole(role)}
                  data-testid={`user-edit-role-${role}`}
                  style={{ width: 18, height: 18 }}
                />
                <span style={{ fontSize: text.sm }}>{role.replace('_', ' ')}</span>
              </label>
            ))}
          </fieldset>

          {/* DV Access */}
          <label style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 20, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={dvAccess}
              onChange={(e) => setDvAccess(e.target.checked)}
              data-testid="user-edit-dvAccess"
              style={{ width: 18, height: 18 }}
            />
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: '#374151' }}>
              <FormattedMessage id="admin.dvAccess" />
            </span>
          </label>

          {/* Error / Success */}
          {error && (
            <div style={{ backgroundColor: '#fef2f2', color: '#dc2626', padding: '10px 14px', borderRadius: 8, marginBottom: 12, fontSize: text.sm }}>
              {error}
            </div>
          )}
          {success && (
            <div style={{ backgroundColor: '#f0fdf4', color: '#166534', padding: '10px 14px', borderRadius: 8, marginBottom: 12, fontSize: text.sm }}>
              {success}
            </div>
          )}
        </div>

        {/* Footer actions */}
        <div style={{ padding: '16px 20px', borderTop: '1px solid #e5e7eb', display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <button
            onClick={handleSave}
            disabled={saving}
            data-testid="user-edit-save"
            style={{
              flex: 1, padding: '10px 16px', backgroundColor: '#1a56db', color: '#ffffff',
              border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: text.sm,
              fontWeight: weight.semibold, minHeight: 44, opacity: saving ? 0.6 : 1,
            }}
          >
            {saving ? '...' : <FormattedMessage id="common.save" />}
          </button>

          {isDeactivated ? (
            <button
              onClick={() => handleStatusChange('ACTIVE')}
              disabled={statusChanging}
              data-testid="user-reactivate-button"
              style={{
                padding: '10px 16px', backgroundColor: '#047857', color: '#ffffff',
                border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: text.sm,
                fontWeight: weight.semibold, minHeight: 44,
              }}
            >
              <FormattedMessage id="admin.user.reactivate" />
            </button>
          ) : (
            <button
              onClick={() => setShowDeactivateConfirm(true)}
              data-testid="user-deactivate-button"
              style={{
                padding: '10px 16px', backgroundColor: 'transparent', color: '#dc2626',
                border: '1px solid #dc2626', borderRadius: 6, cursor: 'pointer',
                fontSize: text.sm, fontWeight: weight.semibold, minHeight: 44,
              }}
            >
              <FormattedMessage id="admin.user.deactivate" />
            </button>
          )}
        </div>

        {/* Deactivation confirmation dialog */}
        {showDeactivateConfirm && (
          <div style={{
            position: 'absolute', inset: 0, backgroundColor: 'rgba(0,0,0,0.4)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1002,
          }}>
            <div
              role="alertdialog"
              aria-label={intl.formatMessage({ id: 'admin.user.deactivateConfirmTitle' })}
              data-testid="deactivate-confirm-dialog"
              style={{
                backgroundColor: '#ffffff', borderRadius: 12, padding: '24px',
                maxWidth: 360, boxShadow: '0 8px 30px rgba(0,0,0,0.2)',
              }}
            >
              <h3 style={{ margin: '0 0 12px', fontSize: text.base, fontWeight: weight.bold }}>
                <FormattedMessage id="admin.user.deactivateConfirmTitle" />
              </h3>
              <p style={{ margin: '0 0 20px', fontSize: text.sm, color: '#6b7280' }}>
                <FormattedMessage id="admin.user.deactivateConfirmMessage" values={{ name: user.displayName }} />
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setShowDeactivateConfirm(false)}
                  style={{
                    padding: '8px 16px', backgroundColor: 'transparent', border: '1px solid #d1d5db',
                    borderRadius: 6, cursor: 'pointer', fontSize: text.sm, minHeight: 40,
                  }}
                >
                  <FormattedMessage id="common.cancel" />
                </button>
                <button
                  onClick={() => handleStatusChange('DEACTIVATED')}
                  disabled={statusChanging}
                  data-testid="deactivate-confirm-button"
                  style={{
                    padding: '8px 16px', backgroundColor: '#dc2626', color: '#ffffff',
                    border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: text.sm,
                    fontWeight: weight.semibold, minHeight: 40,
                  }}
                >
                  {statusChanging ? '...' : <FormattedMessage id="admin.user.deactivate" />}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
