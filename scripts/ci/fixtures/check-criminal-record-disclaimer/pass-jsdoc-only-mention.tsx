/**
 * Fixture: passes the guard.
 * Reason: the only mentions of `criminal_record_policy`,
 * `accepts_felonies`, and `excluded_offense_types` are inside this
 * JSDoc block. The guard's strip-comments pass MUST drop these lines
 * before scanning, so the file is clean.
 *
 * This is the false-positive case the original spec wording got right
 * via `^\s*\*` filtering — JSDoc body lines start with whitespace + `*`.
 */

export function FixtureJsdocOnly({ name }: { name: string }) {
  return <div>{name}</div>;
}
