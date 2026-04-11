import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../../../../theme/colors';
import { text, weight } from '../../../../theme/typography';
import { tableStyle, thStyle, tdStyle } from '../../styles';
import type { EscalatedReferral } from '../../../../hooks/useDvEscalationQueue';

interface EscalatedQueueTableProps {
  queue: EscalatedReferral[];
  /** Click handler — opens the detail modal in the parent. */
  onOpenDetail: (referral: EscalatedReferral) => void;
  /** Inline claim handler — fast path that bypasses the modal. */
  onClaim: (referral: EscalatedReferral) => void;
  /** Whether a network operation is in flight (disables action buttons). */
  submitting: boolean;
}

/**
 * T-34 — Desktop table view of the escalated DV referral queue.
 *
 * <p><b>Color independence (WCAG D4):</b> chain-broken state shows
 * "Owned by [name]" text + DV color, never just a colored dot. Time-to-expiry
 * shows the numeric value, with subtle red background only as a SECONDARY
 * cue when remainingMinutes &lt; 15.</p>
 *
 * <p><b>Accessibility:</b> 44×44px touch targets on Claim button. Row click
 * opens the detail modal — entire row is the click target. The Claim button
 * stops propagation so it doesn't double-fire the row handler.</p>
 *
 * <p><b>data-testid</b> on every interactive element + on each row keyed by
 * referral id, for stable Playwright locators (memory: feedback_data_testid).</p>
 */
export function EscalatedQueueTable({
  queue,
  onOpenDetail,
  onClaim,
  submitting,
}: EscalatedQueueTableProps) {
  const intl = useIntl();

  const renderTimeLeft = (minutes: number) => {
    if (minutes <= 0) {
      return (
        <span style={{ color: color.errorMid, fontWeight: weight.bold }}>
          <FormattedMessage id="dvEscalations.queue.expired" />
        </span>
      );
    }
    return (
      <FormattedMessage id="dvEscalations.queue.minutesLeft" values={{ minutes }} />
    );
  };

  const renderStatus = (referral: EscalatedReferral) => {
    if (referral.escalationChainBroken) {
      // Owner badge — text + DV color (color independence per D4)
      return (
        <span
          data-testid={`dv-escalation-chain-broken-${referral.id}`}
          style={{
            display: 'inline-block',
            padding: '4px 10px',
            borderRadius: 12,
            fontSize: text.xs,
            fontWeight: weight.semibold,
            backgroundColor: color.dvBg,
            color: color.dvText,
            border: `1px solid ${color.dvBorder}`,
          }}
        >
          {referral.claimedByAdminName ? (
            <FormattedMessage
              id="dvEscalations.queue.owner"
              values={{ name: referral.claimedByAdminName }}
            />
          ) : (
            <FormattedMessage id="dvEscalations.queue.ownerUnknown" />
          )}
        </span>
      );
    }
    if (referral.claimedByAdminName) {
      return (
        <span
          data-testid={`dv-escalation-claimed-${referral.id}`}
          style={{
            display: 'inline-block',
            padding: '4px 10px',
            borderRadius: 12,
            fontSize: text.xs,
            fontWeight: weight.semibold,
            backgroundColor: color.bgHighlight,
            color: color.text,
            border: `1px solid ${color.border}`,
          }}
        >
          <FormattedMessage
            id="dvEscalations.queue.claimedBy"
            values={{ name: referral.claimedByAdminName }}
          />
        </span>
      );
    }
    return (
      <span style={{ fontSize: text.xs, color: color.textMuted }}>
        <FormattedMessage id="dvEscalations.queue.unclaimed" />
      </span>
    );
  };

  return (
    <div style={{ overflowX: 'auto' }} data-testid="dv-escalation-queue-table">
      <table style={tableStyle}>
        <thead>
          <tr>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.shelter" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.timeLeft" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.population" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.household" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.urgency" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.coordinator" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.status" /></th>
            <th style={thStyle}><FormattedMessage id="dvEscalations.queue.col.actions" /></th>
          </tr>
        </thead>
        <tbody>
          {queue.map((referral, i) => {
            const isUrgent = referral.remainingMinutes > 0 && referral.remainingMinutes < 15;
            const baseTd = tdStyle(i);
            const rowTd: React.CSSProperties = isUrgent
              ? { ...baseTd, backgroundColor: color.errorBg }
              : baseTd;
            // The inline quick-Claim button is ONLY shown for referrals no one
            // has claimed yet. If *anyone* — me or another admin — is already
            // the claim holder, the row routes through the detail modal
            // ("Open referral detail" button) where override / release /
            // approve actions live. Previously this checked only `isOwnedByMe`,
            // which left the inline Claim button visible to admin B after
            // admin A claimed a row — breaking T-44b's "second admin sees
            // claim blocked" assertion and inviting a blind-click 409 that
            // the spec designed the friction-path to prevent.
            const canClaim =
              !referral.escalationChainBroken && !referral.claimedByAdminId;

            return (
              <tr
                key={referral.id}
                data-testid={`dv-escalation-row-${referral.id}`}
                onClick={() => onOpenDetail(referral)}
                style={{ cursor: 'pointer' }}
              >
                <td style={{ ...rowTd, fontWeight: weight.semibold }}>
                  {referral.shelterName}
                </td>
                <td style={rowTd}>{renderTimeLeft(referral.remainingMinutes)}</td>
                <td style={rowTd}>{referral.populationType}</td>
                <td style={rowTd}>{referral.householdSize}</td>
                <td style={rowTd}>{referral.urgency}</td>
                <td style={rowTd}>
                  {referral.assignedCoordinatorName ?? (
                    <span style={{ color: color.textMuted }}>
                      <FormattedMessage id="dvEscalations.queue.unassigned" />
                    </span>
                  )}
                </td>
                <td style={rowTd}>{renderStatus(referral)}</td>
                <td style={rowTd}>
                  {canClaim ? (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onClaim(referral);
                      }}
                      disabled={submitting}
                      data-testid={`dv-escalation-claim-${referral.id}`}
                      aria-label={intl.formatMessage({ id: 'dvEscalations.action.claim' })}
                      style={{
                        padding: '8px 14px',
                        minHeight: 44, minWidth: 44,
                        background: color.primary,
                        color: color.textInverse,
                        border: 'none',
                        borderRadius: 8,
                        fontSize: text.sm,
                        fontWeight: weight.bold,
                        cursor: submitting ? 'not-allowed' : 'pointer',
                        opacity: submitting ? 0.6 : 1,
                      }}
                    >
                      <FormattedMessage id="dvEscalations.action.claim" />
                    </button>
                  ) : (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        onOpenDetail(referral);
                      }}
                      data-testid={`dv-escalation-open-${referral.id}`}
                      aria-label={intl.formatMessage({ id: 'dvEscalations.action.openDetail' })}
                      style={{
                        padding: '8px 14px',
                        minHeight: 44, minWidth: 44,
                        background: 'none',
                        color: color.primaryText,
                        border: `2px solid ${color.border}`,
                        borderRadius: 8,
                        fontSize: text.sm,
                        fontWeight: weight.semibold,
                        cursor: 'pointer',
                      }}
                    >
                      <FormattedMessage id="dvEscalations.action.more" />
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
