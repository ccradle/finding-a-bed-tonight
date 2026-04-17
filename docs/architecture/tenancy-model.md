# Tenancy model — pool-by-default, silo-on-trigger

**Status:** ADR (architecture decision record). Authoritative. Update with amendments; do not delete.
**Authors:** multi-tenant-production-readiness warroom (Marcus Webb + Alex Chen + Elena Vasquez + Casey Drummond + Jordan Reyes + Sam Okafor + Riley Cho + Maria Torres + Devon Kessler), 2026-04-16.
**Scope:** The tenant-isolation model FABT offers to CoCs, municipalities, foundations, or research partners. Governs procurement conversations, onboarding flows, and architectural change decisions.

## Decision

FABT operates a **hybrid tenancy model** — discriminator column + PostgreSQL RLS as the primary mechanism — with two deployment tiers offered to prospective tenants:

1. **Pooled tier (default).** Multiple CoCs share one FABT instance. Tenant isolation enforced by service-layer `findByIdAndTenantId` discipline (v0.40 audit closed this for 5 admin surfaces) plus defense-in-depth RLS on regulated tables (this change, D14). Suitable for standard CoCs that do not have HIPAA BAA / VAWA-confidentiality / data-residency requirements that mandate physical separation.

2. **Silo tier (on-request).** Dedicated FABT instance per CoC. Same codebase, same deploy artifact, but the CoC gets its own Postgres + Redis + backend containers on its own VM. Suitable for regulated CoCs (HIPAA BAA, VAWA-exposed DV CoCs with "comparable database" requirements, CoCs operating under federal/state data-residency obligations) or any tenant whose procurement review explicitly disqualifies shared infrastructure.

**Schema-per-tenant and database-per-tenant within a single instance are explicitly non-goals.** These would be separate architectural proposals if any future need forces them; the current discriminator + RLS hybrid is what we operate and harden.

## Tier criteria

A CoC is routed to the silo tier when any of the following apply:

1. **HIPAA BAA request.** Any CoC that executes a Business Associate Agreement with FABT (typically required before touching PHI via the HMIS bridge) is offered the silo tier. Pooled is not categorically prohibited by HIPAA, but most BAA-class procurement reviews prefer the stronger isolation model.
2. **VAWA-exposed DV CoC.** A CoC whose deployment would routinely process DV survivor information (beyond the current opaque-referral pattern) is routed to silo. VAWA § 40002(b)(2) + FVPSA confidentiality + the "Comparable Database" construct (see `docs/architecture/vawa-comparable-database.md`) create a data-custody model the pooled tier cannot serve with a straight face.
3. **Data-residency obligation.** Federal (HHS, HUD, VA) or state contracts that pin data to a specific jurisdiction. The pooled tier lives on Oracle Cloud Infrastructure in `us-ashburn`; a CoC with other-region requirements cannot pool.
4. **Procurement explicit disqualification.** A city or county procurement review that explicitly disqualifies multi-tenant-shared infrastructure — regardless of the technical controls — is routed to silo. The cost of arguing past procurement is higher than the cost of a dedicated instance at current scale.

A CoC NOT meeting any of the above is pooled by default.

## Per-component isolation spectrum

Per AWS SaaS Lens guidance (2025 update), isolation is best modeled per-component rather than as a single silo/pool/bridge choice. FABT's current state + target state per this change:

| Component | Current (v0.40) | Target (post multi-tenant-production-readiness) | Pooled-tier-suitable? |
|---|---|---|---|
| Tenant identity | shared control plane (single `tenant` table, discriminator column) | same, with FSM state field | yes |
| Auth / JWT | single HMAC-SHA256 platform key | per-tenant HKDF-derived key + opaque kid (A1–A2) | yes |
| TOTP / secret encryption | single platform AES-GCM key | per-tenant HKDF-derived DEK + kid versioning (A3–A6) | yes |
| Application DB | single shared Postgres; RLS on 9 tables (v0.40) | single shared Postgres; RLS on 14+ tables including audit_events (B2); LEAKPROOF wrapping (B3); FORCE RLS (B3) | yes |
| Application role | single `fabt_app` NOSUPERUSER | same, with `REVOKE UPDATE, DELETE` on audit tables (G2) | yes |
| Cache (Caffeine L1) | per-service bespoke; some UUID-keyed | `TenantScopedCacheService` wrapper + ArchUnit Family C (C1–C2) | yes |
| Cache (Redis L2) | placeholder TODOs per `project_standard_tier_untested.md` | single-tenant Redis default; ACL-per-tenant as regulated-tier option (C4) | yes (single-tenant); no (shared Redis without ACL) |
| Rate limit | per-IP only | per-(api_key_hash, ip) pre-auth + per-(tenant_id, ip) post-auth (E1); per-tenant config table (E2) | yes |
| Connection pool | single 20-slot HikariCP | same + per-tenant `SET LOCAL statement_timeout` + `work_mem` (B9) | yes |
| Background workers (HMIS push, webhook deliver, email) | FIFO over shared queue | per-tenant inner queue + round-robin fair dispatch (E6) | yes |
| SSE event buffer | global `ConcurrentLinkedDeque` | per-tenant shard + fairness + per-tenant emitter cap (E4–E5) | yes |
| Virtual-thread scheduling | ForkJoinPool#commonPool | same, with ArchUnit Family E guard against synchronized in tenant-dispatched paths (E7) | yes |
| Audit log | tenant_id column (v0.40 V57) | tenant_id + per-tenant hash chain (G1) + REVOKE UPDATE/DELETE (G2) + partitioning (B8) | yes |
| Observability (metrics / traces / logs) | partial tenant tagging (9 metrics) | full OTel baggage `fabt.tenant.id` + per-tenant alert routing (G4–G5) | yes |
| Platform-admin access | no tracking | `platform_admin_access_log` + `@PlatformAdminOnly` aspect (G3) | yes |
| File / blob storage | no file I/O today | regression harness for future file-write paths (J13) | N/A |
| Data residency | implicit `us-any` | `data_residency_region` column (F7); controls activate per jurisdiction need | silo required for non-`us-any` |

