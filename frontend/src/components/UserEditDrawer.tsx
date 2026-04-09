import { useState, useEffect, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

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

  // Assigned shelters (read-only view)
  const [assignedShelters, setAssignedShelters] = useState<Array<{ id: string; name: string }>>([]);

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
      // Fetch assigned shelters
      api.get<Array<{ id: string; name: string }>>(`/api/v1/users/${user.id}/shelters`)
        .then(setAssignedShelters)
        .catch(() => setAssignedShelters([]));
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
          backgroundColor: color.bg, boxShadow: '-4px 0 20px rgba(0,0,0,0.15)',
          zIndex: 1001, display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        {/* Header */}
        <div style={{
          padding: '16px 20px', borderBottom: `1px solid ${color.border}`,
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <h2 style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold }}>
            <FormattedMessage id="admin.user.editTitle" />
          </h2>
          <button onClick={onClose} aria-label="Close" style={{
            background: 'none', border: 'none', fontSize: text.xl, cursor: 'pointer', color: color.textMuted,
          }}>×</button>
        </div>

        {/* Status badge */}
        <div style={{ padding: '12px 20px', borderBottom: `1px solid ${color.bgTertiary}` }}>
          <span style={{
            display: 'inline-block', padding: '4px 10px', borderRadius: 12,
            fontSize: text.xs, fontWeight: weight.semibold,
            backgroundColor: isDeactivated ? color.errorBg : color.successBg,
            color: isDeactivated ? color.errorMid : color.success,
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
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: color.textSecondary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="admin.displayName" />
            </span>
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              data-testid="user-edit-displayName"
              style={{
                width: '100%', padding: '10px 12px', border: `1px solid ${color.borderMedium}`,
                borderRadius: 6, fontSize: text.base, boxSizing: 'border-box',
              }}
            />
          </label>

          {/* Email */}
          <label style={{ display: 'block', marginBottom: 16 }}>
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: color.textSecondary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="admin.email" />
            </span>
            <input
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              data-testid="user-edit-email"
              style={{
                width: '100%', padding: '10px 12px', border: `1px solid ${color.borderMedium}`,
                borderRadius: 6, fontSize: text.base, boxSizing: 'border-box',
              }}
            />
          </label>

          {/* Roles */}
          <fieldset style={{ border: 'none', padding: 0, marginBottom: 16 }}>
            <legend style={{ fontSize: text.sm, fontWeight: weight.semibold, color: color.textSecondary, marginBottom: 8 }}>
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
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold, color: color.textSecondary }}>
              <FormattedMessage id="admin.dvAccess" />
            </span>
          </label>

          {/* Assigned Shelters (read-only) */}
          <div style={{ marginBottom: 20 }}>
            <div style={{ fontSize: text.sm, fontWeight: weight.semibold, color: color.textSecondary, marginBottom: 8 }}>
              <FormattedMessage id="admin.assignedShelters" />
            </div>
            {assignedShelters.length === 0 ? (
              <p style={{ color: color.textMuted, fontSize: text.sm, margin: 0 }} data-testid="user-no-shelters">
                <FormattedMessage id="admin.noSheltersAssigned" />
              </p>
            ) : (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }} data-testid="user-assigned-shelters">
                {assignedShelters.map((s) => (
                  <a
                    key={s.id}
                    href={`/coordinator/shelters/${s.id}/edit?from=/admin`}
                    style={{
                      display: 'inline-block',
                      padding: '4px 10px',
                      borderRadius: 16,
                      fontSize: text.sm,
                      fontWeight: weight.medium,
                      color: color.primaryText,
                      backgroundColor: color.primaryLight,
                      border: `1px solid ${color.border}`,
                      textDecoration: 'none',
                      minHeight: 32,
                      lineHeight: '24px',
                    }}
                    data-testid={`user-shelter-chip-${s.id}`}
                  >
                    {s.name}
                  </a>
                ))}
              </div>
            )}
          </div>

          {/* Error / Success */}
          {error && (
            <div style={{ backgroundColor: color.errorBg, color: color.errorMid, padding: '10px 14px', borderRadius: 8, marginBottom: 12, fontSize: text.sm }}>
              {error}
            </div>
          )}
          {success && (
            <div style={{ backgroundColor: color.successBg, color: color.success, padding: '10px 14px', borderRadius: 8, marginBottom: 12, fontSize: text.sm }}>
              {success}
            </div>
          )}
        </div>

        {/* Footer actions */}
        <div style={{ padding: '16px 20px', borderTop: `1px solid ${color.border}`, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <button
            onClick={handleSave}
            disabled={saving}
            data-testid="user-edit-save"
            style={{
              flex: 1, padding: '10px 16px', backgroundColor: color.primary, color: color.textInverse,
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
                padding: '10px 16px', backgroundColor: color.success, color: color.textInverse,
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
                padding: '10px 16px', backgroundColor: 'transparent', color: color.errorMid,
                border: `1px solid ${color.errorMid}`, borderRadius: 6, cursor: 'pointer',
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
                backgroundColor: color.bg, borderRadius: 12, padding: '24px',
                maxWidth: 360, boxShadow: '0 8px 30px rgba(0,0,0,0.2)',
              }}
            >
              <h3 style={{ margin: '0 0 12px', fontSize: text.base, fontWeight: weight.bold }}>
                <FormattedMessage id="admin.user.deactivateConfirmTitle" />
              </h3>
              <p style={{ margin: '0 0 20px', fontSize: text.sm, color: color.textMuted }}>
                <FormattedMessage id="admin.user.deactivateConfirmMessage" values={{ name: user.displayName }} />
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setShowDeactivateConfirm(false)}
                  style={{
                    padding: '8px 16px', backgroundColor: 'transparent', border: `1px solid ${color.borderMedium}`,
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
                    padding: '8px 16px', backgroundColor: color.errorMid, color: color.bg,
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
