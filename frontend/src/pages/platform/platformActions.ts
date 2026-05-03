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

/**
 * Type of inline-edit form input the card renders. Closes the deferred
 * §8.4 follow-up (warroom round 6, 2026-05-03) — replaces the prior
 * `window.prompt()` flow with a type-appropriate inline form per
 * W3C ARIA APG, shadcn/ui form patterns, and Nielsen heuristics for
 * error prevention + recognition over recall.
 *
 * - `toggle`  — boolean. Renders a switch following the W3C ARIA APG
 *               Switch Pattern (role="switch" + aria-checked, label
 *               state-stable).
 * - `number`  — bounded integer. Renders &lt;input type="number"
 *               min={min} max={max} step="1"&gt; with bounds hint
 *               via aria-describedby.
 * - `url`     — non-empty URL string. Renders &lt;input type="url"&gt;
 *               with placeholder + URL.canParse() inline validation.
 * - `none`    — non-editor action (system, lifecycle). Card stays
 *               button-only.
 */
export type ActionFieldType = 'toggle' | 'number' | 'url' | 'none';

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
   *
   * @deprecated As of warroom round 6 (2026-05-03), prefer {@link fieldType}
   *   + {@link min}/{@link max}/{@link placeholder}. The window.prompt
   *   flow that consumed this flag is gone; the inline-edit form in
   *   ObservabilityActionCard reads `fieldType` instead. Kept temporarily
   *   so non-observability call sites compile during the migration; remove
   *   in v0.58+.
   */
  needsValue?: boolean;
  /**
   * Inline-edit form input type. Default: `'none'`. Set on cards that
   * mutate a scalar platform-config field (the 6 observability cards).
   */
  fieldType?: ActionFieldType;
  /** JSONB key the form binds to on the backing endpoint (snake_case). */
  fieldKey?: string;
  /** Lower bound for `fieldType: 'number'` (inclusive). */
  min?: number;
  /** Upper bound for `fieldType: 'number'` (inclusive). */
  max?: number;
  /** Unit suffix shown after the number input ("minutes", "seconds"). */
  unit?: string;
  /** Placeholder for `fieldType: 'url'`. */
  placeholder?: string;
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
    // Warroom round 7 (2026-05-03): drop the flagGate — the dashboard
    // now fetches via platformFetch (carries the JWT) and renders an
    // inline list below the Lifecycle section, replacing the original
    // window.open path that stripped the JWT in a new tab. Slice E will
    // expand this into a sortable/filterable list.
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
    buttonLabel: 'Edit',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'safe',
    fieldType: 'toggle',
    fieldKey: 'prometheus_enabled',
  },
  {
    id: 'obs-tracing',
    title: 'OpenTelemetry Tracing',
    description: 'Toggle the JVM-level OTel exporter.',
    buttonLabel: 'Edit',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'safe',
    fieldType: 'toggle',
    fieldKey: 'tracing_enabled',
  },
  {
    id: 'obs-tracing-endpoint',
    title: 'OTLP Endpoint',
    description:
      'OTLP collector URL (e.g. Jaeger or Tempo). A bad URL blackholes spans — destructive.',
    buttonLabel: 'Edit',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'destructive',
    fieldType: 'url',
    fieldKey: 'tracing_endpoint',
    placeholder: 'http://otel-collector:4318/v1/traces',
  },
  {
    id: 'obs-stale-interval',
    title: 'Stale Shelter Cadence',
    description: 'Minutes between stale-shelter detection cycles.',
    buttonLabel: 'Edit',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'safe',
    fieldType: 'number',
    fieldKey: 'monitor_stale_interval_minutes',
    min: 1,
    max: 1440,
    unit: 'minutes',
  },
  {
    id: 'obs-canary-interval',
    title: 'DV Canary Cadence',
    description:
      'Minutes between security RLS-canary probes. Lengthening this weakens the platform’s DV-leak detection posture — destructive.',
    buttonLabel: 'Edit',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'destructive',
    fieldType: 'number',
    fieldKey: 'monitor_dv_canary_interval_minutes',
    min: 1,
    max: 1440,
    unit: 'minutes',
  },
  {
    id: 'obs-temp-interval',
    title: 'Temperature Cadence',
    description:
      'Minutes between NOAA weather fetch cycles. Floor of 1 minute reserved by NOAA rate-limit — destructive.',
    buttonLabel: 'Edit',
    category: 'observability',
    method: 'PUT',
    endpoint: '/api/v1/platform/observability',
    dangerLevel: 'destructive',
    fieldType: 'number',
    fieldKey: 'monitor_temperature_interval_minutes',
    min: 1,
    max: 1440,
    unit: 'minutes',
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
