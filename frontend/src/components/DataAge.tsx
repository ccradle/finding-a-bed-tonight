import { useIntl } from 'react-intl';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

interface DataAgeProps {
  dataAgeSeconds: number | null;
}

const FRESH_THRESHOLD = 2 * 60 * 60; // 2 hours in seconds
const AGING_THRESHOLD = 8 * 60 * 60; // 8 hours in seconds

function getStatusColor(seconds: number | null): string {
  if (seconds === null) return color.textMuted; // gray - UNKNOWN
  if (seconds < FRESH_THRESHOLD) return color.success; // dark green - FRESH (4.5:1 on white)
  if (seconds < AGING_THRESHOLD) return color.warning; // dark amber - AGING (4.5:1 on white)
  return color.error; // dark red - STALE (4.5:1 on white)
}

function getStatusLabelId(seconds: number | null): string {
  if (seconds === null) return 'data.age.unknown';
  if (seconds < FRESH_THRESHOLD) return 'data.age.fresh';
  if (seconds < AGING_THRESHOLD) return 'data.age.aging';
  return 'data.age.stale';
}

function getTooltipId(seconds: number | null): string {
  if (seconds === null) return 'data.age.tooltip.unknown';
  if (seconds < FRESH_THRESHOLD) return 'data.age.tooltip.fresh';
  if (seconds < AGING_THRESHOLD) return 'data.age.tooltip.aging';
  return 'data.age.tooltip.stale';
}

function getAgeLabelId(seconds: number | null): { id: string; values?: Record<string, number> } {
  if (seconds === null) return { id: 'data.age.unknown' };
  if (seconds < 60) return { id: 'data.age.justNow' };
  if (seconds < 3600) {
    return { id: 'data.age.minutesAgo', values: { minutes: Math.floor(seconds / 60) } };
  }
  return { id: 'data.age.hoursAgo', values: { hours: Math.floor(seconds / 3600) } };
}

export function DataAge({ dataAgeSeconds }: DataAgeProps) {
  const intl = useIntl();
  const statusColor = getStatusColor(dataAgeSeconds);
  const statusLabelId = getStatusLabelId(dataAgeSeconds);
  const statusLabel = intl.formatMessage({ id: statusLabelId });
  const ageInfo = getAgeLabelId(dataAgeSeconds);
  const ageText = intl.formatMessage({ id: ageInfo.id }, ageInfo.values);
  const tooltip = intl.formatMessage({ id: getTooltipId(dataAgeSeconds) });

  return (
    <span
      title={tooltip}
      style={{
        color: statusColor,
        fontSize: text.xs,
        fontWeight: weight.medium,
        display: 'inline-flex',
        alignItems: 'center',
        gap: '4px',
      }}
      aria-label={`${statusLabel}: ${ageText}`}
    >
      <span
        style={{
          width: '8px',
          height: '8px',
          borderRadius: '50%',
          backgroundColor: statusColor,
          display: 'inline-block',
        }}
        aria-hidden="true"
      />
      <span style={{ fontWeight: weight.semibold }}>{statusLabel}</span>
      {' · '}
      {ageText}
    </span>
  );
}
