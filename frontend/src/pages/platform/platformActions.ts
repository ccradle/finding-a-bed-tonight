/**
 * Config-driven action catalogue for the platform-operator dashboard
 * (F11 task 4.8 / spec Requirement: Action cards grouped by category).
 *
 * Each action describes ONE platform-operator capability the dashboard
 * surfaces. The dashboard renders cards from this list rather than
 * hand-authoring 7 components — adding the 8th is a one-line config
 * change (per warroom round 1 Alex recommendation).
 *
 * `flagGate` marks actions disabled in deployments where the named
 * Spring property is false. v0.54 ships ALL tenant-lifecycle actions
 * disabled because `fabt.tenant.lifecycle.enabled=false` in prod
 * (per OpenSpec design.md Decision D3 — render disabled with tooltip).
 */

export type ActionCategory = 'lifecycle' | 'operator' | 'observability' | 'system';

export type DangerLevel = 'safe' | 'destructive';

export interface PlatformAction {
  /** Stable id used in data-testid + as the React key. */
  id: string;
  /** Card title (operator-facing). */
  title: string;
  /** One-sentence description of what this action does. */
  description: string;
  /**
   * Verb-first label shown ON the action button (e.g. "Suspend",
   * "View", "Open"). Round 7 fix: prior version concatenated
   * `${dangerLevel === 'destructive' ? 'Open' : 'Run'} ${title.toLowerCase()}`,
   * which produced incoherent strings like "Open hard-delete tenant"
   * and "Run system health". Per-action labels are operator-readable
   * and avoid the off-by-grammar trap.
   */
  buttonLabel: string;
  /** UI category — drives the heading the card renders under. */
  category: ActionCategory;
  /** HTTP method on the backing API endpoint. */
  method: 'GET' | 'POST' | 'PATCH' | 'DELETE' | 'PUT';
  /** Backing API path. May contain `:tenantId` for tenant-scoped actions. */
  endpoint: string;
  /**
   * If set, the action is disabled when this Spring property is false.
   * The dashboard reads the flag state from the operator-metadata `/me`
   * response (extension to be added in slice D backend follow-up if not
   * already present). For v0.54 this is hard-coded false in the SPA
   * since there's no /me field for it; the F43 follow-up adds the wire.
   */
  flagGate?: 'fabt.tenant.lifecycle.enabled';
  /**
   * Destructive actions trigger the typed-slug confirmation modal
   * (warroom round 1 Jordan + spec). Safe actions submit immediately.
   */
  dangerLevel: DangerLevel;
  /**
   * If true, handleActivate will prompt for a value before sending
   * the request. Used for scalar platform-config fields (intervals, endpoints).
   */
  needsValue?: boolean;
}

