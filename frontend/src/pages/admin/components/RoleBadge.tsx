import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

export function RoleBadge({ role }: { role: string }) {
  return (
    <span style={{
      padding: '3px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.semibold,
      backgroundColor: color.bgHighlight, color: color.primaryText, marginRight: 4,
      border: `1px solid ${color.primaryLight}`,
    }}>{role}</span>
  );
}
