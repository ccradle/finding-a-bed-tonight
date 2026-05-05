import { useContext } from 'react';
import { ContactInfoContext, type ContactInfoState } from './ContactInfoContext';

/**
 * info-email-contact §5.2 — hook for consuming the contact-info state.
 * Returns the platform email, tenant override (when applicable), the
 * resolved value the UI should render, plus loading / error flags.
 *
 * <p>Forward-compat per §5.8: callers should rely on {@code resolvedEmail}
 * for placeholder hydration and only fall back to GH-issues when it is
 * null. {@code platformEmail} and {@code tenantEmail} are exposed for
 * cases that need to distinguish the source (e.g., a debug pane); most
 * UI surfaces should NOT render them separately.
 */
export function useContactInfo(): ContactInfoState {
    return useContext(ContactInfoContext);
}
