# Payment Processing Threat Model

**Version:** 1.0
**Date:** 2026-01-10
**Status:** Active
**Owner:** Platform Security Team

## 1. Executive Summary

This threat model analyzes security risks in the Village Storefront payment processing system, which integrates with Stripe Connect for multi-tenant payment handling. The system processes customer payment information for merchant stores while maintaining PCI DSS compliance through Stripe-hosted payment flows.

## 2. Assets at Risk

### 2.1 Critical Assets
- **Payment Credentials:** Customer credit card data, bank account information
- **Stripe Connect Secrets:** Stripe API keys, webhook signing secrets, OAuth credentials
- **Financial Data:** Order totals, refund amounts, payout records, transaction history
- **Customer PII:** Billing addresses, email addresses linked to payment records
- **Merchant Account Data:** Stripe Connect account IDs, payout schedules, commission rates

### 2.2 Asset Value Assessment
- **Payment Credentials:** CRITICAL - Compromise leads to fraud, regulatory penalties (PCI DSS)
- **Stripe Connect Secrets:** CRITICAL - Enables unauthorized payment operations across all tenants
- **Financial Data:** HIGH - Data manipulation causes financial losses, accounting errors
- **Customer PII:** HIGH - Breach triggers GDPR/CCPA reporting requirements
- **Merchant Account Data:** MEDIUM - Compromise affects merchant trust and platform revenue

## 3. Threat Actors

### 3.1 External Attackers
- **Motivation:** Financial gain through credit card fraud, refund fraud
- **Capabilities:** SQL injection, XSS, API abuse, social engineering
- **Target:** Customer payment data, Stripe API keys, webhook endpoints

### 3.2 Malicious Merchants
- **Motivation:** Increased revenue through payment manipulation, refund abuse
- **Capabilities:** Admin dashboard access, API knowledge, test mode experimentation
- **Target:** Order totals, refund processing, commission calculations

### 3.3 Compromised Customer Accounts
- **Motivation:** Unauthorized purchases using stored payment methods
- **Capabilities:** Valid session tokens, knowledge of checkout flow
- **Target:** Saved payment methods, one-click checkout

### 3.4 Insider Threats
- **Motivation:** Data theft for resale, competitive intelligence
- **Capabilities:** Database access, production credentials, platform admin permissions
- **Target:** Payment data at rest, Stripe API keys in configuration

## 4. Attack Vectors & Existing Controls

### 4.1 Webhook Replay Attacks
**Attack:** Attacker captures Stripe webhook event and replays it to trigger duplicate payments or refunds.

**Existing Controls:**
- Stripe signature validation (stripe-java SDK) on all webhook payloads (HTTP header `Stripe-Signature`)
- Idempotency keys stored in `payment_intents` table to detect duplicate processing
- Webhook events logged with `event_id` for audit trail

**Implementation References:**
- Architecture: Section "Payment Security" - webhook signature validation
- Code: `PaymentWebhookHandler.java` (inferred from Stripe integration patterns)

**Residual Risk:** LOW - Signature validation prevents replay attacks. Idempotency layer prevents duplicate processing even if signature check bypassed.

---

### 4.2 Man-in-the-Middle on Webhook Delivery
**Attack:** Attacker intercepts webhook traffic between Stripe and application to read/modify payment events.

**Existing Controls:**
- HTTPS enforced for all webhook endpoints (Stripe only delivers to HTTPS URLs)
- TLS certificate validation on receiving end
- Webhook signature validation ensures payload integrity

**Implementation References:**
- Architecture: Section "Payment Security" - Stripe-hosted flows

**Residual Risk:** LOW - HTTPS + signature validation provides strong protection. No additional controls needed.

---

### 4.3 Payment Amount Manipulation
**Attack:** Attacker modifies order total before payment intent creation (e.g., via XSS, CSRF, or direct API calls).

**Existing Controls:**
- Server-side order validation: Order total recalculated from `order_items` before creating Stripe PaymentIntent
- Checkout flow validates cart contents against inventory (prevents negative pricing exploits)
- Multi-tenant isolation: TenantContext prevents cross-tenant price manipulation

**Implementation References:**
- Architecture: ADR-001 - tenant isolation via TenantContext ThreadLocal
- Code: `CheckoutService.java` (inferred) - server-side order validation

**Residual Risk:** MEDIUM - Controls assume cart items are validated correctly. Vulnerability if inventory pricing logic has bugs (e.g., negative quantity allowed).

**Mitigation Backlog:**
- Add integration tests for negative quantity handling
- Implement price change alerts (notify merchant if order total deviates >10% from cart estimate)

