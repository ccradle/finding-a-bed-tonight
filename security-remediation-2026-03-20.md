# Security Scan Remediation — 2026-03-20

Jenkins security scan (build #32) identified 4 Semgrep findings in Terraform infrastructure code.
All 4 have been remediated.

## Finding 1: DynamoDB table using default encryption

**Rule:** `aws-dynamodb-table-unencrypted`
**File:** `infra/terraform/bootstrap/main.tf` (DynamoDB state lock table)
**Severity:** Medium

**What changed:** Added `server_side_encryption { enabled = true }` and `point_in_time_recovery { enabled = true }`.

**Why:** The Terraform state lock table was relying on default AWS-managed encryption. Explicitly enabling server-side encryption makes the security posture auditable and ensures the setting cannot silently regress. Point-in-time recovery was added as a related hardening measure — state lock corruption could block all Terraform operations.

---

## Finding 2: ALB listener missing TLS 1.2+ enforcement

**Rule:** `insecure-load-balancer-tls-version`
**File:** `infra/terraform/modules/app/main.tf` (ALB listener)
**Severity:** Medium

**What changed:**
- Added an optional HTTPS listener gated on a new `certificate_arn` variable
- HTTPS listener uses `ELBSecurityPolicy-TLS13-1-2-2021-06` (enforces TLS 1.2+, prefers TLS 1.3)
- When a certificate is provided, the HTTP listener redirects to HTTPS (301)
- API routing rule attaches to the HTTPS listener when available

**Why:** The ALB only had an HTTP listener — all traffic was unencrypted. TLS versions below 1.2 have known vulnerabilities (BEAST, POODLE, etc.). The fix is backward-compatible: without a `certificate_arn`, behavior is unchanged (HTTP-only for local/dev). Once an ACM certificate is provisioned, setting the variable enables full HTTPS with modern TLS.

**New variable added to `modules/app/variables.tf`:**
```hcl
variable "certificate_arn" {
  description = "ACM certificate ARN for HTTPS listener (empty string disables HTTPS)"
  type        = string
  default     = ""
}
```

---

## Finding 3: Public subnet assigns public IPs automatically

**Rule:** `aws-subnet-has-public-ip-address`
**File:** `infra/terraform/modules/network/main.tf` (public subnets)
**Severity:** Medium

**What changed:** Set `map_public_ip_on_launch = false` on public subnets.

**Why:** Automatically assigning public IPs to every resource launched in the subnet violates least-privilege networking. The ALB (the only resource intended for the public subnet) gets its public IP from its own Elastic IP / ENI allocation — it does not depend on `map_public_ip_on_launch`. Disabling this prevents accidental public exposure if an ECS task or other resource is mistakenly placed in the public subnet.

---

## Finding 4: RDS instance missing CloudWatch log exports

**Rule:** `aws-db-instance-no-logging`
**File:** `infra/terraform/modules/postgres/main.tf` (RDS PostgreSQL)
**Severity:** Medium

**What changed:** Added `enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]`.

**Why:** Without log exports, database errors, slow queries, and upgrade issues are only visible inside the RDS instance and are lost on restart or replacement. Exporting PostgreSQL and upgrade logs to CloudWatch enables alerting, audit trails, and post-incident analysis. This is an AWS Well-Architected best practice for any production database.

---

## Scan Summary

| Tool | findABed | finding-a-bed-tonight |
|------|----------|----------------------|
| Gitleaks (secrets) | No leaks | No leaks |
| Maven (SpotBugs, PMD, Checkstyle, OWASP) | N/A (no pom.xml) | 90 medium (dependency CVEs) |
| Semgrep (SAST) | 0 findings | **4 findings → all fixed** |

**Overall:** CRITICAL: 0, HIGH: 0, MEDIUM: 90 (dependency) + 4 (infra, now fixed), LOW: 0
