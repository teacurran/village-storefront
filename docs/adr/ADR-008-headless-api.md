# ADR-008: Headless API Design & OAuth Scopes

**Status:** Accepted
**Date:** 2026-01-18
**Decision Makers:** Architecture Team, API Platform Team
**Related ADRs:** [ADR-001 (Multi-Tenancy)](ADR-001-tenancy.md)

---

## Context

Village Storefront must support external integrations (channel partners, mobile apps, embedded storefronts) requiring programmatic API access. Requirements include secure authentication, rate limiting, and tenant isolation.

---

## Decision

We implement **OAuth 2.0 Client Credentials** flow with tenant-scoped API keys and granular scope-based permissions:

### 1. Authentication Flow

```
Client → POST /oauth/token (client_id + client_secret + scope)
       ← Access Token (JWT, 1 hour expiry)
Client → API Request (Authorization: Bearer <token>)
       ← API Response
```

### 2. Scope System

| Scope | Permissions | Endpoints |
|-------|-------------|-----------|
| `catalog:read` | Read products, categories | `GET /products`, `GET /categories` |
| `catalog:write` | Create/update products | `POST /products`, `PATCH /products/{id}` |
| `checkout:read` | Read carts, orders | `GET /carts`, `GET /orders` |
| `checkout:write` | Create orders, process payments | `POST /orders` |
| `customer:read` | Read customer profiles | `GET /customers` |
| `customer:write` | Create/update customers | `POST /customers` |

### 3. Rate Limiting

- **Standard Tier**: 1000 requests/hour
- **Premium Tier**: 10,000 requests/hour
- **Burst Allowance**: 20 requests/second
- **Headers**: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

### 4. Tenant Isolation

- All API keys scoped to single tenant
- JWT includes `tenant_id` claim
- Middleware validates `tenant_id` matches resource being accessed
- PostgreSQL RLS enforces data isolation (see ADR-001)

---

## Rationale

**Why OAuth 2.0 Client Credentials (vs. API Keys Only)?**
- ✅ Short-lived tokens reduce credential leak risk
- ✅ Revocable without changing client secrets
- ✅ Industry standard (partners already understand OAuth)

**Why Scope-Based Permissions (vs. All-or-Nothing Access)?**
- ✅ Principle of least privilege (partners get only needed permissions)
- ✅ Audit trail shows which scopes were used
- ✅ Gradual permission expansion (start with read, add write later)

---

## Consequences

### Positive
- Secure partner integrations with revocable, short-lived tokens
- Granular permission control prevents over-privileged API clients
- Rate limiting protects platform from abuse

### Negative & Mitigations
- **Token Refresh Overhead**: Partners must refresh every hour → Document refresh best practices
- **Scope Complexity**: Partners may request wrong scopes → Provide scope selection wizard in onboarding
- **Rate Limit Friction**: Legitimate partners may hit limits → Offer premium tier upgrade

---

## References

- [Headless API Runbook](../operations/headless_api_runbook.md)
- [OAuth Endpoints OpenAPI Spec](../../api/v1/oauth-clients-endpoints.yaml)
- [RFC 6749: OAuth 2.0 Client Credentials Grant](https://datatracker.ietf.org/doc/html/rfc6749#section-4.4)