---

### 4.4 Refund Fraud
**Attack:** Merchant or attacker initiates unauthorized refunds to steal funds.

**Existing Controls:**
- Permission checks: Refunds require `refunds:write` permission (enforced by `PlatformAdminAuthorizationService`)
- Audit logging: All refund operations logged to `admin_audit_log` table with user ID, tenant ID, timestamp
- Stripe webhook confirmations: Refund success/failure events trigger notifications

**Implementation References:**
- Code: `PlatformAdminAuthorizationService.java:44-62` - RBAC permission checks
- Architecture: Section "Impersonation" - audit logging requirements

**Residual Risk:** MEDIUM - Controls prevent unauthorized access but do not prevent authorized users abusing permissions. No automated fraud detection.

**Mitigation Backlog:**
- Implement refund velocity limits (e.g., max 5 refunds per merchant per hour)
- Add anomaly detection for refund patterns (alert on >$10k refunds in 24 hours)
- Require secondary approval for refunds >$1000

---

### 4.5 PCI DSS Compliance Violation
**Attack:** Developer accidentally stores raw credit card data in application database or logs.

**Existing Controls:**
- Stripe-hosted payment flows: Card data never enters application backend (client-side Stripe.js tokenization)
- OpenAPI spec: Payment endpoints only accept `payment_method_id` tokens, not raw card data
- Code review checklist: "Does this PR log/store card data?" (documented in `docs/java-project-standards.adoc`)

**Implementation References:**
- Architecture: Section "Payment Security" - PCI DSS Level 1 compliance via Stripe
- OpenAPI: `/api/v1/checkout/payment-intent` endpoint schema

**Residual Risk:** LOW - Stripe-hosted flows eliminate application PCI scope. Risk limited to logging/debugging errors.

**Mitigation Backlog:**
- Add static analysis rule to flag log statements containing keywords: "card", "cvv", "expiry"
- Implement log scrubbing to redact sensitive data patterns

---

### 4.6 Stripe API Key Exposure
**Attack:** Stripe secret keys leaked via code repository, logs, or error messages.

**Existing Controls:**
- Environment variables: Stripe keys loaded from `STRIPE_SECRET_KEY` env var (not hardcoded)
- `.gitignore`: Excludes `.env` files from version control
- Access control: Production keys restricted to CI/CD secrets + ops team

**Implementation References:**
- Config: `application.properties` - Stripe configuration
- CI/CD: `.github/workflows/ci.yml` - secrets management

**Residual Risk:** MEDIUM - Controls prevent intentional commits but not accidental exposure (e.g., error messages, logs).

**Mitigation Backlog:**
- Add GitHub secret scanning (dependabot alerts)
- Implement key rotation policy (rotate Stripe keys every 90 days)
- Add error message sanitization to prevent key leakage in stack traces

---

### 4.7 Cross-Tenant Payment Access
**Attack:** Attacker exploits multi-tenancy bug to process payments using another merchant's Stripe account.

**Existing Controls:**
- Tenant context validation: `TenantContext.getCurrentTenantId()` verified before Stripe API calls
- Row-level security (RLS): PostgreSQL policies filter `orders` and `payments` by `tenant_id`
- Payment intent metadata: Stripe PaymentIntent includes `tenant_id` in metadata for reconciliation

**Implementation References:**
- Architecture: ADR-001 - RISK-001 tenant data leakage mitigations
- Code: `TenantResolutionFilter.java:143-146` - RLS enablement
- Code: `TenantContextClearFilter.java:54-65` - ThreadLocal cleanup to prevent leakage

**Residual Risk:** MEDIUM - Strong controls in place but RISK-001 (tenant leakage) is CRITICAL impact. Requires ongoing test coverage.

**Mitigation Backlog:**
- Multi-tenant integration tests: Verify payment operations enforce tenant isolation (create test in `PaymentServiceIT.java`)
- Static analysis: SonarCloud custom rule to flag Stripe API calls without TenantContext check

---

### 4.8 Webhook Endpoint Enumeration
**Attack:** Attacker discovers webhook endpoint URL and sends fake payment events.

**Existing Controls:**
- Signature validation: All webhook requests rejected without valid Stripe signature
- Rate limiting: Webhook endpoints protected by application-level rate limiting (planned - not yet implemented)
- URL obscurity: Webhook URL includes tenant-specific path segment (e.g., `/webhooks/stripe/{tenantId}`)

**Implementation References:**
- Architecture: Section "Payment Security" - webhook signature validation

**Residual Risk:** MEDIUM - Signature validation prevents fake events, but endpoint DOS attack possible without rate limiting.

