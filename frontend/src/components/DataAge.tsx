import { useIntl } from 'react-intl';
import { text, weight } from '../theme/typography';

interface DataAgeProps {
  dataAgeSeconds: number | null;
}

const FRESH_THRESHOLD = 2 * 60 * 60; // 2 hours in seconds
const AGING_THRESHOLD = 8 * 60 * 60; // 8 hours in seconds

function getColor(seconds: number | null): string {
  if (seconds === null) return '#6b7280'; // gray - UNKNOWN
  if (seconds < FRESH_THRESHOLD) return '#166534'; // dark green - FRESH (4.5:1 on white)
  if (seconds < AGING_THRESHOLD) return '#92400e'; // dark amber - AGING (4.5:1 on white)
  return '#991b1b'; // dark red - STALE (4.5:1 on white)
}

function getStatusLabelId(seconds: number | null): string {
  if (seconds === null) return 'data.age.unknown';
  if (seconds < FRESH_THRESHOLD) return 'data.age.fresh';
  if (seconds < AGING_THRESHOLD) return 'data.age.aging';
  return 'data.age.stale';
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
  const color = getColor(dataAgeSeconds);
  const statusLabelId = getStatusLabelId(dataAgeSeconds);
  const statusLabel = intl.formatMessage({ id: statusLabelId });
  const ageInfo = getAgeLabelId(dataAgeSeconds);
  const ageText = intl.formatMessage({ id: ageInfo.id }, ageInfo.values);

  return (
    <span
      style={{
        color,
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
          backgroundColor: color,
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
