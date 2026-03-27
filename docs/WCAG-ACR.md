# Accessibility Conformance Report — Finding A Bed Tonight

**VPAT 2.5 WCAG Edition**

## Product Information

| Field | Value |
|---|---|
| **Product Name** | Finding A Bed Tonight (FABT) |
| **Product Version** | v0.12.1 |
| **Report Date** | March 26, 2026 |
| **Product Description** | Open-source emergency shelter bed availability platform. React 19 progressive web application with Spring Boot 4.0 backend (Java 25, virtual threads). Used by outreach workers, shelter coordinators, and CoC administrators. |
| **Contact** | GitHub: github.com/ccradle/finding-a-bed-tonight |
| **Applicable Standard** | WCAG 2.1 Level AA (per ADA Title II final rule, 28 CFR 35.200) |

## Self-Assessment Disclaimer

> This report reflects a **self-assessment** conducted by the project team using automated tools and manual review. It does not constitute a third-party certification of accessibility compliance. Organizations considering adoption should conduct their own evaluation appropriate to their requirements. For questions about specific findings or remediation plans, contact the project team via GitHub.

## Evaluation Methods

| Method | Details |
|---|---|
| **Automated scanning** | axe-core 4.x via @axe-core/playwright — 8 page scans covering login, search, coordinator dashboard, admin panel (users, shelters, analytics, observability tabs). Zero violations on all pages. Tags: wcag2a, wcag2aa, wcag21a, wcag21aa. |
| **Virtual screen reader** | @guidepup/virtual-screen-reader — 6 automated tests verifying screen reader navigation output, ARIA role announcements, status label announcements, and lang attribute switching. |
| **Manual review** | Keyboard-only navigation testing of core workflows. Visual inspection of color contrast, touch targets, and focus indicators. |
| **Browsers tested** | Chromium (via Playwright). Manual spot checks in Chrome and Firefox. |
| **Assistive technology** | Virtual screen reader (automated). Real screen reader testing (NVDA/VoiceOver) planned for release-gate CI via @guidepup/playwright. |
| **CI integration** | 114 Playwright tests including 8 axe-core scans + 6 virtual screen reader tests. Zero violations is a blocking gate. |

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
| **1.1.1 Non-text Content** | Partially Supports | All functional images and icons have aria-label or aria-hidden with adjacent text. Map markers and chart data points do not yet have full text alternatives. Recharts accessibilityLayer integration planned. |
| **1.2.1 Audio-only and Video-only** | Not Applicable | Product contains no audio or video content. |
| **1.2.2 Captions (Prerecorded)** | Not Applicable | No prerecorded media. |
| **1.2.3 Audio Description or Media Alternative** | Not Applicable | No prerecorded media. |
| **1.3.1 Info and Relationships** | Partially Supports | Admin tab bar uses role="tablist"/role="tab"/role="tabpanel" with aria-selected and aria-controls. Data tables use semantic HTML. Some form inputs use aria-label instead of linked label elements. Sortable table headers do not yet have aria-sort attributes. |
| **1.3.2 Meaningful Sequence** | Supports | Content follows logical reading order. DOM order matches visual presentation. |
| **1.3.3 Sensory Characteristics** | Supports | Instructions do not rely solely on shape, color, size, or location. Status badges include text labels alongside color. |
| **1.4.1 Use of Color** | Supports | All status indicators (freshness badges, RAG utilization, reservation status, batch job status) include text labels alongside color. Data freshness shows "Fresh", "Stale", "Unknown" text. Utilization shows percentage + status word (OK/Low/Over). |
| **1.4.2 Audio Control** | Not Applicable | No audio content. |
| **2.1.1 Keyboard** | Partially Supports | All primary workflows are keyboard-operable. Admin tab bar supports arrow key navigation per W3C APG tabs pattern. Skip-to-content link available. Some modal dialogs may not fully trap focus. Full keyboard audit of all interactive elements pending. |
| **2.1.2 No Keyboard Trap** | Supports | No known keyboard traps. Tab/Shift+Tab navigates freely. Escape closes modal dialogs. |
| **2.1.4 Character Key Shortcuts** | Not Applicable | No single-character keyboard shortcuts are implemented. |
| **2.2.1 Timing Adjustable** | Supports | Bed reservation hold has a configurable timeout (default 90 minutes, adjustable via Admin UI) with visible countdown. The longer default provides additional time for users with disabilities or complex discharge workflows. JWT session timeout shows a modal alertdialog 2 minutes before expiry with "Continue Session" (triggers token refresh) and "Log Out" options. Uses role="alertdialog" with focus trap, aria-modal, and BroadcastChannel for cross-tab sync. Users can extend unlimited times. |
| **2.2.2 Pause, Stop, Hide** | Not Applicable | No auto-updating, moving, or blinking content. Reservation countdown is informational text, not animated. |
| **2.3.1 Three Flashes or Below Threshold** | Supports | No flashing content. |
| **2.4.1 Bypass Blocks** | Supports | Skip-to-content link is the first focusable element on every page. Visible on keyboard focus, hidden from mouse users. Links to main content area. |
| **2.4.2 Page Titled** | Supports | Browser title is set. Route changes are announced via aria-live region. |
| **2.4.3 Focus Order** | Supports | Focus order follows logical reading sequence. On route change, focus moves to main content area. Admin tab bar uses roving tabindex so focus follows the active tab. |
| **2.4.4 Link Purpose (In Context)** | Supports | All links have descriptive text or aria-label. Download buttons clearly labeled "Download HIC CSV" / "Download PIT CSV". |
| **2.5.1 Pointer Gestures** | Not Applicable | No multi-point or path-based gestures required. All actions achievable via single tap/click. |
| **2.5.2 Pointer Cancellation** | Supports | All actions trigger on click (up event), not on pointer down. |
| **2.5.3 Label in Name** | Supports | Visible labels match or are contained within accessible names. |
| **2.5.4 Motion Actuation** | Not Applicable | No motion-based input used. |
| **3.1.1 Language of Page** | Supports | HTML lang attribute set to "en" by default. Updated to "es" when user switches to Spanish locale. |
| **3.2.1 On Focus** | Supports | No context changes on focus. |
| **3.2.2 On Input** | Supports | Population type filter and locale selector do not trigger unexpected context changes. Form submissions require explicit button press. |
| **3.3.1 Error Identification** | Supports | Form validation errors are displayed inline with descriptive messages. Error states use both color and text. |
| **3.3.2 Labels or Instructions** | Supports | All form inputs have visible labels or placeholders with aria-label. Required fields are indicated. |
| **4.1.1 Parsing** | Not Applicable | WCAG 2.1 notes this criterion is always satisfied for content using HTML or XML specifications. |
| **4.1.2 Name, Role, Value** | Supports | Admin tab bar uses role="tablist"/role="tab" with aria-selected and aria-controls. Toggle switches use role="switch" with aria-checked. Stepper buttons have aria-label ("Increase"/"Decrease") at 44px touch target. All selects, inputs, and date picker have aria-label. Icon-only buttons have aria-label. Session timeout dialog uses role="alertdialog" with aria-modal, aria-labelledby, and aria-describedby. Tables do not have sort functionality (N/A for aria-sort). |

