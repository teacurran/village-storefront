# ADR-007: Feature Flag Governance & Progressive Rollout

**Status:** Accepted
**Date:** 2026-01-18
**Decision Makers:** Architecture Team, Platform Engineering Team, Security Team
**Related ADRs:** [ADR-001 (Multi-Tenancy)](ADR-001-tenancy.md)

---

## Context

Village Storefront's multi-tenant SaaS platform requires the ability to deploy code without immediately exposing it to all tenants. Key use cases include:

- **Dark Launches**: Deploy features disabled by default, enable for testing before general availability
- **Gradual Rollouts**: Enable features for subset of tenants to monitor performance/adoption before full rollout
- **Emergency Kill Switches**: Instantly disable problematic features (payment processing, media uploads) without code deployment
- **A/B Testing**: Experiment with different implementations to measure impact on conversion/revenue
- **Per-Tenant Customization**: Allow individual tenants to enable/disable optional features

### Technical Constraints

1. **Multi-Tenancy Requirements**: Flags must support both global defaults and per-tenant overrides (see ADR-001)
2. **Low Latency**: Flag resolution must not add significant overhead to HTTP request path (<1ms target)
3. **Cache Invalidation**: Flag changes must propagate to all pods within seconds
4. **Observability**: Operations team must track flag lifecycle (creation, rollout progress, expiry)
5. **No External Service**: Platform architecture prohibits external services (LaunchDarkly, Split.io) to avoid vendor lock-in and monthly costs

### Business Requirements

- **Emergency Response**: Platform admins must be able to disable features instantly during incidents (P1: <1 minute to disable)
- **Rollout Safety**: Features must be testable on subset of tenants before general availability
- **Flag Debt Prevention**: Flags must have expiry dates to prevent accumulation of unused flags in codebase
- **Accountability**: Every flag must have an owner responsible for lifecycle management
- **Audit Trail**: All flag changes must be logged for compliance and troubleshooting

---

## Decision

We will implement a **database-backed feature flag system** with Caffeine caching and structured governance:

### 1. Feature Flag Schema

```sql
CREATE TABLE feature_flags (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID REFERENCES tenants(id) ON DELETE CASCADE,  -- NULL = global default
    flag_key          VARCHAR(200) NOT NULL,

    -- Flag state
    enabled           BOOLEAN NOT NULL DEFAULT false,

    -- Governance metadata
    owner             VARCHAR(255) NOT NULL,
    risk_level        VARCHAR(20) NOT NULL,  -- LOW, MEDIUM, HIGH, CRITICAL
    review_cadence_days  INTEGER NOT NULL DEFAULT 90,
    expiry_date       TIMESTAMPTZ,

    -- Documentation
    description       TEXT,
    rollout_plan      TEXT NOT NULL,
    rollback_instructions TEXT,

    -- Audit trail
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by        VARCHAR(255),

    CONSTRAINT flag_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    UNIQUE (tenant_id, flag_key)  -- One value per tenant per flag
);

CREATE INDEX idx_feature_flags_lookup ON feature_flags(tenant_id, flag_key);
CREATE INDEX idx_feature_flags_expiry ON feature_flags(expiry_date) WHERE expiry_date IS NOT NULL AND expiry_date < NOW();
```

### 2. Flag Resolution Strategy

**Lookup Order** (implemented in `FeatureToggle.java`):
1. Check tenant-specific flag: `SELECT enabled FROM feature_flags WHERE tenant_id = :current AND flag_key = :key`
2. If not found, check global flag: `SELECT enabled FROM feature_flags WHERE tenant_id IS NULL AND flag_key = :key`
3. If not found, default to `false` (disabled)

**Caching Layer** (Caffeine in-memory cache):
- Cache key format: `tenant:{tenantId}:flag:{flagKey}` (tenant-specific) or `global:flag:{flagKey}` (global)
- TTL: 10 minutes (configurable via `feature-flag.cache.expiry-seconds`)
- Eviction: LRU with max 10,000 entries (protects against memory exhaustion)
- Invalidation: Manual via `FeatureToggle.invalidate(tenantId, flagKey)` after flag update

**Performance Characteristics:**
- Cache hit: ~0.1ms (in-memory map lookup)
- Cache miss: ~5ms (PostgreSQL query + cache population)
- Acceptable overhead: <1% of typical HTTP request latency (200ms p95)

