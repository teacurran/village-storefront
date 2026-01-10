# Architectural Decision Records (ADRs)

**Version:** 1.0
**Last Updated:** 2026-01-10
**Owner:** Architecture Team
**Review Cycle:** Quarterly

---

## Overview

This directory contains Architectural Decision Records (ADRs) documenting significant architectural and design decisions for the Village Storefront platform.

An ADR captures:
- **Context:** Why we needed to make a decision
- **Decision:** What we decided to do
- **Rationale:** Why we chose this approach over alternatives
- **Consequences:** What trade-offs and implications resulted

---

## ADR Index

| ID | Title | Status | Date | Key Areas | Related ADRs |
|----|-------|--------|------|-----------|--------------|
| [ADR-0001](ADR-001-tenancy.md) | Multi-Tenancy & Tenant Isolation Strategy | Accepted | 2026-01-02 | Tenancy, Data Model, Security | ADR-0003, ADR-0004 |
| [ADR-0002](ADR-002-quality-gates.md) | CI/CD Quality Gates & Build Pipeline | Accepted | 2026-01-02 | CI/CD, Testing, SonarCloud | - |
| [ADR-0003](ADR-003-checkout-saga.md) | Checkout Saga Pattern & Adapter Contracts | Accepted | 2026-01-03 | Checkout, Payments, Transactions | ADR-0001 |
| [ADR-0004](ADR-004-consignment-payouts.md) | Consignment Payout Automation & Compliance | Accepted | 2026-01-03 | Consignment, Stripe Connect, Compliance | ADR-0001 |

---

## ADR Status Definitions

- **Proposed:** Decision is under discussion, not yet approved
- **Accepted:** Decision is approved and implemented
- **Superseded:** Decision has been replaced by a newer ADR (link to successor)
- **Deprecated:** Decision is no longer applicable or in use

---

## ADR Summaries

### ADR-0001: Multi-Tenancy & Tenant Isolation Strategy

**File:** [ADR-001-tenancy.md](ADR-001-tenancy.md)

**Decision:** Implement shared database with tenant-scoped queries via ThreadLocal `TenantContext`, using PostgreSQL Row-Level Security (RLS) as defense-in-depth.

**Rationale:** Balances cost efficiency (single PostgreSQL instance) with operational simplicity (instant tenant provisioning) while maintaining data isolation through application-level filtering plus database-enforced RLS policies.

**Key Trade-offs:**
- ✅ Fast tenant provisioning (INSERT into tenants table, no database creation)
- ✅ Cost efficient (single database vs. 1000+ databases)
- ✅ Zero-downtime tenant onboarding (no migrations or infrastructure changes)
- ⚠️ Data leakage risk (mitigated by mandatory tenant filtering + RLS enforcement)
- ⚠️ Noisy neighbor problem (mitigated by connection pooling + future read replicas)

**Implementation Highlights:**
- All entities extend `TenantAwareEntity` with `tenant_id` column
- `TenantRequestFilter` extracts subdomain/domain from `Host` header and sets `TenantContext.setCurrentTenant()`
- All queries MUST include `WHERE tenant_id = :tenantId` (enforced by code review + RLS)
- Background jobs serialize tenant ID in payload and restore context before execution
- PostgreSQL RLS policies enforce `tenant_id` filtering at database level (defense-in-depth)

**Implementation Status:** Fully implemented in I1.T5, I2.T1

---

### ADR-0002: CI/CD Quality Gates & Build Pipeline

**File:** [ADR-002-quality-gates.md](ADR-002-quality-gates.md)

**Decision:** Multi-stage GitHub Actions pipeline with Spotless, JaCoCo 80% coverage, SonarCloud quality gate, and native build validation running in parallel.

**Rationale:** Fast failure feedback (2 min formatting checks) and parallel execution (JVM + native builds concurrent) optimize developer velocity while maintaining quality standards.

**Key Trade-offs:**
- ✅ Fast feedback (formatting issues surface in 2 minutes)
- ✅ Comprehensive validation (code, specs, diagrams all checked)
- ✅ Parallel stages (JVM and native tests run concurrently)
- ✅ Artifact preservation (coverage reports, test results, build logs retained)
- ⚠️ Native build slowness (20-30 minutes delays PR merges)
- ⚠️ SonarCloud dependency (external SaaS)

