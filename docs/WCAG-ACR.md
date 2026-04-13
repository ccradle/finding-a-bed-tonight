# Accessibility Conformance Report — Finding A Bed Tonight

**VPAT 2.5 WCAG Edition**

## AI Preparation Disclosure

> **This document was prepared with AI assistance** (Claude, Anthropic) based on
> automated test results, source code analysis, and manual review. All conformance
> claims are grounded in verifiable evidence: axe-core scan results, Playwright test
> assertions, and source code inspection. However, this document **must be reviewed
> by a qualified accessibility professional** before being used in procurement
> conversations, government adoption discussions, or formal compliance assertions.
> AI-assisted analysis cannot replace testing with real assistive technology by
> users with disabilities.

## Product Information

| Field | Value |
|---|---|
| **Product Name** | Finding A Bed Tonight (FABT) |
| **Product Version** | v0.37.0 |
| **Report Date** | April 8, 2026 |
| **Product Description** | Open-source emergency shelter bed availability platform. React 19 progressive web application with Spring Boot 4.0 backend (Java 25, virtual threads). Used by outreach workers, shelter coordinators, and CoC administrators to find, hold, and manage emergency shelter beds in real time. |
| **Contact** | GitHub: github.com/ccradle/finding-a-bed-tonight |
| **Applicable Standard** | WCAG 2.1 Level AA (per ADA Title II Final Rule, 28 CFR 35.200) |

## Self-Assessment Disclaimer

> This report reflects a **self-assessment** conducted by the project team using
> automated tools, virtual screen reader simulation, and manual code review. It
> **does not constitute a third-party certification** of accessibility compliance.
> Organizations considering adoption should conduct their own evaluation with
> real assistive technology testing appropriate to their requirements. For questions
> about specific findings or remediation plans, contact the project team via GitHub.

## Evaluation Methods

| Method | Details |
|---|---|
| **Automated scanning** | axe-core 4.x via @axe-core/playwright — 8 page scans covering login, search, coordinator dashboard, admin panel (users, shelters, analytics, observability tabs). Zero violations on all pages. Tags: `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`. CI-blocking gate: any violation fails the build. |
| **VPAT verification tests** | 18 targeted Playwright tests in `wcag-vpat-verification.spec.ts` — each maps to a specific WCAG success criterion to verify ACR claims against actual application behavior. |
| **Virtual screen reader** | @guidepup/virtual-screen-reader 0.32.x — 6 automated tests verifying screen reader navigation output, ARIA role announcements, freshness badge status text, and `lang` attribute switching. |
| **Color system tests** | 6 Playwright tests — light/dark mode contrast scans, no-hardcoded-hex source check, dark mode rendering verification. |
| **Typography tests** | 4 Playwright tests — font consistency, no serif fonts, WCAG 1.4.12 text spacing override injection, form element font inheritance. |
| **Manual review** | Keyboard-only navigation of core workflows. Visual inspection of color contrast, touch targets, and focus indicators. Source code review of ARIA attributes, role assignments, and focus management. |
| **Browsers tested** | Chromium (via Playwright). Manual spot checks in Chrome and Firefox. |
| **Assistive technology** | Virtual screen reader (automated). **Real screen reader testing (NVDA/VoiceOver) has not been performed.** This is the most significant gap in this assessment. |
| **CI integration** | Playwright test suite includes 8 axe-core scans, 6 virtual screen reader tests, 6 color system tests, 4 typography tests, and 18 VPAT verification tests. Zero violations is a blocking gate for axe-core scans. |

## Conformance Level Definitions

| Term | Meaning |
|---|---|
| **Supports** | The functionality of the product has at least one method that meets the criterion without known defects |
| **Partially Supports** | Some functionality of the product does not meet the criterion |
| **Does Not Support** | The majority of product functionality does not meet the criterion |
| **Not Applicable** | The criterion is not relevant to the product |

---

## WCAG 2.1 Level A Success Criteria

