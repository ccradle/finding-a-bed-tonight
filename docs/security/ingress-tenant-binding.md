# Ingress Tenant Binding

**Phase D (demo tier) — 2026-04-22**

## How tenant identity flows through the stack

```
Client ──► Cloudflare ──► host nginx ──► fabt-frontend-nginx ──► Spring backend
                                         (strips headers)         (reads JWT)
```

1. Client sends `Authorization: Bearer <token>`. The JWT payload contains `tenantId`
   (a UUID), signed with the per-tenant key identified by the `kid` header claim
   (Phase A4 enhancement).
2. `fabt-frontend-nginx` (`infra/docker/nginx.conf`) strips any client-supplied tenant
   headers (`X-FABT-Tenant-Id`, `X-Scope-OrgID`, `X-Tenant-Id`) before forwarding to
   the backend. This prevents injection of a forged tenant identity.
3. `JwtAuthenticationFilter` validates the JWT signature, extracts `claims.tenantId()`,
   and binds the tenant to `TenantContext` (a Java 25 `ScopedValue`). No HTTP header
   is ever read for tenant resolution.
4. All downstream code (service layer, repository, RLS policies) reads tenant from
   `TenantContext.getTenantId()` — never from request attributes or headers.

## Why nginx cannot forward a JWT-extracted tenant header

Plain `nginx:alpine` (the image used in this project) has no JWT-parsing capability.
JWT extraction at the nginx layer would require either:

- **nginx-plus** with the `ngx_http_auth_jwt_module` (commercial)
- **OpenResty** (nginx + LuaJIT) with `lua-resty-jwt`

Neither is installed. The security boundary is the backend JWT filter; nginx stripping
is defence-in-depth to prevent header injection, not the primary control.

## Phase D posture

| Control | Implementation | Status |
|---|---|---|
| Tenant from JWT (primary) | `JwtAuthenticationFilter:110` — `claims.tenantId()` | ✅ shipped Phase A |
| Per-tenant signing keys | `kid` header claim, cross-validated in `JwtService` | ✅ shipped Phase A4 |
| Controller path guard (D11) | `TenantPathGuard.requireMatchingTenant` on all tenant-scoped controllers | ✅ shipped Phase D (5.1–5.4, 5.9) |
| Nginx header stripping | `proxy_set_header X-FABT-Tenant-Id ""` in all proxy locations | ✅ shipped Phase D (5.6) |

## Regulated-tier upgrade path (Phase F / D4)

For regulated tenants (HIPAA BAA, CJIS) the intended posture is mutual TLS (mTLS)
at the ingress layer so that only authorised intermediaries can present requests to
the backend. This requires:

1. A TLS-terminating reverse proxy (e.g. Caddy with client cert validation, or a cloud
   load balancer with mTLS policy) in front of the current nginx layer.
2. The proxy forwards a verified `X-FABT-Client-Cert-Subject` (or similar) header after
   mTLS handshake — this header is trustworthy because the proxy controls it.
3. The backend `JwtAuthenticationFilter` can optionally validate the cert subject against
   the token's `tenantId` as an additional claim check.

mTLS implementation is deferred to Phase F. The demo tier (Oracle Always Free, single VM)
does not support it. The current architecture is appropriate for demo/pilot use with
non-regulated data.

## Related files

- `infra/docker/nginx.conf` — proxy locations with header-stripping directives
- `backend/src/main/java/org/fabt/shared/security/JwtAuthenticationFilter.java` — tenant binding from JWT
- `backend/src/main/java/org/fabt/shared/web/TenantContext.java` — ScopedValue binding
- `e2e/playwright/tests/nginx-tenant-header-stripping.spec.ts` — integration test (nginx-mode only)
- `docs/security/compliance-posture-matrix.md` — regulated-tier upgrade roadmap