**Pipeline Stages:**
1. **VALIDATE (fail fast):** Spotless formatting, OpenAPI lint, PlantUML validation (~2 min)
2. **TEST-JVM & TEST-NATIVE (parallel):** Maven verify + JaCoCo, GraalVM native build (~15-30 min)
3. **SONARCLOUD:** Static analysis + 80% coverage enforcement (~5-8 min)
4. **DOCKER (main/beta only):** Native container build and registry push

**Implementation Status:** Fully implemented in I1.T1, enhanced in I6.T1 with artifact signing and blue/green deployment support

---

### ADR-0003: Checkout Saga Pattern & Adapter Contracts

**File:** [ADR-003-checkout-saga.md](ADR-003-checkout-saga.md)

**Decision:** Choreographed saga orchestration for checkout flow (cart → order → payment → fulfillment) with explicit compensation stages, integration adapters, and caching strategies.

**Rationale:** Ensures checkout consistency across multiple services (inventory, payments, shipping) without distributed transactions, using PostgreSQL as coordination store and explicit compensation logic for rollback.

**Key Trade-offs:**
- ✅ Consistency without 2PC (two-phase commit)
- ✅ Explicit compensation logic (clear rollback semantics)
- ✅ Integration adapters abstract external APIs (Stripe, carriers, address validation)
- ✅ Idempotency keys prevent duplicate orders
- ✅ Caching strategies for performance and resilience (carrier rate fallbacks)
- ⚠️ Increased complexity (saga state machine)
- ⚠️ Database contention (saga status polling)

**Saga Stages:**
1. **Address Validation** (USPS/Lob API, read-only, no compensation)
2. **Shipping Rate Fetch** (carrier APIs with fallback caching)
3. **Inventory Reservation** (optimistic locking, compensation: release hold)
4. **Payment Authorization** (Stripe payment intent, compensation: refund)
5. **Order Creation** (PostgreSQL transaction, compensation: cancel order)
6. **Payment Capture** (Stripe capture, compensation: manual intervention)

**Implementation Highlights:**
- `CheckoutOrchestrator` coordinates saga execution
- Idempotency keys (`X-Idempotency-Key` header) prevent duplicate checkouts
- Integration adapters (`ShippingRateAdapter`, `AddressValidationAdapter`, `PaymentProvider`) abstract external service contracts
- Observability: OpenTelemetry spans per stage, Prometheus metrics for success/failure rates

**Implementation Status:** Fully implemented in I3.T1

---

### ADR-0004: Consignment Payout Automation & Compliance Guard Rails

**File:** [ADR-004-consignment-payouts.md](ADR-004-consignment-payouts.md)

**Decision:** Staged automation payout system with Stripe Connect Express, double-entry ledger, approval workflows, and comprehensive audit trail.

**Rationale:** Automates complex payout calculations (commission splits, balance tracking, payout scheduling) while maintaining financial accuracy and compliance through multi-stage validation and audit logging.

**Key Trade-offs:**
- ✅ Automated payout reconciliation (nightly settlement jobs)
- ✅ Comprehensive audit trail (every ledger entry logged with ticket numbers)
- ✅ Fraud prevention (approval workflow for high-value/new consignors)
- ✅ Double-entry bookkeeping (ledger accuracy verified on every payout)
- ✅ Multi-tenant isolation (payout data strictly scoped to tenant)
- ⚠️ Stripe Connect dependency (single payment rail, future: ACH/bank transfer)
- ⚠️ Payout timing constraints (daily settlement window, 24-hour SLA)

**Payout Lifecycle:**
1. **pending:** Batch created (automated monthly or admin-triggered)
2. **awaiting_approval:** Admin reviews and approves (required if >$500 or new consignor)
3. **processing:** Stripe Connect transfer in flight
4. **completed:** Payout successful, consignor notified via email with PDF statement
5. **failed:** Transfer failed, retries 3x with exponential backoff, then manual escalation

**Compliance Features:**
- Approval workflow (configurable thresholds: >$500, new consignor <3 months)
- Audit logging (all admin actions logged with ticket numbers in `platform_commands`)
- Ledger reconciliation (double-entry bookkeeping, balance verification)
- Failure handling (retry logic, manual intervention escalation)
- Notification (email with PDF statement on payout completion)

**Implementation Status:** Fully implemented in I5.T6

---

## Creating a New ADR

### When to Create an ADR

Create an ADR for decisions that:
- **Impact multiple modules:** Cross-cutting architectural changes
- **Affect external systems:** Integration patterns, API contracts
- **Introduce new dependencies:** Libraries, services, infrastructure
- **Change data models:** Schema migrations, storage patterns
- **Alter security posture:** Authentication, authorization, encryption
- **Involve significant trade-offs:** Performance vs. maintainability, cost vs. resilience

