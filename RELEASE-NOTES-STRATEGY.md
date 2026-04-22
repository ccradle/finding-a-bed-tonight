# Release Notes Strategy

## Runbook files

Every production release ships a `docs/oracle-update-notes-vX.Y.Z.md` file.
**All runbooks from v0.50.0 onward MUST follow `docs/runbook-template.md`.**

The template defines:

- Mandatory section order (8 sections, fixed)
- `consulted:` frontmatter block listing every reviewed memory file
- Service-recreate matrix (prevents "forgot to recreate frontend" class of errors)
- Pre-deploy gate checklist derived from the `feedback_*.md` corpus
- Mandatory Playwright smoke gate as a numbered post-deploy step
- Rollback matrix

The worked example is `docs/oracle-update-notes-v0.49.0.md` (back-converted
to the template format on 2026-04-22).

## Authoring a new runbook

1. Copy `docs/runbook-template.md` to `docs/oracle-update-notes-vX.Y.Z.md`
2. Fill in version-specific content
3. Scan `docs/runbook-memory-index.md` and populate the `consulted:` block
4. Check off the service-recreate matrix rows that apply
5. Verify every mandatory pre-deploy gate is present
6. Self-review: all 8 mandatory section headers present, in order

The `opsx-runbook-draft-skill` change (when shipped) automates steps 1–3.
The `ci-runbook-consulted-check` change (when shipped) validates the
`consulted:` block in CI.

## CHANGELOG entries

`CHANGELOG.md` follows the Keep A Changelog convention. `[Unreleased]` items
move to `[vX.Y.Z]` at release-prep time. The release-prep commit is tagged;
the GitHub release is created only after CI scans are green
(`feedback_release_after_scans.md`).

## Older runbooks

`docs/oracle-update-notes-v0.25.0.md` through `docs/oracle-update-notes-v0.48.1.md`
are frozen historical artifacts. They are not migrated to the new template.