**Mitigation Backlog:**
- Implement rate limiting on webhook endpoints (1000 req/min per tenant)
- Add webhook URL rotation capability (allow merchants to regenerate webhook URLs)

---

## 5. Residual Risks Summary

| Risk ID | Description | Likelihood | Impact | Risk Level | Mitigation Status |
|---------|-------------|------------|--------|------------|-------------------|
| PAY-001 | Payment amount manipulation via inventory pricing bugs | Medium | High | MEDIUM | Backlog (integration tests) |
| PAY-002 | Refund fraud by authorized users | Medium | High | MEDIUM | Backlog (velocity limits + anomaly detection) |
| PAY-003 | Stripe API key exposure via logs/errors | Low | Critical | MEDIUM | Backlog (secret scanning + rotation policy) |
| PAY-004 | Cross-tenant payment access | Low | Critical | MEDIUM | Backlog (multi-tenant integration tests) |
| PAY-005 | Webhook endpoint DOS attack | Medium | Medium | MEDIUM | Backlog (rate limiting) |

## 6. Security Controls Inventory

### Implemented Controls
1. **Stripe signature validation** - Webhook replay attack prevention
2. **Idempotency keys** - Duplicate payment prevention
3. **HTTPS enforcement** - Man-in-the-middle protection
4. **Server-side order validation** - Payment amount manipulation prevention
5. **RBAC permission checks** - Refund authorization
6. **Audit logging** - Forensic investigation capability
7. **Stripe-hosted payment flows** - PCI DSS compliance
8. **Environment variable secrets** - API key protection
9. **Tenant context isolation** - Cross-tenant access prevention
10. **Row-level security (RLS)** - Defense-in-depth for multi-tenancy

### Planned Controls (Backlog)
1. Inventory pricing integration tests (PAY-001)
2. Refund velocity limits and anomaly detection (PAY-002)
3. GitHub secret scanning and key rotation policy (PAY-003)
4. Multi-tenant payment integration tests (PAY-004)
5. Webhook endpoint rate limiting (PAY-005)
6. Log scrubbing for sensitive data patterns
7. Static analysis for Stripe API call validation

## 7. Compliance Mapping

### PCI DSS Level 1 Requirements
- **Requirement 3:** Protect stored cardholder data - Mitigated by Stripe-hosted flows (no card data stored)
- **Requirement 6:** Develop secure systems - Code review checklist, Spotless formatting
- **Requirement 8:** Identify and authenticate access - JWT + OAuth authentication
- **Requirement 10:** Track and monitor access - Audit logging for all payment operations
- **Requirement 12:** Maintain information security policy - Documented in threat model

### GDPR Compliance
- **Article 25:** Data protection by design - Multi-tenancy isolation, audit logging
- **Article 32:** Security of processing - Encryption in transit (HTTPS), access controls
- **Article 33:** Breach notification - Audit logs enable breach detection within 72 hours

## 8. Testing & Validation

### Security Test Coverage
- **Unit tests:** Stripe signature validation, idempotency key generation
- **Integration tests:** Payment intent creation, webhook event processing, refund flows
- **Planned:** Multi-tenant payment isolation tests (PAY-004)

### Penetration Testing Scope
- **Phase 1 (Post-Launch):** Stripe webhook replay attacks, payment amount manipulation
- **Phase 2 (6 months):** Bug bounty program for payment security vulnerabilities

## 9. Incident Response

### Detection Mechanisms
- **Metrics:** `payment.stripe.error` counter (alert on rate >10/min)
- **Audit logs:** Daily review of refund operations >$1000
- **Stripe dashboard:** Manual review of chargebacks and disputes

### Response Procedures
1. **Payment fraud detected:** Freeze merchant account, contact Stripe support, review audit logs
2. **API key exposure:** Rotate Stripe keys immediately, audit recent API calls, notify affected merchants
3. **Cross-tenant payment:** Emergency shutdown via feature flag `payments.enabled=false`, rollback recent transactions

## 10. References

- **Architecture:** `docs/architecture_overview.md` - Section "Payment Security"
- **ADR-001:** `docs/adr/ADR-001-tenancy.md` - Multi-tenancy security controls
- **Stripe Docs:** https://stripe.com/docs/security/guide
- **PCI DSS:** https://www.pcisecuritystandards.org/document_library

## 11. Change History

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2026-01-10 | 1.0 | Security Team | Initial threat model (Task I6.T2) |

---

**Review Cadence:** Quarterly (or after major payment feature changes)
**Next Review:** 2026-04-10
