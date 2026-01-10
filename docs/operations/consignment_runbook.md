# Consignment & Payout Runbook

**Component:** Consignment Vendor Management & Automated Payouts
**Owner:** Platform Engineering
**Last Updated:** 2026-01-10
**Related Docs:** [Architecture §3.20.2](../../.codemachine/artifacts/architecture/04_Operational_Architecture.md), [ADR-004 Consignment Payouts](../../.codemachine/artifacts/adr/ADR-004-consignment-payouts.md)

---

## Overview

The Consignment module manages vendor relationships, sales tracking, double-entry ledger accounting, and automated payout batch processing via Stripe Connect. This runbook provides operational guidance for payout reconciliation, ledger integrity, and settlement troubleshooting.

### Component KPIs

| KPI | Target | Alert Threshold | Criticality |
|-----|--------|----------------|-------------|
| Payout Reconciliation Delay | <24 hours | >24h for 1h | Warning (Sev2) |
| Payout Batch Processing (p95) | <5 minutes | >5min for 15min | Warning |
| Payout Failure Rate | <5% | >5% for 10min | Warning |
| Ledger Discrepancy | $0 | >$0 for 30min | Critical |
| Settlement Duration (p95) | <10 seconds | >10s for 10min | Warning |

### Service Dependencies

- **PostgreSQL**: Double-entry ledger, payout records, vendor accounts
- **Stripe Connect**: Payout API for vendor disbursements
- **Background Job Scheduler**: Automated daily payout batch creation
- **Payment Module**: Sale transaction integration

---

## Alerts

### Alert: ConsignmentPayoutReconciliationDelayed (SEV2)

**Symptom:** Payout reconciliation job delayed beyond daily window

**Severity:** Warning (Sev2 per Architecture §3.20.2)
**Component:** Consignment

#### Investigation Steps

1. **Check Last Reconciliation:**
   ```promql
   time() - consignment_payout_reconciliation_last_success_timestamp
   ```

2. **Check Job Status:**
   ```bash
   kubectl logs -l app=storefront-worker --tail=100 | grep "consignment.payout.reconciliation"
   ```

3. **Review Ledger State:**
   ```sql
   SELECT COUNT(*) FROM consignment_payout_ledger
   WHERE status = 'pending_reconciliation'
     AND created_at < NOW() - INTERVAL '24 hours';
   ```

#### Resolution

1. **Manually Trigger Reconciliation:**
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/consignment/reconcile-payouts \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"tenant_id": "YOUR_TENANT_UUID", "force": true}'
   ```

2. **If Job Stuck, Restart Workers:**
   ```bash
   kubectl rollout restart deployment/storefront-worker
   ```

---

### Alert: ConsignmentLedgerDiscrepancy (CRITICAL)

**Symptom:** Ledger balance discrepancy detected between pending and available balances

**Severity:** Critical
**Component:** Consignment

#### Investigation Steps

1. **Identify Discrepancy:**
   ```promql
   consignment_ledger_discrepancy{vendor_id="VENDOR_UUID"}
   ```

2. **Audit Ledger Entries:**
   ```sql
   SELECT * FROM consignment_payout_ledger
   WHERE vendor_id = 'VENDOR_UUID'
     AND tenant_id = 'TENANT_UUID'
   ORDER BY created_at DESC LIMIT 50;
   ```

3. **Compare Stripe Payout Records:**
   ```bash
   stripe payouts list --destination=acct_VENDOR_STRIPE_ID --limit=20
   ```

#### Resolution

1. **Run Ledger Reconciliation:**
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/consignment/vendors/{vendorId}/reconcile-ledger \
     -H "Authorization: Bearer $TOKEN"
   ```

2. **Manual Ledger Adjustment (if authorized by finance):**
   ```sql
   INSERT INTO consignment_payout_ledger (vendor_id, tenant_id, amount_cents, transaction_type, notes)
   VALUES ('VENDOR_UUID', 'TENANT_UUID', 1000, 'adjustment', 'Reconciliation adjustment approved by finance team - ticket #12345');
   ```

3. **Escalate to Finance Team:**
   - Discrepancies >$100 require finance approval before adjustment
   - Contact: finance@villagecompute.com

---

### Alert: ConsignmentPayoutBatchProcessingSlow

**Symptom:** Payout batch processing p95 exceeds 5-minute target

**Severity:** Warning
**Component:** Consignment

