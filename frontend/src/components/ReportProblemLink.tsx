import { FormattedMessage } from 'react-intl';
import { useContactInfo } from '../contact/useContactInfo';
import { color } from '../theme/colors';

/**
 * issue-reporting-feedback §2 + §3 — "Report a Problem" link surface used
 * by the authenticated-app footer (Layout.tsx) and (via shared URL builder
 * helpers below) the mobile kebab "Help" item.
 *
 * <p>URL pre-fill is restricted to a compile-time allowlist
 * (warroom round 1 B1 + H1):
 * <ul>
 *   <li>{@code template=report-a-problem.yml} (constant)</li>
 *   <li>{@code labels=triage} (constant; {@code bug} is auto-applied by
 *       the GitHub form template, so duplicating it in the URL is wasted
 *       and would contradict spec {@code report-link-prefill-privacy})</li>
 *   <li>{@code fabt_version=&lt;major.minor&gt;} (when available; omitted
 *       when {@code appVersion} is null per warroom H5 null-state contract
 *       to avoid literal "null" in the URL)</li>
 * </ul>
 * The URL builder SHALL NOT read from {@code window.location},
 * {@code document.title}, route parameters, JWT claim values, user role,
 * tenant slug, current page path, user ID, or user email — these would
 * funnel PII into a world-readable GitHub issue body (Casey veto).
 *
 * <p><b>DV-policy gate</b> (warroom round 1 B3 + round 2 B1):
 * authenticated surfaces SHALL replace the GitHub-Issues link with a
 * {@code mailto:} from {@code useContactInfo()} when the calling tenant
 * has {@code dv_policy_enabled === true}. Survivors borrowing a
 * coordinator's screen — or the coordinator herself — must not have the
 * path-of-least-resistance be "type PII into a world-readable issue."
 * The DV flag is sourced from the {@code useContactInfo().tenant?.dvPolicyEnabled}
 * shape (not the JWT; OUTREACH and COORDINATOR roles cannot read
 * {@code /api/v1/tenants/{id}/config}, only the public
 * {@code /api/v1/public/contact-info} endpoint extended in §12).
 *
 * <p><b>JS-disabled fallback (§2.8):</b> a {@code &lt;noscript&gt;} block
 * lives in {@code frontend/index.html} outside {@code #root} so it
 * survives React mounting. React-rendered {@code &lt;noscript&gt;}
 * elements only fire when React fails to mount entirely, which is not the
 * spec scenario being defended.
 */

const GITHUB_ISSUES_REPO = 'https://github.com/ccradle/finding-a-bed-tonight/issues';

export function buildReportProblemUrl(appVersion: string | null): string {
    const params = new URLSearchParams();
    params.set('template', 'report-a-problem.yml');
    params.set('labels', 'triage');
    if (appVersion) {
        params.set('fabt_version', appVersion);
    }
    return `${GITHUB_ISSUES_REPO}/new?${params.toString()}`;
}

export function buildIssueChooserUrl(): string {
    return `${GITHUB_ISSUES_REPO}/new/choose`;
}

export function buildIssuesIndexUrl(): string {
    return GITHUB_ISSUES_REPO;
}

/**
 * Returns true when the authenticated user's tenant has DV-policy
 * enabled AND a mailto fallback is available. Callers route to
 * {@code mailto:{resolvedEmail}} in this case instead of the GitHub URL.
 *
 * <p>If the tenant has DV-policy enabled but no mailto is available
 * (e.g., platform contact email unset), this returns false — the GitHub
 * URL stays, because the alternative (no link at all) hurts UX. The
 * mailto-injection guard (§7.8) catches the case where a hostile
 * tenant-email response value tries to inject query params.
 */
export function shouldRouteToMailto(
    dvPolicyEnabled: boolean | undefined,
    resolvedEmail: string | null
): boolean {
    return dvPolicyEnabled === true && !!resolvedEmail;
}

/**
 * Footer-style Report-a-Problem link group. Renders:
 * <ul>
 *   <li>Primary link — GitHub Issues URL (DV-off) OR
 *       {@code mailto:{resolvedEmail}} (DV-on with mailto available)</li>
 *   <li>Secondary mailto link (separator + label) — ONLY on non-DV
 *       tenants when {@code resolvedEmail} is non-empty. On DV tenants,
 *       the primary IS the mailto, so no second link.</li>
 * </ul>
 *
 * <p>The secondary mailto is the "no GitHub account?" fallback path for
 * non-technical users (Rev. Monroe's volunteer coordinator persona).
 *
 * <p>WCAG: links are focusable, carry visible focus indicator via
 * existing browser default + theme color, and have descriptive
 * {@code <FormattedMessage>} text (not "click here").
 */
export function FooterReportProblemLink({ appVersion }: { appVersion: string | null }) {
    const { resolvedEmail, tenant } = useContactInfo();
    const dvRoutesToMailto = shouldRouteToMailto(tenant?.dvPolicyEnabled, resolvedEmail);

    const primaryHref = dvRoutesToMailto && resolvedEmail
        ? `mailto:${resolvedEmail}`
        : buildReportProblemUrl(appVersion);

    const showSecondaryMailto = !dvRoutesToMailto && !!resolvedEmail;

    const linkStyle: React.CSSProperties = {
        color: color.textTertiary,
        textDecoration: 'underline',
    };

    return (
        <span data-testid="footer-feedback-group">
            <a
                href={primaryHref}
                target={dvRoutesToMailto ? undefined : '_blank'}
                rel={dvRoutesToMailto ? undefined : 'noopener noreferrer'}
                data-testid="footer-report-problem"
                style={linkStyle}
            >
                <FormattedMessage id="feedback.reportProblem" />
            </a>
            {showSecondaryMailto && resolvedEmail && (
                <>
                    <span aria-hidden="true">{' · '}</span>
                    <a
                        href={`mailto:${resolvedEmail}`}
                        data-testid="footer-report-problem-email"
                        style={linkStyle}
                    >
                        <FormattedMessage id="feedback.reportProblem.email" />
                    </a>
                </>
            )}
        </span>
    );
}
