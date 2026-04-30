// Fixture: passes the guard.
// Reason: the only mention of `criminal_record_policy` is inside an
// inline `/* ... */` block on a single line. The guard's
// `s,/\*[^*]*\*/,,g` sed pass strips it before token detection.

export function FixtureInlineBlockComment({ name }: { name: string }) {
  /* explainer: this used to read criminal_record_policy directly */ const x = name;
  return <div>{x}</div>;
}
