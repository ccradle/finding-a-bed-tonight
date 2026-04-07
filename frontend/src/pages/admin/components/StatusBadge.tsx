import { FormattedMessage } from 'react-intl';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

export function StatusBadge({ active, yesId, noId }: { active: boolean; yesId: string; noId: string }) {
  return (
    <span style={{
      padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
      backgroundColor: active ? color.successBg : color.errorBg,
      color: active ? color.success : color.error,
      border: `1px solid ${active ? color.successBorder : color.errorBorder}`,
    }}>
      <FormattedMessage id={active ? yesId : noId} />
    </span>
  );
}
