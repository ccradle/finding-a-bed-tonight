# Shelter Import Format — 211 CSV

Import shelters in bulk via the Admin panel → Imports → 211 CSV tab.

## Quick Start

1. **Download** the [template CSV](../infra/templates/shelter-import-template.csv) or the [example CSV with sample data](../infra/templates/shelter-import-example.csv)
2. **Fill in** your shelter data — one row per shelter
3. **Upload** on the Import page and review the preview before committing

## File Requirements

- **Format:** CSV (comma-separated values)
- **Encoding:** UTF-8 (Excel users: Save As → CSV UTF-8)
- **First row:** Column headers (names are flexible — see "Recognized Headers" below)
- **Max file size:** 5 MB

## Re-Import (Update Existing Shelters)

If a shelter with the **same name and city** already exists in your organization, it will be **updated** with the new data from the CSV. If no match is found, a new shelter is created.

The import preview shows "Will update: N / Will create: M" before you commit, so you can verify the outcome.

**DV shelter safety:** If a re-import changes a shelter's DV status (dvShelter column), the preview will flag this as a safety-sensitive change because it affects who can see the shelter's address.

## Column Reference

### Required Columns

| Column | Type | Description | Example |
|---|---|---|---|
| `name` | Text | Shelter name | Hope House Emergency Shelter |
| `addressCity` | Text | City | Asheville |

### Recommended Columns

| Column | Type | Description | Example |
|---|---|---|---|
| `addressStreet` | Text | Street address | 123 Main St |
| `addressState` | Text | State (2-letter code) | NC |
| `addressZip` | Text | ZIP code | 28801 |
| `phone` | Text | Intake phone number | 828-555-0100 |
| `populationTypesServed` | Text | Populations served (semicolon-delimited) | SINGLE_ADULT;VETERAN |
| `bedsTotal` | Number | Total bed capacity | 50 |
| `bedsOccupied` | Number | Currently occupied beds | 30 |

### Optional Columns

| Column | Type | Description | Default | Example |
|---|---|---|---|---|
| `dvShelter` | Boolean | Is this a DV (domestic violence) shelter? | false | true |
| `sobrietyRequired` | Boolean | Must guests be sober? | false | true |
| `referralRequired` | Boolean | Is a referral required for entry? | false | false |
| `idRequired` | Boolean | Is government ID required? | false | false |
| `petsAllowed` | Boolean | Are pets allowed? | false | true |
| `wheelchairAccessible` | Boolean | Is the facility wheelchair accessible? | false | true |
| `maxStayDays` | Number | Maximum length of stay in days | (none) | 90 |
| `latitude` | Decimal | GPS latitude (WGS 84) | (none) | 35.5951 |
| `longitude` | Decimal | GPS longitude (WGS 84) | (none) | -82.5515 |

## Allowed Values

### Population Types

Use these exact values in the `populationTypesServed` column, separated by semicolons:

| Value | Description |
|---|---|
| `SINGLE_ADULT` | Single adults (18+) |
| `FAMILY_WITH_CHILDREN` | Families with children |
| `WOMEN_ONLY` | Women only |
| `VETERAN` | Veterans |
| `YOUTH_18_24` | Transition-age youth (18-24) |
| `YOUTH_UNDER_18` | Unaccompanied youth (under 18) |
| `DV_SURVIVOR` | Domestic violence survivors |

**Example:** A shelter serving single adults and veterans: `SINGLE_ADULT;VETERAN`

### Boolean Values

All boolean columns accept flexible values:

| True | False |
|---|---|
| `true`, `yes`, `1`, `Y` | `false`, `no`, `0`, `N`, (empty) |

Case-insensitive: `TRUE`, `Yes`, `y` all work.

## Recognized Header Names

Each column accepts multiple header name formats. The importer automatically matches these (case-insensitive):

| Column | Accepted headers |
|---|---|
| name | `name`, `agency_name`, `agency name`, `organization`, `shelter_name`, `shelter name` |
| addressStreet | `address`, `street`, `street_address`, `street address`, `address_1` |
| addressCity | `city`, `address_city`, `address city` |
| addressState | `state`, `address_state`, `province`, `state_province` |
| addressZip | `zip`, `zipcode`, `zip_code`, `zip code`, `postal_code`, `postal code` |
| phone | `phone`, `telephone`, `phone_number`, `phone number` |
| latitude | `lat`, `latitude` |
| longitude | `lng`, `lon`, `longitude` |
| dvShelter | `dvshelter`, `dv_shelter`, `dv shelter`, `is_dv`, `dv` |
| populationTypesServed | `populationtypesserved`, `population_types_served`, `population types`, `populations_served` |
| bedsTotal | `bedstotal`, `beds_total`, `beds total`, `total_beds`, `total beds` |
| bedsOccupied | `bedsoccupied`, `beds_occupied`, `beds occupied`, `occupied_beds` |
| sobrietyRequired | `sobrietyrequired`, `sobriety_required`, `sobriety` |
| referralRequired | `referralrequired`, `referral_required`, `referral` |
| idRequired | `idrequired`, `id_required`, `id required` |
| petsAllowed | `petsallowed`, `pets_allowed`, `pets` |
| wheelchairAccessible | `wheelchairaccessible`, `wheelchair_accessible`, `wheelchair`, `ada` |
| maxStayDays | `maxstaydays`, `max_stay_days`, `max_stay`, `max stay` |

## Error Handling

If any rows have validation errors, the import preview shows:
- A summary: "47 valid rows, 3 rows with errors"
- Per-row error details: the row number, the field, and a human-readable message
- A "Download errors" button to export just the failed rows for offline correction

Valid rows are imported even if other rows have errors. Fix the failed rows and re-upload only those.

## Bed Availability After Import

- **bedsTotal** sets the total capacity per population type
- **bedsOccupied** sets the current occupancy (defaults to 0 if not provided)
- **bedsAvailable** is calculated automatically: `bedsTotal - bedsOccupied - bedsOnHold`
- **bedsOnHold** is managed by the reservation system, not the import

If `populationTypesServed` is provided with `bedsTotal`, a capacity entry is created for each listed population type. Coordinators can refine per-type capacity after import via the admin panel.
