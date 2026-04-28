/**
 * Single action card on the platform-operator dashboard (F11 task 4.9).
 *
 * Renders one {@link PlatformAction}. Lifecycle actions are disabled
 * with a tooltip when the deployment has
 * `fabt.tenant.lifecycle.enabled=false` — operator decision D3:
 * render-disabled, not hide, so the operator's mental model of "what
 * platform operators CAN do" is intact.
 *
 * Click-handling is delegated to the dashboard via `onActivate` so the
 * card doesn't carry destructive-confirm-modal state itself; the
 * dashboard owns one ConfirmActionModal instance shared across cards.
 */

import { color } from '../../../theme/colors';
import type { PlatformAction } from '../platformActions';

interface Props {
  action: PlatformAction;
  /** True iff the action's `flagGate` is satisfied in this deployment. */
  enabled: boolean;
  /** Called when the operator clicks the card. Dashboard handles
   *  destructive-confirm vs immediate-submit branching. */
  onActivate: (action: PlatformAction) => void;
}

const TOOLTIP_DISABLED =
  'This action is disabled in this deployment. Contact platform engineering to enable.';

export function PlatformActionCard({ action, enabled, onActivate }: Props) {
  const isDisabled = !enabled;
  // Wave 2 / §6.9 axe — `opacity: 0.6` for disabled cards effectively
  // blended `--color-text-secondary` to ~#878d97 over `--color-bg`,
  // which axe correctly flagged as 3.25:1 contrast (needs 4.5:1).
  // Same opacity→token anti-pattern the project resolved elsewhere
  // (see memory `project_accessibility_contrast_failures.md`). Disabled
  // state is now communicated by the disabled button (browser-native
  // styling + `disabled` attr blocks click), the tooltip on the
  // button, and the cursor:not-allowed — all of which axe accepts.
  // Text stays at full token contrast in both themes.
  return (
    <div
      style={{
        border: `1px solid ${color.border}`,
        borderRadius: '8px',
        padding: '1rem',
        backgroundColor: color.bgSecondary,
      }}
      data-testid={`platform-action-${action.id}`}
    >
      <h3 style={{ marginTop: 0, marginBottom: '0.5rem', fontSize: '1rem' }}>
        {action.title}
      </h3>
      <p
        style={{
          marginTop: 0,
          marginBottom: '0.75rem',
          fontSize: '0.875rem',
          color: color.textSecondary,
        }}
      >
        {action.description}
      </p>
      <button
        type="button"
        disabled={isDisabled}
        onClick={() => onActivate(action)}
        title={isDisabled ? TOOLTIP_DISABLED : undefined}
        data-testid={`platform-action-${action.id}-button`}
        style={{
          padding: '0.5rem 0.875rem',
          fontSize: '0.875rem',
          fontWeight: 600,
          borderRadius: '4px',
          border: 'none',
          cursor: isDisabled ? 'not-allowed' : 'pointer',
          backgroundColor: isDisabled
            ? color.primaryDisabled
            : action.dangerLevel === 'destructive'
              ? color.error
              : color.primary,
          color: color.textInverse,
        }}
      >
        {action.buttonLabel}
      </button>
    </div>
  );
}