### 3. Flag Naming Convention

Format: `{area}.{feature}.{variant}`

**Examples:**
- `checkout.apple-pay` - Apple Pay integration at checkout
- `storefront.hero.beta` - Beta hero component design
- `media.uploads.enabled` - Kill switch for media uploads
- `payments.stripe.enabled` - Kill switch for Stripe payment processing

**Rationale:**
- Hierarchical structure enables bulk operations (e.g., disable all `media.*` flags)
- Searchable by area/feature in admin UI
- Self-documenting (key describes what it controls)

### 4. Risk Levels & Kill Switches

| Risk Level | Review Cadence | Description | Examples |
|------------|----------------|-------------|----------|
| **LOW** | 90 days | Non-critical UI/UX changes | Hero component style, footer links |
| **MEDIUM** | 60 days | User-facing features | Product filters, cart recommendations |
| **HIGH** | 30 days | Business-critical flows | Checkout process, payment methods |
| **CRITICAL** | 14 days | Emergency kill switches | Payment processing, media uploads, admin impersonation |

**Emergency Kill Switches** (mandated by Blueprint Foundation Section 3):

| Flag Key | Owner | Rollback Impact |
|----------|-------|-----------------|
| `payments.stripe.enabled` | payments@villagecompute.com | Orders fail gracefully with "Payment system unavailable" |
| `checkout.order-creation.enabled` | platform-team | Checkout button disabled with maintenance notice |
| `media.uploads.enabled` | media-team | Upload UI hidden, existing media still served |
| `media.processing.enabled` | media-team | Uploads queued, no derivatives created until re-enabled |
| `admin.impersonation.enabled` | security-team | Impersonation UI hidden, audit log continues |

**Kill Switch Requirements:**
- Risk level: CRITICAL
- Detailed rollback instructions documented
- Automated monitoring (error rate thresholds trigger alerts)
- 24/7 admin access for emergency disable
- On-call escalation if disabled unexpectedly

### 5. Rollout Strategy

**Gradual Enablement Process:**

1. **Phase 1: Test Tenant** (Day 0)
   - Create global flag: `enabled=false`
   - Create tenant override for test tenant: `enabled=true`
   - Monitor metrics (error rates, performance, adoption)

2. **Phase 2: Pilot Cohort** (Day 3-7)
   - Enable for 10 pilot tenants (preferably friendly customers)
   - Monitor business metrics (conversions, revenue impact)
   - Gather user feedback

3. **Phase 3: Progressive Rollout** (Day 14-30)
   - 5% of tenants → 25% → 50% → 100%
   - Use tenant attributes for segmentation (e.g., by plan tier, geography)
   - Pause rollout if error rate exceeds threshold

4. **Phase 4: Global Enable** (Day 30+)
   - Update global flag: `enabled=true`
   - Remove tenant overrides (all tenants inherit global default)

5. **Phase 5: Flag Retirement** (Day 90-180)
   - Remove flag checks from code (feature now permanent)
   - Delete flag from database
   - Update documentation

### 6. Governance & Lifecycle Management

**Required Metadata for Every Flag:**
- `owner`: Email or team alias (must be current employee/team)
- `risk_level`: LOW | MEDIUM | HIGH | CRITICAL
- `expiry_date`: When to remove flag from codebase (max 12 months)
- `rollout_plan`: Timeline and tenant cohort strategy
- `description`: What this flag controls and why
- `rollback_instructions`: How to safely disable (mandatory for HIGH/CRITICAL)

**Automated Governance Checks:**
- Dashboard alerts when flags exceed `expiry_date`
- Weekly email reminders to owners for flags approaching expiry
- Prometheus metric: `feature_flags.expired.count` (alert if >0)
- CI pipeline blocks PRs adding flags without required metadata

**Review Cadence:**
- CRITICAL: 14 days (bi-weekly review)
- HIGH: 30 days (monthly review)
- MEDIUM: 60 days (bi-monthly review)
- LOW: 90 days (quarterly review)

**Flag Retirement Process:**
1. Owner confirms feature is stable and flag no longer needed
2. Create PR removing flag checks from code
3. Deploy code change
4. Monitor for 7 days (no rollback requests)
5. Delete flag from database: `DELETE FROM feature_flags WHERE flag_key = :key`

---

## Rationale

