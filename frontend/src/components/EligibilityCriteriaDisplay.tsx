import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../theme/colors';
import { text, weight } from '../theme/typography';
import { CriminalRecordPolicyDisclaimer } from './CriminalRecordPolicyDisclaimer';
import type { EligibilityCriteria } from '../types/eligibilityCriteria';

/**
 * transitional-reentry-support slice 4 §10.4 + §10.7. Read-only display
 * of `eligibility_criteria` for the OutreachSearch expanded shelter
 * modal. Renders {@link CriminalRecordPolicyDisclaimer} adjacent to
 * the criminal_record_policy fields per design D6 (Casey-reviewed
 * legal posture) — same co-rendering rule the §7 CI guard enforces.
 *
 * <p>Empty-state semantics (§10.4): every field renders "Not specified"
 * when null/empty, NEVER omitted silently. An operator reading a
 * shelter card needs to know the difference between "no eligibility
 * data" and "data was somehow blank" — both surface the same way to
 * the human, but the per-field "Not specified" rendering keeps the
 * data shape consistent across all shelters.
 */
export interface EligibilityCriteriaDisplayProps {
  value: EligibilityCriteria | null;
}

export function EligibilityCriteriaDisplay({ value }: EligibilityCriteriaDisplayProps) {
  const intl = useIntl();
  const notSpecified = intl.formatMessage({ id: 'common.notSpecified' });

  const crp = value?.criminal_record_policy || {};
  const programReqs = value?.program_requirements || [];
  const docsReqs = value?.documentation_required || [];
  const intakeHours = value?.intake_hours || '';

  const acceptsFeloniesLabel = (() => {
    if (crp.accepts_felonies === true) return 'Yes';
    if (crp.accepts_felonies === false) return 'No';
    return notSpecified;
  })();

  const fieldRow: React.CSSProperties = {
    display: 'flex', justifyContent: 'space-between', gap: 12,
    padding: '6px 0',
  };
  const fieldLabel: React.CSSProperties = {
    fontSize: text.sm, fontWeight: weight.semibold, color: color.textTertiary,
  };
  const fieldValue: React.CSSProperties = {
    fontSize: text.sm, color: color.text, textAlign: 'right',
    maxWidth: '60%',
  };
  const emptyValue: React.CSSProperties = {
    ...fieldValue, color: color.textMuted, fontStyle: 'italic',
  };

  const renderArrayField = (values: string[]) => {
    if (values.length === 0) return <span style={emptyValue}>{notSpecified}</span>;
    return (
      <span style={fieldValue}>
        {values.join(', ')}
      </span>
    );
  };

  return (
    <div data-testid="eligibility-criteria-display">
      {/* Disclaimer FIRST — same DOM-order discipline as the form section.
          Renders the VAWA addendum when the shelter has vawa_protections_apply=true. */}
      <CriminalRecordPolicyDisclaimer
        vawaProtectionsApply={crp.vawa_protections_apply === true}
      />

      {/* Criminal record policy — per Casey's review, presented as a
          terse Yes/No/Not-specified summary; navigators deciding in
          ~3 seconds need pattern-recognizable values, not paragraphs. */}
      <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.text, marginTop: 12, marginBottom: 4 }}>
        <FormattedMessage id="shelter.eligibility.criminalRecordPolicy" />
      </h4>
      <div style={fieldRow}>
        <span style={fieldLabel}><FormattedMessage id="shelter.acceptsFelonies" /></span>
        <span style={crp.accepts_felonies == null ? emptyValue : fieldValue}>{acceptsFeloniesLabel}</span>
      </div>
      <div style={fieldRow}>
        <span style={fieldLabel}><FormattedMessage id="shelter.excludedOffenseTypes" /></span>
        {(crp.excluded_offense_types && crp.excluded_offense_types.length > 0) ? (
          <span style={fieldValue}>
            {crp.excluded_offense_types
              .map((ot) => intl.formatMessage({ id: `offenseType.${ot}` }))
              .join(', ')}
          </span>
        ) : (
          <span style={emptyValue}>{notSpecified}</span>
        )}
      </div>
      {crp.notes && crp.notes.trim() !== '' ? (
        <div style={{ marginTop: 6 }}>
          <span style={fieldLabel}>
            <FormattedMessage id="shelter.eligibility.notes" />:
          </span>{' '}
          <span style={{ fontSize: text.sm, color: color.text }}>{crp.notes}</span>
        </div>
      ) : null}

      {/* Operational fields */}
      <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.text, marginTop: 12, marginBottom: 4 }}>
        <FormattedMessage id="shelter.eligibility.programRequirements" />
      </h4>
      <div>{renderArrayField(programReqs)}</div>

      <h4 style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.text, marginTop: 12, marginBottom: 4 }}>
        <FormattedMessage id="shelter.eligibility.documentationRequired" />
      </h4>
      <div>{renderArrayField(docsReqs)}</div>

      <div style={fieldRow}>
        <span style={fieldLabel}><FormattedMessage id="shelter.eligibility.intakeHours" /></span>
        {intakeHours ? (
          <span style={fieldValue}>{intakeHours}</span>
        ) : (
          <span style={emptyValue}>{notSpecified}</span>
        )}
      </div>
    </div>
  );
}
