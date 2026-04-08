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

export type TabKey = 'users' | 'shelters' | 'apiKeys' | 'imports' | 'subscriptions' | 'surge' | 'observability' | 'oauth2Providers' | 'hmisExport' | 'analytics';