| Criterion | Conformance | Remarks |
|---|---|---|
| **1.1.1 Non-text Content** | Partially Supports | All functional images and icons have `aria-label` or `aria-hidden="true"` with adjacent visible text. Icon-only buttons (kebab menu, notification bell, stepper +/−) have explicit `aria-label`. **Gap:** Chart data points in the Analytics tab (Recharts) do not have full text alternatives. A "Show as table" toggle provides an alternative data representation. Map markers, if used, do not have individual text alternatives. Verified by VPAT test and axe-core scan. |
| **1.2.1 Audio-only and Video-only** | Not Applicable | Product contains no audio or video content. |
| **1.2.2 Captions (Prerecorded)** | Not Applicable | No prerecorded media. |
| **1.2.3 Audio Description or Media Alternative** | Not Applicable | No prerecorded media. |
| **1.3.1 Info and Relationships** | Partially Supports | Admin tab bar uses `role="tablist"` / `role="tab"` / `role="tabpanel"` with `aria-selected` and `aria-controls`. Session timeout dialog uses `role="alertdialog"` with `aria-modal`, `aria-labelledby`, `aria-describedby`. Kebab overflow menu uses `role="menu"` / `role="menuitem"`. Data tables use semantic HTML `<table>`. Tables are not sortable, so `aria-sort` is not applicable. **Gap:** Some form inputs use `aria-label` instead of linked `<label>` elements. Verified by virtual screen reader tests and VPAT verification tests. |
| **1.3.2 Meaningful Sequence** | Supports | Content follows logical reading order. DOM order matches visual presentation. Mobile bottom nav and desktop sidebar maintain consistent item ordering. |
| **1.3.3 Sensory Characteristics** | Supports | Instructions and status indicators do not rely solely on shape, color, size, or location. Freshness badges show "Fresh", "Stale", "Unknown" text alongside color. Utilization indicators show percentage and status word. |
| **1.4.1 Use of Color** | Supports | All status indicators include text labels alongside color: freshness badges ("Fresh"/"Stale"/"Unknown"), RAG utilization (percentage + "OK"/"Low"/"Over"), reservation status (text label), batch job status (text), connection status banner (text). Verified by virtual screen reader test confirming badge text is announced. |
| **1.4.2 Audio Control** | Not Applicable | No audio content. |
| **2.1.1 Keyboard** | Partially Supports | All primary workflows are keyboard-operable: login, bed search, shelter card expansion, bed hold, coordinator dashboard updates, admin tab navigation. Skip-to-content link present on every page. Admin tab bar supports arrow key navigation per W3C APG tabs pattern. Modal dialogs implement focus management with Escape to close and focus return. **Gap:** Full keyboard audit of every interactive element across all pages has not been performed. Some secondary flows (e.g., import, export) have not been keyboard-tested. |
| **2.1.2 No Keyboard Trap** | Supports | No known keyboard traps. Tab/Shift+Tab navigates freely through all pages. Escape closes modal dialogs (session timeout, change password, shelter detail). Focus returns to trigger element on modal close (kebab menu, notification bell). Verified by VPAT keyboard tests. |
| **2.1.4 Character Key Shortcuts** | Not Applicable | No single-character keyboard shortcuts are implemented. All keyboard interaction uses standard Tab, Arrow, Escape, and Enter keys. |
| **2.2.1 Timing Adjustable** | Supports | Bed reservation hold has a configurable timeout (default 90 minutes, adjustable per tenant via Admin UI up to 240 minutes) with visible countdown timer. JWT session timeout shows a modal `alertdialog` 2 minutes before expiry with "Continue Session" and "Log Out" options. Session can be extended unlimited times. Uses `role="alertdialog"` with focus trap, `aria-modal`, and `BroadcastChannel` for cross-tab sync. |
| **2.2.2 Pause, Stop, Hide** | Not Applicable | No auto-updating, moving, or blinking content beyond text-based countdown timers. SSE-driven availability updates are text changes, not animations. Analytics charts respect `prefers-reduced-motion`. |
| **2.3.1 Three Flashes or Below Threshold** | Supports | No flashing content anywhere in the application. |
| **2.4.1 Bypass Blocks** | Supports | Skip-to-content link is the first focusable element on every page. Visible only on keyboard focus, hidden from mouse users. Links to `#main-content` which receives programmatic focus on route change. Verified by VPAT test and virtual screen reader test. |
| **2.4.2 Page Titled** | Supports | Browser title is set for the application. Route changes are announced to screen readers via `aria-live="polite"` region with descriptive page names (e.g., "Navigated to Search Beds", "Navigated to Administration"). |
| **2.4.3 Focus Order** | Supports | Focus order follows logical reading sequence matching visual layout. On route change, focus moves to main content area via `ref` + `tabIndex={-1}`. Admin tab bar uses roving tabindex — focus follows the active tab. |
| **2.4.4 Link Purpose (In Context)** | Supports | All links have descriptive text or `aria-label`. Navigation items are descriptive ("Shelters", "Search", "Admin"). App title link has `aria-label="Finding A Bed Tonight — go to home page"`. |
| **2.5.1 Pointer Gestures** | Not Applicable | No multi-point or path-based gestures required. All actions achievable via single tap or click. |
| **2.5.2 Pointer Cancellation** | Supports | All actions trigger on click (up event), not on pointer down. |
| **2.5.3 Label in Name** | Supports | Visible labels match or are contained within accessible names. Buttons show their text content as the accessible name. Icon-only buttons use `aria-label` matching their visible tooltip or function. |
| **2.5.4 Motion Actuation** | Not Applicable | No motion-based input (shake, tilt) is used. |
| **3.1.1 Language of Page** | Supports | HTML `lang` attribute set to "en" by default. Updated to "es" when user switches to Spanish locale via `document.documentElement.lang = locale`. Verified by VPAT test and virtual screen reader test. |
| **3.2.1 On Focus** | Supports | No context changes on focus. Receiving keyboard focus does not trigger navigation, form submission, or modal dialogs. |
| **3.2.2 On Input** | Supports | Population type filter, constraint filter, and locale selector do not trigger unexpected context changes. All form submissions require explicit button press. |
| **3.3.1 Error Identification** | Supports | Form validation errors are displayed inline adjacent to the input with descriptive text messages. Error states use both color and text labels. Login errors show specific messages ("Invalid credentials", "Tenant not found"). |
| **3.3.2 Labels or Instructions** | Supports | All form inputs have visible labels, placeholder text, or `aria-label`. Required fields are indicated. Login form fields have descriptive `data-testid` attributes and visible labels. |
| **4.1.1 Parsing** | Not Applicable | WCAG 2.1 errata marks this criterion as always satisfied for content using HTML specifications. React 19's virtual DOM produces valid HTML. |
| **4.1.2 Name, Role, Value** | Supports | Admin tab bar: `role="tablist"` / `role="tab"` with `aria-selected`, `aria-controls`. Toggle switches: `role="switch"` with `aria-checked`. Stepper buttons: `aria-label="Increase"` / `aria-label="Decrease"` at 44px minimum. Session timeout: `role="alertdialog"` with `aria-modal`, `aria-labelledby`, `aria-describedby`. Kebab menu: `role="menu"` / `role="menuitem"` with `aria-expanded`. Notification bell: `aria-expanded`, `aria-label`. All verified by virtual screen reader tests. |

