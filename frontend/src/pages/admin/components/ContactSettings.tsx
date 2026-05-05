import { useState, useEffect, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

import { AuthContext } from '../../../auth/AuthContext';

/**
 * Pure helper extracted for Vitest coverage (codebase convention — same
 * pattern as {@code parseDvPolicyError} in DvPolicySettings.tsx). Inspects
 * an error thrown by the contact-email PATCH endpoint and returns the
 * shape the caller should render:
 *
 * <ul>
 *   <li>{@code dvPolicyForbidden} — backend rejected a non-empty PATCH on a
 *       DV-flagged tenant ({@code errorCode === "tenant.contactEmail.dvPolicyForbidden"});
 *       caller renders the localized {@code admin.contactEmail.dvPolicyForbidden}
 *       message.</li>
 *   <li>{@code beanValidation} — Bean Validation 400 with {@code context.detail}
 *       set; caller surfaces the boundary error verbatim (preserves the
 *       server-side detail message).</li>
 *   <li>{@code generic} — any other error; caller renders a generic
 *       localized {@code admin.contactEmail.saveError} fallback.</li>
 * </ul>
 *
 * <p>info-email-contact §5.6 / §5.9.
 */
export type ContactEmailErrorParse =
    | { kind: 'dvPolicyForbidden' }
    | { kind: 'beanValidation'; detail: string }
    | { kind: 'generic'; message?: string };

export function parseContactEmailError(err: unknown): ContactEmailErrorParse {
    if (!(err instanceof ApiError)) {
        return { kind: 'generic' };
    }
    const errorCode = err.context?.errorCode as string | undefined;
    if (errorCode === 'tenant.contactEmail.dvPolicyForbidden') {
        return { kind: 'dvPolicyForbidden' };
    }
    const detail = err.context?.detail;
    if (typeof detail === 'string' && detail.length > 0) {
        return { kind: 'beanValidation', detail };
    }
    return { kind: 'generic', message: err.message };
}

const TOAST_DISMISS_MS = 4000;

/**
 * info-email-contact §5.6 — admin panel section letting a COC_ADMIN read
 * + update the per-tenant contact email surfaced through the public
 * {@code GET /api/v1/public/contact-info} endpoint.
 *
 * <p>Mirrors {@link ReservationSettings} in shape: GET tenant config on
 * mount, render an input + Save button, PATCH the dedicated
 * {@code /api/v1/admin/tenants/{id}/contact-email} endpoint on submit,
 * surface success / error / load-failure states with the same banner +
 * toast pattern.
 *
 * <p><b>DV-policy gating (§5.6):</b> the input field and Save button are
 * disabled when {@code tenant.config.dv_policy_enabled === true}. A
 * localized note explains why and points the operator to disable
 * DV-shelter operations first if they need to set a per-tenant override.
 * The backend allows empty-string PATCH even on DV-flagged tenants (§3
 * design — operator escape hatch), but the admin UI disables it to
 * prevent accidental edits; an operator who needs to clear a stale
 * pre-DV-flag value must (1) flip DV off, (2) clear contact-email, (3)
 * flip DV back on.
 *
 * <p><b>Empty-string clearing semantics:</b> when not DV-disabled, an
 * empty input + Save submits {@code {email: ""}} which the backend treats
 * as "remove the per-tenant override and inherit the platform default."
 * The success toast keys on whether the new value is empty (cleared) vs
 * non-empty (set) so the operator sees what actually happened.
 */
export function ContactSettings() {
    const intl = useIntl();
    const { user } = useContext(AuthContext);
    const tenantId = user?.tenantId;

    const [email, setEmail] = useState('');
    const [dvPolicyEnabled, setDvPolicyEnabled] = useState(false);
    const [loaded, setLoaded] = useState(false);
    const [loadFailed, setLoadFailed] = useState(false);
    const [saving, setSaving] = useState(false);
    const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

    useEffect(() => {
        if (!tenantId) return;
        (async () => {
            try {
                const config = await api.get<Record<string, unknown>>(`/api/v1/tenants/${tenantId}/config`);
                if (config && typeof config === 'object') {
                    // contact.email lives at config.contact.email (nested
                    // object); same shape returned by the backend
                    // TenantService.getConfig path.
                    const contact = config.contact;
                    if (contact && typeof contact === 'object' && 'email' in contact) {
                        const value = (contact as Record<string, unknown>).email;
                        setEmail(typeof value === 'string' ? value : '');
                    }
                    if ('dv_policy_enabled' in config) {
                        setDvPolicyEnabled(Boolean(config.dv_policy_enabled));
                    }
                }
                setLoaded(true);
            } catch {
                setLoadFailed(true);
                setLoaded(true);
            }
        })();
    }, [tenantId]);

    // Auto-dismiss success messages so the panel doesn't accumulate stale
    // state. Errors stay visible until the next save attempt — same posture
    // as ReservationSettings.
    useEffect(() => {
        if (message?.type !== 'success') return undefined;
        const t = setTimeout(() => setMessage(null), TOAST_DISMISS_MS);
        return () => clearTimeout(t);
    }, [message]);

    const handleSave = async () => {
        if (!tenantId) return;
        // Defense-in-depth: button is disabled in these states but a
        // direct invocation (e.g., via dev console) shouldn't bypass.
        if (loadFailed) return;
        if (dvPolicyEnabled) return;

        setSaving(true);
        setMessage(null);
        try {
            await api.patch(`/api/v1/admin/tenants/${tenantId}/contact-email`, { email });
            const cleared = email.trim().length === 0;
            setMessage({
                type: 'success',
                text: cleared
                    ? intl.formatMessage({ id: 'admin.contactEmail.savedCleared' })
                    : intl.formatMessage(
                        { id: 'admin.contactEmail.savedWithValue' },
                        { email },
                    ),
            });
        } catch (err: unknown) {
            const parsed = parseContactEmailError(err);
            let messageText: string;
            if (parsed.kind === 'dvPolicyForbidden') {
                messageText = intl.formatMessage({ id: 'admin.contactEmail.dvPolicyForbidden' });
            } else if (parsed.kind === 'beanValidation') {
                messageText = parsed.detail;
            } else {
                messageText = parsed.message
                    || intl.formatMessage({ id: 'admin.contactEmail.saveError' });
            }
            setMessage({ type: 'error', text: messageText });
        } finally {
            setSaving(false);
        }
    };

    if (!loaded) return null;

    const inputDisabled = dvPolicyEnabled || loadFailed || saving;
    const saveDisabled = inputDisabled;

    return (
        <div
            style={{
                background: color.bg,
                borderRadius: 12,
                padding: 16,
                marginBottom: 16,
                border: `1px solid ${color.border}`,
                boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
            }}
            data-testid="contact-settings"
        >
            {loadFailed && (
                <div
                    role="alert"
                    data-testid="contact-email-load-failed"
                    style={{
                        marginBottom: 12,
                        padding: '10px 14px',
                        borderRadius: 8,
                        backgroundColor: color.errorBg,
                        color: color.error,
                        border: `1px solid ${color.errorBorder}`,
                        fontSize: text.sm,
                        fontWeight: weight.semibold,
                    }}
                >
                    <FormattedMessage id="admin.contactEmail.loadFailed" />
                </div>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                <label
                    htmlFor="contact-email-input"
                    style={{ fontSize: text.base, fontWeight: weight.semibold, color: color.text }}
                >
                    <FormattedMessage id="admin.contactEmail.label" />
                </label>
                <input
                    id="contact-email-input"
                    type="email"
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    aria-label={intl.formatMessage({ id: 'admin.contactEmail.label' })}
                    aria-disabled={inputDisabled}
                    disabled={inputDisabled}
                    data-testid="contact-email-input"
                    placeholder="info@example.org"
                    style={{
                        flex: '1 1 220px',
                        minWidth: 220,
                        padding: '8px 12px',
                        borderRadius: 8,
                        border: `2px solid ${color.border}`,
                        fontSize: text.base,
                        minHeight: 44,
                        backgroundColor: inputDisabled ? color.bgSecondary : color.bg,
                        color: color.text,
                    }}
                />
                <button
                    onClick={handleSave}
                    disabled={saveDisabled}
                    data-testid="contact-email-save"
                    style={{
                        padding: '8px 16px',
                        backgroundColor: saveDisabled ? color.borderMedium : color.primary,
                        color: color.textInverse,
                        border: 'none',
                        borderRadius: 8,
                        fontSize: text.sm,
                        fontWeight: weight.bold,
                        cursor: saveDisabled ? 'not-allowed' : 'pointer',
                        minHeight: 44,
                    }}
                >
                    {saving ? '...' : intl.formatMessage({ id: 'common.save' })}
                </button>
                {message && (
                    <span
                        aria-live="polite"
                        role={message.type === 'error' ? 'alert' : 'status'}
                        data-testid={`contact-email-${message.type}`}
                        style={{
                            fontSize: text.sm,
                            color: message.type === 'success' ? color.success : color.error,
                            fontWeight: weight.semibold,
                        }}
                    >
                        {message.text}
                    </span>
                )}
            </div>
            {dvPolicyEnabled ? (
                <p
                    data-testid="contact-email-dv-policy-disabled"
                    style={{
                        fontSize: text.xs,
                        color: color.textTertiary,
                        margin: '8px 0 0',
                        fontStyle: 'italic',
                    }}
                >
                    <FormattedMessage id="admin.contactEmail.dvPolicyDisabled" />
                </p>
            ) : (
                <p style={{ fontSize: text.xs, color: color.textTertiary, margin: '8px 0 0' }}>
                    <FormattedMessage id="admin.contactEmail.description" />
                </p>
            )}
        </div>
    );
}
