# Phase G-3 — OCI Object Storage Audit-Anchor Setup (Operator)

This runbook covers provisioning the OCI infrastructure that the FABT audit-chain external anchor (Phase G-3, `multi-tenant-production-readiness` §8.5) writes to. It is operator-facing — the developer has shipped the code; this document is what the operator runs at deploy time to wire the service to a real OCI bucket.

**Audience**: an operator who manages the FABT prod tenancy, has OCI CLI configured, and has tenancy-admin permissions in their target Oracle Cloud account.

**Prerequisites**:

- `oci` CLI installed and authenticated to the target tenancy
- `openssl` available (key generation)
- The FABT VM running v0.53+ (the version that ships `AuditChainAnchorJobConfig`)

---

## 1. What gets provisioned

| Resource | Name | Purpose |
|---|---|---|
| Compartment | `fabt-audit` | Blast-radius isolation for audit-anchor resources |
| Bucket | `fabt-audit-anchor` | Receives weekly anchor JSON objects |
| Retention rule | `fabt-7yr-worm` | 7-year locked WORM (Oracle-enforced; lock activates 14 days after rule creation) |
| User | `fabt-audit-anchor-svc` | Service principal — no human login |
| Group | `fabt-audit-anchor-writers` | Holds the service principal |
| Policy | `fabt-audit-anchor-policy` | Append-only access; explicitly omits `OBJECT_DELETE` and `OBJECT_OVERWRITE` |
| API key | RSA-2048 keypair | Authenticates the FABT app to OCI |

**Cost**: $0/month at FABT's scale (well under OCI Always Free limits — 10 GB Object Storage, 50,000 writes/month). Anchor payloads are ~256 bytes each; one upload per tenant per week.

---

## 2. Provisioning commands