---

## WCAG 2.1 Level AA Success Criteria

| Criterion | Conformance | Remarks |
|---|---|---|
| **1.2.4 Captions (Live)** | Not Applicable | No live video or audio content. |
| **1.2.5 Audio Description (Prerecorded)** | Not Applicable | No prerecorded video content. |
| **1.3.4 Orientation** | Supports | Content is not restricted to a single orientation. Responsive design adapts to portrait and landscape via flexbox layout. Mobile bottom navigation appears below 768px breakpoint. |
| **1.3.5 Identify Input Purpose** | Supports | Login form inputs have `autocomplete="email"`, `autocomplete="current-password"`, and `autocomplete="organization"`. Change password modal uses `autocomplete="current-password"` and `autocomplete="new-password"`. Admin password reset uses `autocomplete="new-password"`. All standard personal data form fields have appropriate `autocomplete` attributes. Verified by VPAT verification test (hard assertion on all 6 fields). |
| **1.4.3 Contrast (Minimum)** | Supports | All text meets 4.5:1 minimum contrast ratio in both light and dark modes. Color system uses CSS custom properties with `@media (prefers-color-scheme: dark)` overrides. Light mode: primary text on white = 7.8:1, body text = 17.4:1, muted text = 4.6:1. Dark mode: Carbon Blue-40 on dark = 7.7:1, body text = 13.5:1, muted text = 5.76:1 (previously 3.75:1, fixed in color system migration). Automated axe-core contrast scans run in both light and dark modes — zero violations. Documented contrast ratios verified in `frontend/src/global.css`. |
| **1.4.4 Resize Text** | Supports | Text can be resized to 200% without loss of content or functionality. Layout uses flexbox for reflow. Typography system uses CSS custom properties — all font sizes centrally defined and scale consistently. Base font size set via `var(--text-base)`. |
| **1.4.5 Images of Text** | Supports | No images of text used. All text is rendered as HTML text with system fonts. |
| **1.4.10 Reflow** | Supports | Content reflows at 320px viewport width without horizontal scrolling. Mobile bottom navigation provides access to all sections. Responsive layout adapts at 768px breakpoint (sidebar → bottom nav). Verified by VPAT reflow test at 320px viewport. |
| **1.4.11 Non-text Contrast** | Supports | UI components (buttons, inputs, toggle switches) meet 3:1 contrast ratio against adjacent colors. Focus border color: `#1a56db` in light mode (against white = 7.8:1), `#78a9ff` in dark mode (against dark bg = 7.7:1). Interactive element borders visible against backgrounds. |
| **1.4.12 Text Spacing** | Supports | No text clipping when WCAG 1.4.12 text spacing overrides are injected (line-height 1.5×, letter-spacing 0.12em, word-spacing 0.16em, paragraph spacing 2em). Global CSS sets `line-height: var(--leading-normal)` (1.5, unitless ratio). All line-height values use unitless ratios, not fixed pixels. Verified by automated Playwright test that injects spacing overrides and checks for overflow on all views. |
| **1.4.13 Content on Hover or Focus** | Supports | Shelter card hover effects are purely visual (border color change). No content appears on hover/focus that is not also available through other means. No tooltips that show content only on hover. Kebab menu content accessible via click/keyboard, not hover. |
| **2.4.5 Multiple Ways** | Supports | Content accessible via navigation menu (sidebar/bottom nav) and direct URL. Bed search provides text search and population type filter as alternative discovery methods. |
| **2.4.6 Headings and Labels** | Supports | Pages use descriptive headings (h1-h3). Section headings in admin panel clearly label each section. Form labels describe their purpose. Navigation items have descriptive text. |
| **2.4.7 Focus Visible** | Supports | Global `:focus-visible` CSS rules in `global.css` provide a 2px solid outline using `var(--color-border-focus)` with 2px offset on all focusable elements. Input fields use a border-color highlight with box-shadow instead of outline for a cleaner visual. Focus token switches from `#1a56db` (light, 7.8:1 contrast) to `#78a9ff` (dark, 7.7:1 contrast). All previous `outline: 'none'` overrides on interactive inputs have been removed — only the main content programmatic focus target (`tabIndex={-1}`) retains `outline: 'none'`, which is an accepted pattern per WCAG. Skip-to-content link has explicit focus styling. Verified by VPAT verification tests: login form inputs, outreach search input, and dark mode focus indicators all pass hard assertions. |
| **3.1.2 Language of Parts** | Supports | When user switches to Spanish locale, `document.documentElement.lang` is updated to "es". Content is served in the selected language via `react-intl`. Verified by VPAT test and virtual screen reader test. **Note:** Individual inline content in a different language (e.g., shelter names in Spanish within an English page) is not separately marked with `lang` attributes. This edge case has not been observed in practice. |
| **3.2.3 Consistent Navigation** | Supports | Navigation is consistent across all pages. Desktop sidebar and mobile bottom nav maintain same item order and labeling. Header items appear in the same position on all authenticated pages. |
| **3.2.4 Consistent Identification** | Supports | Components with the same functionality use consistent labeling. Save buttons, status badges, freshness indicators, and navigation items are identified consistently throughout the application. |
| **3.3.3 Error Suggestion** | Supports | When form input errors are detected, suggestions for correction are provided: "Email is required", "Password must be at least 8 characters", "Tenant slug is required". Login errors distinguish between invalid credentials and tenant not found. |
| **3.3.4 Error Prevention (Legal, Financial, Data)** | Supports | Destructive actions require confirmation dialogs: surge activation, user deletion, TOTP disable, test data reset. Bed holds are reversible (cancel available). DV referral acceptance/rejection requires explicit action with confirmation. |
| **4.1.3 Status Messages** | Supports | Save confirmation on coordinator dashboard uses `aria-live="polite"` for screen reader announcement. Route changes announced via `aria-live` region. Session timeout warning uses `role="alertdialog"` for immediate announcement. Connection status banner uses `role="status"`. Replay summary (after offline queue sync) uses `role="status"` with `aria-live="polite"`. |

