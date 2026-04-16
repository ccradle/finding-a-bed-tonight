# Content Security Policy (CSP)

## Current policy

Served by nginx on every HTML response:

```
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
img-src 'self' data:;
font-src 'self';
connect-src 'self';
manifest-src 'self';
worker-src 'self';
frame-ancestors 'none';
base-uri 'self';
form-action 'self';
```

Configuration source: `infra/nginx/` (search for `Content-Security-Policy`).

## Threat-model rationale per directive

| Directive | Value | Why |
|---|---|---|
| `default-src 'self'` | strict | Deny by default for unspecified resource types. |
| **`script-src 'self'`** | **strict — no `unsafe-inline`, no `unsafe-eval`** | The actual XSS line of defense. JavaScript only loads from the same origin; bundle is built from the trusted `frontend/` source tree. |
| `style-src 'self' 'unsafe-inline'` | exception (see "Accepted exception" below) | Carbon Design System library injects React `style={{}}` props at runtime; cannot be removed without forking the library. |
| `img-src 'self' data:` | strict + data: | `data:` allows base64 image URIs used by Carbon icon components. |
| `font-src 'self'` | strict | All fonts bundled with the app; no Google Fonts or external CDN. |
| `connect-src 'self'` | strict | XHR/fetch/SSE only to the same origin; no third-party APIs. |
| `manifest-src 'self'` | strict | PWA manifest served by the app. |
| `worker-src 'self'` | strict | Service worker only from same origin. |
| `frame-ancestors 'none'` | strict | Clickjacking defense — FABT cannot be embedded in any iframe. |
| `base-uri 'self'` | strict | Prevents `<base>` tag injection from rewriting relative URLs. |
| `form-action 'self'` | strict | Form submissions only to same origin. |

## Accepted exception — `style-src 'unsafe-inline'`

**Status:** accepted risk, tracked enhancement.
**Source:** IBM Carbon Design System (`@carbon/react`) injects inline styles via React `style={{...}}` props on component internals (DataGrid, FilterPanel, InlineEditCell, and similar). This is acknowledged in IBM's tracker [carbon-design-system/ibm-products#5678](https://github.com/carbon-design-system/ibm-products/issues/5678) (opened Jul 2024, Severity 2). IBM's planned remediation: replace `style={{}}` with classNames, set dynamic styles via `useIsomorphicEffect()`, add `react/forbid-component-props` ESLint rule. **Not yet shipped.**

**Why we cannot mitigate via CSP nonces or hashes:**
- **Nonces** (`'nonce-X'`) apply to `<style>` elements. Carbon uses `style="..."` HTML **attributes**. Per [MDN style-src](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/style-src), nonces do not cover the attribute form.
- **Hashes** (`'sha256-X'`) require each inline style block be known at build time. Carbon's runtime styles vary with viewport, scroll state, and component state — every render would produce a different hash.
- **`'strict-dynamic'`** for styles does not exist. W3C [webappsec-csp#625](https://github.com/w3c/webappsec-csp/issues/625) is the open proposal; no shipping browser support.

**Why the risk is acceptable for FABT:**
- Realistic CSS-injection attacks (per [Scott Helme — "Can you get pwned with CSS?"](https://scotthelme.co.uk/can-you-get-pwned-with-css/)) require either:
  - An HTML/CSS injection sink — FABT has none (no user-generated HTML, server-rendered React from a trusted bundle, all user input is escaped via React's default JSX behavior), OR
  - An existing XSS — in which case `script-src 'self'` (no `unsafe-inline`, no `unsafe-eval`) is the actual defense, and the attacker would already have JavaScript execution.
- IBM's own products (Cloud, Watson) ship CSPs that include `'unsafe-inline'` in `style-src`. No published case study of a Carbon-React app successfully removing it.

**Cost to remove without IBM's fix:** multi-week effort to fork `@carbon/react` and `@carbon/ibm-products`, rewrite every `style={{}}` site to use classNames, AND maintain the fork against IBM's release cadence (ongoing upgrade tax). Cost-benefit fails for FABT's threat model.

## Compensating controls

The following directives compensate for the `style-src` exception by closing the realistic exploit paths:

1. **`script-src 'self'`** (strict) — the only place an attacker could inject executable code is blocked.
2. **`frame-ancestors 'none'`** — FABT cannot be embedded; clickjacking defense.
3. **`base-uri 'self'`** — prevents `<base>` tag injection that could redirect relative URLs.
4. **No `unsafe-eval` anywhere.**
5. **All user-supplied content escaped at the React layer** — JSX auto-escapes string children; we never use `dangerouslySetInnerHTML` for user content.
6. **Output sanitization at the API layer** — webhook response bodies are redacted via `WebhookResponseRedactor`; user input echoed in error messages is HTML-encoded by Spring's default `ObjectMapper`.

## Scanner findings

- **ZAP baseline scan v0.40 (2026-04-16):** 1 Medium alert — "CSP: style-src unsafe-inline" (4 instances). Findings file: `docs/security/zap-v0.40-local.md`. Noted in v0.40 release notes as accepted risk.

## Tracking + revisit cadence

- **GitHub issue:** `[CSP] Remove style-src unsafe-inline when IBM Carbon #5678 ships` (file via `gh issue create`, label `security` + `tech-debt`, link IBM #5678).
- **Revisit cadence:** quarterly review. On review:
  1. Check IBM #5678 status — has it shipped in `@carbon/react`?
  2. If yes: bump Carbon dep, retest CSP, update this doc.
  3. If no: document next review date.
- **Memory note:** `project_csp_unsafe_inline_carbon_tracking.md` in operator memory.

## Compliance posture

This CSP is **designed to mitigate** the XSS and clickjacking threat surface for FABT's no-user-HTML data model. It is **not "CSP compliant"** in the strict sense expected by frameworks like PCI DSS 4.0 (which flag any `'unsafe-inline'`). FABT does not currently target PCI DSS or any framework that requires `'unsafe-inline'` removal as a hard control.

Casey-style language: "The CSP is designed to mitigate XSS and clickjacking for FABT's threat model. The single `style-src 'unsafe-inline'` exception is tracked for removal upon IBM Carbon Design System resolution of [issue #5678](https://github.com/carbon-design-system/ibm-products/issues/5678)."
