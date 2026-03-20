interface DataAgeProps {
  dataAgeSeconds: number | null;
}

const FRESH_THRESHOLD = 2 * 60 * 60; // 2 hours in seconds
const AGING_THRESHOLD = 8 * 60 * 60; // 8 hours in seconds

function getColor(seconds: number | null): string {
  if (seconds === null) return '#9ca3af'; // gray - UNKNOWN
  if (seconds < FRESH_THRESHOLD) return '#22c55e'; // green - FRESH
  if (seconds < AGING_THRESHOLD) return '#eab308'; // yellow - AGING
  return '#ef4444'; // red - STALE
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
  const text = formatAge(dataAgeSeconds);

  return (
    <span
      style={{
        color,
        fontSize: '12px',
        fontWeight: 500,
        display: 'inline-flex',
        alignItems: 'center',
        gap: '4px',
      }}
    >
      <span
        style={{
          width: '8px',
          height: '8px',
          borderRadius: '50%',
          backgroundColor: color,
          display: 'inline-block',
        }}
      />
      {text}
    </span>
  );
}
