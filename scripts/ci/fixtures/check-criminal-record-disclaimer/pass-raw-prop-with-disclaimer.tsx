// Fixture: passes the guard.
// Reason: references `criminal_record_policy` AND renders
// <CriminalRecordPolicyDisclaimer> adjacent to the data.

import { CriminalRecordPolicyDisclaimer } from '../../../../frontend/src/components/CriminalRecordPolicyDisclaimer';

interface ShelterCard {
  criminal_record_policy: { accepts_felonies: boolean };
  vawa_protections_apply: boolean;
}

export function FixturePass({ shelter }: { shelter: ShelterCard }) {
  return (
    <div>
      <p>Accepts felonies: {String(shelter.criminal_record_policy.accepts_felonies)}</p>
      <CriminalRecordPolicyDisclaimer vawaProtectionsApply={shelter.vawa_protections_apply} />
    </div>
  );
}
