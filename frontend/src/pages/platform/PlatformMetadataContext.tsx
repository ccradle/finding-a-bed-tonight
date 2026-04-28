/**
 * Shared `/me` metadata for the authenticated platform-operator subtree
 * (round 7 H4 fix — eliminate the duplicate fetch banner+dashboard were
 * both firing).
 *
 * Mounted inside {@link PlatformLayout} ONLY when a JWT is present so
 * the unauthenticated `/platform/login` screen doesn't hit `/me`. Both
 * the banner (email + countdown anchor) and the dashboard
 * (last-login / mfa-enrolled / backup-codes) consume this context via
 * {@link usePlatformMetadata} — one fetch, two readers.
 */

import { createContext, useContext, type ReactNode } from 'react';
import { useOperatorMetadata, type PlatformOperatorMe } from './helpers/useOperatorMetadata';

interface PlatformMetadataValue {
  data: PlatformOperatorMe | null;
  loading: boolean;
  error: Error | null;
  anonymized: boolean;
  refetch: () => void;
}

const PlatformMetadataContext = createContext<PlatformMetadataValue | null>(null);

export function PlatformMetadataProvider({ children }: { children: ReactNode }) {
  const value = useOperatorMetadata();
  return (
    <PlatformMetadataContext.Provider value={value}>
      {children}
    </PlatformMetadataContext.Provider>
  );
}

export function usePlatformMetadata(): PlatformMetadataValue {
  const ctx = useContext(PlatformMetadataContext);
  if (!ctx) {
    throw new Error(
      'usePlatformMetadata must be used inside <PlatformMetadataProvider>',
    );
  }
  return ctx;
}
