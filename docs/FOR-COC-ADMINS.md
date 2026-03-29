# For CoC Administrators

> This page is written for Continuum of Care administrators and program managers. For technical details, see [FOR-DEVELOPERS.md](FOR-DEVELOPERS.md).
>
> **Note on personas:** Marcus Okafor is an AI-generated persona defined in [PERSONAS.md](../PERSONAS.md) to guide design decisions. He represents the type of CoC administrator this platform is built for, not a real individual.

---

## What Problem Does This Solve?

Right now, when an outreach worker needs to place a family in emergency shelter, they make serial phone calls. They call shelters that may be closed, full, or unable to serve that family's needs. There is no shared, real-time system for bed availability in most US communities.

Finding A Bed Tonight gives your outreach workers a live view of every shelter bed in your CoC. Coordinators update their bed counts through a simple dashboard. Workers search, filter, and place a temporary hold on a bed in under a minute. You, as the CoC administrator, see the whole picture: which shelters are full, where demand exceeds supply, and what your community's unmet need looks like over time.

---

## What Can You Do Right Now?

The platform is functional today with these capabilities:

- **Manage shelters and users** -- create shelter profiles, assign coordinators, manage accounts
- **Real-time bed availability** -- coordinators update counts; workers search and filter by population type, constraints (pets, wheelchair, sobriety), and location
- **Bed holds** -- outreach workers hold a bed while transporting a client (default 90 minutes, configurable per tenant; hospital discharge planners can use 2-3 hour holds)
- **Surge events** -- activate White Flag or emergency overflow with one action
- **DV opaque referrals** -- privacy-preserving referral flow for domestic violence shelters (zero client PII, address never stored)
- **HMIS bridge** -- async data push to HMIS vendors (Clarity, WellSky, ClientTrack) with DV aggregation
- **CoC analytics** -- utilization trends, demand signals, batch job management
- **HIC/PIT export** -- available through the analytics dashboard, designed to align with HUD export format
- **Multi-language** -- English and Spanish interface
- **Observability** -- Grafana dashboards, structured logging, health monitoring

---

## HUD Reporting

The analytics module includes HIC/PIT data export aligned with the HUD Inventory.csv schema (FY2024+).

**HIC export columns:** InventoryID, ProjectID, CoCCode, HouseholdType, Availability, UnitInventory, BedInventory, veteran bed breakdown (CHVet/YouthVet/Vet/CHYouth/Youth/CH/Other), ESBedType, InventoryStartDate, InventoryEndDate. All coded fields use HUD integer values (e.g., HouseholdType: 1=without children, 3=adult+child, 4=children only).

**PIT export:** Working document for CoC administrators showing sheltered counts by household type. Note: PIT data is submitted via direct entry in HDX 2.0, not CSV upload. This CSV supports your preparation workflow.

**DV shelters:** Aggregated in both exports per HUD guidance. Suppressed entirely if fewer than 3 DV shelters exist (small-cell protection). DV shelters report HMISParticipation=2 (Comparable Database).

**Important clarification:** The export is designed to support HUD reporting requirements. It has not been certified by HUD. You should review exported data against your existing submission process before relying on it as your sole source.

The [CoC Analytics SPM Mapping](coc-analytics-spm-mapping.md) document details how platform metrics align with HUD System Performance Measures.

---

## How to Onboard a New Shelter

A typical onboarding takes about 7 days:

**Day 1-2: Setup**
1. Create the shelter profile in the admin panel — either one at a time, or bulk-import from a 211 CSV file (Admin → Imports → 2-1-1 Import)
2. Review imported data and edit any details that need correction (phone numbers, addresses) via the Edit link on the Shelters tab
3. If the shelter serves DV survivors, enable the DV Shelter toggle on the edit form — this activates address redaction and audit logging
4. Create a coordinator user account for the shelter's point person
5. Assign the coordinator to the shelter

