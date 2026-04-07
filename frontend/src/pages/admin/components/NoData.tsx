import { FormattedMessage } from 'react-intl';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

export function NoData() {
  return (
    <div style={{ textAlign: 'center', padding: 40, color: color.textMuted, fontSize: text.base, fontWeight: weight.medium }}>
      <FormattedMessage id="admin.noData" />
    </div>
  );
}
