import { FormattedMessage } from 'react-intl';
import { useOnlineStatus } from '../hooks/useOnlineStatus';

const bannerStyle: React.CSSProperties = {
  backgroundColor: '#fbbf24',
  color: '#78350f',
  padding: '12px 16px',
  textAlign: 'center',
  fontWeight: 600,
  fontSize: '14px',
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