---

## Summary of Conformance

| Level | Supports | Partially Supports | Does Not Support | Not Applicable |
|---|---|---|---|---|
| **Level A (30)** | 18 | 3 | 0 | 9 |
| **Level AA (20)** | 18 | 0 | 0 | 2 |
| **Total (50)** | **36** | **3** | **0** | **11** |

### Criteria Requiring Attention

| Criterion | Status | Gap | Remediation |
|---|---|---|---|
| **1.1.1 Non-text Content** | Partially Supports | Chart data points lack text alternatives | Recharts `accessibilityLayer` integration or aria-describedby on chart containers |
| **1.3.1 Info and Relationships** | Partially Supports | Some inputs use `aria-label` instead of `<label>` | Associate inputs with `<label for="...">` where possible |
| **2.1.1 Keyboard** | Partially Supports | Full keyboard audit of all interactive elements not completed | Complete keyboard-only walkthrough of every page and flow |

### Criteria Fixed in This Version

| Criterion | Previous | Now | What Changed |
|---|---|---|---|
| **1.3.5 Identify Input Purpose** | Does Not Support | Supports | Added `autocomplete` attributes to all login, password change, and admin password reset forms (6 fields total) |
| **2.4.7 Focus Visible** | Partially Supports | Supports | Added global `:focus-visible` CSS rules with `var(--color-border-focus)` token. Removed `outline: 'none'` from 5 interactive input elements. Input fields use border-color + box-shadow on focus. Verified in light mode, dark mode, and through nginx. |

