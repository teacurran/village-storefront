# Security Operations Runbook

**Status:** Authoritative
**Last Updated:** 2026-01-18
**Owner:** Platform Security Team
**Related Docs:** [Threat Models](../security/threat-models/), [Architecture](../architecture_overview.md), [Main Runbook](./runbook.md)

## Document Purpose

This runbook provides security operations procedures, threat response playbooks, and hardening workflows for Village Storefront. It covers JWT key rotation, password hash migration, rate limit enforcement, CSP implementation, and incident response based on threat models.

**Intended Audience:** Security engineers, on-call engineers responding to security incidents, DevOps teams performing security maintenance, compliance auditors reviewing security controls.

---

## Table of Contents

1. [Quick Reference](#quick-reference)
2. [Security Maintenance Procedures](#security-maintenance-procedures)
3. [Threat-Based Incident Response](#threat-based-incident-response)
4. [Security Controls Verification](#security-controls-verification)
5. [Compliance & Audit](#compliance--audit)
6. [Emergency Procedures](#emergency-procedures)
7. [References](#references)

---

## 1. Quick Reference

### Security Contacts

| Role | Responsibility | Contact | Escalation |
|------|---------------|---------|------------|
| Security Engineer (On-Call) | First responder for security incidents | PagerDuty rotation | Security Lead after 30m |
| Security Lead | Coordinate security response | security@villagecompute.com | CISO (SEV-1) |
| CISO | Executive security decisions | CISO direct line | CEO (data breach) |
| Compliance Officer | Regulatory reporting (GDPR, PCI DSS) | compliance@villagecompute.com | Legal (breach notification) |
| DBA Team | Database security, audit log access | Slack @dba-team | Infra Lead |

### Critical Security Dashboards

| Dashboard | URL | Purpose |
|-----------|-----|---------|
| Security Metrics | `https://grafana.villagecompute.com/d/security` | Auth failures, rate limits, impersonation events |
| Audit Log Analysis | `https://grafana.villagecompute.com/d/audit-logs` | Platform admin actions, impersonation sessions |
| OWASP Dependency-Check | GitHub Actions → Security tab | Vulnerable dependencies from CI scans |
| Trivy Container Scan | GitHub Actions → Security tab | Container image vulnerabilities |

### Security Threat Models

Reference these documents for threat-specific response procedures:

- **Payment Processing:** `docs/security/threat-models/payment-processing.md` (Stripe integration, refund fraud, API key exposure)
- **Platform Impersonation:** `docs/security/threat-models/impersonation.md` (Privilege escalation, unauthorized data access)
- **Media Pipeline:** `docs/security/threat-models/media-pipeline.md` (FFmpeg exploits, XSS via SVG, EXIF injection)

---

## 2. Security Maintenance Procedures

### 2.1 JWT Key Rotation

**Frequency:** Every 90 days (scheduled) or immediately after suspected key compromise

**Procedure:**

1. **Generate New Keypair**

   ```bash
   cd /path/to/village-storefront
   ./tools/security/rotate-jwt-keys.sh
   ```

   This script:
   - Generates RSA 2048-bit keypair
   - Backs up existing keys with timestamp
   - Outputs new public key in PEM format

2. **Configure Dual-Key Verification**

   Update `application.properties` to verify tokens with **both** old and new keys:

   ```properties
   # Old key (keep for backward compatibility during transition)
   mp.jwt.verify.publickey.location=file://./keys/publicKey.pem

   # New key (for signing new tokens)
   mp.jwt.sign.key.location=file://./keys/newPrivateKey.pem
   mp.jwt.verify.publickey.alt-location=file://./keys/newPublicKey.pem
   ```

3. **Deploy Application**

   ```bash
   kubectl set image deployment/storefront-api api=ghcr.io/village-storefront:latest -n production
   kubectl rollout status deployment/storefront-api -n production
   ```

   - **Verification:** Existing users with old tokens remain logged in
   - **Verification:** New logins receive tokens signed with new key

4. **Wait for Token Expiration**

   JWT access token lifetime: **15 minutes**

   ```bash
   # Monitor active tokens (optional)
   psql -h 10.50.0.10 -U storefront -d storefront -c \
     "SELECT COUNT(*) FROM user_sessions WHERE last_seen_at > NOW() - INTERVAL '15 minutes';"
   ```

   ⚠️ **DO NOT proceed to step 5 until 15 minutes have elapsed!**

5. **Remove Old Key**

   Update `application.properties` to use only new key:

   ```properties
   mp.jwt.verify.publickey.location=file://./keys/newPublicKey.pem
   mp.jwt.sign.key.location=file://./keys/newPrivateKey.pem
   # Remove mp.jwt.verify.publickey.alt-location
   ```

   Rename keys:

   ```bash
   cd keys/
   mv publicKey.pem publicKey_old_$(date +%Y%m%d).pem
   mv newPublicKey.pem publicKey.pem
   mv newPrivateKey.pem privateKey.pem
   ```

6. **Redeploy & Securely Delete Old Key**

   ```bash
   kubectl set image deployment/storefront-api api=ghcr.io/village-storefront:latest -n production

   # Securely delete old private key
   shred -u keys/backups/privateKey_YYYYMMDD_HHMMSS.pem
   ```

**Rollback Procedure:**

If rotation causes authentication failures:

```bash
# Revert to old key configuration
kubectl rollout undo deployment/storefront-api -n production
```

**Metrics to Monitor:**

- `auth.jwt.verification_failure` (should remain 0)
- `auth.login.success_total` (should remain stable)
- User-reported login issues in support channels

---

### 2.2 BCrypt Work Factor Migration

**Purpose:** Upgrade password hash strength from cost=12 (4096 rounds) to cost=13 (8192 rounds) for better brute-force protection

**Migration Strategy:** Automatic re-hashing on successful login (zero-downtime)

**Procedure:**

1. **Apply Database Migration**

   ```bash
   cd migrations/
   psql -h 10.50.0.10 -U storefront -d storefront < tools/security/migrate-bcrypt-cost.sql
   ```

   This adds `password_hash_version` column to `oauth_clients` table.

2. **Deploy Application with Auto-Upgrade Logic**

   The `BcryptMigrationService` automatically re-hashes passwords on login:

   ```java
   // Implemented in: modules/core-platform/src/main/java/villagecompute/storefront/security/BcryptMigrationService.java
   if (BCrypt.checkpw(password, client.clientSecretHash) && client.passwordHashVersion < 2) {
       String newHash = BCrypt.hashpw(password, BCrypt.gensalt(13));
       // Update client_secret_hash and password_hash_version=2
   }
   ```

   **No code changes needed if service already deployed.**

3. **Monitor Migration Progress**

   ```sql
   -- Check percentage migrated
   SELECT
       ROUND(100.0 * SUM(CASE WHEN password_hash_version >= 2 THEN 1 ELSE 0 END) / COUNT(*), 2) as pct_migrated,
       SUM(CASE WHEN password_hash_version >= 2 THEN 1 ELSE 0 END) as migrated_count,
       COUNT(*) as total_count
   FROM oauth_clients;
   ```

   Run weekly until >95% migrated.

4. **Handle Inactive Clients**

   After 90 days, identify clients that haven't logged in:

   ```sql
   SELECT client_id, tenant_id, last_used_at
   FROM oauth_clients
   WHERE password_hash_version < 2
   AND last_used_at < NOW() - INTERVAL '90 days'
   LIMIT 20;
   ```

   **Options:**
   - Email notification to reactivate account
   - Force password reset on next login
   - Archive inactive clients (per data retention policy)

**Performance Impact:**

- Login duration increases by ~150ms (one-time per client)
- Monitor `auth.login.duration_ms` p95 latency

**Rollback:**

Cannot revert hashes without plaintext passwords. To pause migration:

```sql
-- Mark all clients for re-migration (does not change hash)
UPDATE oauth_clients SET password_hash_version = 1 WHERE password_hash_version = 2;
```

---

### 2.3 Rate Limiting Configuration

**Current Implementation:** In-memory token bucket per OAuth client + scope

**Rate Limit Defaults:**

| Scope | Default Limit | Adjustable Per Client | Enforcement Location |
|-------|--------------|----------------------|---------------------|
| `catalog:read` | 5000 req/min | Yes (via `oauth_clients.rate_limit_per_minute`) | `HeadlessAuthFilter` |
| `cart:write` | 5000 req/min | Yes | `HeadlessAuthFilter` |
| `orders:write` | 1000 req/min | Yes | `HeadlessAuthFilter` |
| `customer:write` | 1000 req/min | Yes | `HeadlessAuthFilter` |

**Adjusting Rate Limits:**

```sql
-- Increase rate limit for specific OAuth client
UPDATE oauth_clients
SET rate_limit_per_minute = 10000
WHERE client_id = 'client_abc123';
```

**Rate Limit Response (RFC 6585 Compliant):**

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 42
X-RateLimit-Limit: 5000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1737159600
Content-Type: application/json

{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded. Please retry after 42 seconds."
}
```

**Manually Reset Rate Limit (Admin Operation):**

```bash
# Via Admin API (requires platform admin role)
curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
  https://api.villagecompute.com/admin/rate-limits/reset \
  -d '{"client_id": "client_abc123", "scope": "catalog:read"}'
```

**Metrics to Monitor:**

- `headless.rate_limit.exceeded` (counter by client_id, scope)
- `http.server.requests{status="429"}` (total 429 responses)

---

### 2.4 Content Security Policy (CSP) Headers

**Purpose:** Prevent XSS attacks via inline scripts, SVG uploads, and EXIF metadata injection

**CSP Policies:**

| Path | Policy | Rationale |
|------|--------|-----------|
| Storefront (`/`, `/products`, `/cart`, `/checkout`) | `default-src 'self'; script-src 'self' https://js.stripe.com; report-uri /api/v1/csp-report` | Allows Stripe.js for payment processing, blocks inline scripts |
| Admin SPA (`/admin/*`) | `default-src 'self'; script-src 'self'; report-uri /api/v1/csp-report` | Strict policy, no external scripts |
| API endpoints (`/api/*`, `/q/*`, `/openapi`) | No CSP headers | Not applicable to JSON responses |

**Configuration:**

```properties
# modules/core-platform/src/main/resources/application.properties
csp.storefront.policy=default-src 'self'; script-src 'self' https://js.stripe.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self' https://api.stripe.com
csp.admin.policy=default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'
csp.report-uri=/api/v1/csp-report
csp.enabled=true
```

**Disable CSP (Emergency Only):**

```properties
csp.enabled=false
```

Redeploy application.

**CSP Violation Reporting:**

Violations are logged to `/api/v1/csp-report` endpoint:

```sql
-- Query recent CSP violations
SELECT
    document_uri,
    violated_directive,
    blocked_uri,
    COUNT(*) as violation_count
FROM csp_violation_reports
WHERE created_at > NOW() - INTERVAL '24 hours'
GROUP BY document_uri, violated_directive, blocked_uri
ORDER BY violation_count DESC
LIMIT 10;
```

---

## 3. Threat-Based Incident Response

### 3.1 Payment Processing Threats

**Reference:** `docs/security/threat-models/payment-processing.md`

#### Incident: Suspected Stripe API Key Exposure

**Detection:**
- Alert: `payment.stripe.error` counter spike
- GitHub secret scanning alert
- Unusual Stripe API calls in logs

**Response Steps:**

1. **Immediate Containment (< 5 minutes)**

   ```bash
   # Rotate Stripe keys immediately via Stripe Dashboard
   # 1. Login to https://dashboard.stripe.com
   # 2. Developers → API keys → Reveal test/live secret key → Roll key

   # Update application secrets
   kubectl create secret generic stripe-keys \
     --from-literal=secret-key=sk_live_NEW_KEY \
     --dry-run=client -o yaml | kubectl apply -f - -n production

   # Restart pods to pick up new secret
   kubectl rollout restart deployment/storefront-api -n production
   ```

2. **Forensic Investigation (< 30 minutes)**

   ```bash
   # Search application logs for leaked key
   kubectl logs deployment/storefront-api -n production --since=24h | grep -i "sk_live_"

   # Query Stripe API for unauthorized operations
   # Check Stripe Dashboard → Events for API calls from unknown IPs
   ```

3. **Impact Assessment**

   - Review recent payments for suspicious activity
   - Check for unauthorized refunds
   - Verify no payment amount manipulation

4. **Notification**

   - Notify Finance Team (potential fraudulent transactions)
   - Document incident in `admin_audit_log` table
   - If PCI DSS breach suspected, notify acquiring bank within 24 hours

**Prevention (from Threat Model Mitigation Backlog):**

- GitHub secret scanning enabled (dependency-check workflow)
- Key rotation policy: Every 90 days
- Log scrubbing for sensitive patterns

---

#### Incident: Refund Fraud Detected

**Detection:**
- Alert: `payment.refund.total` spike
- Manual review: Admin issues >5 refunds in 1 hour
- Anomaly: Single merchant has >$10k refunds in 24 hours

**Response Steps:**

1. **Freeze Suspicious Activity**

   ```sql
   -- Temporarily revoke refund permission for admin
   UPDATE platform_admin_roles
   SET permissions = array_remove(permissions, 'refunds:write')
   WHERE admin_user_id = 'SUSPECT_ADMIN_ID';
   ```

2. **Audit Recent Refunds**

   ```sql
   SELECT
       a.admin_user_id,
       a.action_type,
       a.target_resource,
       a.created_at,
       (a.metadata->>'refund_amount')::numeric as amount
   FROM admin_audit_log a
   WHERE a.admin_user_id = 'SUSPECT_ADMIN_ID'
   AND a.action_type = 'refund.issued'
   AND a.created_at > NOW() - INTERVAL '24 hours'
   ORDER BY a.created_at DESC;
   ```

3. **Contact Finance**

   - Provide audit log export
   - Request Stripe Dashboard review for refund reversals

4. **Implement Controls (from Mitigation Backlog)**

   - Refund velocity limits: Max 5 refunds per admin per hour
   - Anomaly detection: Alert on >$10k refunds in 24 hours
   - Secondary approval for refunds >$1000

---

### 3.2 Platform Impersonation Threats

**Reference:** `docs/security/threat-models/impersonation.md`

#### Incident: Unauthorized Impersonation Attempt

**Detection:**
- Alert: `impersonation.session.start` counter spike
- Failed permission check logged: `PlatformAdminAuthorizationService` throws 403
- Real-time Slack alert (planned mitigation)

**Response Steps:**

1. **Emergency Kill Switch (< 2 minutes)**

   ```bash
   # Disable all impersonation immediately
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "impersonation.disable", "enabled": true, "reason": "Security incident #456"}' \
     https://api.villagecompute.com/admin/feature-flags
   ```

   Verify: Impersonation endpoints return 403

2. **Identify Attack Vector**

   ```sql
   -- Find failed impersonation attempts
   SELECT
       admin_user_id,
       target_tenant_id,
       attempted_at,
       failure_reason
   FROM impersonation_attempts
   WHERE succeeded = false
   AND attempted_at > NOW() - INTERVAL '1 hour'
   ORDER BY attempted_at DESC;
   ```

3. **Review Audit Logs**

   ```sql
   -- Check if attacker gained access before detection
   SELECT * FROM admin_audit_log
   WHERE admin_user_id = 'SUSPECT_ADMIN_ID'
   AND action_type LIKE 'impersonate%'
   AND created_at > NOW() - INTERVAL '24 hours';
   ```

4. **Containment**

   - Revoke admin credentials
   - Force password reset for compromised account
   - Review tenant allowlist for unauthorized assignments

5. **Notify Affected Merchants**

   If unauthorized data access occurred:

   - Email affected merchants within 72 hours (GDPR compliance)
   - Provide audit log summary
   - Offer credit monitoring if PII exposed

**Post-Incident Actions (from Mitigation Backlog):**

- Implement required justification (support ticket number) for impersonation
- Add integration tests for permission checks on all impersonation endpoints
- Enable MFA for platform admins

---

### 3.3 Media Pipeline Threats

**Reference:** `docs/security/threat-models/media-pipeline.md`

#### Incident: Suspected FFmpeg Exploit Attempt

**Detection:**
- Alert: `media.ffmpeg.error` counter spike
- Worker node high CPU usage
- FFmpeg process crashes in logs

**⚠️ CRITICAL RESPONSE (< 5 minutes)**

1. **Kill Switch Activation**

   ```bash
   # Disable media processing immediately
   curl -X POST -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"flag": "media.processing.enabled", "enabled": false, "reason": "FFmpeg security incident"}' \
     https://api.villagecompute.com/admin/feature-flags
   ```

2. **Isolate Worker Nodes**

   ```bash
   # Cordon affected nodes
   kubectl cordon node-media-worker-1

   # Drain pods
   kubectl drain node-media-worker-1 --ignore-daemonsets --delete-emptydir-data
   ```

3. **Forensic Analysis**

   ```bash
   # Extract FFmpeg worker logs
   kubectl logs -l app=media-worker --since=1h > ffmpeg-incident-logs.txt

   # Find suspicious media files
   kubectl exec -it media-worker-pod -- ls -lah /tmp/media-processing/
   ```

4. **Identify Malicious Upload**

   ```sql
   SELECT
       m.id,
       m.tenant_id,
       m.original_filename,
       m.mime_type,
       m.uploaded_at,
       j.status,
       j.error_message
   FROM media_assets m
   JOIN media_processing_jobs j ON j.media_asset_id = m.id
   WHERE j.status = 'failed'
   AND j.error_message LIKE '%segmentation fault%'
   AND j.created_at > NOW() - INTERVAL '1 hour';
   ```

5. **Quarantine File**

   ```bash
   # Move suspicious file to quarantine S3 bucket
   aws s3 mv s3://village-storefront-media/tenant_123/media/asset_456.mp4 \
              s3://village-storefront-quarantine/incident_789/asset_456.mp4
   ```

6. **Notify Security Team**

   - Provide sample file for malware analysis
   - Report CVE if FFmpeg vulnerability confirmed
   - Coordinate with FFmpeg team if zero-day

**Prevention (URGENT - from Threat Model):**

- **[P0 CRITICAL]** Implement FFmpeg sandboxing (Docker with `--security-opt=no-new-privileges`, `--cap-drop=ALL`, `--network=none`)
- **[P0 CRITICAL]** File type validation (magic byte inspection, blocklist SVG)
- **[P0 CRITICAL]** Per-tenant storage quotas

---

#### Incident: XSS via SVG Upload

**Detection:**
- CSP violation report with `blocked-uri` containing script
- User report: "Seeing JavaScript popup on product page"

**Response Steps:**

1. **Identify Malicious SVG**

   ```sql
   -- Find recently uploaded SVG files
   SELECT id, tenant_id, original_filename, s3_key, uploaded_at
   FROM media_assets
   WHERE mime_type = 'image/svg+xml'
   AND uploaded_at > NOW() - INTERVAL '7 days'
   ORDER BY uploaded_at DESC;
   ```

2. **Delete from S3 + Database**

   ```bash
   # Delete malicious SVG
   aws s3 rm s3://village-storefront-media/tenant_123/media/malicious.svg

   # Mark as deleted in database
   psql -c "UPDATE media_assets SET deleted_at = NOW() WHERE id = 'ASSET_ID';"
   ```

3. **Purge CDN Cache**

   ```bash
   # Invalidate CloudFront cache
   aws cloudfront create-invalidation \
     --distribution-id E1234567890ABC \
     --paths "/tenant_123/media/malicious.svg"
   ```

4. **Block SVG Uploads (Permanent Fix)**

   Update file type validation in `MediaUploadService`:

   ```java
   // Reject SVG uploads
   if ("image/svg+xml".equals(mimeType)) {
       throw new MediaValidationException("SVG uploads are not supported for security reasons");
   }
   ```

   Redeploy application.

**Verification:**

- Test SVG upload → Should return 400 error
- Check CSP headers on storefront → Should block inline scripts

---

## 4. Security Controls Verification

### 4.1 Rate Limiting Verification

**Test Procedure:**

```bash
# Create OAuth client for testing
CLIENT_ID="test_client_123"
CLIENT_SECRET="test_secret_456"

# Exhaust rate limit (default 5000 req/min)
for i in {1..5005}; do
  curl -u "$CLIENT_ID:$CLIENT_SECRET" \
    https://api.villagecompute.com/api/v1/headless/catalog \
    -w "%{http_code}\n" -o /dev/null -s
done

# Expected: First 5000 return 200, remaining return 429
```

**Expected Response Headers:**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 42
X-RateLimit-Limit: 5000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1737159600
```

---

### 4.2 CSP Headers Verification

**Test Procedure:**

```bash
# Test storefront CSP
curl -I https://villagecompute.com/ | grep "Content-Security-Policy"

# Expected:
# Content-Security-Policy: default-src 'self'; script-src 'self' https://js.stripe.com; report-uri /api/v1/csp-report

# Test admin CSP
curl -I https://villagecompute.com/admin | grep "Content-Security-Policy"

# Expected:
# Content-Security-Policy: default-src 'self'; script-src 'self'; report-uri /api/v1/csp-report

# Test API (should have NO CSP)
curl -I https://api.villagecompute.com/api/v1/health | grep "Content-Security-Policy"

# Expected: (empty - no CSP header)
```

**Browser DevTools Test:**

1. Open storefront page
2. Open DevTools → Console
3. Run: `eval('alert("XSS")')`
4. Expected: CSP violation error (script not executed)

---

### 4.3 JWT Key Rotation Verification

**Test Procedure:**

```bash
# Before rotation: Generate token with old key
OLD_TOKEN=$(curl -X POST https://api.villagecompute.com/auth/login \
  -d '{"username":"admin","password":"password"}' | jq -r '.access_token')

# Rotate keys (steps 1-3 from Section 2.1)

# After dual-key deployment: Old token should still work
curl -H "Authorization: Bearer $OLD_TOKEN" \
  https://api.villagecompute.com/api/v1/user/profile

# Expected: 200 OK (old token verified with old key)

# Generate new token (signed with new key)
NEW_TOKEN=$(curl -X POST https://api.villagecompute.com/auth/login \
  -d '{"username":"admin","password":"password"}' | jq -r '.access_token')

# New token should work
curl -H "Authorization: Bearer $NEW_TOKEN" \
  https://api.villagecompute.com/api/v1/user/profile

# Expected: 200 OK (new token verified with new key)

# After single-key deployment (step 5): Old token should fail
curl -H "Authorization: Bearer $OLD_TOKEN" \
  https://api.villagecompute.com/api/v1/user/profile

# Expected: 401 Unauthorized
```

---

## 5. Compliance & Audit

### 5.1 Audit Log Queries

**Impersonation Activity (Last 24 Hours):**

```sql
SELECT
    a.admin_user_id,
    a.impersonated_user_id,
    a.target_tenant_id,
    a.action_type,
    a.created_at,
    a.metadata->>'justification' as justification
FROM admin_audit_log a
WHERE a.action_type LIKE 'impersonate%'
AND a.created_at > NOW() - INTERVAL '24 hours'
ORDER BY a.created_at DESC;
```

**Refund Operations (By Admin):**

```sql
SELECT
    admin_user_id,
    COUNT(*) as refund_count,
    SUM((metadata->>'refund_amount')::numeric) as total_refunded
FROM admin_audit_log
WHERE action_type = 'refund.issued'
AND created_at > NOW() - INTERVAL '30 days'
GROUP BY admin_user_id
ORDER BY total_refunded DESC;
```

**Failed Authentication Attempts:**

```sql
SELECT
    username,
    ip_address,
    COUNT(*) as failure_count,
    MAX(attempted_at) as last_attempt
FROM auth_failures
WHERE attempted_at > NOW() - INTERVAL '1 hour'
GROUP BY username, ip_address
HAVING COUNT(*) > 5
ORDER BY failure_count DESC;
```

---

### 5.2 Security Metrics (Quarterly Review)

**Run these queries for compliance audits:**

1. **BCrypt Migration Progress**

   ```sql
   SELECT
       password_hash_version,
       COUNT(*) as count,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) as percentage
   FROM oauth_clients
   GROUP BY password_hash_version;
   ```

2. **JWT Rotation History**

   ```bash
   # List key rotation dates from backup directory
   ls -lh keys/backups/
   ```

3. **Rate Limit Violations (Top Offenders)**

   ```promql
   # Prometheus query
   topk(10, sum by (client_id) (rate(headless_rate_limit_exceeded[7d])))
   ```

4. **CSP Violations (By Type)**

   ```sql
   SELECT
       violated_directive,
       COUNT(*) as violation_count
   FROM csp_violation_reports
   WHERE created_at > NOW() - INTERVAL '30 days'
   GROUP BY violated_directive
   ORDER BY violation_count DESC;
   ```

---

## 6. Emergency Procedures

### 6.1 Security Incident Severity Matrix

| Severity | Description | Response Time | Notification |
|----------|-------------|--------------|-------------|
| SEV-1 (Critical) | Data breach, PCI DSS violation, active exploit | < 15 minutes | Page CISO, CTO, CEO immediately |
| SEV-2 (High) | Suspected compromise, privilege escalation | < 30 minutes | Notify Security Lead, Engineering Manager |
| SEV-3 (Medium) | Vulnerability disclosed, audit finding | < 2 hours | Slack @security-team |
| SEV-4 (Low) | Security enhancement, routine maintenance | < 1 business day | Email security@villagecompute.com |

---

### 6.2 Data Breach Response (SEV-1)

**Trigger:** Confirmed unauthorized access to customer PII, payment data, or merchant financial records

**Immediate Actions (< 30 minutes):**

1. **Activate Incident Response Team**
   - CISO (Incident Commander)
   - Security Lead (Technical Lead)
   - Legal Counsel (Regulatory Compliance)
   - PR/Communications (Customer Notification)

2. **Containment**
   - Freeze compromised admin accounts
   - Enable impersonation kill switch
   - Rotate all API keys (Stripe, AWS, database)
   - Isolate affected systems

3. **Evidence Preservation**
   ```bash
   # Snapshot database for forensics
   pg_dump -h 10.50.0.10 -U storefront -d storefront > breach-snapshot-$(date +%Y%m%d-%H%M%S).sql

   # Export audit logs
   psql -c "COPY admin_audit_log TO '/tmp/audit-export.csv' CSV HEADER;"

   # Save application logs
   kubectl logs -l app=storefront-api --since=24h > breach-app-logs.txt
   ```

4. **Regulatory Notification (< 72 hours for GDPR)**
   - Document scope of breach (number of affected users, data types)
   - Notify Data Protection Authority (DPA)
   - Prepare customer notification email (Legal approval required)

---

### 6.3 Emergency Feature Flag Reference

**Kill Switches (Disable Functionality):**

```bash
# Disable checkout (stops all new orders)
kubectl exec postgres-pod -- psql -U storefront -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_name = 'checkout.kill-switch';"

# Disable impersonation (blocks platform admin access to merchant data)
kubectl exec postgres-pod -- psql -U storefront -c \
  "UPDATE feature_flags SET enabled = true WHERE flag_name = 'impersonation.disable';"

# Disable media processing (stops FFmpeg workers)
kubectl exec postgres-pod -- psql -U storefront -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_name = 'media.processing.enabled';"

# Disable Stripe webhook processing (circuit breaker)
kubectl exec postgres-pod -- psql -U storefront -c \
  "UPDATE feature_flags SET enabled = false WHERE flag_name = 'stripe.webhook.processing.enabled';"
```

**Verification:**

```bash
# Check flag status
kubectl exec postgres-pod -- psql -U storefront -c \
  "SELECT flag_name, enabled, updated_at FROM feature_flags ORDER BY updated_at DESC LIMIT 5;"
```

---

## 7. References

### Security Documentation

- **Threat Models:**
  - [Payment Processing](../security/threat-models/payment-processing.md)
  - [Platform Impersonation](../security/threat-models/impersonation.md)
  - [Media Pipeline](../security/threat-models/media-pipeline.md)

- **Architecture:**
  - [Security Overview](../architecture_overview.md#security)
  - [ADR-001: Tenancy & Isolation](../adr/ADR-001-tenancy.md)

- **Tooling:**
  - JWT Rotation Script: `tools/security/rotate-jwt-keys.sh`
  - BCrypt Migration SQL: `tools/security/migrate-bcrypt-cost.sql`

### External Resources

- **RFC 6585:** HTTP Status Code 429 (Too Many Requests)
  https://tools.ietf.org/html/rfc6585

- **RFC 7807:** Problem Details for HTTP APIs
  https://tools.ietf.org/html/rfc7807

- **OWASP Top 10 2021:**
  https://owasp.org/www-project-top-ten/

- **PCI DSS v4.0:**
  https://www.pcisecuritystandards.org/document_library

- **GDPR Breach Notification Requirements:**
  https://gdpr.eu/data-breach-notification/

### GitHub Actions Security Workflows

- **OWASP Dependency-Check:** `.github/workflows/ci.yml` (security-scan job)
- **Trivy Container Scan:** `.github/workflows/ci.yml` (security-scan job)
- **SARIF Upload:** Vulnerability results visible in GitHub Security tab

---

**Last Review:** 2026-01-18
**Next Review:** 2026-04-18 (quarterly)
**Change History:**

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2026-01-18 | 1.0 | Security Team | Initial security runbook (Task I6.T2) |

---

**Emergency Contact Card (Print & Laminate for On-Call Engineers):**

```
┌─────────────────────────────────────────────────────────┐
│ VILLAGE STOREFRONT SECURITY EMERGENCY CONTACT CARD      │
├─────────────────────────────────────────────────────────┤
│ Security Lead:    security@villagecompute.com           │
│ CISO Pager:       [REDACTED - Insert pager number]      │
│ Incident Command: incidents.pagerduty.com/villagecompute│
│                                                           │
│ KILL SWITCHES:                                          │
│ - Checkout:       checkout.kill-switch = false          │
│ - Impersonation:  impersonation.disable = true          │
│ - Media:          media.processing.enabled = false       │
│ - Webhooks:       stripe.webhook.processing.enabled=false│
│                                                           │
│ THREAT MODELS:                                          │
│ docs/security/threat-models/{payment,impersonation,media}│
└─────────────────────────────────────────────────────────┘
```
