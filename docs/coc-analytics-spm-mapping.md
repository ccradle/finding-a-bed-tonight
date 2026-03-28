# CoC Analytics — HUD System Performance Measures Mapping

> **AI-Generated Document:** This mapping was produced by an AI assistant (Claude) based on published HUD SPM programming specifications and FABT's analytics implementation. It should be reviewed by a subject matter expert familiar with HUD reporting requirements before use in any formal submission.

## Purpose

Map each FABT CoC Analytics metric to its relationship with HUD System Performance Measures (SPMs). Confirm that FABT's metrics complement — not conflict with or attempt to replicate — the seven HUD SPMs.

## Key Finding

**FABT and HUD SPMs measure fundamentally different things:**

- **HUD SPMs** measure **individual client outcomes** — length of homelessness, returns, employment, exits. They require client-level HMIS enrollment data (name, SSN, entry/exit dates, income).
- **FABT** measures **system capacity and operational demand** — bed availability, utilization, search activity, reservation conversion. FABT stores zero client PII.

These are complementary data layers. FABT provides supply-side and demand-side data that HMIS cannot produce. There is no conflict.

## HMIS Data Standards Version

FABT's HMIS Bridge targets **FY 2026 HMIS Data Standards** (effective October 1, 2025). Element 2.07 (Bed and Unit Inventory Information) is the primary data element for HIC reporting.

## Mapping Table

| FABT Metric | HUD SPM | Relationship | Notes |
|---|---|---|---|
| **Bed utilization rate** (avg_utilization in daily_utilization_summary) | SPM 3: Number of Persons Experiencing Homelessness | **Complements** | FABT shows system capacity; SPM 3 counts individuals. High utilization + high SPM 3 count = capacity gap. |
| **Zero-result search count** (bed_search_log where results_count=0) | None | **Novel** | No SPM captures unmet demand from the operational perspective. Zero-result searches are the strongest signal of system capacity shortfall. This is genuinely new data for HUD grant applications. |
| **Reservation conversion rate** (CONFIRMED / total reservations) | None | **Novel** | Measures system friction. Low conversion may indicate hold duration mismatch, transportation barriers, or coordination gaps. No SPM equivalent. |
| **Reservation expiry rate** (EXPIRED / total reservations) | None | **Novel** | Proxy for unmet demand — reservations that expired without placement. Combined with zero-result searches, provides a demand picture no SPM captures. |
| **HIC export** (bed inventory CSV) | SPM 3 (input data) | **Direct input** | HIC data feeds into SPM 3's point-in-time component. FABT generates HIC-format CSV from bed_availability snapshots. |
| **PIT export** (sheltered count CSV) | SPM 3 (input data) | **Direct input** | Sheltered PIT count feeds directly into SPM 3 calculations. FABT generates PIT-format CSV from beds_occupied. |
| **Total system beds** (sum of beds_total) | SPM 3 | **Context** | Total capacity provides denominator for utilization. SPM 3 reports only the count of persons; FABT shows what capacity existed to serve them. |
| **DV aggregate** (suppressed if < 3 shelters) | SPM 3 | **Complements** | FABT's DV aggregation with small-cell suppression (D18) protects shelter identity while contributing to system-wide capacity counts. |
| **Search volume by population type** | SPM 5: First-Time Homelessness | **Context** | Search patterns by population type indicate which populations are seeking shelter most actively. SPM 5 measures first-time entries; FABT shows demand pressure. |
| **Demand vs capacity trends** | All SPMs | **Context** | Long-term trends in demand vs capacity provide planning context for all SPMs. A CoC with declining capacity and rising demand will likely see worsening SPM results. |

## What FABT Does NOT Provide

| HUD SPM Data Need | Why FABT Cannot Provide It |
|---|---|
| Client identity (for deduplication) | Zero-PII design — no names, SSNs, DOBs stored |
| Length of stay (SPM 1) | No enrollment/exit tracking — FABT tracks beds, not clients |
| Exit destinations (SPMs 2, 7) | No client outcome tracking |
| Employment/income (SPM 4) | Not in scope — workforce data requires HMIS |
| Prior homelessness history (SPM 5) | No client history — each search is anonymous |

## Recommendation

FABT should be positioned in grant applications as providing **operational infrastructure data** that enhances SPM reporting, not as a replacement for HMIS client-level tracking. The recommended language:

> "FABT provides real-time bed availability, utilization analytics, and unmet demand tracking that complement HMIS System Performance Measures. FABT's zero-result search data and reservation conversion metrics capture demand-side signals that HMIS cannot produce, strengthening CoC grant applications with operational evidence."

## Sources

- [HUD System Performance Measures — HUD Exchange](https://www.hudexchange.info/programs/coc/system-performance-measures/)
- [SPM Programming Specifications (PDF) — HUD Exchange](https://files.hudexchange.info/resources/documents/System-Performance-Measures-HMIS-Programming-Specifications.pdf)
- [SPM Introductory Guide (PDF) — HUD Exchange](https://files.hudexchange.info/resources/documents/System-Performance-Measures-Introductory-Guide.pdf)
- [HMIS Data Element 2.07 — HUD Exchange](https://www.hudexchange.info/programs/hmis/hmis-data-standards/standards/project-descriptor-data-elements/207-bed-and-unit-inventory-information/)

---

*Finding A Bed Tonight — SPM Methodology Mapping*
*AI-generated document. Review by subject matter expert recommended before formal use.*