#### Investigation Steps

1. **Check Batch Size:**
   ```promql
   consignment_payout_batch_vendors{tenant_id="YOUR_TENANT"}
   ```

2. **Check Stripe API Latency:**
   ```bash
   curl https://status.stripe.com/api/v2/status.json
   ```

3. **Review Individual Payout Failures:**
   ```sql
   SELECT vendor_id, failure_reason, COUNT(*) AS failure_count
   FROM consignment_payouts
   WHERE status = 'failed'
     AND created_at > NOW() - INTERVAL '24 hours'
   GROUP BY vendor_id, failure_reason
   ORDER BY failure_count DESC;
   ```

#### Resolution

1. **Reduce Batch Size:**
   ```properties
   consignment.payout.batch-size=50
   consignment.payout.parallel-workers=3
   ```

2. **Retry Failed Payouts:**
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/consignment/payouts/retry-batch \
     -H "Authorization: Bearer $TOKEN" \
     -d '{"batch_id": "BATCH_UUID"}'
   ```

3. **Enable Parallel Processing:**
   ```properties
   consignment.payout.parallel-enabled=true
   consignment.payout.max-parallelism=5
   ```

---

## Common Issues

### Issue: Vendor Not Receiving Payouts

**Diagnosis:**
1. Check vendor Stripe Connect status:
   ```sql
   SELECT stripe_account_id, onboarding_status, payout_enabled
   FROM consignment_vendors
   WHERE id = 'VENDOR_UUID';
   ```

2. Verify pending balance:
   ```sql
   SELECT SUM(amount_cents) AS pending_balance
   FROM consignment_payout_ledger
   WHERE vendor_id = 'VENDOR_UUID'
     AND status = 'pending';
   ```

**Resolution:**
- If onboarding incomplete, send new onboarding link
- If balance below minimum threshold ($25), explain accumulation policy
- If Stripe Connect account suspended, contact Stripe support

---

### Issue: Settlement Delay (Pending → Available)

**Diagnosis:**
1. Check settlement configuration:
   ```sql
   SELECT * FROM consignment_settlement_config
   WHERE tenant_id = 'TENANT_UUID';
   ```

2. Review settlement job logs:
   ```bash
   kubectl logs -l app=storefront-worker --tail=100 | grep "consignment.settlement"
   ```

**Resolution:**
1. Manually trigger settlement:
   ```bash
   curl -X POST https://admin.villagecompute.com/api/admin/consignment/vendors/{vendorId}/settle \
     -H "Authorization: Bearer $TOKEN"
   ```

2. Adjust settlement delay:
   ```sql
   UPDATE consignment_settlement_config
   SET settlement_delay_days = 3
   WHERE tenant_id = 'TENANT_UUID';
   ```

---

## Metrics Reference

| Metric | Query |
|--------|-------|
| Pending Payout Liability | `consignment_payout_pending_total` |
| Available Payout Balance | `consignment_payout_available_total` |
| Payout Success Rate | `sum(rate(consignment_payout_success[5m])) / sum(rate(consignment_payout_created[5m]))` |
| Settlement Duration (p95) | `histogram_quantile(0.95, consignment_ledger_settlement_duration_bucket)` |
| Reconciliation Duration | `consignment_payout_reconciliation_duration` |

### Dashboard Links

- [Consignment KPIs Dashboard](https://grafana.villagecompute.com/d/component-kpis?var-component=consignment)
- [Jaeger Traces - Consignment](https://jaeger.villagecompute.com/search?service=storefront&tags=%7B%22component%22%3A%22consignment%22%7D)

---

## Feature Flags

| Flag | Purpose | Default |
|------|---------|---------|
| `consignment.payout.enabled` | Disable automated payouts | `true` |
| `consignment.payout.batch-enabled` | Disable batch processing | `true` |
| `consignment.payout.parallel-enabled` | Enable parallel payout creation | `false` |
| `consignment.settlement.enabled` | Disable settlement job | `true` |

---

## Escalation

### When to Escalate

1. **Critical**: Ledger discrepancies >$1000
2. **High**: Payout failures affecting >25% of vendors
3. **Medium**: Reconciliation delayed >48 hours
4. **Low**: Individual vendor payout issues

### Contacts

- **On-Call Engineer**: PagerDuty rotation
- **Finance Team**: finance@villagecompute.com (for ledger issues)
- **Stripe Support**: support@stripe.com
