import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../theme/colors';
import { text, weight } from '../theme/typography';
import { CriminalRecordPolicyDisclaimer } from './CriminalRecordPolicyDisclaimer';
import { TagEditor } from './TagEditor';
import {
  type EligibilityCriteria,
  type CriminalRecordPolicy,
  OFFENSE_TYPES,
} from '../types/eligibilityCriteria';

/**
 * transitional-reentry-support slice 4 §10.1 / §10.2 / §10.3 / §10.5.
 *
 * <p>Editable section for `shelter_constraints.eligibility_criteria`.
 * Renders {@link CriminalRecordPolicyDisclaimer} FIRST in DOM order
 * (Casey-reviewed legal requirement: disclaimer must precede the data
 * it annotates so screen readers announce it before the criminal-record
 * policy fields). The §7 CI guard checks this co-rendering at the
 * file-scope, but rendering it first is the load-bearing UX guarantee.
 *
 * <p>Role gating (§10.1): visible to COC_ADMIN only — passed in via
 * the {@code visibleToRole} prop. The parent (ShelterForm) decides;
 * this component renders nothing when invisible.
 *
 * <p>Empty-vs-populated normalization happens in the parent on save,
 * not here. This component just propagates structured state changes
 * via `onChange`. The `normalizeEligibilityCriteria` helper at write
 * time decides whether to send the object or `null`.
 *
 * <p>Casey-reviewed `shelter.criminalRecordPolicyDisclaimer` and
 * `shelter.vawaNoteDisclaimer` (search-side) and
 * `shelter.vawaProtectionsApplyNote` (form-level — distinct from the
 * search-side disclaimer addendum). Don't cross-wire them.
 */
export interface EligibilityCriteriaSectionProps {
  value: EligibilityCriteria | null;
  onChange: (next: EligibilityCriteria | null) => void;
  /**
   * §10.1 role gating. Parent decides; component renders nothing when
   * false. (Cleaner than wrapping every render site in a conditional —
   * keeps the gating decision near the role check.)
   */
  visible: boolean;
}