### Why Database-Backed Flags (vs. External Service like LaunchDarkly)?

**Rejected Alternative: LaunchDarkly / Split.io / Unleash**
- **Cost**: $50-500/month for SaaS services vs. $0 for database-backed solution
- **Vendor Lock-In**: Proprietary SDKs, difficult migration path
- **Latency**: Network round-trip to external service adds 50-100ms
- **Data Privacy**: Feature flag evaluation data sent to third-party (potential compliance issue)
- **Dependency Risk**: External service outage disables feature flag resolution

**Chosen Solution Benefits:**
- ✅ **Zero Marginal Cost**: PostgreSQL already deployed, no additional infrastructure
- ✅ **Low Latency**: In-memory cache (<1ms) vs. network call (50-100ms)
- ✅ **Data Sovereignty**: All data remains within infrastructure
- ✅ **Transactional Consistency**: Flag updates atomic with related database changes
- ✅ **Portability**: No vendor lock-in, simple SQL schema

### Why Database (vs. Environment Variables or Config Files)?

**Rejected Alternative: Environment Variables**
- Cannot change flags without pod restart (5-10 minute rollout delay)
- No per-tenant overrides (all tenants inherit same value)
- No audit trail (who changed what, when?)
- No rollback capability (requires redeployment)

**Rejected Alternative: Config Files (YAML/JSON)**
- Requires code deployment to change flags (defeats purpose)
- No dynamic tenant-specific overrides
- No admin UI for non-developers to manage flags

**Chosen Solution Benefits:**
- ✅ **Instant Propagation**: Flag changes propagate within seconds (cache TTL: 10 minutes)
- ✅ **Per-Tenant Overrides**: Supports gradual rollout per tenant
- ✅ **Audit Trail**: All changes logged with timestamp and user
- ✅ **Admin UI**: Non-developers can manage flags without code changes

### Why Caffeine Caching (vs. No Cache or Redis Cache)?

**Rejected Alternative: No Cache (Database Every Time)**
- Every HTTP request would query database (adds 5ms latency)
- Database hotspot on `feature_flags` table (hundreds of queries/second)
- Scales poorly (database becomes bottleneck)

**Rejected Alternative: Redis Cache**
- Violates "No Redis" platform constraint (see ADR-006)
- Additional infrastructure to manage
- Network round-trip to Redis adds 1-2ms latency

**Chosen Solution Benefits:**
- ✅ **Low Latency**: In-memory cache (~0.1ms) avoids database on every request
- ✅ **No Additional Infrastructure**: Caffeine is in-process (no separate service)
- ✅ **Automatic Eviction**: LRU eviction with max size prevents memory exhaustion
- ✅ **Cache Invalidation**: Manual invalidation API for immediate propagation

### Why Hierarchical Naming (`area.feature.variant`) (vs. Flat Keys)?

**Rejected Alternative: Flat Keys (`applePayEnabled`, `betaHero`)**
- Cannot group related flags (e.g., all `media.*` flags)
- Difficult to search/filter in admin UI
- No namespace isolation (risk of key collisions)

**Chosen Solution Benefits:**
- ✅ **Grouping**: Bulk operations on related flags (disable all `media.*`)
- ✅ **Searchability**: Filter flags by area in admin UI
- ✅ **Self-Documenting**: Key describes what it controls
- ✅ **Namespace Isolation**: Prevents collisions across teams

---

## Consequences

### Positive Consequences

1. **Safe Deployments**: Features can be deployed disabled, tested in production, then enabled gradually
2. **Emergency Response**: Kill switches enable instant feature disable without code deployment (P1: <1 minute)
3. **Rollout Control**: Gradual enablement detects issues before affecting all tenants
4. **Cost Efficiency**: Zero marginal cost vs. $50-500/month for external services
5. **Multi-Tenancy Support**: Per-tenant overrides enable customization and gradual rollouts
6. **Audit Compliance**: All flag changes logged with timestamp and user for regulatory compliance
7. **Flag Debt Prevention**: Expiry dates and automated alerts prevent accumulation of unused flags

### Negative Consequences & Mitigations

1. **Cache Invalidation Delay**
   - **Issue**: Flag changes take up to 10 minutes to propagate (cache TTL)
   - **Mitigation**:
     - Admin UI calls `FeatureToggle.invalidateAll()` after flag updates (immediate propagation)
     - Kill switches invalidate cache synchronously (P1 emergency response)
     - Prometheus metric tracks cache invalidation latency

