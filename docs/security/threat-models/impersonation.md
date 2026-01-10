# Platform Admin Impersonation Threat Model

**Version:** 1.0
**Date:** 2026-01-10
**Status:** Active
**Owner:** Platform Security Team

## 1. Executive Summary

This threat model analyzes security risks in the Village Storefront platform admin impersonation feature, which allows platform administrators to access merchant stores and user accounts for support and troubleshooting purposes. Impersonation is a high-privilege operation requiring strict access controls and comprehensive audit logging.

## 2. Assets at Risk

### 2.1 Critical Assets
- **Merchant Store Data:** Products, orders, customer information, payment records
- **Customer PII:** Email addresses, billing/shipping addresses, order history
- **Platform Admin Credentials:** Platform admin user accounts, permission sets
- **Audit Logs:** Impersonation event logs, admin action trails
- **System Configuration:** Feature flags, tenant settings, payment gateway credentials

### 2.2 Asset Value Assessment
- **Merchant Store Data:** CRITICAL - Unauthorized access causes merchant trust loss, regulatory violations
- **Customer PII:** CRITICAL - Breach triggers GDPR/CCPA reporting, potential fines
- **Platform Admin Credentials:** CRITICAL - Compromise enables platform-wide data access
- **Audit Logs:** HIGH - Tampering hides malicious activity, prevents forensic investigation
- **System Configuration:** HIGH - Manipulation can disable security controls, cause outages

## 3. Threat Actors

### 3.1 External Attackers
- **Motivation:** Data theft, competitive intelligence, extortion
- **Capabilities:** Credential stuffing, phishing, session hijacking
- **Target:** Platform admin credentials to gain impersonation access

### 3.2 Malicious Insiders (Platform Admins)
- **Motivation:** Data theft for resale, unauthorized merchant monitoring
- **Capabilities:** Valid admin credentials, knowledge of impersonation workflows
- **Target:** Merchant financial data, customer PII, competitor store analytics

### 3.3 Compromised Admin Accounts
- **Motivation:** Attacker leveraging stolen admin credentials
- **Capabilities:** Full platform admin permissions including impersonation
- **Target:** High-value merchant accounts, payment processing credentials

### 3.4 Social Engineering Attacks
- **Motivation:** Trick platform admins into unauthorized impersonation
- **Capabilities:** Fake support requests, phishing emails pretending to be merchants
- **Target:** Admin impersonation actions under false pretenses

## 4. Attack Vectors & Existing Controls

### 4.1 Unauthorized Privilege Escalation
**Attack:** Attacker exploits permission check bugs to gain impersonation capability without proper authorization.

**Existing Controls:**
- RBAC enforcement: `PlatformAdminAuthorizationService.java:44-62` validates permissions before allowing impersonation
- Permission storage: Permissions stored as JSON array in `platform_admin_roles.permissions` column
- JWT claims: Admin permissions embedded in JWT token claims (verified per request)
- Role hierarchy: Platform admin roles defined in database with explicit permission sets

**Implementation References:**
- Code: `PlatformAdminAuthorizationService.java` - resolves SecurityIdentity and validates permissions
- Architecture: Section "Authentication & Authorization" - JWT token claims structure

**Residual Risk:** MEDIUM - Controls assume permission checks are applied to all impersonation endpoints. Missing check = privilege escalation.

**Mitigation Backlog:**
- Integration tests: Verify all impersonation endpoints reject requests without `impersonate:execute` permission
- Static analysis: Flag API endpoints matching pattern `/admin/impersonate/*` without `@RolesAllowed` annotation

---

### 4.2 Missing Permission Checks on Sensitive Operations
**Attack:** Admin impersonates user and performs sensitive actions (e.g., delete products, issue refunds) without additional authorization.

**Existing Controls:**
- Permission granularity: Impersonation permission separate from operation permissions (e.g., `impersonate:execute` + `refunds:write` both required)
- Audit logging: All impersonated actions logged with original admin user ID + impersonated user ID
- Feature flags: Emergency kill switch `impersonation.disable=true` to block all impersonation (per ADR-001)

**Implementation References:**
- Architecture: ADR-001 - impersonation feature flag for emergency shutdown
- Architecture: Section "Impersonation" - audit logging requirements

**Residual Risk:** MEDIUM - Controls prevent unauthorized impersonation but do not prevent authorized admins from abusing permissions.

**Mitigation Backlog:**
- Secondary approval: Require second admin approval for high-risk actions during impersonation (e.g., refunds >$1000)
- Time limits: Impersonation sessions auto-expire after 30 minutes of inactivity
- Action allowlisting: Restrict impersonation to read-only operations by default (write actions require explicit approval)

---

### 4.3 Audit Log Tampering
**Attack:** Admin manipulates or deletes audit logs to hide malicious impersonation activity.

**Existing Controls:**
- Append-only table: `admin_audit_log` table has no UPDATE/DELETE permissions for application role
- Database-level permissions: Only DBA role can modify audit logs (production DBAs are separate team)
- Immutable fields: Audit log includes timestamp, user ID, tenant ID, action type, request payload

