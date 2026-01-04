# Feature Flag Governance

This document defines the governance process, lifecycle, and best practices for feature flags in Village Storefront to prevent "flag debt" and ensure safe, controlled rollouts.

## Table of Contents

1. [Overview](#overview)
2. [Governance Model](#governance-model)
3. [Flag Lifecycle](#flag-lifecycle)
4. [Risk Levels & Kill Switches](#risk-levels--kill-switches)
5. [Review Process](#review-process)
6. [Rollback Procedures](#rollback-procedures)
7. [Tools & Automation](#tools--automation)
8. [Best Practices](#best-practices)

---

## Overview

### Purpose

Feature flags enable:

- **Dark launches**: Deploy code without exposing it to users
- **Gradual rollouts**: Enable features for subset of tenants
- **Emergency kill switches**: Instantly disable problematic features
- **A/B testing**: Experiment with different implementations

### Governance Goals

1. **Prevent flag debt**: Remove flags when no longer needed
2. **Maintain accountability**: Every flag has an owner
3. **Ensure safety**: Critical flags have rollback instructions
4. **Track health**: Metrics and dashboards monitor flag lifecycle

### References

- **Architecture**: Blueprint Foundation Section 3 (Feature Flag Strategy)
- **Rationale**: Section 4.1.12 (Feature Flag Discipline)
- **UI Design**: Section 4.9 (Feature Flag Exposure)
- **Task**: I5.T7 (Feature flag governance + release process)

---

## Governance Model

### Required Metadata

Every feature flag MUST include:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `flagKey` | String | Yes | Unique identifier (e.g., `storefront.hero.beta`) |
| `enabled` | Boolean | Yes | Current state (true/false) |
| `owner` | Email/Team | Yes | Who owns this flag's lifecycle |
| `riskLevel` | Enum | Yes | LOW, MEDIUM, HIGH, CRITICAL |
| `reviewCadenceDays` | Integer | No | How often to review (default: 90 days) |
| `expiryDate` | Timestamp | No | When to remove from codebase |
| `description` | Text | No | What this flag controls and why |
| `rollbackInstructions` | Text | Recommended | How to safely disable |

### Ownership Model

**Owner Responsibilities:**

- Monitor flag usage and performance
- Respond to incidents related to the flag
- Review flag every `reviewCadenceDays`
- Remove flag when no longer needed
- Document rollback steps for HIGH/CRITICAL flags

**Acceptable Owners:**

- Individual email: `alice@example.com`
- Team alias: `platform-team`
- Shared inbox: `payments@example.com`

**Unacceptable:**

- Generic emails: `admin@example.com`
- Departed employees
- Null/empty values

---

## Flag Lifecycle

### Phase 1: Creation

**When creating a flag:**

1. Choose meaningful `flagKey` following convention:
   - Format: `{area}.{feature}.{variant}`
   - Examples: `checkout.apple-pay`, `storefront.hero.beta`

2. Set initial metadata:
   ```json
   {
     "flagKey": "checkout.apple-pay",
     "enabled": false,
     "owner": "payments@example.com",
     "riskLevel": "HIGH",
     "reviewCadenceDays": 30,
     "expiryDate": "2026-12-31T23:59:59Z",
     "description": "Enable Apple Pay integration at checkout",
     "rollbackInstructions": "Set enabled=false. Fallback to card/PayPal only."
   }
   ```

3. Add database record:
   ```sql
   INSERT INTO feature_flags (
     flag_key, enabled, owner, risk_level,
     review_cadence_days, expiry_date, description,
     rollback_instructions, created_at, updated_at
   ) VALUES (
     'checkout.apple-pay', false, 'payments@example.com', 'HIGH',
     30, '2026-12-31T23:59:59Z',
     'Enable Apple Pay integration at checkout',
     'Set enabled=false. Fallback to card/PayPal only.',
     NOW(), NOW()
   );
   ```

4. Implement feature gated by flag:
   ```java
   @Inject
   FeatureToggle featureToggle;

   public Response processPayment(PaymentRequest request) {
       if (featureToggle.isEnabled("checkout.apple-pay") &&
           request.method.equals("apple_pay")) {
           return applePayProvider.charge(request);
       }
       return fallbackPaymentFlow(request);
   }
   ```

### Phase 2: Rollout

**Gradual enablement:**

1. **Test tenant first:**
   ```bash
   # Create tenant-specific override
   INSERT INTO feature_flags (tenant_id, flag_key, enabled, ...)
   VALUES ('test-tenant-uuid', 'checkout.apple-pay', true, ...);
   ```

2. **Monitor metrics:**
   - Error rates
   - Performance impact
   - User adoption
   - Revenue changes (if applicable)

3. **Expand gradually:**
   - 5% of tenants → 25% → 50% → 100%
   - Use tenant attributes for segmentation

4. **Global enable:**
   ```bash
   node tools/featureflag-cli/featureflag.cjs set <flag-id> \
     --enabled=true \
     --reason="Rollout complete - performance validated"
   ```

### Phase 3: Stabilization

**Once feature is stable:**

1. **Remove flag from code:**
   ```java
   // Before
   if (featureToggle.isEnabled("checkout.apple-pay")) {
       return applePayProvider.charge(request);
   }

   // After (flag removed)
   return applePayProvider.charge(request);
   ```

2. **Delete flag from database:**
   ```bash
   node tools/featureflag-cli/featureflag.cjs set <flag-id> \
     --enabled=false \
     --reason="Code cleanup - feature now permanent"

   # After verifying no issues
   DELETE FROM feature_flags WHERE flag_key = 'checkout.apple-pay';
   ```

3. **Update documentation:**
   - Remove flag from README
   - Update API docs if behavior changed
   - Archive rollback plan

### Phase 4: Retirement

**Maximum flag lifetime: 12 months**

Flags exceeding `expiryDate` trigger:

- Dashboard alerts
- Weekly Slack/email reminders
- Automated PR comments
- Blocking PR merges (configurable)

**Forced cleanup process:**

1. Owner reviews flag status
2. If still needed: extend expiry + justification
3. If no longer needed: remove code + database entry
4. If owner unresponsive: escalate to tech lead

---

## Risk Levels & Kill Switches

### Risk Level Definitions

| Level | Description | Examples | Review Cadence |
|-------|-------------|----------|----------------|
| **LOW** | Non-critical UI/UX changes | Hero component style, footer links | 90 days |
| **MEDIUM** | User-facing features | Product filters, cart recommendations | 60 days |
| **HIGH** | Business-critical flows | Checkout process, payment methods | 30 days |
| **CRITICAL** | Emergency kill switches | Payment processing, media uploads, impersonation | 14 days |

### Emergency Kill Switches

**Section 3 Rulebook mandates kill switches for:**

1. **Payment flows**: All payment provider integrations
2. **Checkout**: Cart → Order conversion
3. **Media processing**: Upload, resize, derivative generation
4. **Impersonation**: Platform admin → tenant user

**Kill switch requirements:**

- `riskLevel = CRITICAL`
- Detailed `rollbackInstructions`
- Automated monitoring (error rate thresholds)
- On-call escalation if disabled
- 24/7 access for platform admins

**Emergency disable procedure:**

```bash
# Immediate disable
export API_TOKEN=<platform-admin-token>
node tools/featureflag-cli/featureflag.cjs set <flag-id> \
  --enabled=false \
  --reason="INCIDENT #789: Payment failures spiking"

# Verify cache invalidated (should take <5 seconds)
curl https://api.example.com/q/health/feature-flags
```

---

## Review Process

### Scheduled Reviews

**Owner obligations:**

- Review flag every `reviewCadenceDays`
- Determine if flag still needed
- Update metadata or remove flag
- Document decision in audit trail

**Review checklist:**

- [ ] Is feature fully rolled out?
- [ ] Is flag still referenced in code?
- [ ] Are there any tenant overrides?
- [ ] Has behavior changed since creation?
- [ ] Is owner still correct?
- [ ] Are rollback instructions current?

**Mark as reviewed:**

```bash
node tools/featureflag-cli/featureflag.cjs review <flag-id> \
  --reason="Q1 2026 review: Flag still needed for A/B test through March"
```

### Automated Detection

**Stale flag detection job runs daily:**

- Identifies flags past `expiryDate`
- Finds flags overdue for review
- Updates Prometheus metrics
- Sends Slack notifications (if configured)

**View stale flags:**

```bash
# CLI
node tools/featureflag-cli/featureflag.cjs stale-report

# Dashboard
Open Grafana → Village Storefront - Feature Flag Governance
```

---

## Rollback Procedures

### Rollback Scenarios

1. **Feature causing errors**: Disable flag immediately
2. **Performance degradation**: Disable flag, analyze metrics
3. **User complaints**: Gradual rollback (100% → 50% → 0%)
4. **Security vulnerability**: Emergency disable + incident response

### Rollback Steps

**Standard rollback:**

1. **Disable flag:**
   ```bash
   node tools/featureflag-cli/featureflag.cjs set <flag-id> \
     --enabled=false \
     --reason="Rollback: {incident description}"
   ```

2. **Verify impact:**
   - Check error rate decreased
   - Monitor user reports
   - Review Sentry/logs

3. **Communicate:**
   - Post in #incidents Slack channel
   - Update status page (if customer-facing)
   - Notify stakeholders

4. **Post-mortem:**
   - Document root cause
   - Update rollback instructions
   - Add monitoring/alerts
   - Plan fix + re-rollout

**Tenant-specific rollback:**

```bash
# Disable for specific tenant
UPDATE feature_flags
SET enabled = false, updated_at = NOW()
WHERE tenant_id = '<tenant-uuid>' AND flag_key = '<flag-key>';

# Invalidate cache
curl -X POST https://api.example.com/api/v1/platform/feature-flags/<flag-id>/invalidate
```

### Rollback Instructions Template

```markdown
## Rollback: {Feature Name}

**Flag Key:** `{flag-key}`
**Risk Level:** {LOW|MEDIUM|HIGH|CRITICAL}

### Quick Disable
```bash
node tools/featureflag-cli/featureflag.cjs set {flag-id} --enabled=false --reason="Rollback"
```

### Validation Steps
1. Check error rate: `{Grafana dashboard link}`
2. Verify fallback behavior: `{test steps}`
3. Monitor for 15 minutes

### Fallback Behavior
- Users see: {description of old behavior}
- API returns: {fallback response format}
- Database changes: {none | reverted | manual cleanup needed}

### Known Issues
- {List any known edge cases or cleanup tasks}

### Re-Enable Criteria
- [ ] Root cause identified and fixed
- [ ] Tests added to prevent regression
- [ ] Monitoring/alerts configured
- [ ] Stakeholder approval
```

---

## Tools & Automation

### CLI Tool

**Installation:**

```bash
export API_TOKEN=<your-platform-admin-token>
export API_BASE_URL=https://api.example.com
node tools/featureflag-cli/featureflag.cjs --help
```

**Common operations:**

```bash
# List all flags
node tools/featureflag-cli/featureflag.cjs list

# Check stale flags
node tools/featureflag-cli/featureflag.cjs list --stale

# Get flag details
node tools/featureflag-cli/featureflag.cjs get <flag-id>

# Update flag
node tools/featureflag-cli/featureflag.cjs set <flag-id> \
  --enabled=true \
  --owner=alice@example.com \
  --reason="Ownership transfer"

# Review flag
node tools/featureflag-cli/featureflag.cjs review <flag-id> \
  --reason="Q1 review completed"

# View history
node tools/featureflag-cli/featureflag.cjs history <flag-id>
```

See [`tools/featureflag-cli/README.md`](../../tools/featureflag-cli/README.md) for full documentation.

### Grafana Dashboard

**Access:** https://grafana.example.com/d/feature-flags

**Panels:**

- Flag adoption overview
- Enabled vs disabled count
- Expired flags (with alerts)
- Review backlog
- Risk level distribution
- Governance health score

**Alerts:**

- Expired flags > 0 → Slack #platform-ops
- Review backlog > 5 → Email to owners
- CRITICAL flags disabled → PagerDuty (ops on-call)

### GitHub Automation

**PR Template Checklist:**

```markdown
## Feature Flag Checklist

- [ ] Flag created with required metadata (owner, risk level, expiry)
- [ ] Rollback instructions documented for HIGH/CRITICAL flags
- [ ] Feature works when flag is disabled (graceful degradation)
- [ ] Tests cover both enabled and disabled states
- [ ] Monitoring/alerts configured
- [ ] Expiry date set (max 12 months)
```

**Stale flag GitHub Action:**

```yaml
# .github/workflows/check-stale-flags.yml
name: Check Stale Flags
on:
  schedule:
    - cron: '0 9 * * MON'  # Every Monday at 9 AM
  workflow_dispatch:

jobs:
  check-stale:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
        with:
          node-version: '18'
      - name: Check for stale flags
        env:
          API_TOKEN: ${{ secrets.PLATFORM_ADMIN_TOKEN }}
          API_BASE_URL: ${{ secrets.API_BASE_URL }}
        run: |
          REPORT=$(node tools/featureflag-cli/featureflag.cjs stale-report)
          echo "$REPORT"

          EXPIRED=$(echo "$REPORT" | grep "Expired Flags:" | awk '{print $3}')
          if [ "$EXPIRED" -gt 0 ]; then
            echo "::error::Found $EXPIRED expired feature flags"
            exit 1
          fi
```

---

## Best Practices

### DO

✅ **Set expiry dates**: Default to 6 months, extend if needed
✅ **Document rollback steps**: Especially for HIGH/CRITICAL flags
✅ **Test both states**: Feature works when enabled AND disabled
✅ **Use descriptive keys**: `checkout.apple-pay` not `flag123`
✅ **Remove old flags**: Delete from code AND database
✅ **Monitor metrics**: Track adoption, errors, performance
✅ **Gradual rollouts**: Start with test tenant, expand slowly
✅ **Assign clear owners**: Individuals or teams, not generic emails

### DON'T

❌ **Don't nest flags**: `if (flagA && flagB)` creates complexity
❌ **Don't use flags for config**: Use `application.properties` instead
❌ **Don't let flags live forever**: 12 months maximum
❌ **Don't skip rollback docs**: Critical flags MUST have instructions
❌ **Don't forget cache invalidation**: Always call `FeatureToggle.invalidate()`
❌ **Don't hardcode tenant IDs**: Use tenant attributes for segmentation
❌ **Don't disable monitoring**: Flags need observability

### Code Patterns

**Good:**

```java
// Clear, single responsibility
if (featureToggle.isEnabled("checkout.apple-pay")) {
    return applePayProvider.charge(request);
}
return stripeProvider.charge(request);
```

**Bad:**

```java
// Nested flags (hard to reason about)
if (featureToggle.isEnabled("checkout.new-flow")) {
    if (featureToggle.isEnabled("checkout.apple-pay")) {
        // 4 possible states - very complex
    }
}
```

**Good:**

```java
// Graceful degradation
if (featureToggle.isEnabled("cart.recommendations")) {
    items.addAll(recommendationService.getSuggestions(cart));
}
// Cart still works without recommendations
```

**Bad:**

```java
// Throws exception when flag disabled
if (!featureToggle.isEnabled("cart.recommendations")) {
    throw new IllegalStateException("Recommendations required");
}
```

---

## Appendix

### Quick Reference Card

| Task | Command |
|------|---------|
| List stale flags | `node featureflag.cjs list --stale` |
| Emergency disable | `node featureflag.cjs set <id> --enabled=false --reason="INCIDENT #..."` |
| Mark reviewed | `node featureflag.cjs review <id> --reason="Q1 review"` |
| View history | `node featureflag.cjs history <id>` |
| Check health | Open Grafana → Feature Flag Governance dashboard |

### Related Documentation

- [Architecture Overview](../architecture_overview.md)
- [Operations: Observability](../operations/observability.md)
- [CLI Tool README](../../tools/featureflag-cli/README.md)
- [Platform Admin Console](../../src/main/webui/admin-spa/README.md)

### Support

- **Slack**: `#platform-ops`
- **Email**: `platform-team@example.com`
- **On-call**: PagerDuty rotation for CRITICAL flag incidents