2. **Manual Governance Overhead**
   - **Issue**: Requires discipline to review flags every 14-90 days
   - **Mitigation**:
     - Automated email reminders to flag owners
     - Dashboard shows flags approaching expiry
     - CI pipeline blocks PRs adding flags without metadata
     - Quarterly review meetings for flag cleanup

3. **Database Query Overhead on Cache Miss**
   - **Issue**: Cache miss adds 5ms database query latency
   - **Mitigation**:
     - Dedicated index: `idx_feature_flags_lookup(tenant_id, flag_key)`
     - Query plan uses index-only scan (no table access)
     - Cache hit rate >95% in production (measured via Prometheus)

4. **No Native A/B Testing**
   - **Issue**: Database flags don't include built-in A/B test analytics (unlike LaunchDarkly)
   - **Mitigation**:
     - Application emits custom metrics per flag variant
     - Grafana dashboards visualize adoption and conversion rates
     - For complex experiments, use dedicated A/B testing library (e.g., Google Optimize)

5. **Potential Flag Proliferation**
   - **Issue**: Easy to create flags without cleanup plan
   - **Mitigation**:
     - Mandatory `expiry_date` field (max 12 months)
     - Automated alerts for expired flags
     - Monthly flag review meetings
     - CI blocks PRs adding flags without justification

6. **No Multi-Variant Support**
   - **Issue**: Database flags are boolean only (no percentage rollouts like "50% see variant A, 50% see variant B")
   - **Mitigation**:
     - For percentage-based rollouts, use multiple boolean flags: `feature.variantA`, `feature.variantB`
     - Application logic implements percentage logic via deterministic hash (tenant_id % 100 < threshold)

---

## Implementation References

### Code Locations
- **FeatureToggle Service**: `villagecompute.storefront.services.FeatureToggle`
- **Tenant Context**: `villagecompute.storefront.tenant.TenantContext`
- **Cache Configuration**: `application.properties` (quarkus.cache.caffeine.feature-flag-cache.*)

### Documentation
- [Feature Flag Governance](../feature_flags/governance.md) - Detailed lifecycle management procedures
- [Feature Flag Admin UI](../feature_flags/admin-ui.md) - Admin UI user guide
- [Hypercare Plan](../architecture/ops/hypercare-plan.md) - Feature flag rollout strategy during launch

### Database Migration
- **Schema**: `migrations/V2.0__baseline_schema.sql` (feature_flags table)

### Admin Tools
- **CLI Tool**: `tools/featureflag-cli/featureflag.cjs` (command-line flag management)
- **Admin UI**: `/admin/feature-flags` (web-based flag browser and editor)

### Monitoring
- **Grafana Dashboard**: "Village Storefront > Feature Flags Lifecycle"
- **Key Metrics**:
  - `feature_flags.total{risk_level}` - Total flags by risk level
  - `feature_flags.expired.count` - Flags past expiry date (alert if >0)
  - `feature_flags.cache.hit_rate` - Cache hit percentage (target >95%)
  - `feature_flags.resolution_duration_ms` - Flag lookup latency (p95 <1ms)
- **Alerts**:
  - `FeatureFlagExpired` - Flags past expiry date (P3, weekly reminder)
  - `FeatureFlagCacheHitRateLow` - Cache hit rate <80% for 10 minutes (P2)
  - `KillSwitchDisabled` - CRITICAL flag unexpectedly disabled (P1, on-call escalation)

---

## Revision History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| 2026-01-18 | 1.0 | Initial ADR documenting implemented architecture | Architecture Team |

---

## References

- [ADR-001: Multi-Tenancy & Tenant Isolation Strategy](ADR-001-tenancy.md)
- [Blueprint Foundation: Section 3 Rulebook (Kill Switches)](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-3-rulebook)
- [Blueprint Foundation: Section 4.1.12 (Feature Flag Discipline)](../../.codemachine/artifacts/architecture/01_Blueprint_Foundation.md#section-4-deep-dives)
- [Feature Flags Best Practices (Martin Fowler)](https://martinfowler.com/articles/feature-toggles.html)
- [Caffeine Cache Documentation](https://github.com/ben-manes/caffeine)