A pooled-tier CoC accepts this spectrum. A silo-tier CoC optionally layers on: mTLS at the ingress (D4), HashiCorp Vault Transit for key derivation (D3, A5), egress proxy per-tenant allowlist (I5), per-tenant observability read access (G9). The silo tier runs the same code but additionally activates these regulated-tier features.

## Upgrade path: pooled → silo

A CoC that starts pooled and later requires silo isolation (e.g., adds HIPAA BAA, discovers a data-residency obligation, or a procurement review demands it) can be migrated via:

1. Operator provisions new siloed FABT instance for the CoC.
2. Operator triggers tenant-offboard-with-export from the pooled instance (F5). JSON export with all shelters, beds, users, referrals, audit history, config.
3. Operator imports export into the new siloed instance as a fresh single-tenant deployment.
4. DNS routing + JWT signing keys cut over.
5. Pooled instance executes tenant hard-delete with crypto-shredding of the CoC's DEK (F6). Pooled tenants' data is unaffected.

Estimated migration window: 1–2 business days. Pooled downtime: zero. Silo-instance downtime: new provision, no prior state.

## Anti-goals (explicitly NOT in this model)

- **Schema-per-tenant within a single database.** Rejected: adds schema-migration complexity (every Flyway migration runs N times), fragments query plans, complicates backup/restore at row granularity. If ever needed, separate ADR.
- **Database-per-tenant within a single Postgres cluster.** Rejected: high operational cost for the solo/small-team deployment model; Postgres max_connections scales poorly per-DB.
- **Kubernetes namespace-per-tenant.** Rejected: Oracle Always Free has no K8s; regulated-tier silo tenants get their own VMs instead.
- **True multi-region deployment within one instance.** Rejected: violates discriminator + RLS assumption (data cannot be partitioned geographically without schema changes). If a tenant needs EU residency, they get a siloed EU deployment.

## Blast-radius statement (for pooled tier)

If a cross-tenant leak is discovered in the pooled tier:

- **Worst case scenario:** one tenant's data exposed to another authenticated tenant's user via a code bug (not external attacker).
- **Detection mechanisms:** `fabt.security.cross_tenant_404s` counter (v0.40); tenant-audit hash chain (G1); external weekly anchor (G1); tamper-evident audit logs (G2); `platform_admin_access_log` (G3).
- **Containment:** tenant-quarantine break-glass (K1) isolates affected tenants within minutes.
- **Notification SLAs:** HIPAA 60 days for individuals / HHS; VAWA 24 hours to OVW; GDPR 72 hours to DPA (if applicable).
- **Remediation:** crypto-shred compromised-tenant DEKs (F6); rotate master KEK if scope warrants; pen-test re-engagement.
- **External attack pathway:** not in scope of this blast-radius statement — standard OWASP Top-10 surface covered separately.

## Stakeholder sign-offs

The pool-by-default + silo-on-trigger model has been reviewed and approved by:

- Marcus Webb (security lens) — pool-by-default acceptable for standard CoCs given the defense-in-depth this change installs
- Alex Chen (architecture lens) — discriminator + RLS + per-component isolation spectrum is the right abstraction
- Casey Drummond (legal lens) — HIPAA BAA and VAWA Comparable Database obligations route to silo; pooled tier is defensible for standard tier
- Jordan Reyes (operations lens) — pooled is cheaper to operate; silo-on-request is a documented upgrade path, not a code-rewrite
- Sam Okafor (performance lens) — pooled per-tenant SLOs are achievable per J18 NoisyNeighborSimulation gate
- Maria Torres (PM lens) — aligns with "most CoCs pool; regulated ones get dedicated" procurement reality
- Devon Kessler (training lens) — model is explainable in 3 minutes on the multi-tenant demo walkthrough (M6)
- Riley Cho (QA lens) — both tiers testable; live demo (M1–M11) proves pooled isolation on findabed.org

Future changes to this model require a new ADR addendum reviewed by the same lens set.
