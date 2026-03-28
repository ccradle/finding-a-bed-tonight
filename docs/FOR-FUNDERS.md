# For Funders and Foundation Officers

> This page is written for foundation program officers, grant reviewers, and boards evaluating this project for funding. For technical details, see [FOR-DEVELOPERS.md](FOR-DEVELOPERS.md).
>
> **Note on personas:** Priya Anand is an AI-generated persona defined in [PERSONAS.md](../PERSONAS.md) to guide design decisions. She represents the type of foundation officer this platform is built for, not a real individual.

---

## The Problem

A family of five is sitting in a parking lot at midnight. A social worker has 30 minutes before the family's willingness to engage disappears. Right now, that social worker is making phone calls -- to shelters that may be closed, full, or unable to serve that family's specific needs. There is no shared system for real-time shelter bed availability in most US communities.

Commercial software does not serve this space because there is no profit motive. Homeless services operate on tight grants with no margin for per-seat licensing. The result: social workers keep personal spreadsheets, shelter coordinators answer midnight phone calls, and families wait in parking lots while the system fails them.

Finding A Bed Tonight exists to close that gap.

---

## What This Platform Does

An outreach worker opens the app, searches for available beds filtered by the family's needs (family with children, wheelchair accessible, pets allowed), and places a hold on a bed in under a minute. The shelter coordinator sees the hold and knows someone is coming. The family rides to the shelter instead of waiting in a parking lot while phone calls are made.

The platform is designed to reduce total crisis-to-placement time from an estimated 55-140 minutes (serial phone calls, voicemails, stale spreadsheets) to 25-45 minutes. The digital workflow itself -- search to bed hold -- takes under 10 seconds in testing. The remaining time is human: conversation, decision-making, and transport, which no technology can eliminate.

**Important:** The time reduction is a target outcome based on workflow analysis. It has not yet been measured in a real-world pilot. The platform captures the data needed for measurement automatically. A 90-day pilot with 5+ shelters would produce the evidence to validate or revise this claim.

For the full logic model, evidence basis, and recommended grant language, see the [Theory of Change](theory-of-change.md).

---

## Who Uses It

The platform is not yet deployed in any community. It is designed to serve any US Continuum of Care, from a rural volunteer-run network of faith shelters to a metro area with 50+ shelters. The demo environment uses Raleigh, North Carolina as its reference geography (shelter names, addresses, and NOAA weather station) because the project team is based there — this does not represent a partnership or deployment agreement with Wake County or any local organization.

Three deployment tiers keep costs proportional to community size:
- **Lite** ($15-30/month): small CoCs, rural counties
- **Standard** ($30-75/month): mid-size cities
- **Full** ($100+/month): metro areas with real-time HMIS integration

---

## Sustainability

The honest answer to "what keeps this project alive in year 3?" is a staged plan:

1. **Now:** Open-source under Apache 2.0. The software cannot disappear. Any deployment survives independently.
2. **With pilot adoption:** Establish a fiscal sponsor (Open Source Collective, Software Freedom Conservancy, or a local NC nonprofit) to receive grants and contributions.
3. **With multiple adopters:** Cost-sharing consortium where participating CoCs share maintenance costs.
4. **At scale:** Institutional host (Code for America, a university, or a managed services model).

The zero-vendor-dependency architecture is intentional: every deployment runs on standard, widely available technology (Java, PostgreSQL, React). No proprietary component. No single point of failure.

For the full sustainability path, see the [Sustainability Narrative](sustainability-narrative.md).

---

## What Funding Enables

Funding accelerates three things that the project cannot achieve on volunteer effort alone:

1. **Community adoption support** -- helping additional CoCs deploy the platform, training coordinators, supporting the onboarding process
2. **A full-time maintainer** -- moving from single-maintainer open source to a sustainable development pace with security patches, feature development, and community support
3. **Third-party accessibility audit** -- the platform has a self-assessed WCAG 2.1 AA conformance report; an independent audit by a qualified accessibility firm would strengthen the case for government adoption

Every dollar builds permanent infrastructure. The Apache 2.0 license means the software, its documentation, and every improvement funded by a grant remains freely available to every community, forever. There is no scenario where funding builds something that later becomes proprietary or inaccessible.

---

## What Makes This Different

- **Open source means permanent.** Unlike vendor platforms that disappear when contracts end, this software is irrevocably free.
- **Zero client PII.** The platform never stores names, dates of birth, or identifying information about the people being served. This is a design choice, not a limitation.
- **DV survivor protection.** Domestic violence shelters are invisible in the system. No address stored, no client information, referral tokens hard-deleted within 24 hours. Designed to support VAWA and FVPSA requirements.
- **Novel demand data.** The platform captures unmet demand (searches that return zero results by population type, time, and geography). No HMIS system currently produces this data. It is the evidence base for "we need more beds for families on Tuesday nights" -- the kind of data that drives funding and policy decisions.

---

## Recommended Grant Language

> "Finding A Bed Tonight is designed to reduce total crisis-to-placement time from an estimated 55-140 minutes (serial phone calls, stale spreadsheets) to 25-45 minutes by providing real-time bed availability with a 3-tap hold to secure the bed during transport. This target is based on workflow analysis; the digital workflow alone (search to hold) takes under 10 seconds in end-to-end testing. Pilot measurement with [named partner] is planned for [timeframe]."

This language is honest, specific, and defensible. It names the basis for the claim and commits to measurement. See the [Theory of Change](theory-of-change.md) for the full evidence basis.

---

*Finding A Bed Tonight -- For Funders and Foundation Officers*