**Implementation References:**
- Architecture: Section "Impersonation" - audit log structure

**Residual Risk:** LOW - Strong controls prevent application-level tampering. Risk limited to database compromise.

**Mitigation Backlog:**
- Write-ahead logging: Export audit logs to external SIEM system (e.g., Splunk, Datadog) for immutable storage
- Periodic integrity checks: Hash audit log entries and verify against external checksum store

---

### 4.4 Session Hijacking During Impersonation
**Attack:** Attacker steals admin JWT token during impersonation session to perform unauthorized actions.

**Existing Controls:**
- JWT expiration: Short-lived access tokens (15 minutes) + refresh tokens (7 days)
- HTTPS enforcement: All admin API endpoints require HTTPS (prevents network sniffing)
- Secure cookie flags: JWT stored in cookie with `HttpOnly`, `Secure`, `SameSite=Strict` flags
- IP binding: JWT claims include original IP address (validated on each request)

**Implementation References:**
- Config: `application.properties:61-64` - JWT configuration
- Architecture: Section "Authentication & Authorization" - JWT token pattern

**Residual Risk:** LOW - Short token lifetime limits attack window. IP binding prevents token reuse from different location.

**Mitigation Backlog:**
- Device fingerprinting: Include browser/device fingerprint in JWT claims (detect token theft to different device)
- Admin MFA requirement: Require multi-factor authentication for all platform admin logins

---

### 4.5 Unauthorized Data Access via Impersonation
**Attack:** Admin impersonates user to access data they shouldn't have access to (e.g., competitor merchant's store).