---

## Remediation Plan

**Completed in v0.29.4 WCAG fixes:**
- ~~`autocomplete` attributes~~ — Added to all login, password change, and admin reset forms (6 fields)
- ~~Focus visible styles~~ — Global `:focus-visible` CSS added; `outline: 'none'` removed from 5 inputs
- ~~`aria-sort` on tables~~ — Tables are not sortable; `aria-sort` is not applicable

**Remaining:**

| Item | Priority | Description |
|---|---|---|
| **Real screen reader testing** | High | Test with NVDA (Windows) and VoiceOver (macOS/iOS) by users with screen reader experience. Virtual screen reader tests are a useful CI gate but do not replace real AT testing. This is the most significant gap in this assessment. |
| **Chart text alternatives** | Medium | Add `aria-describedby` to Recharts chart containers linking to data tables, or integrate Recharts `accessibilityLayer` when stable. |
| **Full keyboard audit** | Medium | Complete keyboard-only walkthrough of every page and interactive flow. Document any elements that cannot be reached or activated via keyboard. |
| **`<label>` associations** | Low | Where form inputs use `aria-label` instead of linked `<label for="...">` elements, prefer the linked pattern for better AT support. |

---

## What This Assessment Does NOT Cover

Transparency about the limits of this assessment:

1. **No real screen reader testing.** All screen reader assertions are based on virtual simulation (@guidepup/virtual-screen-reader) and ARIA attribute inspection. NVDA, JAWS, and VoiceOver may behave differently with this markup.

2. **No testing with users with disabilities.** The most important accessibility test — having people with disabilities use the application — has not been performed.

3. **No mobile AT testing.** TalkBack (Android) and VoiceOver (iOS) have not been tested. The application is responsive and used primarily on mobile by outreach workers (Darius Webb persona).

4. **Automated tools catch ~30-40% of WCAG issues** (per Deque research). The remaining 60-70% require manual testing, AT testing, and user testing. axe-core zero violations does not mean zero accessibility issues.

5. **Hospital locked-down environments.** Dr. James Whitfield's use case (locked-down hospital Chrome, no service worker, no app install) has not been accessibility-tested in that specific environment.

---

## Testing Tools

| Tool | Version | Purpose |
|---|---|---|
| axe-core | 4.x | Automated WCAG 2.1 AA scanning (8 page scans, zero violations) |
| @axe-core/playwright | 4.11.1 | Integration with Playwright E2E test suite |
| @guidepup/virtual-screen-reader | 0.32.x | Virtual screen reader simulation (6 tests) |
| Playwright | 1.49.x | Browser automation for accessibility, color, typography, and VPAT verification tests |
| Chromium | Latest | Primary test browser |

---

## Evidence: Test Coverage

The following automated tests directly verify WCAG claims in this report:

| Test File | Tests | WCAG Coverage |
|---|---|---|
| `accessibility.spec.ts` | 8 | Full axe-core WCAG 2.1 AA scan across all pages |
| `screen-reader.spec.ts` | 6 | 1.1.1, 1.3.1, 1.4.1, 2.4.1, 3.1.1, 4.1.2 |
| `color-system.spec.ts` | 6 | 1.4.3, 1.4.11 (light + dark mode contrast) |
| `typography.spec.ts` | 4 | 1.4.12 (text spacing), font consistency |
| `wcag-vpat-verification.spec.ts` | 18 | 1.1.1, 1.3.1, 1.3.5, 1.4.1, 1.4.3, 1.4.10, 1.4.12, 2.1.1, 2.4.1, 2.4.2, 2.4.5, 2.4.7, 3.1.1, 3.1.2, 4.1.3 |

**Total: 42 accessibility-specific automated tests.**

---

*Finding A Bed Tonight — Accessibility Conformance Report*
*VPAT 2.5 WCAG Edition*
*Self-assessment, prepared with AI assistance — not a third-party certification*
*Must be reviewed by a qualified accessibility professional before use in procurement*
*Report date: April 13, 2026 — Product version: v0.36.0*
