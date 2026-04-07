import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { UserEditDrawer } from '../../../components/UserEditDrawer';
import { color } from '../../../theme/colors';
import { text, weight, font } from '../../../theme/typography';
import { StatusBadge, RoleBadge, ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';
import type { User } from '../types';

const ROLE_OPTIONS = ['PLATFORM_ADMIN', 'COC_ADMIN', 'COORDINATOR', 'OUTREACH_WORKER'];

function UsersTab() {
  const intl = useIntl();
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formEmail, setFormEmail] = useState('');
  const [formDisplayName, setFormDisplayName] = useState('');
  const [formPassword, setFormPassword] = useState('');
  const [formRoles, setFormRoles] = useState<string[]>([]);
  const [formDvAccess, setFormDvAccess] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [editUser, setEditUser] = useState<User | null>(null);
  const [resetUser, setResetUser] = useState<User | null>(null);
  const [resetPassword, setResetPassword] = useState('');
  const [accessCodeUser, setAccessCodeUser] = useState<{ id: string; email: string } | null>(null);
  const [generatedCode, setGeneratedCode] = useState<string | null>(null);
  const [accessCodeLoading, setAccessCodeLoading] = useState(false);
  const [resetConfirm, setResetConfirm] = useState('');
  const [resetError, setResetError] = useState<string | null>(null);
  const [resetSubmitting, setResetSubmitting] = useState(false);
  const [resetSuccess, setResetSuccess] = useState<string | null>(null);

  const handleResetPassword = async () => {
    if (!resetUser) return;
    setResetError(null);
    if (resetPassword !== resetConfirm) {
      setResetError(intl.formatMessage({ id: 'password.change.mismatch' }));
      return;
    }
    if (resetPassword.length < 12) {
      setResetError(intl.formatMessage({ id: 'password.change.tooShort' }));
      return;
    }
    setResetSubmitting(true);
    try {
      await api.post(`/api/v1/users/${resetUser.id}/reset-password`, { newPassword: resetPassword });
      setResetSuccess(intl.formatMessage({ id: 'password.reset.success' }, { username: resetUser.displayName }));
      setResetUser(null);
      setResetPassword('');
      setResetConfirm('');
    } catch (err: unknown) {
      const apiErr = err as { status?: number; message?: string };
      if (apiErr.status === 409) {
        setResetError(intl.formatMessage({ id: 'password.reset.ssoOnly' }));
      } else {
        setResetError(apiErr.message || intl.formatMessage({ id: 'password.reset.error' }));
      }
    } finally {
      setResetSubmitting(false);
    }
  };

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<User[]>('/api/v1/users');
      setUsers(data || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchUsers(); }, [fetchUsers]);

  const toggleRole = (role: string) => {
    setFormRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role]
    );
  };

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post('/api/v1/users', {
        email: formEmail,
        displayName: formDisplayName,
        password: formPassword,
        roles: formRoles,
        dvAccess: formDvAccess,
      });
      setShowForm(false);
      setFormEmail('');
      setFormDisplayName('');
      setFormPassword('');
      setFormRoles([]);
      setFormDvAccess(false);
      await fetchUsers();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <button onClick={() => setShowForm(!showForm)} style={primaryBtnStyle}>
          <FormattedMessage id="admin.createUser" />
        </button>
      </div>

      {/* Create user form */}
      {showForm && (
        <div style={{
          padding: 20, border: `2px solid ${color.border}`, borderRadius: 14,
          marginBottom: 20, backgroundColor: color.bgSecondary,
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.email" />
              </label>
              <input value={formEmail} onChange={(e) => setFormEmail(e.target.value)}
                type="email" data-testid="create-user-email" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.displayName" />
              </label>
              <input value={formDisplayName} onChange={(e) => setFormDisplayName(e.target.value)}
                data-testid="create-user-name" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                Password
              </label>
              <input value={formPassword} onChange={(e) => setFormPassword(e.target.value)}
                type="password" data-testid="create-user-password" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.roles" />
              </label>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {ROLE_OPTIONS.map((role) => (
                  <button
                    key={role}
                    onClick={() => toggleRole(role)}
                    style={{
                      padding: '8px 14px', borderRadius: 8, border: `2px solid ${formRoles.includes(role) ? color.primary : color.border}`,
                      backgroundColor: formRoles.includes(role) ? color.bgHighlight : color.bg,
                      color: formRoles.includes(role) ? color.primaryText : color.textTertiary,
                      fontSize: text.sm, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 40,
                    }}
                  >{role}</button>
                ))}
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary }}>
                <FormattedMessage id="admin.dvAccess" />
              </label>
              <button
                onClick={() => setFormDvAccess(!formDvAccess)}
                style={{
                  padding: '6px 14px', borderRadius: 8, border: '2px solid',
                  borderColor: formDvAccess ? color.successBright : color.border,
                  backgroundColor: formDvAccess ? color.successBg : color.bg,
                  color: formDvAccess ? color.success : color.error,
                  fontSize: text.sm, fontWeight: weight.semibold, cursor: 'pointer',
                }}
              >
                {formDvAccess ? 'ON' : 'OFF'}
              </button>
            </div>
            <button onClick={handleCreate} disabled={submitting} data-testid="create-user-submit"
              style={{ ...primaryBtnStyle, width: '100%', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? '...' : <FormattedMessage id="admin.createUser" />}
            </button>
          </div>
        </div>
      )}

      {/* Users table */}
      {users.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}><FormattedMessage id="admin.email" /></th>
                <th style={thStyle}><FormattedMessage id="admin.displayName" /></th>
                <th style={thStyle}><FormattedMessage id="admin.roles" /></th>
                <th style={thStyle}><FormattedMessage id="admin.dvAccess" /></th>
                <th style={thStyle}><FormattedMessage id="admin.user.statusHeader" /></th>
                <th style={thStyle}></th>
              </tr>
            </thead>
            <tbody>
              {users.map((u, i) => (
                <tr key={u.id}>
                  <td style={tdStyle(i)}>{u.email}</td>
                  <td style={tdStyle(i)}>{u.displayName}</td>
                  <td style={tdStyle(i)}>
                    {u.roles.map((r) => <RoleBadge key={r} role={r} />)}
                  </td>
                  <td style={tdStyle(i)}>
                    <StatusBadge active={u.dvAccess} yesId="admin.active" noId="admin.inactive" />
                  </td>
                  <td style={tdStyle(i)}>
                    <StatusBadge
                      active={u.status !== 'DEACTIVATED'}
                      yesId="admin.user.statusActive"
                      noId="admin.user.statusDeactivated"
                    />
                  </td>
                  <td style={{ ...tdStyle(i), display: 'flex', gap: 6, flexWrap: 'nowrap' }}>
                    <button
                      onClick={() => setEditUser(u)}
                      data-testid={`edit-user-${u.email}`}
                      style={{
                        padding: '6px 12px',
                        backgroundColor: 'transparent',
                        color: color.primaryText,
                        border: `1px solid ${color.primaryText}`,
                        borderRadius: 6,
                        fontSize: text.xs,
                        fontWeight: weight.semibold,
                        cursor: 'pointer',
                        minHeight: 32,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <FormattedMessage id="admin.user.edit" />
                    </button>
                    <button
                      onClick={() => { setResetUser(u); setResetError(null); setResetSuccess(null); setResetPassword(''); setResetConfirm(''); }}
                      data-testid={`reset-password-${u.email}`}
                      style={{
                        padding: '6px 12px',
                        backgroundColor: 'transparent',
                        color: color.primaryText,
                        border: `1px solid ${color.primaryText}`,
                        borderRadius: 6,
                        fontSize: text.xs,
                        fontWeight: weight.semibold,
                        cursor: 'pointer',
                        minHeight: 32,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <FormattedMessage id="password.reset.button" />
                    </button>
                    <button
                      onClick={async () => {
                        setAccessCodeUser(u);
                        setGeneratedCode(null);
                        setAccessCodeLoading(true);
                        try {
                          const res = await api.post<{ code: string }>(`/api/v1/users/${u.id}/generate-access-code`, {});
                          setGeneratedCode(res.code);
                        } catch (err: unknown) {
                          const apiErr = err as { message?: string };
                          setGeneratedCode(null);
                          if (apiErr.message) setError(apiErr.message);
                        } finally {
                          setAccessCodeLoading(false);
                        }
                      }}
                      data-testid={`generate-access-code-${u.email}`}
                      style={{
                        padding: '6px 12px',
                        backgroundColor: 'transparent',
                        color: color.warning,
                        border: `1px solid ${color.warning}`,
                        borderRadius: 6,
                        fontSize: text.xs,
                        fontWeight: weight.semibold,
                        cursor: 'pointer',
                        minHeight: 32,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      Access Code
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Reset success message */}
      {resetSuccess && (
        <div style={{ backgroundColor: color.successBg, color: color.success, padding: '12px 16px', borderRadius: 10, marginTop: 12, fontSize: text.base, fontWeight: weight.medium }}>
          {resetSuccess}
        </div>
      )}

      {/* Reset Password Modal */}
      {resetUser && (
        <div
          style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9000 }}
          onClick={(e) => { if (e.target === e.currentTarget) setResetUser(null); }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="reset-password-title"
            style={{ background: color.bg, borderRadius: 16, padding: 32, maxWidth: 420, width: '90%', boxShadow: '0 8px 32px rgba(0,0,0,0.2)' }}
          >
            <h3 id="reset-password-title" style={{ margin: '0 0 20px', fontSize: text.xl, fontWeight: weight.bold, color: color.text }}>
              <FormattedMessage id="password.reset.title" values={{ username: resetUser.displayName }} />
            </h3>
            {resetError && (
              <div role="alert" style={{ backgroundColor: color.errorBg, color: color.error, padding: '12px 16px', borderRadius: 10, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium }}>
                {resetError}
              </div>
            )}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <label htmlFor="reset-new-password" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                  <FormattedMessage id="password.reset.new" />
                </label>
                <input id="reset-new-password" type="password" autoComplete="new-password" value={resetPassword} onChange={(e) => setResetPassword(e.target.value)}
                  style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box', color: color.text, fontWeight: weight.medium }}
                  data-testid="reset-new-password-input" />
                <span style={{ fontSize: text.xs, color: color.textMuted, marginTop: 2, display: 'block' }}><FormattedMessage id="password.change.minLength" /></span>
              </div>
              <div>
                <label htmlFor="reset-confirm-password" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                  <FormattedMessage id="password.reset.confirm" />
                </label>
                <input id="reset-confirm-password" type="password" autoComplete="new-password" value={resetConfirm} onChange={(e) => setResetConfirm(e.target.value)}
                  style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box', color: color.text, fontWeight: weight.medium }}
                  data-testid="reset-confirm-password-input" />
              </div>
              <div style={{ display: 'flex', gap: 10, marginTop: 8 }}>
                <button type="button" onClick={() => setResetUser(null)}
                  style={{ flex: 1, padding: '12px 16px', backgroundColor: color.bgTertiary, color: color.textSecondary, border: `1px solid ${color.border}`, borderRadius: 10, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44 }}>
                  <FormattedMessage id="password.cancel" />
                </button>
                <button type="button" onClick={handleResetPassword} disabled={resetSubmitting}
                  data-testid="reset-password-submit"
                  style={{ flex: 1, padding: '12px 16px', backgroundColor: color.primary, color: color.textInverse, border: 'none', borderRadius: 10, fontSize: text.base, fontWeight: weight.bold, cursor: resetSubmitting ? 'not-allowed' : 'pointer', minHeight: 44, opacity: resetSubmitting ? 0.7 : 1 }}>
                  {resetSubmitting ? <FormattedMessage id="password.submitting" /> : <FormattedMessage id="password.reset.submit" />}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Access Code Modal */}
      {accessCodeUser && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 1002,
          backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center',
        }} onClick={() => setAccessCodeUser(null)}>
          <div data-testid="access-code-modal" style={{
            backgroundColor: color.bg, borderRadius: 16, padding: 32, maxWidth: 400, width: '90%',
          }} onClick={e => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', fontSize: text.lg, fontWeight: weight.bold, color: color.text }}>
              Access Code for {accessCodeUser.email}
            </h3>
            {accessCodeLoading && <p style={{ color: color.textMuted }}>Generating...</p>}
            {generatedCode && (
              <div>
                <div data-testid="generated-access-code" style={{
                  padding: 16, borderRadius: 12, backgroundColor: color.bgTertiary,
                  fontFamily: font.mono, fontSize: text['2xl'], fontWeight: weight.extrabold,
                  textAlign: 'center', letterSpacing: '0.2em', color: color.text, marginBottom: 16,
                }}>
                  {generatedCode}
                </div>
                <p style={{ fontSize: text.xs, color: color.warning, fontWeight: weight.semibold, marginBottom: 12 }}>
                  This code expires in 15 minutes and can only be used once.
                  Communicate it verbally or by phone — do not send via email.
                </p>
              </div>
            )}
            <button onClick={() => setAccessCodeUser(null)} style={{
              width: '100%', padding: 10, borderRadius: 8, border: `2px solid ${color.border}`,
              backgroundColor: color.bg, color: color.text, fontSize: text.sm, fontWeight: weight.semibold,
              cursor: 'pointer', minHeight: 44,
            }}>
              Close
            </button>
          </div>
        </div>
      )}

      {/* User Edit Drawer */}
      <UserEditDrawer
        user={editUser}
        onClose={() => setEditUser(null)}
        onSaved={() => { fetchUsers(); setEditUser(null); }}
      />
    </div>
  );
}


export default UsersTab;
