import { color } from '../../../theme/colors';

export function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: color.textMuted }}>
      <div style={{
        width: 32, height: 32, border: `3px solid ${color.border}`, borderTopColor: color.primary,
        borderRadius: '50%', animation: 'fabt-spin 0.7s linear infinite', margin: '0 auto 10px',
      }} />
      <style>{`@keyframes fabt-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
