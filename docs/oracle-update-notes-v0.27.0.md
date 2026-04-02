# Oracle Demo — v0.27.0 Update Notes

**Previous version:** v0.26.0 (Overflow Beds Management)
**This version:** v0.27.0 (Password Recovery + TOTP 2FA)
**Date:** 2026-04-01
**Base runbook:** `oracle-demo-runbook-v0.21.0.md`

---

## What Changed

### New features
- **TOTP two-factor authentication** — "Sign-in verification" via Google Authenticator / Authy
- **Admin-generated one-time access codes** — 15-minute expiry, single-use, for locked-out field workers
- **Two-phase login** — password → mfaRequired → TOTP code → JWTs
- **8 single-use backup codes** — bcrypt-hashed, displayed once at enrollment
- **PasswordChangeRequiredFilter** — forces password change after access-code login
- **Auth capabilities endpoint** — `GET /api/v1/auth/capabilities`
- **Forgot password page** — directs to admin access code path (email reset deferred until SMTP configured)

### Infrastructure changes
- **Flyway V31:** TOTP columns on `app_user` (`totp_secret_encrypted`, `totp_enabled`, `recovery_codes`)
- **Flyway V32:** `one_time_access_code` table
- **New env var:** `FABT_TOTP_ENCRYPTION_KEY` (AES-256-GCM, 32 bytes base64) — **REQUIRED for TOTP enrollment**
- **New bucket4j rate limits:** `forgot-password` (3/60min), `verify-totp` (20/15min)
- **New JCache definitions:** `rate-limit-forgot-password`, `rate-limit-verify-totp`
- **New npm dependency:** `qrcode` (client-side QR rendering — TOTP secret never leaves browser)
- **New npm dev dependency:** `otpauth` (Playwright test TOTP code generation)

### Security
- TOTP secrets AES-256-GCM encrypted at rest (key from env var, NOT in database)
- mfaToken single-use (jti blocklist, Caffeine cache 5-min TTL)
- 5-attempt rate limit per mfaToken on verify-totp
- DV safeguard: access code for dvAccess users requires dvAccess admin
- Audit events: TOTP_ENABLED, TOTP_DISABLED_BY_ADMIN, BACKUP_CODES_REGENERATED, ACCESS_CODE_GENERATED, ACCESS_CODE_USED

---

## ONE-TIME STEP: TOTP Encryption Key

**This must be done BEFORE deploying v0.27.0.** Without it, TOTP enrollment returns 503.

```bash
# SSH to Oracle VM
ssh -i ~/.ssh/fabt-oracle ubuntu@150.136.221.232

# Generate and add encryption key
# IMPORTANT: Generate a UNIQUE key. Do NOT copy the key from dev-start.sh —
# that key is committed to the public repo and using it means no real encryption.
echo "FABT_TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32)" >> ~/fabt-secrets/.env.prod

# Verify it was added
grep TOTP ~/fabt-secrets/.env.prod
```

Also add to `~/fabt-secrets/docker-compose.prod.yml` backend environment section:
```yaml
    environment:
      - FABT_TOTP_ENCRYPTION_KEY=${FABT_TOTP_ENCRYPTION_KEY}
```

---

## Update Procedure

Standard Part 13 from base runbook:

```bash
cd ~/finding-a-bed-tonight
git fetch --tags
git checkout v0.27.0

cd backend && mvn package -DskipTests -q && cd ..
cd frontend && npm ci --silent && npm run build && cd ..

docker build -f infra/docker/Dockerfile.backend -t fabt-backend:latest .
docker build -f infra/docker/Dockerfile.frontend -t fabt-frontend:latest .

docker compose \
  -f docker-compose.yml \
  -f ~/fabt-secrets/docker-compose.prod.yml \
  --env-file ~/fabt-secrets/.env.prod \
  --profile observability \
  up -d --force-recreate backend frontend
```

Flyway V31-V32 run automatically on backend startup.

---

## Post-Update Verification

```bash
# Version
curl -s https://YOUR_IP.nip.io/api/v1/version
# Expected: {"version":"0.27"}

# Auth capabilities (TOTP available)
curl -s https://YOUR_IP.nip.io/api/v1/auth/capabilities
# Expected: {"emailResetAvailable":false,"totpAvailable":true,"accessCodeAvailable":true}

# Health
curl -sf http://localhost:9091/actuator/health/liveness
# Expected: {"status":"UP"}
```

**Manual TOTP test:** Login → Security → Set Up Sign-In Verification → scan QR with authenticator → enter code → backup codes displayed. Log out, log in again → should prompt for 6-digit code.

---

## Troubleshooting

### TOTP enrollment returns 503
```
"Sign-in verification is not available on this server."
```
**Cause:** `FABT_TOTP_ENCRYPTION_KEY` not set or not reaching the backend container.
**Fix:** Check `grep TOTP ~/fabt-secrets/.env.prod` and verify the key is passed to the backend in docker-compose.prod.yml.

### User locked out (lost phone + no backup codes)
```bash
# Admin disables TOTP for the user:
# 1. Login as admin → Administration → Users tab
# 2. (Future: "Disable 2FA" button per user row)
# 3. Or via API:
curl -X DELETE https://YOUR_IP.nip.io/api/v1/auth/totp/{userId} \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

### User locked out (forgot password)
```bash
# Admin generates one-time access code:
# 1. Login as admin → Administration → Users tab → "Access Code" button
# 2. Give code to worker verbally or by phone
# 3. Worker enters code at /login/access-code
# 4. Worker must set new password before accessing app
```

---

## Demo Credentials

| Role | Email | Password | 2FA |
|------|-------|----------|-----|
| Platform Admin | `admin@dev.fabt.org` | Changed | No |
| CoC Admin | `cocadmin@dev.fabt.org` | `admin123` | No |
| Outreach Worker | `outreach@dev.fabt.org` | `admin123` | No |
| DV Outreach Worker | `dv-outreach@dev.fabt.org` | `admin123` | No |

Tenant slug: `dev-coc`

To test 2FA: create a new user via admin panel, enable sign-in verification, then test login.
