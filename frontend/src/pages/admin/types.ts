/**
 * Shared TypeScript interfaces for admin panel tabs.
 * Only types used by multiple tabs or the orchestrator live here.
 * Tab-specific types stay in their respective tab files.
 */

export interface User {
  id: string;
  email: string;
  displayName: string;
  roles: string[];
  dvAccess: boolean;
  status: string;
}

export interface ShelterListItem {
  shelter: {
    id: string;
    name: string;
    addressCity: string;
    updatedAt: string;
    dvShelter: boolean;
    active: boolean;
    deactivatedAt: string | null;
    deactivatedBy: string | null;
    deactivationReason: string | null;
  };
  availabilitySummary: {
    totalBedsAvailable: number | null;
    dataFreshness: string;
    dataAgeSeconds: number | null;
  } | null;
}

export interface ApiKeyRow {
  id: string;
  suffix: string;
  label: string;
  role: string;
  active: boolean;
  createdAt: string;
  lastUsedAt: string | null;
  oldKeyExpiresAt: string | null;
}

export interface ApiKeyCreateResponse {
  id: string;
  plaintextKey: string;
  suffix: string;
  label: string;
  role: string;
}

export interface ImportRow {
  id: string;
  importType: string;
  filename: string;
  created: number;
  updated: number;
  skipped: number;
  errors: number;
  createdAt: string;
}

export type SubscriptionStatus = 'ACTIVE' | 'PAUSED' | 'FAILING' | 'DEACTIVATED' | 'CANCELLED';

export interface SubscriptionRow {
  id: string;
  eventType: string;
  callbackUrl: string;
  status: SubscriptionStatus;
  expiresAt: string | null;
  lastError: string | null;
  consecutiveFailures: number;
  createdAt: string;
}

// platform-observability-split (2026-05-02): 'observability' removed —
// the per-tenant observability tab is gone; platform-wide config moved
// to the platform-operator dashboard, the temperature threshold moved
// to the surge tab.
export type TabKey = 'users' | 'shelters' | 'apiKeys' | 'imports' | 'subscriptions' | 'surge' | 'oauth2Providers' | 'hmisExport' | 'analytics' | 'dvEscalations';
