/**
 * Structured shape of `shelter_constraints.eligibility_criteria` JSONB
 * (transitional-reentry-support design D1). Backend stores this as
 * opaque JSON string (`JsonString`); frontend parses on read and
 * serializes on write so the form can work in structured space.
 *
 * <p>Every field is optional/nullable per design D1 — most shelters
 * launch with the entire object null. The whole object should be
 * serialized as `null` when the operator hasn't populated any field;
 * see {@link normalizeEligibilityCriteria}. This is load-bearing for
 * the BedSearchService H1 three-way `acceptsFelonies` filter — branch
 * (c) (any-null path) only fires when the JSONB is literally null OR
 * missing the criminal_record_policy key.
 *
 * <p>{@code custom_tags} is in the schema but not surfaced as a UI
 * field in slice 4 §10.3. The parse/serialize layer round-trips it
 * untouched so a non-UI consumer (CSV import, future bulk-edit) can
 * populate it without losing data on a subsequent admin save (warroom
 * N1).
 */
export interface CriminalRecordPolicy {
  accepts_felonies?: boolean | null;
  excluded_offense_types?: string[];
  individualized_assessment?: boolean | null;
  vawa_protections_apply?: boolean | null;
  notes?: string;
}

export interface EligibilityCriteria {
  criminal_record_policy?: CriminalRecordPolicy | null;
  program_requirements?: string[];
  documentation_required?: string[];
  intake_hours?: string;
  custom_tags?: string[];
}

/**
 * Parse the backend's `JsonString` (stringified JSON or null) into a
 * structured object. Returns null on null/empty/parse-failure — same
 * fallback semantics as ShelterService.getActiveCounties' parse-failure
 * branch (defensive, prefer null over throwing).
 */
export function parseEligibilityCriteria(raw: string | null | undefined): EligibilityCriteria | null {
  if (!raw || raw.trim() === '' || raw.trim() === '{}') return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as EligibilityCriteria;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Normalize a structured EligibilityCriteria to the wire form. Returns
 * `null` when every field is at default — preserves design D1's "most
 * shelters launch with null" assumption AND keeps the BedSearchService
 * branch (c) reachable (warroom H1, slice 4 §10).
 *
 * <p>If ANY field is non-default, returns the populated object with
 * empty branches still elided so a partial save doesn't bloat the
 * payload.
 */
export function normalizeEligibilityCriteria(input: EligibilityCriteria | null | undefined): EligibilityCriteria | null {
  if (!input) return null;

  const crp = input.criminal_record_policy;
  const crpHasContent = !!crp && (
    crp.accepts_felonies != null
    || (crp.excluded_offense_types && crp.excluded_offense_types.length > 0)
    || crp.individualized_assessment != null
    || crp.vawa_protections_apply != null
    || (crp.notes && crp.notes.trim() !== '')
  );

  const hasProgramReqs = !!input.program_requirements && input.program_requirements.length > 0;
  const hasDocReqs = !!input.documentation_required && input.documentation_required.length > 0;
  const hasIntakeHours = !!input.intake_hours && input.intake_hours.trim() !== '';
  const hasCustomTags = !!input.custom_tags && input.custom_tags.length > 0;

  if (!crpHasContent && !hasProgramReqs && !hasDocReqs && !hasIntakeHours && !hasCustomTags) {
    return null;
  }

  // Build a populated object. Elide empty/null branches but preserve
  // any value the operator actually set (or that round-tripped from a
  // prior save / non-UI consumer).
  const out: EligibilityCriteria = {};
  if (crpHasContent && crp) {
    const cleanCrp: CriminalRecordPolicy = {};
    if (crp.accepts_felonies != null) cleanCrp.accepts_felonies = crp.accepts_felonies;
    if (crp.excluded_offense_types && crp.excluded_offense_types.length > 0) {
      cleanCrp.excluded_offense_types = crp.excluded_offense_types;
    }
    if (crp.individualized_assessment != null) cleanCrp.individualized_assessment = crp.individualized_assessment;
    if (crp.vawa_protections_apply != null) cleanCrp.vawa_protections_apply = crp.vawa_protections_apply;
    if (crp.notes && crp.notes.trim() !== '') cleanCrp.notes = crp.notes.trim();
    out.criminal_record_policy = cleanCrp;
  }
  if (hasProgramReqs) out.program_requirements = input.program_requirements;
  if (hasDocReqs) out.documentation_required = input.documentation_required;
  if (hasIntakeHours) out.intake_hours = (input.intake_hours as string).trim();
  if (hasCustomTags) out.custom_tags = input.custom_tags;

  return out;
}

/**
 * The 6 controlled offense type values per design D1 / slice 3 §6.3
 * i18n keys (offenseType.SEX_OFFENSE, offenseType.ARSON, etc.). Used
 * by the §10.2 multi-select.
 */
export const OFFENSE_TYPES = [
  'SEX_OFFENSE',
  'ARSON',
  'DRUG_MANUFACTURING',
  'VIOLENT_FELONY',
  'PENDING_CHARGES',
  'OPEN_WARRANTS',
] as const;
