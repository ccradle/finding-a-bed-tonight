/**
 * Operator-facing landing for `/platform/*` between the §3 foundation
 * landing and §4 page components landing. Section 4 replaces this with
 * real login / MFA / dashboard routes.
 *
 * If §3 ships standalone (not recommended; see runbook), an operator
 * who tunnels in sees this page rather than dev-grade "Section 3
 * placeholder" text. Copy is operator-appropriate and points at the
 * runbook for what to do until §4 lands.
 */

import { color } from '../../theme/colors';

export default function PlatformPlaceholder() {
  return (
    <div
      style={{
        backgroundColor: color.bg,
        color: color.text,
        minHeight: '100vh',
      }}
      data-testid="platform-placeholder"
    >
      {/* Banner is rendered by PlatformLayout; this page is just the
          page content. */}
      <main style={{ padding: '2rem', maxWidth: '720px' }}>
        <h1>Platform operator console — coming soon</h1>
        <p>
          The platform-operator dashboard is being delivered in stages.
          Login, MFA enrollment, and tenant lifecycle actions land in the
          next release.
        </p>
        <p>
          Until then, platform-operator activations are performed via the
          documented procedure in the deploy runbook (SSH tunnel + curl
          flow). Refer to{' '}
          <code>docs/operations/oracle-update-notes-v0.53.0.md §5.10</code>
          {' '}for the activation path.
        </p>
      </main>
    </div>
  );
}
