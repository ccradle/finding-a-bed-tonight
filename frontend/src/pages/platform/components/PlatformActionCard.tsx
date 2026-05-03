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
  /**
   * Current persisted value for the field this action edits, pre-formatted
   * for display (e.g., "5 minutes", "enabled", "http://otel:4318/v1/traces").
   * Optional — actions that aren't field-editors (system status, lifecycle
   * triggers) leave this undefined and the card renders no value row.
   * Warroom round 6 fix: previously the operator could not see the
   * current state before deciding to set a new one.
   */
  currentValueDisplay?: string;
}

const TOOLTIP_DISABLED =
  'This action is disabled in this deployment. Contact platform engineering to enable.';

export function PlatformActionCard({ action, enabled, onActivate, currentValueDisplay }: Props) {
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
      {currentValueDisplay !== undefined && (
        <p
          style={{
            marginTop: 0,
            marginBottom: '0.75rem',
            fontSize: '0.875rem',
            color: color.text,
            fontWeight: 600,
            wordBreak: 'break-all',
          }}
          data-testid={`platform-action-${action.id}-current`}
        >
          Current: <span style={{ fontWeight: 400 }}>{currentValueDisplay}</span>
        </p>
      )}
      <button
        type="button"
        // Warroom round 7 (2026-05-03) — Tomás recommendation per W3C ARIA APG
        // (https://www.w3.org/WAI/ARIA/apg/practices/keyboard-interface/#focusabilityofdisabledcontrols).
        // Native `disabled` removes the button from tab order, so a keyboard-
        // only operator can never land on it to discover *why* it's disabled
        // (the title tooltip is mouse-only). `aria-disabled="true"` keeps the
        // button focusable + screen-reader-discoverable, and the click handler
        // is guarded explicitly below to preserve the no-op behavior.
        aria-disabled={isDisabled}
        onClick={() => {
          // Click guard — `aria-disabled` does NOT block the browser-level click
          // event the way the native `disabled` attribute does. Without this
          // guard, a flag-gated lifecycle action would fire its handler and
          // throw the defensive Error in PlatformDashboard.handleActivate.
          if (isDisabled) return;
          onActivate(action);
        }}
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