export const PLATFORM_ACTIONS: PlatformAction[] = [
  // ---- Tenant Lifecycle ----------------------------------------------
  {
    id: 'tenant-list',
    title: 'List tenants',
    description: 'View all CoC tenants and their current state.',
    buttonLabel: 'View',
    category: 'lifecycle',
    method: 'GET',
    endpoint: '/api/v1/tenants',
    // Round 7 ground-truth: /api/v1/tenants requires PLATFORM_OPERATOR
    // (SecurityConfig.java:179). A new-tab `window.open` strips the
    // sessionStorage JWT and gets a 401. Until slice E ships an in-page
    // result viewer with platformFetch, gate this with the lifecycle
    // flag so it renders disabled-with-tooltip alongside the other
    // tenant-lifecycle cards (matches D3 — render disabled, not hidden).
    flagGate: 'fabt.tenant.lifecycle.enabled',
    dangerLevel: 'safe',
  },
  {
    id: 'tenant-suspend',
    title: 'Suspend tenant',
    description:
      'Block all tenant logins and mutations. Reversible via Unsuspend. Audit-logged.',
    buttonLabel: 'Suspend',
    category: 'lifecycle',
    method: 'POST',
    endpoint: '/api/v1/tenants/:tenantId/suspend',
    flagGate: 'fabt.tenant.lifecycle.enabled',
    dangerLevel: 'destructive',
  },
  {
    id: 'tenant-unsuspend',
    title: 'Unsuspend tenant',
    description: 'Re-enable a previously suspended tenant. Audit-logged.',
    buttonLabel: 'Unsuspend',
    category: 'lifecycle',
    method: 'POST',
    endpoint: '/api/v1/tenants/:tenantId/unsuspend',
    flagGate: 'fabt.tenant.lifecycle.enabled',
    dangerLevel: 'destructive',
  },
  {
    id: 'tenant-offboard',
    title: 'Offboard tenant',
    description:
      'Begin tenant data export + retention countdown. Reversible only via DBA before hard-delete.',
    buttonLabel: 'Offboard',
    category: 'lifecycle',
    method: 'POST',
    endpoint: '/api/v1/tenants/:tenantId/offboard',
    flagGate: 'fabt.tenant.lifecycle.enabled',
    dangerLevel: 'destructive',
  },
  {
    id: 'tenant-hard-delete',
    title: 'Hard-delete tenant',
    description:
      'Crypto-shred tenant data via DEK destruction. NOT REVERSIBLE. Requires offboarded state.',
    buttonLabel: 'Hard-delete',
    category: 'lifecycle',
    method: 'DELETE',
    endpoint: '/api/v1/tenants/:tenantId',
    flagGate: 'fabt.tenant.lifecycle.enabled',
    dangerLevel: 'destructive',
  },
  // ---- Observability -------------------------------------------------
  // platform-observability-split (2026-05-02). dangerLevel per tasks.md §8.2:
  //  - prometheus / tracing toggles → safe (boolean flips, easy to undo)
  //  - tracing-endpoint            → destructive (a bad endpoint blackholes spans)
  //  - dv-canary-interval          → destructive (security cadence; lowering
  //                                  weakens platform's posture)
  //  - temperature-interval        → destructive (NOAA rate-limit risk)
  //  - stale-interval              → safe (no external rate-limit, easy to revert)
  // Warroom round 4 fix: original implementation had ALL six as safe.
  {
    id: 'obs-prometheus',
    title: 'Prometheus Metrics',
    description: 'Toggle the JVM-level Prometheus scrape endpoint.',
    buttonLabel: 'Toggle',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'safe',
    needsValue: true,
  },
  {
    id: 'obs-tracing',
    title: 'OpenTelemetry Tracing',
    description: 'Toggle the JVM-level OTel exporter.',
    buttonLabel: 'Toggle',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'safe',
    needsValue: true,
  },
  {
    id: 'obs-tracing-endpoint',
    title: 'OTLP Endpoint',
    description:
      'Set the OTLP collector URL (e.g. Jaeger/Tempo). A bad URL blackholes spans — destructive.',
    buttonLabel: 'Set URL',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'destructive',
    needsValue: true,
  },
  {
    id: 'obs-stale-interval',
    title: 'Stale Shelter Cadence',
    description: 'Minutes between stale-shelter detection cycles.',
    buttonLabel: 'Set Interval',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'safe',
    needsValue: true,
  },
  {
    id: 'obs-canary-interval',
    title: 'DV Canary Cadence',
    description:
      'Minutes between security RLS-canary probes. Lengthening this weakens the platform’s DV-leak detection posture — destructive.',
    buttonLabel: 'Set Interval',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'destructive',
    needsValue: true,
  },
  {
    id: 'obs-temp-interval',
    title: 'Temperature Cadence',
    description:
      'Minutes between NOAA weather fetch cycles. Floor of 1 minute reserved by NOAA rate-limit — destructive.',
    buttonLabel: 'Set Interval',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'destructive',
    needsValue: true,
  },
  // ---- System Status -------------------------------------------------
  {
    id: 'system-health',
    title: 'System health',
    description: 'Open the health endpoint in a new tab. Read-only.',
    buttonLabel: 'Open',
    category: 'system',
    method: 'GET',
    endpoint: '/actuator/health',
    dangerLevel: 'safe',
  },
  {
    id: 'system-version',
    title: 'Platform version',
    description: 'Show the running platform version and build commit.',
    buttonLabel: 'View',
    category: 'system',
    method: 'GET',
    endpoint: '/api/v1/version',
    dangerLevel: 'safe',
  },
];

export const CATEGORY_LABELS: Record<ActionCategory, string> = {
  lifecycle: 'Tenant Lifecycle',
  operator: 'Operator Management',
  observability: 'Observability',
  system: 'System Status',
};

export const CATEGORY_ORDER: ActionCategory[] = ['lifecycle', 'operator', 'observability', 'system'];
