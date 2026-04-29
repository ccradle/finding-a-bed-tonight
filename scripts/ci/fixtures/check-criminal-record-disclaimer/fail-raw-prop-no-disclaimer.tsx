// Fixture for check-criminal-record-disclaimer-co-rendering.sh — task 7.3.
// Expectation: the guard FAILS on this file (exit 1).
// Reason: it references `criminal_record_policy` in real JSX code but
// does NOT render <CriminalRecordPolicyDisclaimer>.
//
// This file intentionally has the `// criminal_record_policy` mentions
// inside the leading comment block; the strip pass removes those, so
// only the JSX line below counts as the violation.

interface ShelterCard {
  criminal_record_policy: { accepts_felonies: boolean };
}

export function FixtureFail({ shelter }: { shelter: ShelterCard }) {
  return (
    <div>
      <p>Accepts felonies: {String(shelter.criminal_record_policy.accepts_felonies)}</p>
    </div>
  );
}
