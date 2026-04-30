// Fixture: passes the guard. This is the warroom H3 case.
//
// The original spec wording — `grep -vE '^\s*(\*|//|/\*)'` — would
// FAIL this file because the `// accepts_felonies` mention below is
// at the END of a code line, NOT at column 0. The tightened guard
// strips trailing `//.*` BEFORE the token search, so this case passes.
//
// Without the H3 fix, every developer who writes a `// TODO: handle
// accepts_felonies later` inline comment near unrelated code would
// trigger a false-positive guard failure.

export function FixtureInlineComment({ flag }: { flag: boolean }) {
  const x = flag; // accepts_felonies — TODO: rename when criminal_record_policy ships
  return <div>{String(x)}</div>;
}
