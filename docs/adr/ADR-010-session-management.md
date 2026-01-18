# ADR-010: Session Management & JWT Token Strategy

**Status:** Accepted
**Date:** 2026-01-18
**Decision Makers:** Architecture Team, Security Team
**Related ADRs:** [ADR-001 (Multi-Tenancy)](ADR-001-tenancy.md), [ADR-006 (Background Jobs)](ADR-006-background-jobs.md)

---

## Context

Multi-tenant ecommerce platform requires secure authentication for store owners, staff, and platform admins. Must support admin impersonation for support workflows while maintaining audit compliance.

---

## Decision

We implement **JWT tokens with short-lived access + refresh tokens** and database session logging:

### 1. Token Architecture

| Token Type | Purpose | Lifetime | Storage |
|------------|---------|----------|---------|
| **Access Token** | API authentication | 15 minutes | Browser memory (no localStorage) |
| **Refresh Token** | Renew access tokens | 7 days | HttpOnly cookie |
| **Impersonation Token** | Admin → tenant user | 1 hour | Browser memory + audit log |

**JWT Claims** (Access Token):
```json
{
  "sub": "user-uuid",
  "tenant_id": "tenant-uuid",
  "roles": ["STORE_OWNER", "ADMIN"],
  "impersonator_id": "admin-uuid",  // Only present during impersonation
  "iat": 1705574400,
  "exp": 1705575300
}
```

### 2. Authentication Flow

**Standard Login**:
```
User → POST /auth/login (email + password)
     ← Access Token (15min) + Refresh Token (7 days, HttpOnly cookie)
User → API Request (Authorization: Bearer <access-token>)
     ← API Response
```

**Token Refresh**:
```
User → POST /auth/refresh (Refresh Token via cookie)
     ← New Access Token (15min)
```

**Impersonation Flow**:
```
Admin → POST /admin/impersonate (target_user_id)
      ← Impersonation Token (includes impersonator_id claim)
      → Database: INSERT INTO session_log (user_id, impersonator_id, action='impersonate_start')
Admin acts as user...
Admin → POST /admin/end-impersonation
      → Database: UPDATE session_log SET action='impersonate_end'
```

### 3. Session Logging

**Schema** (`session_log` table):
```sql
CREATE TABLE session_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    impersonator_id UUID,  -- NULL for normal sessions
    action VARCHAR(50) NOT NULL,  -- 'login', 'logout', 'impersonate_start', 'impersonate_end'
    ip_address INET,
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Audit Requirements**:
- All login/logout events logged
- All impersonation sessions logged with start/end timestamps
- Retention: 1 year for compliance

### 4. Security Measures

- **Short Access Token TTL**: 15 minutes limits exposure if leaked
- **Refresh Token Rotation**: New refresh token issued on every refresh (invalidates old one)
- **HttpOnly Cookies**: Refresh token inaccessible to JavaScript (XSS protection)
- **Impersonation Audit Trail**: All admin actions logged with `impersonator_id`
- **Session Revocation**: Database tracks active refresh tokens for emergency revocation

---

## Rationale

**Why JWT (vs. Server-Side Sessions)?**
- ✅ Stateless: No Redis/session store required (aligns with "No Redis" constraint)
- ✅ Horizontal Scaling: No session affinity needed (any pod validates token)
- ✅ Embedded Claims: Tenant ID + roles in token (no database lookup per request)

**Why Short Access Token TTL (15 min)?**
- ✅ Limits blast radius if token leaked
- ✅ Forces periodic refresh (detect revoked users within 15 min)
- ✅ Acceptable UX burden (refresh transparent to user)

**Why Database Session Logging (vs. No Logging)?**
- ✅ Compliance: Audit trail for security incidents
- ✅ Impersonation Accountability: Track all admin impersonation activity
- ✅ Forensics: Investigate suspicious login patterns

**Why Refresh Token Rotation (vs. Long-Lived Refresh Tokens)?**
- ✅ Limits refresh token reuse (old tokens invalidated)
- ✅ Detects token theft (simultaneous use of old + new refresh token)
- ✅ Security best practice (OAuth 2.1 recommendation)

---

## Consequences

### Positive
- Stateless authentication enables horizontal scaling without session store
- Short access token TTL reduces credential leak risk
- Impersonation audit trail meets compliance requirements
- Refresh token rotation detects token theft

### Negative & Mitigations
- **Token Refresh Overhead**: Refresh every 15 minutes → Automatic refresh before expiry (client-side logic)
- **Session Log Growth**: Audit table grows unbounded → Partition by month, archive after 1 year
- **Refresh Token Theft Risk**: Stolen cookie enables access until revoked → Monitor for suspicious refresh patterns (geography, user-agent changes)
- **Impersonation Abuse Risk**: Admins could misuse impersonation → Quarterly audit report of all impersonation sessions

---

## References

- [Identity Service Implementation](../../modules/core-platform/src/main/java/villagecompute/storefront/services/identity/)
- [RFC 7519: JSON Web Tokens](https://datatracker.ietf.org/doc/html/rfc7519)
- [OAuth 2.1 Security Best Practices](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-security-topics)