**Existing Controls:**
- Tenant context enforcement: Impersonation requires specifying target `tenant_id` (verified against admin's allowed tenants)
- Tenant allowlist: Platform admins assigned to specific tenants (stored in `platform_admin_tenant_access` table)
- RLS policies: Row-level security filters data by tenant_id even during impersonation

**Implementation References:**
- Architecture: ADR-001 - tenant isolation via TenantContext ThreadLocal
- Code: `TenantResolutionFilter.java:143-146` - RLS session variable seeding

**Residual Risk:** MEDIUM - Controls assume tenant allowlist is maintained correctly. Risk if admin assigned to wrong tenants.

**Mitigation Backlog:**
- Quarterly access reviews: Audit platform admin tenant assignments (remove stale access)
- Just-in-time access: Require approval workflow for impersonation access (auto-expire after 24 hours)
- Access logging: Alert on impersonation attempts to tenants outside admin's normal pattern

---

### 4.6 Audit Log Bypass via Direct Database Access
**Attack:** Admin bypasses audit logging by performing actions directly via database queries instead of application APIs.

**Existing Controls:**
- Database access control: Production database access restricted to DBA team + read-only analysts
- VPN + bastion host: Database accessible only via VPN + jump server (no direct internet access)
- Query logging: PostgreSQL logs all queries to `pg_log` (reviewed weekly)

**Implementation References:**
- Operations: Database access policy (documented in `docs/operations/database-access.md` - inferred)

**Residual Risk:** LOW - Strong perimeter controls prevent unauthorized database access. Risk limited to DBA team.

**Mitigation Backlog:**
- Database audit triggers: PostgreSQL triggers to log all INSERT/UPDATE/DELETE operations to audit table
- Anomaly detection: Alert on unusual query patterns (e.g., direct UPDATE to `orders` table)

---

### 4.7 Impersonation Without Valid Business Justification
**Attack:** Admin impersonates user for curiosity, personal gain, or unauthorized monitoring (no malicious intent but still policy violation).

**Existing Controls:**
- Audit logging: All impersonation events logged with admin user ID, target user ID, timestamp
- Manual review: Security team reviews impersonation logs weekly
- Policy training: Admin onboarding includes acceptable use policy for impersonation

**Implementation References:**
- Architecture: Section "Impersonation" - audit logging requirements

**Residual Risk:** HIGH - Controls detect abuse after the fact but do not prevent unauthorized impersonation.

**Mitigation Backlog:**
- Justification required: Admins must enter support ticket number when initiating impersonation
- Manager approval: Impersonation requests require manager approval (workflow integration with ticket system)
- Automated alerts: Real-time alert to security team on impersonation start (Slack notification)

---

### 4.8 Emergency Kill Switch Failure
**Attack:** Security incident requires disabling impersonation immediately, but feature flag fails to take effect.

**Existing Controls:**
- Feature flag: `impersonation.disable=true` blocks all impersonation endpoints (per ADR-001)
- Runtime refresh: Feature flags refreshed every 60 seconds (no restart required)
- Graceful degradation: Existing impersonation sessions terminated on next API call after flag enabled

**Implementation References:**
- Architecture: ADR-001 - emergency feature flags for security controls

**Residual Risk:** LOW - Feature flag provides rapid shutdown capability. Risk limited to 60-second delay.

**Mitigation Backlog:**
- Instant refresh: WebSocket push for feature flag updates (zero delay)
- Admin notification: Display banner in admin UI when impersonation disabled (prevent confusion)

---

## 5. Residual Risks Summary

| Risk ID | Description | Likelihood | Impact | Risk Level | Mitigation Status |
|---------|-------------|------------|--------|------------|-------------------|
| IMP-001 | Privilege escalation via missing permission checks | Medium | Critical | HIGH | Backlog (integration tests + static analysis) |
| IMP-002 | Abuse of impersonation by authorized admins | Medium | High | MEDIUM | Backlog (secondary approval + time limits) |
| IMP-003 | Unauthorized data access via tenant allowlist errors | Low | High | MEDIUM | Backlog (quarterly access reviews + JIT access) |
| IMP-004 | Impersonation without business justification | High | Medium | HIGH | Backlog (justification required + manager approval) |
| IMP-005 | Session hijacking during impersonation | Low | High | MEDIUM | Backlog (device fingerprinting + admin MFA) |

## 6. Security Controls Inventory

### Implemented Controls
1. **RBAC permission checks** - `PlatformAdminAuthorizationService` validates impersonation permission
2. **Audit logging** - All impersonation events logged to `admin_audit_log` table
3. **JWT short-lived tokens** - 15-minute access token expiration
4. **HTTPS enforcement** - Prevents session token sniffing
5. **Tenant context isolation** - RLS + TenantContext ThreadLocal
6. **Append-only audit table** - No UPDATE/DELETE permissions
7. **Feature flag kill switch** - `impersonation.disable` for emergency shutdown
8. **Database access control** - VPN + bastion host for production DB
9. **Tenant allowlist** - Platform admins assigned to specific tenants

### Planned Controls (Backlog)
1. Integration tests for permission checks on all impersonation endpoints (IMP-001)
2. Static analysis for missing @RolesAllowed annotations (IMP-001)
3. Secondary approval workflow for high-risk actions during impersonation (IMP-002)
4. Time-limited impersonation sessions (30-minute auto-expire) (IMP-002)
5. Quarterly platform admin access reviews (IMP-003)
6. Just-in-time impersonation access with approval workflow (IMP-003)
7. Required justification (support ticket number) for impersonation (IMP-004)
8. Manager approval for impersonation requests (IMP-004)
9. Real-time Slack alerts on impersonation start (IMP-004)
10. Admin MFA requirement (IMP-005)
11. Device fingerprinting for JWT tokens (IMP-005)
12. External SIEM export for audit logs (tamper protection)

## 7. Compliance Mapping

### SOC 2 Type II Requirements
- **CC6.1:** Logical access controls - RBAC + permission checks
- **CC6.2:** Prior to issuing credentials - Admin onboarding with acceptable use policy
- **CC6.3:** Removes access when appropriate - Quarterly access reviews (planned)
- **CC7.2:** System monitoring - Audit logging + weekly log reviews
- **CC7.3:** Evaluation of security events - Real-time alerts on impersonation (planned)

### GDPR Compliance
- **Article 25:** Data protection by design - Audit logging, permission checks, tenant isolation
- **Article 30:** Records of processing - Audit logs document data access by admins
- **Article 32:** Security of processing - RBAC, JWT encryption, HTTPS

## 8. Testing & Validation

### Security Test Coverage
- **Unit tests:** Permission validation logic, JWT token generation
- **Integration tests (planned):** All impersonation endpoints reject unauthorized requests (IMP-001)
- **Manual testing:** Weekly security team review of impersonation audit logs

### Penetration Testing Scope
- **Phase 1 (Post-Launch):** Privilege escalation, missing permission checks, audit log tampering
- **Phase 2 (6 months):** Session hijacking, tenant isolation bypass

## 9. Incident Response

### Detection Mechanisms
- **Audit logs:** Daily review of impersonation events (filter by admin user ID)
- **Metrics:** `impersonation.session.start` counter (alert on rate >10/hour for single admin)
- **Alerts:** Real-time Slack notification on impersonation start (planned)

### Response Procedures
1. **Unauthorized impersonation detected:** Disable admin account immediately, rotate admin credentials, audit recent actions
2. **Privilege escalation:** Enable feature flag `impersonation.disable=true`, patch vulnerability, rollback unauthorized changes
3. **Data breach via impersonation:** Notify affected merchants within 72 hours (GDPR), preserve audit logs for investigation

## 10. References

- **Architecture:** `docs/architecture_overview.md` - Section "Authentication & Authorization"
- **ADR-001:** `docs/adr/ADR-001-tenancy.md` - Tenant isolation + impersonation feature flags
- **Code:** `modules/core-platform/src/main/java/villagecompute/storefront/platformops/security/PlatformAdminAuthorizationService.java`

## 11. Change History

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2026-01-10 | 1.0 | Security Team | Initial threat model (Task I6.T2) |

---

**Review Cadence:** Quarterly (or after impersonation feature changes)
**Next Review:** 2026-04-10