**Do NOT create ADRs for:**
- Implementation details (belongs in code comments)
- Minor refactoring (belongs in PR description)
- Bug fixes (belongs in issue tracker)

---

## ADR Template

Use the following template for new ADRs:

```markdown
# ADR-00XX: [Decision Title]

**Status:** Proposed | Accepted | Superseded | Deprecated
**Date:** YYYY-MM-DD
**Deciders:** [Names/Roles of decision makers]
**Consulted:** [Names/Roles consulted]
**Informed:** [Names/Roles informed]

---

## Context

[Describe the problem/challenge requiring a decision. Include business/technical context, constraints, and requirements.]

### Technical Context

[Relevant technical details: framework versions, existing architecture, dependencies]

### Success Criteria

[What does success look like? How will we measure if this decision was correct?]

---

## Decision

[State the decision clearly and concisely. What are we going to do?]

### Option Chosen: [Option Name]

[Detailed description of the chosen approach]

---

## Rationale

### Why This Option?

[Explain why this option was chosen over alternatives. Include comparison table if multiple options were considered.]

### Alternatives Considered

**Option 1: [Name]**
- Pro: [Benefit 1]
- Con: [Drawback 1]
- Rejected because: [Reason]

**Option 2: [Name]**
- Pro: [Benefit 1]
- Con: [Drawback 1]
- Rejected because: [Reason]

---

## Consequences

### Positive Consequences

1. [Benefit 1]
2. [Benefit 2]

### Negative Consequences

1. [Trade-off 1]
   - Mitigation: [How we'll address this]
2. [Trade-off 2]
   - Mitigation: [How we'll address this]

### Technical Debt Accepted

- [Debt item 1]: [Deferred to when/why]
- [Debt item 2]: [Deferred to when/why]

### Risks Introduced

- **RISK-XXX:** [Risk description]
  - Likelihood: [Low/Medium/High]
  - Impact: [Low/Medium/High/Critical]
  - Mitigation: [How we'll mitigate]

---

## Implementation Checklist

- [ ] [Task 1]
- [ ] [Task 2]
- [ ] [Task 3]

---

## References

- [Link to standards doc]
- [Link to competitor research]
- [Link to architecture overview]
- [Link to related ADRs]

---

**Document Version:** 1.0
**Last Updated:** YYYY-MM-DD
**Maintained By:** [Team/Person]
**Next Review:** [Date]
```

---

## ADR Approval Process

1. **Draft:** Author creates ADR in `Proposed` status
2. **Review:** Architecture team reviews (async via PR comments or sync in architecture review meeting)
3. **Discussion:** Alternatives debated, trade-offs evaluated
4. **Decision:** Team approves or requests changes
5. **Acceptance:** ADR status updated to `Accepted`, merged to main
6. **Implementation:** Work begins, checklist items tracked
7. **Review:** Quarterly review to assess if decision is still valid

**Timeline:** ADR approval should take <1 week for non-controversial decisions, <2 weeks for complex decisions requiring deep analysis.

---

## Superseding an ADR

When a decision needs to change:

1. Create new ADR documenting the new decision
2. Update old ADR:
   - Status: `Superseded by ADR-00XX`
   - Add supersession note at top of document
3. Update ADR index with supersession link
4. Keep old ADR for historical record (do NOT delete)

**Example:**

```markdown
# ADR-0001: Multi-Tenancy Strategy

**Status:** Superseded by [ADR-0010](ADR-0010-multi-region-tenancy.md)
**Original Date:** 2026-01-02
**Superseded Date:** 2026-06-15

> **Note:** This decision was superseded by ADR-0010 which introduces multi-region tenant sharding.
> The original decision remains valid for single-region deployments.
```

---

## Review Schedule

**Frequency:** Quarterly
**Owner:** Architecture Team
**Next Review:** 2026-04-10

**Quarterly Review Checklist:**
- [ ] Review all `Accepted` ADRs - are they still valid?
- [ ] Update ADR status if decisions have changed
- [ ] Archive completed implementation checklists
- [ ] Update trade-off assessments based on operational experience
- [ ] Identify new ADRs needed for upcoming work

---

**For Questions or Feedback:**
- Slack: #architecture-team
- Email: architecture@villagecompute.com
- Office Hours: Tuesdays 2-3 PM EST
