# GitHub Issue Draft

**To create:** `gh issue create --title "[CSP] Remove style-src unsafe-inline when IBM Carbon #5678 ships" --label security --label tech-debt --body "$(cat docs/security/github-issue-csp-unsafe-inline.md)"`

(Or copy/paste the body below into the GitHub web UI.)

---

## Title
`[CSP] Remove style-src unsafe-inline when IBM Carbon #5678 ships`

## Labels
- `security`
- `tech-debt`

## Body

### Summary

Our nginx CSP includes `style-src 'self' 'unsafe-inline'` because IBM Carbon Design System (`@carbon/react`) injects React `style={{...}}` props on component internals at runtime. ZAP baseline scans flag this as a Medium finding (alert 10055-6).

We accepted this risk for v0.40 (cross-tenant-isolation-audit) per a warroom review — no realistic exploit path for FABT's no-user-HTML data model, and removing it requires forking Carbon (multi-week effort + ongoing upgrade tax). Full rationale: `docs/security/csp-policy.md`.

This issue tracks the eventual removal once IBM ships their planned fix.

### What we're waiting on

[carbon-design-system/ibm-products#5678](https://github.com/carbon-design-system/ibm-products/issues/5678) — opened Jul 2024, Severity 2.

IBM's planned remediation:
1. Replace `style={{}}` with classNames on internal components (DataGrid, FilterPanel, InlineEditCell, etc.)
2. Set dynamic styles via `useIsomorphicEffect()`
3. Add `react/forbid-component-props` ESLint rule

Status as of issue creation: **open, no shipped fix.**

### Why we can't fix it ourselves

- **CSP nonces don't apply.** Nonces cover `<style>` elements; Carbon uses `style="..."` HTML attributes. Per [MDN style-src](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/style-src), attribute-form inline is not noncealble.
- **CSP hashes don't apply.** Carbon's runtime styles vary with viewport, scroll state, component state — every render produces a different hash.
- **`'strict-dynamic'` for styles doesn't exist.** W3C webappsec-csp#625 is the open proposal; no shipping browser support.
- **Forking Carbon costs multi-week + perpetual integration debt** against IBM's release cadence. Cost-benefit fails for FABT's threat model.

### Compensating controls (already in place)

- `script-src 'self'` (no `unsafe-inline`, no `unsafe-eval`) — the actual XSS line of defense
- `frame-ancestors 'none'` — clickjacking defense
- `base-uri 'self'` — prevents `<base>` tag injection
- All user-supplied content escaped at the React layer (no `dangerouslySetInnerHTML` for user content)
- API responses redacted (`WebhookResponseRedactor`); errors HTML-encoded by Spring `ObjectMapper`

### Action when fix ships

1. Bump `@carbon/react` (and `@carbon/ibm-products` if applicable) in `frontend/package.json`
2. Remove `'unsafe-inline'` from `style-src` in `infra/nginx/` (search for `Content-Security-Policy`)
3. Re-run ZAP baseline against local stack: `docker run --rm zaproxy/zap-stable ...` (see `docs/security/csp-policy.md`)
4. Confirm 0 Medium findings related to CSP `style-src`
5. Update `docs/security/csp-policy.md` to remove the "Accepted exception" section
6. Update memory: `project_csp_unsafe_inline_carbon_tracking.md`
7. Close this issue with the merge commit + ZAP scan output as evidence

### Revisit cadence

**Quarterly.** Next manual review: **2026-07-16** (90 days after v0.40 ship). Add a calendar reminder OR check the operator memory `project_csp_unsafe_inline_carbon_tracking.md` for the date.

### Cross-references

- `docs/security/csp-policy.md` — full CSP policy + threat-model rationale + compensating controls
- `docs/security/zap-v0.40-local.md` — ZAP scan that surfaced this as Medium
- `CHANGELOG.md` v0.40.0 — accepted-risk note
- IBM tracker: https://github.com/carbon-design-system/ibm-products/issues/5678
