import { FormattedMessage } from 'react-intl';
import { useOnlineStatus } from '../hooks/useOnlineStatus';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

const bannerStyle: React.CSSProperties = {
  backgroundColor: color.warningBright,
  color: color.warning,
  padding: '12px 16px',
  textAlign: 'center',
  fontWeight: weight.semibold,
  fontSize: text.base,
  position: 'sticky',
  top: 0,
  zIndex: 1000,
};

export function OfflineBanner() {
  const { isOnline } = useOnlineStatus();

  if (isOnline) return null;

  return (
    <div style={bannerStyle} role="alert">
      <FormattedMessage id="offline.banner" />
    </div>
  );
}
