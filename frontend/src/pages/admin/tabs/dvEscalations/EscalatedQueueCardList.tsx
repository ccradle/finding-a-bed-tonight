import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../../../../theme/colors';
import { text, weight } from '../../../../theme/typography';
import type { EscalatedReferral } from '../../../../hooks/useDvEscalationQueue';

interface EscalatedQueueCardListProps {
  queue: EscalatedReferral[];
  onOpenDetail: (referral: EscalatedReferral) => void;
  onClaim: (referral: EscalatedReferral) => void;
  submitting: boolean;
}

/**
 * T-35 — Mobile card list view of the escalated DV referral queue.
 *
 * <p>Linear's progressive-disclosure pattern: one card per referral, single
 * primary CTA (Claim), all other actions hidden behind "More" which opens
 * the detail modal. Card itself is keyboard-focusable and clickable to open
 * the detail modal as a fallback.</p>
 *
 * <p>44×44px touch targets on Claim and More buttons (WCAG D5).</p>
 */
export function EscalatedQueueCardList({
  queue,
  onOpenDetail,
  onClaim,
  submitting,
}: EscalatedQueueCardListProps) {
  const intl = useIntl();

  return (
    <div
      data-testid="dv-escalation-queue-cards"
      style={{ display: 'flex', flexDirection: 'column', gap: 12 }}
    >
      {queue.map((referral) => {
        const isUrgent = referral.remainingMinutes > 0 && referral.remainingMinutes < 15;
        const isExpired = referral.remainingMinutes <= 0;
        // See EscalatedQueueTable.tsx for the rationale: the inline quick-Claim
        // button is ONLY shown for referrals no one has claimed yet. Owned-by-me
        // AND claimed-by-another-admin both route through the detail modal.
        const canClaim =
          !referral.escalationChainBroken && !referral.claimedByAdminId;

        return (
          <article
            key={referral.id}
            data-testid={`dv-escalation-card-${referral.id}`}
            role="article"
            tabIndex={0}
            onClick={() => onOpenDetail(referral)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onOpenDetail(referral);
              }
            }}
            style={{
              padding: 16,
              borderRadius: 12,
              border: `2px solid ${isUrgent || isExpired ? color.errorBorder : color.border}`,
              backgroundColor: isUrgent || isExpired ? color.errorBg : color.bg,
              cursor: 'pointer',
              outline: 'none',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
              <div style={{
                fontSize: text.base,
                fontWeight: weight.bold,
                color: color.text,
                flex: 1,
                marginRight: 8,
              }}>
                {referral.shelterName}
              </div>
              <div style={{
                fontSize: text.sm,
                fontWeight: weight.bold,
                color: isExpired ? color.errorMid : (isUrgent ? color.errorMid : color.text),
                whiteSpace: 'nowrap',
              }}>
                {isExpired ? (
                  <FormattedMessage id="dvEscalations.queue.expired" />
                ) : (
                  <FormattedMessage
                    id="dvEscalations.queue.minutesLeft"
                    values={{ minutes: referral.remainingMinutes }}
                  />
                )}
              </div>
            </div>

            {/* Chain-broken owner badge or claim status */}
            {referral.escalationChainBroken && (
              <div
                data-testid={`dv-escalation-card-chain-broken-${referral.id}`}
                style={{
                  display: 'inline-block',
                  padding: '4px 10px',
                  borderRadius: 12,
                  fontSize: text.xs,
                  fontWeight: weight.semibold,
                  backgroundColor: color.dvBg,
                  color: color.dvText,
                  border: `1px solid ${color.dvBorder}`,
                  marginBottom: 12,
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
              </div>
            )}

            {!referral.escalationChainBroken && referral.claimedByAdminName && (
              <div style={{
                fontSize: text.xs,
                color: color.textSecondary,
                marginBottom: 12,
              }}>
                <FormattedMessage
                  id="dvEscalations.queue.claimedBy"
                  values={{ name: referral.claimedByAdminName }}
                />
              </div>
            )}

            {/* Action row */}
            <div style={{ display: 'flex', gap: 8 }}>
              {canClaim ? (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    onClaim(referral);
                  }}
                  disabled={submitting}
                  data-testid={`dv-escalation-card-claim-${referral.id}`}
                  aria-label={intl.formatMessage({ id: 'dvEscalations.action.claim' })}
                  style={{
                    padding: '12px 20px',
                    minHeight: 44, minWidth: 44,
                    background: color.primary,
                    color: color.textInverse,
                    border: 'none',
                    borderRadius: 8,
                    fontSize: text.base,
                    fontWeight: weight.bold,
                    cursor: submitting ? 'not-allowed' : 'pointer',
                    opacity: submitting ? 0.6 : 1,
                    flex: 1,
                  }}
                >
                  <FormattedMessage id="dvEscalations.action.claim" />
                </button>
              ) : null}
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  onOpenDetail(referral);
                }}
                data-testid={`dv-escalation-card-more-${referral.id}`}
                aria-label={intl.formatMessage({ id: 'dvEscalations.action.more' })}
                style={{
                  padding: '12px 20px',
                  minHeight: 44, minWidth: 44,
                  background: 'none',
                  color: color.primaryText,
                  border: `2px solid ${color.border}`,
                  borderRadius: 8,
                  fontSize: text.base,
                  fontWeight: weight.semibold,
                  cursor: 'pointer',
                  flex: canClaim ? 0 : 1,
                }}
              >
                <FormattedMessage id="dvEscalations.action.more" />
              </button>
            </div>
          </article>
        );
      })}
    </div>
  );
}