**Day 3-4: Verification**
4. The coordinator logs in and verifies the shelter information is correct
5. They make their first bed count update to confirm the flow works
6. You review the data on your admin dashboard to confirm it appears correctly

**Day 5-7: Go Live**
7. The shelter begins regular updates (2-4 times per day recommended)
8. Outreach workers can now see the shelter's availability in search results

No developer is needed for any of these steps. The entire process is done through the admin interface.

For shelters that want to participate at a limited level (bed counts only, no reservations), see the [Partial Participation Guide](partial-participation-guide.md).

---

## DV Shelter Protection

This is the most important section for your DV shelter directors. Here is what they need to know, in plain language:

### What the system stores about a DV shelter

- The shelter exists (name, general service area, population types)
- Current bed availability (number only)
- **No client names. No client information of any kind. Zero PII.**
- **The shelter's physical address is never stored in the system.**

### How referrals work

DV shelters are invisible in public search results. An outreach worker cannot see them, find them, or contact them through the platform unless they use the opaque referral flow:

1. The outreach worker requests a referral (no client name entered)
2. The DV shelter coordinator receives the request and screens it
3. If accepted, the coordinator calls the outreach worker directly (warm handoff)
4. The shelter address is shared verbally, never through the platform

### What happens to referral data

Referral tokens are hard-deleted within 24 hours. Not archived. Not soft-deleted. Permanently destroyed. After 24 hours, there is no record in the system that a referral was ever requested.

### Legal framing

The DV protection architecture is designed to support VAWA and FVPSA requirements. It has not been independently certified as compliant. The zero-PII, zero-address, 24-hour hard-delete design means there is effectively nothing to subpoena -- the data does not exist.

For the full technical and legal basis, see [DV-OPAQUE-REFERRAL.md](DV-OPAQUE-REFERRAL.md).

---

## HMIS Connectivity

The HMIS bridge module pushes data asynchronously to your HMIS vendor (Clarity, WellSky, or ClientTrack). Key points:

- **DV shelter data is aggregated before transmission** -- individual DV shelter counts are combined into a single aggregate so no individual shelter is identifiable
- **Outbox pattern** -- data is queued reliably and retried on failure
- **Audit trail** -- every push is logged for your records
- **You control when data is pushed** -- manual trigger or scheduled

---

## What Does It Cost to Deploy?

Finding A Bed Tonight is free, open-source software (Apache 2.0 license). You pay only for the server it runs on.

| Tier | What You Get | Monthly Cost |
|---|---|---|
| **Lite** | PostgreSQL only. Suitable for small CoCs, rural counties, volunteer-run networks. | $15-30 |
| **Standard** | PostgreSQL + Redis caching. Faster search, better for mid-size CoCs with 10-30 shelters. | $30-75 |
| **Full** | PostgreSQL + Redis + Kafka event streaming. For metro areas with 30+ shelters, real-time HMIS push. | $100+ |

Most communities start with Lite and move to Standard when they outgrow it. The same software runs on all three tiers.

For a plain-language explanation of what "free and open-source" means in practice, see [What Does Free Mean?](what-does-free-mean.md).

---

## Support Model

This is a community-supported open-source project. Support is best-effort through GitHub Issues and documentation. There is no 24/7 support hotline.

For communities that need formal support, the platform is designed so that any qualified IT professional or integrator can maintain a deployment. See the [Support Model](support-model.md) for details.

---

## Your Checklist Before Bringing This to Partner Agencies

- [ ] Review the admin panel: can you add a shelter, create a user, assign a coordinator?
- [ ] Walk a coordinator through the 3-tap bed update flow
- [ ] Review the DV protection summary so you can explain it to DV shelter directors
- [ ] Check the HIC/PIT export against your current reporting workflow
- [ ] Understand the hosting cost for your tier
- [ ] Review the [Support Model](support-model.md) so you can answer "who do we call?"

---

*Finding A Bed Tonight -- For CoC Administrators*
