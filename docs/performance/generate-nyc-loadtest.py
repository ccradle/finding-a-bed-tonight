#!/usr/bin/env python3
"""
FABT Scale Load Test Data Generator
====================================
Generates configurable-scale data in a dedicated 'nyc-loadtest' tenant
for performance testing. Default: 1 year, 500 shelters, 600 users (~2.8M rows).

Usage:
  # Dry run (estimate only):
  python generate-nyc-loadtest.py --dry-run --days 30 --shelters 50

  # 30-day small-scale trial:
  python generate-nyc-loadtest.py --days 30 --shelters 50 --outreach 50

  # Full NYC year:
  python generate-nyc-loadtest.py

  # Reproducible run:
  python generate-nyc-loadtest.py --seed 42

Cleanup:
  psql -U fabt -d fabt -f docs/performance/nyc-loadtest-cleanup.sql

Requirements:
  pip install psycopg[binary]

Reference: docs/performance/nyc-scale-load-test-plan.md
"""

import argparse
import csv
import io
import math
import random
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone

psycopg = None  # Lazy import — not needed for --dry-run

# =============================================================================
# Constants
# =============================================================================

TENANT_SLUG = "nyc-loadtest"
TENANT_NAME = "NYC Load Test (Temporary)"
TENANT_ID = uuid.UUID("f0000000-0000-0000-0000-000000000001")

DEFAULT_START_DATE = datetime(2025, 4, 1, tzinfo=timezone.utc)

POPULATION_TYPES = [
    "SINGLE_ADULT", "FAMILY_WITH_CHILDREN", "WOMEN_ONLY",
    "VETERAN", "YOUTH_18_24", "YOUTH_UNDER_18", "DV_SURVIVOR",
]

POP_TYPE_PROB = {
    "SINGLE_ADULT": 0.90,
    "FAMILY_WITH_CHILDREN": 0.40,
    "WOMEN_ONLY": 0.15,
    "VETERAN": 0.10,
    "YOUTH_18_24": 0.08,
    "YOUTH_UNDER_18": 0.05,
    "DV_SURVIVOR": 0.0,  # DV shelters only — handled separately
}

# Hour-of-day activity weights (index 0=midnight, 23=11pm)
# Peak: 6pm-midnight. Quiet: 1am-6am.
HOURLY_WEIGHTS = [1,1,1,1,1,2, 3,4,5,6,7,8, 8,7,6,8,9,10, 12,12,10,8,5,3]

NC_CITIES = [
    "Charlotte", "Raleigh", "Durham", "Greensboro", "Winston-Salem",
    "Fayetteville", "Cary", "Wilmington", "High Point", "Concord",
    "Gastonia", "Asheville", "Huntersville", "Kannapolis", "Mooresville",
]

SHELTER_NAME_PREFIXES = [
    "Safe Haven", "Harbor House", "New Beginnings", "Hope Center",
    "Crossroads", "Open Door", "Lighthouse", "Bridge to Home",
    "Cornerstone", "Mercy", "Grace", "Restoration", "Covenant",
    "Salvation", "Freedom", "Journey", "Pathways", "Sunrise",
    "Community", "Family", "Unity", "Compassion", "Guardian",
    "Anchor", "Beacon", "Refuge", "Oasis", "Horizon", "Serenity",
    "Phoenix", "Summit", "Harvest", "Mission", "Haven",
]

SHELTER_NAME_SUFFIXES = [
    "Shelter", "Center", "House", "Mission", "Place", "Lodge",
    "Residence", "Campus", "Village", "Station", "Home",
]

RESERVATION_STATUSES = ["CONFIRMED", "CANCELLED", "EXPIRED"]
RESERVATION_STATUS_WEIGHTS = [0.75, 0.15, 0.10]

# BCrypt hash of 'admin123' — same as seed data users for testing convenience.
# DO NOT use this pattern in production seed data.
TEST_PW_HASH = "$2b$10$D0ZKzFrhx0qdM0mQy9iZQeLYJPX8/eeEfrJi4TsO5D2o62Q/Fwhva"


# =============================================================================
# Helpers
# =============================================================================

