import { FormattedMessage } from 'react-intl';
import { color } from '../theme/colors';
import { text } from '../theme/typography';

/**
 * Non-dismissable disclaimer rendered adjacent to any UI surface that
 * displays a shelter's criminal record policy fields
 * (`criminal_record_policy`, `accepts_felonies`, `excluded_offense_types`).
 *
 * Required by transitional-reentry-support task 7.1 (design D6,
 * internal legal review 2026-04-28). The CI guard at
 * `scripts/ci/check-criminal-record-disclaimer-co-rendering.sh` enforces
 * the co-rendering requirement: any frontend file that names one of
 * those tokens in non-comment code MUST also render
 * `<CriminalRecordPolicyDisclaimer>`. Reword the disclaimer text only via
 * a Casey re-review (the i18n strings live in
 * `openspec/changes/transitional-reentry-support/i18n-legal-review-strings.md`).
 *
 * <p>ARIA / accessibility:
 * <ul>
 *   <li>{@code role="note"} — passive informational note, NOT
 *       {@code role="alert"}. The disclaimer is always present; it is
 *       not a state change to announce. Screen readers announce notes
 *       in reading order, which is what we want.</li>
 *   <li>Non-dismissable (no close button) — the disclaimer is part of
 *       the data, not an overlay.</li>
 *   <li>Visible at 400% browser zoom and in dark mode with passing
 *       WCAG AA contrast — uses `color.warningBg` (subtle background)
 *       + `color.warning` (high-contrast text) which both adapt to
 *       dark mode via CSS custom properties.</li>
 * </ul>
 *
 * <p>VAWA addendum (§7.2): when the prop {@code vawaProtectionsApply}
 * is true, an additional paragraph renders the
 * `shelter.vawaNoteDisclaimer` key. The two strings are written so
 * they read coherently in either order; we render base first, VAWA
 * second so navigators encounter the universal-applicability note
 * before the case-specific override.
 */
export interface CriminalRecordPolicyDisclaimerProps {
  /**
   * Pass `true` when the shelter's `vawa_protections_apply` flag is
   * true. Renders the additional VAWA note alongside the base
   * disclaimer.
   */
  vawaProtectionsApply?: boolean;
}

const containerStyle: React.CSSProperties = {
  backgroundColor: color.warningBg,
  color: color.warning,
  border: `1px solid ${color.warning}`,
  borderRadius: 6,
  padding: '12px 16px',
  fontSize: text.sm,
  // Reasonable inline spacing — leaves room for surrounding form
  // controls without crowding. Margin-block lets parent layouts override
  // via flex gap if they prefer.
  marginBlock: 12,
};

const vawaParagraphStyle: React.CSSProperties = {
  marginTop: 8,
  marginBottom: 0,
};

export function CriminalRecordPolicyDisclaimer({
  vawaProtectionsApply,
}: CriminalRecordPolicyDisclaimerProps) {
  return (
    <div
      role="note"
      data-testid="criminal-record-policy-disclaimer"
      style={containerStyle}
    >
      <FormattedMessage id="shelter.criminalRecordPolicyDisclaimer" />
      {vawaProtectionsApply ? (
        <p
          data-testid="criminal-record-policy-disclaimer-vawa"
          style={vawaParagraphStyle}
        >
          <FormattedMessage id="shelter.vawaNoteDisclaimer" />
        </p>
      ) : null}
    </div>
  );
}
