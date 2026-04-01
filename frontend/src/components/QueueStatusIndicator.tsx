import { useState } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { getQueuedActions, type QueuedAction } from '../services/offlineQueue';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

interface QueueStatusIndicatorProps {
  count: number;
}

const ACTION_LABEL_KEYS: Record<string, string> = {
  HOLD_BED: 'queue.holdBed',
  UPDATE_AVAILABILITY: 'queue.updateAvailability',
};

export function QueueStatusIndicator({ count }: QueueStatusIndicatorProps) {
  const intl = useIntl();
  const [open, setOpen] = useState(false);
  const [actions, setActions] = useState<QueuedAction[]>([]);
  const [renderTime, setRenderTime] = useState(Date.now);

  if (count === 0) return null;

  const handleClick = async () => {
    if (!open) {
      const items = await getQueuedActions();
      setActions(items);
      setRenderTime(Date.now());
    }
    setOpen(!open);
  };

  return (
    <div style={{ position: 'relative' }}>
      <button
        onClick={handleClick}
        data-testid="queue-status-badge"
        aria-label={intl.formatMessage({ id: 'queue.pending' }, { count })}
        style={{
          position: 'relative',
          background: 'transparent',
          border: '1px solid rgba(255,255,255,0.3)',
          borderRadius: 8,
          padding: '6px 10px',
          cursor: 'pointer',
          color: color.headerText,
          fontSize: text.sm,
          fontWeight: weight.bold,
          minHeight: 44,
          minWidth: 44,
          display: 'flex',
          alignItems: 'center',
          gap: 4,
        }}
      >
        <span aria-hidden="true" style={{ fontSize: text.base }}>&#9202;</span>
        <span data-testid="queue-count">{count}</span>
      </button>

      {open && (
        <div
          data-testid="queue-panel"
          style={{
            position: 'absolute',
            top: '100%',
            right: 0,
            marginTop: 8,
            width: 280,
            backgroundColor: color.bg,
            border: `1px solid ${color.border}`,
            borderRadius: 12,
            boxShadow: '0 4px 20px rgba(0,0,0,0.15)',
            zIndex: 1000,
            padding: '12px 16px',
          }}
        >
          <h4 style={{
            margin: '0 0 8px', fontSize: text.sm, fontWeight: weight.bold,
            color: color.text, textTransform: 'uppercase', letterSpacing: '0.04em',
          }}>
            <FormattedMessage id="queue.title" />
          </h4>
          {actions.length === 0 ? (
            <div style={{ fontSize: text.xs, color: color.textMuted }}>
              <FormattedMessage id="queue.empty" />
            </div>
          ) : (
            actions.map((action) => {
              const minutesAgo = Math.floor((renderTime - action.timestamp) / 60000);
              const labelKey = ACTION_LABEL_KEYS[action.type] || 'queue.unknownAction';
              return (
                <div
                  key={action.id}
                  style={{
                    padding: '8px 0',
                    borderBottom: `1px solid ${color.borderLight}`,
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                  }}
                >
                  <span style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.text }}>
                    <span aria-hidden="true" style={{ color: color.warning, marginRight: 4 }}>&#128336;</span>
                    <FormattedMessage id={labelKey} />
                  </span>
                  <span style={{ fontSize: text['2xs'], color: color.textMuted }}>
                    <FormattedMessage id="queue.queuedAgo" values={{ minutes: minutesAgo }} />
                  </span>
                </div>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}
