# Operations docs

Operator-facing documentation that does not belong in the
top-level [`runbook.md`](../runbook.md) (general FABT operations)
or the version-pinned `oracle-update-notes-v*.md` (per-deploy
procedures).

## Contents

- [`platform-operator-user-guide.md`](./platform-operator-user-guide.md)
  — F11 v0.54 — onboarding and daily-use guide for platform-operator
  users (the small group who hold cross-tenant operational
  responsibility for a running FABT deployment). Covers first-time
  setup, daily login, lost-phone recovery, operator-2 onboarding,
  and the escalate-vs-self-serve decision tree.
- [`screenshots/`](./screenshots) — annotated screenshots used by
  the user guide. Captured via
  `e2e/playwright/tests/capture-platform-operator-screenshots.spec.ts`
  against the local dev stack; re-run after UI copy or layout
  changes.

## Related

- [`../runbook.md`](../runbook.md) — top-level FABT operator runbook.
- [`../observability/platform-admin-monitoring.md`](../observability/platform-admin-monitoring.md)
  — Prometheus metrics, alert rules, and runbook anchors for the
  platform-operator surface.
- `../oracle-update-notes-v*.md` — per-version deploy procedures.