The commands below assume PowerShell on Windows or bash; adapt line-continuation (`` ` `` for PowerShell, `\` for bash) as needed. Each phase ends with output that the operator captures for the FABT app's environment variables.

### 2.1 Discover tenancy info

```bash
oci iam region-subscription list --query 'data[?"is-home-region"==`true`].{Region:"region-name"}' --output table
oci os ns get
oci iam compartment list --all --include-root
```

Capture: home region (e.g. `us-ashburn-1`), namespace string, root compartment OCID (the `id` of the row whose `compartment-id` is `null`).

### 2.2 Create the compartment

```bash
oci iam compartment create \
  --compartment-id <TENANCY_OCID> \
  --name "fabt-audit" \
  --description "FABT audit-chain external anchor (Phase G-3)"
```

Capture the new compartment OCID.

### 2.3 Create the bucket

```bash
oci os bucket create \
  --compartment-id <COMPARTMENT_OCID> \
  --name "fabt-audit-anchor" \
  --storage-tier Standard \
  --public-access-type NoPublicAccess \
  --object-events-enabled false
```

**Do NOT enable versioning** — OCI rejects retention-rule creation on a versioning-enabled bucket. The locked retention rule is the stronger primitive for our threat model (Oracle-enforced immutability vs. soft "all versions retained"). If versioning is on by default on your tenancy, suspend it before proceeding to 2.6:

```bash
oci os bucket update \
  --namespace-name <NAMESPACE> \
  --bucket-name "fabt-audit-anchor" \
  --versioning Suspended
```

### 2.4 Create the IAM service principal + group + policy

```bash
oci iam user create \
  --name "fabt-audit-anchor-svc" \
  --description "Service principal: FABT writes weekly audit-chain anchors. No human login." \
  --email "fabt-audit-anchor-svc+noreply@<your-domain>"

oci iam group create \
  --name "fabt-audit-anchor-writers" \
  --description "Append-only access to fabt-audit-anchor bucket. No delete, no overwrite."

oci iam group add-user --group-id <GROUP_OCID> --user-id <USER_OCID>
```

Policy (write the statements to a file, then create — single quotes inside JSON cause CLI parsing issues otherwise):

```bash
cat > /tmp/fabt-policy.json <<'EOF'
[
  "Allow group fabt-audit-anchor-writers to inspect buckets in compartment fabt-audit",
  "Allow group fabt-audit-anchor-writers to read buckets in compartment fabt-audit where target.bucket.name='fabt-audit-anchor'",
  "Allow group fabt-audit-anchor-writers to manage objects in compartment fabt-audit where all { target.bucket.name='fabt-audit-anchor', any { request.permission='OBJECT_CREATE', request.permission='OBJECT_INSPECT', request.permission='OBJECT_READ' } }"
]
EOF

oci iam policy create \
  --compartment-id <TENANCY_OCID> \
  --name "fabt-audit-anchor-policy" \
  --description "FABT audit-anchor service principal — append-only access to fabt-audit-anchor bucket" \
  --statements file:///tmp/fabt-policy.json

rm /tmp/fabt-policy.json
```

The policy explicitly omits `OBJECT_DELETE` and `OBJECT_OVERWRITE`; even if the service-principal credentials leak, the bucket refuses any tampering with anchored hashes.

### 2.5 Lock down service-principal capabilities

Default OCI users have every credential type enabled (console password, SMTP, OAuth2, etc.). Disable everything except `can-use-api-keys`:

```bash
oci iam user update-user-capabilities \
  --user-id <USER_OCID> \
  --can-use-console-password false \
  --can-use-auth-tokens false \
  --can-use-customer-secret-keys false \
  --can-use-smtp-credentials false \
  --can-use-db-credentials false \
  --can-use-o-auth2-client-credentials false
```

### 2.6 Generate and upload the API keypair

```bash
mkdir -p ~/.fabt-oci-keys && chmod 700 ~/.fabt-oci-keys
openssl genrsa -out ~/.fabt-oci-keys/fabt-audit-anchor.pem 2048
chmod 600 ~/.fabt-oci-keys/fabt-audit-anchor.pem
openssl rsa -pubout -in ~/.fabt-oci-keys/fabt-audit-anchor.pem -out ~/.fabt-oci-keys/fabt-audit-anchor.pub

oci iam user api-key upload \
  --user-id <USER_OCID> \
  --key-file ~/.fabt-oci-keys/fabt-audit-anchor.pub
```

Capture the fingerprint from the response.

**Critical**: the private key (`fabt-audit-anchor.pem`) is what gets deployed to the FABT VM. **Never commit it to git** — the FABT repo's `.gitignore` covers `*.pem` patterns, but treat it as a credential regardless.

### 2.7 Apply the locked WORM retention rule

```bash
LOCK_AT=$(date -u -d '+14 days 1 hour' +'%Y-%m-%dT%H:%M:%S.000Z')

oci os retention-rule create \
  --namespace-name <NAMESPACE> \
  --bucket-name "fabt-audit-anchor" \
  --display-name "fabt-7yr-worm" \
  --time-amount 7 \
  --time-unit YEARS \
  --time-rule-locked "$LOCK_AT"
```

**Critical operational note**: between rule creation and the timestamp in `LOCK_AT` (14 days + 1 hour), the rule is still mutable. If you discover a bucket misconfiguration during that window, you can delete the rule and start over. After the lock activates, the rule is immutable for the 7-year retention duration.

---

## 3. Deploy the private key to the FABT VM

```bash
# On the operator workstation:
scp ~/.fabt-oci-keys/fabt-audit-anchor.pem fabt-prod:/tmp/audit-anchor.pem

# On the FABT VM:
sudo mkdir -p /var/lib/fabt/oci
sudo mv /tmp/audit-anchor.pem /var/lib/fabt/oci/audit-anchor.pem
sudo chown fabt:fabt /var/lib/fabt/oci/audit-anchor.pem
sudo chmod 600 /var/lib/fabt/oci/audit-anchor.pem
sudo chmod 700 /var/lib/fabt/oci
```

Substitute `fabt:fabt` with whatever user/group the FABT JVM runs as.

---

## 4. Set environment variables on the FABT VM

Add to the FABT app's `.env` (or systemd unit, or whatever the deployment uses):

```bash
FABT_OCI_AUDIT_ANCHOR_ENABLED=true
FABT_OCI_AUDIT_ANCHOR_REGION=<region from 2.1>
FABT_OCI_AUDIT_ANCHOR_NAMESPACE=<namespace from 2.1>
FABT_OCI_AUDIT_ANCHOR_TENANCY_OCID=<tenancy OCID from 2.1>
FABT_OCI_AUDIT_ANCHOR_USER_OCID=<service principal user OCID from 2.4>
FABT_OCI_AUDIT_ANCHOR_FINGERPRINT=<API key fingerprint from 2.6>
FABT_OCI_AUDIT_ANCHOR_PRIVATE_KEY_PATH=/var/lib/fabt/oci/audit-anchor.pem
FABT_OCI_AUDIT_ANCHOR_BUCKET=fabt-audit-anchor
FABT_OCI_AUDIT_ANCHOR_COMPARTMENT_OCID=<compartment OCID from 2.2>
```

Restart the FABT app. On boot, you'll see:

```
INFO  o.f.o.anchor.OciAuditAnchorConfig - OCI audit-anchor: building authentication provider for region=us-ashburn-1 tenancy=ocid1.tenancy... user=ocid1.user... fingerprint=[redacted]
INFO  o.f.o.anchor.OciAuditAnchorConfig - OCI audit-anchor: ObjectStorageClient configured for namespace=... bucket=fabt-audit-anchor
INFO  o.f.a.config.BatchJobScheduler - Registered batch job 'auditChainAnchor' with cron '0 0 5 * * MON' (dvAccess=false)
```

---

## 5. Verify with an on-demand run

Don't wait for next Monday — trigger the job manually:

```bash
curl -X POST -H "Authorization: Bearer <PLATFORM_ADMIN_JWT>" \
  https://findabed.org/api/v1/batch/jobs/auditChainAnchor/run
```

Then check execution history:

```bash
curl -H "Authorization: Bearer <PLATFORM_ADMIN_JWT>" \
  https://findabed.org/api/v1/batch/jobs/auditChainAnchor/executions
```

Confirm `status=COMPLETED` and check the FABT logs for `OCI audit-anchor uploaded:` lines, one per tenant with a non-zero chain head.

Verify objects landed in the bucket:

```bash
oci os object list --namespace-name <NAMESPACE> --bucket-name "fabt-audit-anchor" --prefix "audit-anchors/" --output table
```

Expected: one object per tenant per run, key shape `audit-anchors/yyyy/MM/dd/{tenant_id}-{run_id}.json`.

---

## 6. The 14-day lock activation window

After step 2.7, you have a 14-day grace period to verify the bucket configuration. **Use it.** Run at least one anchor batch (step 5), confirm objects appear with the right shape, confirm the retention rule cannot be bypassed via attempted overwrites or deletes (the IAM policy prevents these from the service principal anyway, but verify by attempting from your admin OCI CLI session — they should fail).

After the lock activates (`LOCK_AT` from step 2.7), the bucket is committed for 7 years. Migration off OCI Object Storage during that window means archiving the existing bucket as-is and pointing future writes elsewhere; you cannot delete or overwrite anchored content.

---

## 7. Recovery scenarios

### Anchor upload failures (`FabtAuditAnchorUploadFailing` alert)

See alert annotations in `deploy/prometheus/phase-g-chain-verify.rules.yml`. Common causes:

- API key fingerprint drift after key rotation — re-upload the public key + update `FABT_OCI_AUDIT_ANCHOR_FINGERPRINT`
- Bucket name typo — confirm `fabt-audit-anchor` exact spelling
- Region mismatch — `FABT_OCI_AUDIT_ANCHOR_REGION` must match the bucket's home region

### Lost private key

The private key is local to the operator workstation (path: `~/.fabt-oci-keys/fabt-audit-anchor.pem`) AND on the FABT VM (path: `/var/lib/fabt/oci/audit-anchor.pem`). If both are lost, generate a new keypair, upload the new public key, retire the old API key:

```bash
# Generate new keypair (steps 2.6 again)
# Upload new public key
oci iam user api-key upload --user-id <USER_OCID> --key-file ~/.fabt-oci-keys/fabt-audit-anchor.pub

# Get the fingerprint of the OLD key
oci iam user api-key list --user-id <USER_OCID>

# Delete the OLD key (after the new one is verified working)
oci iam user api-key delete --user-id <USER_OCID> --fingerprint <OLD_FINGERPRINT> --force
```

---

## 8. Forensic retrieval (incident response)

To pull the anchor history for a tenant during an investigation:

```bash
oci os object list \
  --namespace-name <NAMESPACE> \
  --bucket-name "fabt-audit-anchor" \
  --prefix "audit-anchors/" \
  --output json | jq '.data[] | select(.name | contains("<TENANT_OCID>")) | .name'

# Then fetch a specific anchor:
oci os object get \
  --namespace-name <NAMESPACE> \
  --bucket-name "fabt-audit-anchor" \
  --name "audit-anchors/yyyy/MM/dd/{tenant_id}-{run_id}.json" \
  --file ./anchor.json

cat ./anchor.json | jq '.'
```

The JSON payload contains `last_hash_hex` (the chain-head hash at anchor time). Compare against the live `tenant_audit_chain_head.last_hash` for that tenant. Mismatch on a row whose `audit_events.timestamp` predates the anchor's `anchored_at` timestamp = forensic evidence of post-anchor tampering.
