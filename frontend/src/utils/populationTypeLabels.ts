/**
 * Maps API population type enum values to i18n label IDs.
 *
 * IMPORTANT: Raw enum values (e.g., "DV_SURVIVOR") must NEVER be displayed
 * to users. Always use this mapping with intl.formatMessage() to get the
 * dignity-centered display label (e.g., "Safety Shelter").
 *
 * If you need to display a population type, use:
 *   import { getPopulationTypeLabel } from '../utils/populationTypeLabels';
 *   const label = getPopulationTypeLabel(enumValue, intl);
 *
 * Do NOT use: populationType.replace(/_/g, ' ')
 * That pattern exposes raw enum values including "DV SURVIVOR" to users.
 */

import type { IntlShape } from 'react-intl';

const POPULATION_TYPE_I18N: Record<string, string> = {
  SINGLE_ADULT: 'search.singleAdult',
  FAMILY_WITH_CHILDREN: 'search.family',
  WOMEN_ONLY: 'search.womenOnly',
  VETERAN: 'search.veteran',
  YOUTH_18_24: 'search.youth1824',
  YOUTH_UNDER_18: 'search.youthUnder18',
  DV_SURVIVOR: 'search.dvSurvivor',
};

/**
 * Get the dignity-centered display label for a population type enum value.
 * Falls back to the enum value with underscores replaced (for unknown types)
 * but logs a warning so we catch missing mappings during development.
 */
export function getPopulationTypeLabel(enumValue: string, intl: IntlShape): string {
  const labelId = POPULATION_TYPE_I18N[enumValue];
  if (labelId) {
    return intl.formatMessage({ id: labelId });
  }
  // Unknown population type — fall back but warn
  if (typeof console !== 'undefined') {
    console.warn(`Unknown population type "${enumValue}" — add to populationTypeLabels.ts`);
  }
  return enumValue.replace(/_/g, ' ').toLowerCase();
}
