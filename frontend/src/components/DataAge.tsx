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

// WCAG 1.4.1 — status label text so color is not the sole indicator
function getStatusLabel(seconds: number | null): string {
  if (seconds === null) return 'Unknown';
  if (seconds < FRESH_THRESHOLD) return 'Fresh';
  if (seconds < AGING_THRESHOLD) return 'Aging';
  return 'Stale';
}

function formatAge(seconds: number | null): string {
  if (seconds === null) return 'Unknown';
  if (seconds < 60) return 'Updated just now';
  if (seconds < 3600) {
    const minutes = Math.floor(seconds / 60);
    return `Updated ${minutes} minute${minutes !== 1 ? 's' : ''} ago`;
  }
  const hours = Math.floor(seconds / 3600);
  return `Updated ${hours} hour${hours !== 1 ? 's' : ''} ago`;
}

export function DataAge({ dataAgeSeconds }: DataAgeProps) {
  const color = getColor(dataAgeSeconds);
  const ageText = formatAge(dataAgeSeconds);
  const statusLabel = getStatusLabel(dataAgeSeconds);

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