---

## WCAG 2.1 Level AA Success Criteria

| Criterion | Conformance | Remarks |
|---|---|---|
| **1.3.4 Orientation** | Supports | Content is not restricted to a single orientation. Responsive design adapts to portrait and landscape. |
| **1.3.5 Identify Input Purpose** | Partially Supports | Login form inputs have appropriate type attributes (email, password). Not all form inputs use autocomplete attributes for standard fields. |
| **1.4.3 Contrast (Minimum)** | Supports | All text meets 4.5:1 minimum contrast ratio. Secondary text colors updated from #64748b to #475569 (7.5:1). Status badge text verified against badge backgrounds. Opacity-based transparency removed where it reduced effective contrast. axe-core confirms zero contrast violations. |
| **1.4.4 Resize Text** | Supports | Text can be resized to 200% without loss of content or functionality. Layout is responsive. |
| **1.4.5 Images of Text** | Supports | No images of text used. All text is rendered as HTML text. |
| **1.4.10 Reflow** | Supports | Content reflows at 320px viewport width. Mobile bottom navigation provides access to all sections. No horizontal scrolling required for primary content. |
| **1.4.11 Non-text Contrast** | Supports | UI components (buttons, inputs, toggle switches) meet 3:1 contrast ratio against adjacent colors. Focus indicators are visible. |
| **1.4.12 Text Spacing** | Supports | No text clipping when line height, paragraph spacing, letter spacing, or word spacing are increased per criterion requirements. Inline styles use relative units where possible. |
| **1.4.13 Content on Hover or Focus** | Partially Supports | Shelter card hover effects are purely visual (border color change). No content appears on hover that is not also available through other means. Tooltip content is not used. |
| **2.4.5 Multiple Ways** | Supports | Content accessible via navigation menu and direct URL. Bed search provides text search and population type filter. |
| **2.4.6 Headings and Labels** | Supports | Pages use descriptive headings (h1-h3). Section headings in admin panel clearly label each section. Form labels describe their purpose. |
| **2.4.7 Focus Visible** | Partially Supports | Browser default focus indicators are visible on most elements. Custom-styled buttons and tabs may have reduced focus visibility in some browsers. Skip-to-content link has explicit focus styling. |
| **3.1.2 Language of Parts** | Supports | When user switches to Spanish locale, document.documentElement.lang is updated to "es". Content is served in the selected language via react-intl. |
| **3.2.3 Consistent Navigation** | Supports | Navigation is consistent across all pages. Sidebar (desktop) and bottom nav (mobile) maintain same order and labeling. |
| **3.2.4 Consistent Identification** | Supports | Components that have the same functionality are identified consistently. Save buttons, status badges, and navigation elements use consistent labeling. |
| **3.3.3 Error Suggestion** | Supports | When form input errors are detected, suggestions for correction are provided (e.g., "Email is required", "Password must be at least 8 characters"). |
| **3.3.4 Error Prevention (Legal, Financial, Data)** | Supports | Destructive actions (surge activation, user deletion, test data reset) require confirmation dialogs. Bed holds are reversible (cancel). DV referral acceptance/rejection requires explicit action. |
| **4.1.3 Status Messages** | Supports | Save confirmation on coordinator dashboard uses aria-live="polite" for screen reader announcement. Route changes announced via aria-live region. Session timeout warning uses role="alertdialog" which is announced immediately. Error states use visible text messages. |