def progress(msg):
    print(f"  [{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def shelters_active_on_day(day_num, total_shelters, total_days):
    """How many shelters are active on a given day (0-based).
    Ramps from 10% at day 0 to 100% at final day following a sqrt curve."""
    progress_pct = min(day_num / max(total_days, 1), 1.0)
    ramp_pct = 0.10 + 0.90 * math.sqrt(progress_pct)
    return max(1, int(total_shelters * ramp_pct))


def random_phone():
    return f"{random.randint(200,999)}-{random.randint(100,999)}-{random.randint(1000,9999)}"


def csv_buffer(rows):
    """Write rows to a StringIO using csv.writer (handles quoting)."""
    buf = io.StringIO()
    writer = csv.writer(buf, quoting=csv.QUOTE_MINIMAL)
    for row in rows:
        writer.writerow(row)
    return buf


def copy_from_buffer(cur, table, columns, buf):
    """Bulk load using COPY FROM STDIN with CSV format."""
    buf.seek(0)
    col_list = ", ".join(columns)
    with cur.copy(f"COPY {table} ({col_list}) FROM STDIN WITH (FORMAT csv, NULL 'NULL')") as copy:
        copy.write(buf.read())


# =============================================================================
# Data Generation
# =============================================================================

def generate_shelters(num_shelters, dv_count):
    """Generate shelter records. Returns list of dicts."""
    shelters = []
    used_names = set()
    for i in range(num_shelters):
        is_dv = i < dv_count
        attempts = 0
        while True:
            prefix = random.choice(SHELTER_NAME_PREFIXES)
            suffix = random.choice(SHELTER_NAME_SUFFIXES)
            # Append index for uniqueness at high shelter counts
            idx_suffix = f" {i+1}" if attempts > 5 else ""
            name = f"{prefix} DV {suffix}{idx_suffix}" if is_dv else f"{prefix} {suffix}{idx_suffix}"
            if name not in used_names:
                used_names.add(name)
                break
            attempts += 1

        city = random.choice(NC_CITIES)
        pop_types = []
        if is_dv:
            pop_types = ["DV_SURVIVOR"]
            if random.random() < 0.3:
                pop_types.append("FAMILY_WITH_CHILDREN")
        else:
            for pt, prob in POP_TYPE_PROB.items():
                if random.random() < prob:
                    pop_types.append(pt)
            if not pop_types:
                pop_types = ["SINGLE_ADULT"]

        shelters.append({
            "id": uuid.uuid4(),
            "name": name,
            "city": city,
            "dv_shelter": is_dv,
            "pop_types": pop_types,
            "beds_total": {pt: random.randint(10, 80) for pt in pop_types},
            "lat": round(35.2 + random.random() * 0.8, 4),
            "lon": round(-80.9 + random.random() * 0.4, 4),
        })
    return shelters


def generate_users(num_outreach, num_coordinators, num_admins, num_dv_outreach):
    """Generate user records. Returns list of dicts."""
    users = []
    for i in range(num_outreach):
        users.append({
            "id": uuid.uuid4(),
            "email": f"outreach-{i:04d}@nyc-loadtest.fabt.org",
            "display_name": f"Outreach Worker {i+1}",
            "roles": ["OUTREACH_WORKER"],
            "dv_access": False,
        })
    for i in range(num_dv_outreach):
        users.append({
            "id": uuid.uuid4(),
            "email": f"dv-outreach-{i:04d}@nyc-loadtest.fabt.org",
            "display_name": f"DV Outreach Worker {i+1}",
            "roles": ["OUTREACH_WORKER"],
            "dv_access": True,
        })
    for i in range(num_coordinators):
        users.append({
            "id": uuid.uuid4(),
            "email": f"coordinator-{i:04d}@nyc-loadtest.fabt.org",
            "display_name": f"Coordinator {i+1}",
            "roles": ["COORDINATOR"],
            "dv_access": i < 20,
        })
    for i in range(num_admins):
        users.append({
            "id": uuid.uuid4(),
            "email": f"admin-{i:04d}@nyc-loadtest.fabt.org",
            "display_name": f"CoC Admin {i+1}",
            "roles": ["COC_ADMIN"],
            "dv_access": i < 5,
        })
    return users


# =============================================================================
# Insert Functions
# =============================================================================

def insert_tenant(cur):
    progress("Creating tenant...")
    cur.execute("""
        INSERT INTO tenant (id, name, slug, config, created_at, updated_at)
        VALUES (%s, %s, %s, %s, NOW(), NOW())
        ON CONFLICT (slug) DO NOTHING
    """, (str(TENANT_ID), TENANT_NAME, TENANT_SLUG,
          '{"default_locale":"en","hold_duration_minutes":90}'))


def insert_users(cur, users):
    progress(f"Inserting {len(users)} users...")
    now = datetime.now(timezone.utc).isoformat()
    rows = []
    for u in users:
        roles_str = "{" + ",".join(u["roles"]) + "}"
        rows.append([
            str(u["id"]), str(TENANT_ID), u["email"], u["display_name"],
            TEST_PW_HASH, roles_str, str(u["dv_access"]).lower(),
            "ACTIVE", now, now, "0",
        ])
    copy_from_buffer(cur, "app_user", [
        "id", "tenant_id", "email", "display_name", "password_hash",
        "roles", "dv_access", "status", "created_at", "updated_at", "token_version",
    ], csv_buffer(rows))


def insert_shelters(cur, shelters):
    progress(f"Inserting {len(shelters)} shelters...")
    now = datetime.now(timezone.utc).isoformat()
    rows = []
    for s in shelters:
        rows.append([
            str(s["id"]), str(TENANT_ID), s["name"],
            f"{random.randint(100,999)} Main St", s["city"], "NC",
            f"{random.randint(27000,28999)}", random_phone(),
            str(s["lat"]), str(s["lon"]),
            str(s["dv_shelter"]).lower(), now, now,
        ])
    copy_from_buffer(cur, "shelter", [
        "id", "tenant_id", "name", "address_street", "address_city",
        "address_state", "address_zip", "phone", "latitude", "longitude",
        "dv_shelter", "created_at", "updated_at",
    ], csv_buffer(rows))


def insert_shelter_constraints(cur, shelters):
    progress(f"Inserting {len(shelters)} shelter constraints...")
    rows = []
    for s in shelters:
        pop_types_str = "{" + ",".join(s["pop_types"]) + "}"
        rows.append([
            str(s["id"]),
            str(random.random() < 0.1).lower(),
            str(random.random() < 0.15).lower(),
            str(random.random() < 0.2).lower(),
            str(random.random() < 0.3).lower(),
            str(random.random() < 0.4).lower(),
            pop_types_str,
        ])
    copy_from_buffer(cur, "shelter_constraints", [
        "shelter_id", "sobriety_required", "id_required", "referral_required",
        "pets_allowed", "wheelchair_accessible", "population_types_served",
    ], csv_buffer(rows))


def insert_initial_availability(cur, shelters):
    """Create initial bed_availability snapshot for each shelter+population_type.
    shelter_capacity was dropped in V20 — capacity data lives in bed_availability."""
    total = sum(len(s["pop_types"]) for s in shelters)
    progress(f"Inserting {total} initial availability snapshots...")
    now = datetime.now(timezone.utc).isoformat()
    rows = []
    for s in shelters:
        for pt in s["pop_types"]:
            beds = s["beds_total"][pt]
            rows.append([
                str(uuid.uuid4()), str(s["id"]), str(TENANT_ID), pt,
                str(beds), "0", "0", str(beds),
                "true", now, "seed-data",
            ])
    copy_from_buffer(cur, "bed_availability", [
        "id", "shelter_id", "tenant_id", "population_type",
        "beds_total", "beds_occupied", "beds_on_hold", "overflow_beds",
        "accepting_new_guests", "snapshot_ts", "updated_by",
    ], csv_buffer(rows))


def insert_coordinator_assignments(cur, shelters, users):
    coordinators = [u for u in users if "COORDINATOR" in u["roles"]]
    dv_coordinators = [u for u in coordinators if u["dv_access"]]
    non_dv_coordinators = [u for u in coordinators if not u["dv_access"]]

    progress("Inserting coordinator assignments...")
    rows = []
    for s in shelters:
        pool = dv_coordinators if s["dv_shelter"] else non_dv_coordinators
        if not pool:
            continue
        num_assign = random.randint(1, 3)
        assigned = random.sample(pool, min(num_assign, len(pool)))
        for coord in assigned:
            rows.append([str(coord["id"]), str(s["id"])])
    progress(f"  {len(rows)} assignments")
    copy_from_buffer(cur, "coordinator_assignment", ["user_id", "shelter_id"], csv_buffer(rows))


def generate_day_activity(shelters, outreach_users, day_date, day_num, total_days):
    """Generate one day of activity data. Returns (avail_rows, search_rows, res_rows, summary_rows)."""
    active_count = shelters_active_on_day(day_num, len(shelters), total_days)
    active_shelters = shelters[:active_count]
    active_workers = outreach_users[:min(len(outreach_users), active_count)]

    avail_rows = []
    search_rows = []
    res_rows = []
    summary_rows = []

    # --- bed_availability ---
    for s in active_shelters:
        updates_per_day = 2 if s["dv_shelter"] else random.randint(3, 6)
        for update_idx in range(updates_per_day):
            hour = [7, 11, 15, 19, 22, 2][update_idx % 6]
            ts = day_date.replace(hour=hour, minute=random.randint(0, 59))
            for pt in s["pop_types"]:
                total = s["beds_total"][pt]
                occupied = random.randint(int(total * 0.5), min(total, int(total * 0.95)))
                on_hold = random.randint(0, min(3, total - occupied))
                avail_rows.append([
                    str(uuid.uuid4()), str(s["id"]), str(TENANT_ID), pt,
                    str(total), str(occupied), str(on_hold), "0",
                    str(random.random() < 0.85).lower(),
                    ts.isoformat(), "system",
                ])

    # --- bed_search_log ---
    num_searches = len(active_workers) * random.randint(4, 12)
    for _ in range(num_searches):
        hour = random.choices(range(24), weights=HOURLY_WEIGHTS)[0]
        ts = day_date.replace(hour=hour, minute=random.randint(0, 59))
        pt = random.choice(["SINGLE_ADULT", "FAMILY_WITH_CHILDREN", "VETERAN", "WOMEN_ONLY", "YOUTH_18_24"])
        results = random.randint(0, 8)
        search_rows.append([
            str(uuid.uuid4()), str(TENANT_ID),
            pt, str(results), ts.isoformat(),
        ])

    # --- reservation ---
    num_reservations = int(num_searches * 0.30)
    non_dv_active = [s for s in active_shelters if not s["dv_shelter"]]
    for _ in range(num_reservations):
        hour = random.choices(range(24), weights=HOURLY_WEIGHTS)[0]
        created = day_date.replace(hour=hour, minute=random.randint(0, 59))
        expires = created + timedelta(minutes=90)
        worker = random.choice(active_workers)
        shelter = random.choice(non_dv_active) if non_dv_active else random.choice(active_shelters)
        status = random.choices(RESERVATION_STATUSES, RESERVATION_STATUS_WEIGHTS)[0]
        pt = random.choice(shelter["pop_types"])
        res_rows.append([
            str(uuid.uuid4()), str(shelter["id"]), str(TENANT_ID),
            str(worker["id"]), pt, status,
            created.isoformat(), expires.isoformat(),
        ])

    # --- daily_utilization_summary ---
    # daily_utilization_summary: one row per shelter per population_type per day
    # Schema (V23): id, tenant_id, shelter_id, population_type, summary_date,
    #               avg_utilization, max_occupied, min_available, snapshot_count
    for s in active_shelters:
        for pt in s["pop_types"]:
            total = s["beds_total"][pt]
            utilization = random.uniform(0.55, 0.95)
            max_occupied = int(total * utilization)
            min_available = total - max_occupied
            snapshot_count = random.randint(2, 6)
            summary_rows.append([
                str(uuid.uuid4()), str(TENANT_ID), str(s["id"]),
                pt, day_date.strftime("%Y-%m-%d"),
                f"{utilization:.4f}",
                str(max_occupied), str(min_available),
                str(snapshot_count),
            ])

    return avail_rows, search_rows, res_rows, summary_rows


def flush_activity_buffers(cur, avail_rows, search_rows, res_rows, summary_rows):
    """COPY buffered activity data to PostgreSQL."""
    if avail_rows:
        copy_from_buffer(cur, "bed_availability", [
            "id", "shelter_id", "tenant_id", "population_type",
            "beds_total", "beds_occupied", "beds_on_hold", "overflow_beds",
            "accepting_new_guests", "snapshot_ts", "updated_by",
        ], csv_buffer(avail_rows))
    if search_rows:
        copy_from_buffer(cur, "bed_search_log", [
            "id", "tenant_id",
            "population_type", "results_count", "search_ts",
        ], csv_buffer(search_rows))
    if res_rows:
        copy_from_buffer(cur, "reservation", [
            "id", "shelter_id", "tenant_id", "user_id",
            "population_type", "status", "created_at", "expires_at",
        ], csv_buffer(res_rows))
    if summary_rows:
        copy_from_buffer(cur, "daily_utilization_summary", [
            "id", "tenant_id", "shelter_id",
            "population_type", "summary_date",
            "avg_utilization", "max_occupied", "min_available",
            "snapshot_count",
        ], csv_buffer(summary_rows))


def validate_row_counts(cur, expected):
    """Query actual row counts and compare to expected. Returns True if all match."""
    progress("Validating row counts...")

    # Tables with direct tenant_id column
    TENANT_SCOPED = {"bed_search_log", "notification", "referral_token", "app_user",
                     "daily_utilization_summary", "audit_events"}
    # Tables scoped through shelter.tenant_id
    SHELTER_SCOPED = {"bed_availability", "reservation", "shelter_capacity",
                      "shelter_constraints", "coordinator_assignment"}

    ok = True
    for table, expected_count in expected.items():
        if table in TENANT_SCOPED:
            cur.execute(f"SELECT COUNT(*) FROM {table} WHERE tenant_id = %s",
                        (str(TENANT_ID),))
        elif table in SHELTER_SCOPED:
            cur.execute(
                f"SELECT COUNT(*) FROM {table} WHERE shelter_id IN "
                f"(SELECT id FROM shelter WHERE tenant_id = %s)",
                (str(TENANT_ID),))
        else:
            progress(f"  {table}: SKIPPED (unknown scope)")
            continue

        actual = cur.fetchone()[0]
        status = "OK" if actual == expected_count else f"MISMATCH (expected {expected_count:,})"
        if actual != expected_count:
            ok = False
        progress(f"  {table}: {actual:,} rows — {status}")
    return ok


# =============================================================================
# Main
# =============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="Generate scale load test data for FABT performance testing",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n"
               "  %(prog)s --dry-run                    # Estimate rows for full NYC year\n"
               "  %(prog)s --dry-run --days 30 --shelters 50  # Estimate for 30-day trial\n"
               "  %(prog)s --days 30 --shelters 50 --outreach 50  # Load 30-day trial\n"
               "  %(prog)s --seed 42                    # Reproducible full year\n"
               "  %(prog)s --validate                   # Load + verify row counts\n",
    )
    parser.add_argument("--host", default="localhost", help="PostgreSQL host")
    parser.add_argument("--port", type=int, default=5432, help="PostgreSQL port")
    parser.add_argument("--db", default="fabt", help="Database name")
    parser.add_argument("--user", default="fabt", help="Database user")
    parser.add_argument("--password", default="fabt", help="Database password")
    parser.add_argument("--days", type=int, default=365, help="Days to generate (default 365)")
    parser.add_argument("--shelters", type=int, default=500, help="Total shelters at full scale (default 500)")
    parser.add_argument("--dv-shelters", type=int, default=50, help="DV shelters included in total (default 50)")
    parser.add_argument("--outreach", type=int, default=500, help="Outreach workers (default 500)")
    parser.add_argument("--coordinators", type=int, default=80, help="Coordinators (default 80)")
    parser.add_argument("--admins", type=int, default=15, help="CoC admins (default 15)")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducible data")
    parser.add_argument("--batch-days", type=int, default=30, help="Days to buffer before COPY flush (default 30)")
    parser.add_argument("--dry-run", action="store_true", help="Print estimated row counts without inserting")
    parser.add_argument("--validate", action="store_true", help="Verify row counts after insertion")
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    total_users = args.outreach + args.coordinators + args.admins + 5
    conninfo = f"host={args.host} port={args.port} dbname={args.db} user={args.user} password={args.password}"

    print(f"=== FABT Scale Load Test Data Generator ===")
    print(f"Target: {args.days} days, {args.shelters} shelters ({args.dv_shelters} DV), {total_users} users")
    print(f"Database: {args.host}:{args.port}/{args.db}")
    if args.seed is not None:
        print(f"Seed: {args.seed} (reproducible)")
    print()

    if args.dry_run:
        avg_active = args.shelters * 0.6
        est_avail = int(avg_active * 10 * args.days)
        est_searches = int(args.outreach * 8 * args.days * 0.6)
        est_reservations = int(est_searches * 0.30)
        est_summaries = int(avg_active * args.days)
        est_total = est_avail + est_searches + est_reservations + est_summaries + args.shelters + total_users
        print(f"=== DRY RUN — Estimated Rows ===")
        print(f"  Shelters:        {args.shelters:,}")
        print(f"  Users:           {total_users:,}")
        print(f"  Availability:    {est_avail:,}")
        print(f"  Searches:        {est_searches:,}")
        print(f"  Reservations:    {est_reservations:,}")
        print(f"  Summaries:       {est_summaries:,}")
        print(f"  Total:           {est_total:,}")
        print(f"  Est. storage:    ~{est_total * 250 / 1024 / 1024 / 1024:.2f} GB (rough)")
        print(f"  Batch size:      {args.batch_days} days per COPY flush")
        print(f"  COPY operations: ~{(args.days // args.batch_days + 1) * 4}")
        sys.exit(0)

    # Import psycopg (not needed for --dry-run)
    global psycopg
    try:
        import psycopg as _psycopg
        psycopg = _psycopg
    except ImportError:
        print("ERROR: psycopg not installed. Run: pip install 'psycopg[binary]'")
        sys.exit(1)

    start_time = time.time()

    progress(f"Generating {args.shelters} shelters...")
    shelters = generate_shelters(args.shelters, args.dv_shelters)

    progress(f"Generating {total_users} users...")
    users = generate_users(args.outreach, args.coordinators, args.admins, 5)

    # Pre-compute user lists (Alex: avoid recomputing per day)
    outreach_users = [u for u in users if "OUTREACH_WORKER" in u["roles"]]

    with psycopg.connect(conninfo, autocommit=False) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM tenant WHERE slug = %s", (TENANT_SLUG,))
            if cur.fetchone()[0] > 0:
                print(f"ERROR: Tenant '{TENANT_SLUG}' already exists. Run cleanup first:")
                print(f"  psql -U {args.user} -d {args.db} -f docs/performance/nyc-loadtest-cleanup.sql")
                sys.exit(1)

            # Structure data
            insert_tenant(cur)
            insert_users(cur, users)
            insert_shelters(cur, shelters)
            insert_shelter_constraints(cur, shelters)
            insert_initial_availability(cur, shelters)
            insert_coordinator_assignments(cur, shelters, users)
            conn.commit()
            progress("Structure data committed.")

            # Activity data — batched by days (Alex: reduce COPY round-trips)
            total_avail = 0
            total_searches = 0
            total_reservations = 0
            total_summaries = 0

            batch_avail = []
            batch_search = []
            batch_res = []
            batch_summary = []

            for day_num in range(args.days):
                day_date = DEFAULT_START_DATE + timedelta(days=day_num)

                a, s, r, sm = generate_day_activity(
                    shelters, outreach_users, day_date, day_num, args.days)
                batch_avail.extend(a)
                batch_search.extend(s)
                batch_res.extend(r)
                batch_summary.extend(sm)

                total_avail += len(a)
                total_searches += len(s)
                total_reservations += len(r)
                total_summaries += len(sm)

                # Flush every batch_days
                if (day_num + 1) % args.batch_days == 0 or day_num == args.days - 1:
                    flush_activity_buffers(cur, batch_avail, batch_search, batch_res, batch_summary)
                    conn.commit()
                    batch_avail.clear()
                    batch_search.clear()
                    batch_res.clear()
                    batch_summary.clear()

                    elapsed = time.time() - start_time
                    active = shelters_active_on_day(day_num, len(shelters), args.days)
                    progress(f"Day {day_num+1}/{args.days} ({day_date.strftime('%Y-%m-%d')}): "
                             f"{active} shelters, "
                             f"{total_avail:,} avail, {total_searches:,} searches, "
                             f"{total_reservations:,} reservations "
                             f"({elapsed:.0f}s elapsed)")

            # Validate if requested (Riley: post-load verification)
            if args.validate:
                # bed_availability includes initial seed snapshots + activity snapshots
                initial_snapshots = sum(len(s["pop_types"]) for s in shelters)
                validate_row_counts(cur, {
                    "bed_availability": total_avail + initial_snapshots,
                    "bed_search_log": total_searches,
                    "reservation": total_reservations,
                    "daily_utilization_summary": total_summaries,
                })

    elapsed = time.time() - start_time
    total_rows = total_avail + total_searches + total_reservations + total_summaries + len(shelters) + len(users)

    print()
    print(f"=== Load Complete ({elapsed:.1f}s) ===")
    print(f"  Tenant:       {TENANT_SLUG} ({TENANT_ID})")
    print(f"  Shelters:     {len(shelters):,}")
    print(f"  Users:        {len(users):,}")
    print(f"  Availability: {total_avail:,} rows")
    print(f"  Searches:     {total_searches:,} rows")
    print(f"  Reservations: {total_reservations:,} rows")
    print(f"  Summaries:    {total_summaries:,} rows")
    print(f"  Total:        {total_rows:,} rows")
    print()
    print(f"Cleanup: psql -U {args.user} -d {args.db} -f docs/performance/nyc-loadtest-cleanup.sql")


if __name__ == "__main__":
    main()