export function EligibilityCriteriaSection({
  value,
  onChange,
  visible,
}: EligibilityCriteriaSectionProps) {
  const intl = useIntl();
  if (!visible) return null;

  // Local destructuring for read clarity; we still call onChange with
  // the FULL EligibilityCriteria object so the parent stays in
  // structured-state-only mode.
  const crp: CriminalRecordPolicy = value?.criminal_record_policy || {};
  const programReqs = value?.program_requirements || [];
  const docsReqs = value?.documentation_required || [];
  const intakeHours = value?.intake_hours || '';

  const updateCrp = (next: Partial<CriminalRecordPolicy>) => {
    onChange({
      ...(value || {}),
      criminal_record_policy: { ...crp, ...next },
    });
  };

  const updateField = <K extends keyof EligibilityCriteria>(
    key: K,
    next: EligibilityCriteria[K],
  ) => {
    onChange({ ...(value || {}), [key]: next });
  };

  const fieldStyle: React.CSSProperties = { marginBottom: 16 };
  const labelStyle: React.CSSProperties = {
    display: 'block', fontSize: text.sm, fontWeight: weight.semibold,
    color: color.textTertiary, marginBottom: 4,
  };
  const optionalSuffix = ` (${intl.formatMessage({ id: 'common.optional' })})`;

  return (
    <fieldset
      data-testid="eligibility-criteria-section"
      style={{
        border: `1px solid ${color.border}`, borderRadius: 8,
        padding: 16, marginTop: 24, marginBottom: 16,
      }}
    >
      <legend style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.text, padding: '0 8px' }}>
        <FormattedMessage id="shelter.eligibility.section" />
      </legend>

      {/* Disclaimer FIRST — Casey-reviewed legal requirement; precedes
          the criminal_record_policy fields in DOM/reading order. */}
      <CriminalRecordPolicyDisclaimer
        vawaProtectionsApply={crp.vawa_protections_apply === true}
      />

      {/* Criminal record policy section */}
      <h4 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginTop: 16, marginBottom: 8 }}>
        <FormattedMessage id="shelter.eligibility.criminalRecordPolicy" />
      </h4>

      {/* accepts_felonies — tri-state: true / false / unset (null). The
          tri-state matters because the H1 three-way evaluator
          differentiates explicit-false from absent-data. UI uses three
          radio buttons rather than a checkbox. */}
      <div style={fieldStyle}>
        <label style={labelStyle}>
          <FormattedMessage id="shelter.acceptsFelonies" />{optionalSuffix}
        </label>
        <div style={{ display: 'flex', gap: 12 }} role="radiogroup" aria-label={intl.formatMessage({ id: 'shelter.acceptsFelonies' })}>
          {[
            { value: true, label: 'Yes' },
            { value: false, label: 'No' },
            { value: null, label: intl.formatMessage({ id: 'common.notSpecified' }) },
          ].map((opt) => (
            <label key={String(opt.value)} style={{ display: 'flex', alignItems: 'center', gap: 4, cursor: 'pointer' }}>
              <input
                type="radio"
                name="accepts-felonies"
                data-testid={`accepts-felonies-${opt.value === null ? 'unset' : String(opt.value)}`}
                checked={(crp.accepts_felonies ?? null) === opt.value}
                onChange={() => updateCrp({ accepts_felonies: opt.value })}
              />
              <span style={{ fontSize: text.sm }}>{opt.label}</span>
            </label>
          ))}
        </div>
      </div>

      {/* excluded_offense_types multi-select — chip group of 6 controlled
          values. Slice 3 §6.3 i18n keys (offenseType.SEX_OFFENSE etc.). */}
      <div style={fieldStyle}>
        <label style={labelStyle}>
          <FormattedMessage id="shelter.excludedOffenseTypes" />{optionalSuffix}
        </label>
        <div
          data-testid="excluded-offense-types"
          role="group"
          aria-label={intl.formatMessage({ id: 'shelter.excludedOffenseTypes' })}
          style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}
        >
          {OFFENSE_TYPES.map((ot) => {
            const active = (crp.excluded_offense_types || []).includes(ot);
            return (
              <button
                key={ot}
                type="button"
                data-testid={`excluded-offense-types-${ot}`}
                aria-pressed={active}
                onClick={() => {
                  const list = crp.excluded_offense_types || [];
                  const next = active ? list.filter((x) => x !== ot) : [...list, ot];
                  updateCrp({ excluded_offense_types: next });
                }}
                style={{
                  padding: '6px 12px', borderRadius: 16,
                  border: `1.5px solid ${active ? color.primary : color.border}`,
                  backgroundColor: active ? color.primary : color.bg,
                  color: active ? color.textInverse : color.text,
                  fontSize: text.sm, fontWeight: weight.medium, cursor: 'pointer',
                  minHeight: 32,
                }}
              >
                {intl.formatMessage({ id: `offenseType.${ot}` })}
              </button>
            );
          })}
        </div>
      </div>

      {/* individualized_assessment toggle — checkbox is fine here
          (binary, no tri-state requirement; absent === false is OK). */}
      <div style={fieldStyle}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
          <input
            type="checkbox"
            data-testid="individualized-assessment-toggle"
            checked={crp.individualized_assessment === true}
            onChange={(e) => updateCrp({ individualized_assessment: e.target.checked || null })}
          />
          <span style={{ fontSize: text.sm }}>
            <FormattedMessage id="shelter.eligibility.individualizedAssessment" />{optionalSuffix}
          </span>
        </label>
      </div>

      {/* vawa_protections_apply — when true, the form-level note
          renders below to explain to admins what enabling this does
          (slice 3 §6.2 Casey-reviewed `shelter.vawaProtectionsApplyNote`).
          The disclaimer above ALSO reflects this state by rendering
          `shelter.vawaNoteDisclaimer` — that's the search-side note
          shown to navigators. The two are deliberately distinct
          strings (warroom M3). */}
      <div style={fieldStyle}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
          <input
            type="checkbox"
            data-testid="vawa-protections-apply-checkbox"
            checked={crp.vawa_protections_apply === true}
            onChange={(e) => updateCrp({ vawa_protections_apply: e.target.checked || null })}
          />
          <span style={{ fontSize: text.sm }}>
            <FormattedMessage id="shelter.eligibility.vawaProtectionsApply" />{optionalSuffix}
          </span>
        </label>
        {crp.vawa_protections_apply === true && (
          <p
            data-testid="vawa-protections-apply-note"
            style={{
              marginTop: 8, padding: '10px 12px', borderRadius: 6,
              backgroundColor: color.bgSecondary, color: color.textTertiary,
              fontSize: text.xs, lineHeight: 1.5,
            }}
          >
            <FormattedMessage id="shelter.vawaProtectionsApplyNote" />
          </p>
        )}
      </div>

      {/* notes textarea */}
      <div style={fieldStyle}>
        <label htmlFor="criminal-record-notes" style={labelStyle}>
          <FormattedMessage id="shelter.eligibility.notes" />{optionalSuffix}
        </label>
        <textarea
          id="criminal-record-notes"
          data-testid="criminal-record-notes"
          value={crp.notes || ''}
          onChange={(e) => updateCrp({ notes: e.target.value })}
          rows={3}
          maxLength={500}
          style={{
            width: '100%', padding: '8px 12px', borderRadius: 6,
            border: `1.5px solid ${color.border}`, fontSize: text.base,
            fontFamily: 'inherit', boxSizing: 'border-box', resize: 'vertical',
          }}
        />
      </div>

      {/* Operational fields */}
      <h4 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginTop: 16, marginBottom: 8 }}>
        <FormattedMessage id="shelter.eligibility.programRequirements" />{optionalSuffix}
      </h4>
      <div style={fieldStyle}>
        <TagEditor
          values={programReqs}
          onChange={(next) => updateField('program_requirements', next)}
          data-testid="program-requirements-tag-editor"
        />
      </div>

      <h4 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginTop: 16, marginBottom: 8 }}>
        <FormattedMessage id="shelter.eligibility.documentationRequired" />{optionalSuffix}
      </h4>
      <div style={fieldStyle}>
        <TagEditor
          values={docsReqs}
          onChange={(next) => updateField('documentation_required', next)}
          data-testid="documentation-required-tag-editor"
        />
      </div>

      <div style={fieldStyle}>
        <label htmlFor="intake-hours" style={labelStyle}>
          <FormattedMessage id="shelter.eligibility.intakeHours" />{optionalSuffix}
        </label>
        <input
          id="intake-hours"
          type="text"
          data-testid="intake-hours-input"
          value={intakeHours}
          onChange={(e) => updateField('intake_hours', e.target.value)}
          maxLength={200}
          placeholder={intl.formatMessage({ id: 'common.notSpecified' })}
          style={{
            width: '100%', padding: '8px 12px', borderRadius: 6,
            border: `1.5px solid ${color.border}`, fontSize: text.base, minHeight: 38,
            boxSizing: 'border-box',
          }}
        />
      </div>
    </fieldset>
  );
}