---

## Remediation Plan

| Item | Target Version | Description |
|---|---|---|
| Real screen reader CI gate | v0.14.0 | Add @guidepup/playwright with NVDA on Windows CI runners for release-gate testing |
| Focus visible enhancement | v0.14.0 | Add explicit focus ring styles to all custom interactive elements |
| Autocomplete attributes | v0.14.0 | Add autocomplete attributes to login and form inputs |
| Keyboard-only flow test | v0.14.0 | Automated Playwright test for outreach search→hold→confirm via keyboard only |

**Completed in v0.13.0:**
- ~~Session timeout warning~~ — implemented with role="alertdialog", BroadcastChannel sync
- ~~Recharts data table toggle~~ — "Show as table" toggle with prefers-reduced-motion support
- ~~Screen reader testing~~ — automated via @guidepup/virtual-screen-reader (6 tests)
- ~~Consistent aria-live~~ — save confirmations, route announcements use aria-live

---

## Testing Tools

| Tool | Version | Purpose |
|---|---|---|
| axe-core | 4.x | Automated WCAG 2.1 AA scanning (8 page scans, zero violations) |
| @axe-core/playwright | Latest | Integration with Playwright E2E test suite |
| @guidepup/virtual-screen-reader | 0.32.x | Virtual screen reader simulation (6 tests, no real SR required) |
| Playwright | Latest | Browser automation for accessibility and functional testing |
| Chromium | Latest | Primary test browser |

---

*Finding A Bed Tonight — Accessibility Conformance Report*
*VPAT 2.5 WCAG Edition*
*Self-assessment — not a third-party certification*
*Report date: March 26, 2026 — Product version: v0.12.1*
