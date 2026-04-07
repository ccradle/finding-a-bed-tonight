import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

export function ErrorBox({ message }: { message: string }) {
  return (
    <div style={{
      backgroundColor: color.errorBg, color: color.error, padding: '14px 18px',
      borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium,
    }}>{message}</div>
  );
}
