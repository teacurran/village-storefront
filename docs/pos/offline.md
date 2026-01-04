# POS Offline Mode Operations Guide

**Version:** 1.0
**Last Updated:** 2026-01-10
**Audience:** Store Staff, System Administrators, Support Engineers

---

## Overview

The POS offline mode enables continued sales operations when internet connectivity is lost. Transactions are encrypted and queued locally, then automatically synchronized when connectivity is restored.

**Key Features:**
- Automatic offline detection and queue management
- AES-256-GCM encryption for sensitive payment data
- Idempotent sync prevents duplicate charges
- Visual indicators for offline state and sync progress
- Comprehensive audit trail

**References:**
- Architecture: `docs/architecture/04_Operational_Architecture.md` §3.19.10
- Technical Implementation: Task I4.T7

---

## Device Pairing Workflow

### Initial Setup

1. **Initiate Pairing (Admin Dashboard)**
   - Navigate to **POS → Devices**
   - Click **Add New Device**
   - Enter device details:
     - Device Name (e.g., "Front Counter Register")
     - Location (e.g., "Main Store Floor")
     - Hardware Model (e.g., "iPad Pro 12.9")
   - Click **Generate Pairing Code**
   - An 8-character code will be displayed (e.g., `ABCD1234`)
   - Code expires in 15 minutes

2. **Complete Pairing (POS Terminal)**
   - Open POS application on device
   - Navigate to **Settings → Device Pairing**
   - Enter the 8-character pairing code
   - Click **Pair Device**
   - Device receives the AES key plus a Stripe Terminal connection token (expires in 24h)
   - Token can be refreshed later from **POS → Devices → Refresh Terminal Token**
   - Device will receive encryption key and activate

3. **Verification**
   - Device status changes to **Active** in admin dashboard
   - POS terminal displays "Connected and Synced" status
   - Encryption key stored securely in browser IndexedDB

### Pairing Troubleshooting

| Issue | Solution |
|-------|----------|
| Pairing code expired | Generate new code from admin dashboard |
| Invalid code error | Verify code entry (no spaces, case-insensitive) |
| Encryption key storage failed | Check browser IndexedDB quota (Settings → Storage) |
| Device not appearing in list | Ensure tenant context is correct, refresh page |

---

## Offline Operations

### When Offline Mode Activates

Offline mode automatically activates when:
- Network connection is lost
- Server unreachable for 10+ seconds
- Service worker detects offline state

**Visual Indicators:**
- Header badge changes to **orange** with "Offline" label
- Pulsing animation on offline indicator
- Queue count badge shows pending transactions
- Toast notification: "Network offline - transactions will be queued"

### Creating Offline Transactions

1. **Process Sale Normally**
   - Scan items or manually add products
   - Apply discounts, gift cards, loyalty points
   - Select payment method (card on file, cash, etc.)
   - Click **Complete Sale**

2. **Transaction Queuing**
   - POS encrypts transaction data (cart, payment, customer)
   - Stores encrypted payload in local IndexedDB
   - Generates idempotency key: `{deviceId}:{localTxId}`
   - Displays confirmation: "Sale queued for sync"
   - Receipt printed with "PENDING SYNC" watermark

3. **Queue Visibility**
   - Navigate to **POS → Offline Queue**
   - View list of pending transactions:
     - Local Transaction ID
     - Timestamp
     - Amount
     - Staff initials
     - Sync status (Queued, Syncing, Synced, Failed)

### Offline Limitations

⚠️ **Features NOT Available Offline:**
- Real-time inventory checks (uses last synced levels)
- New customer creation (must use guest checkout)
- Live payment authorization (uses saved payment methods)
- Loyalty tier recalculation (deferred until sync)
- Gift card balance checks (validates against cached data)

✅ **Features Available Offline:**
- Cart management (add, remove, update quantities)
- Discount application (percentage, fixed amount)
- Gift card redemption (if balance cached)
- Store credit redemption (if balance cached)
- Cash transactions
- Saved payment method charging

---

## Sync Operations

### Automatic Sync

Sync automatically triggers when:
- Network connection restored (detected by Service Worker)
- POS application regains focus after offline period
- Manual "Sync Now" button clicked
- Scheduled background sync (every 5 minutes when online)
- Background sync registered by `pos-sw.js` service worker ensures retries even when tab is minimized

### Sync Process

1. **Preparation**
   - Queued transactions marked as "Syncing"
   - Progress bar appears in header indicator
   - UI shows "Syncing X of Y transactions"

2. **Upload Batch**
   - Encrypted payloads sent to `/api/pos/offline/upload`
   - Server validates idempotency keys (prevents duplicates)
   - Transactions enqueued for background processing

3. **Server Processing**
   - Background job decrypts payload
   - Validates tenant context and device authorization
   - Calls PaymentProvider with idempotency key
   - Creates order and captures payment
   - Logs audit record to `pos_offline_transactions`

4. **Completion**
   - Synced transactions marked with green checkmark
   - Header indicator returns to "Online" status
   - Synced entries auto-deleted after 5 minutes
   - Success toast: "X transactions synced successfully"

### Sync Failure Handling

If sync fails:

1. **Transient Errors** (network timeout, server busy)
   - Status: Queued (will retry)
   - Action: Wait for automatic retry (exponential backoff)
   - Display: Yellow warning icon

2. **Data Errors** (invalid payload, constraint violation)
   - Status: Failed
   - Action: Review error message, contact support if needed
   - Display: Red error icon with message

3. **Duplicate Detection**
   - Status: Skipped
   - Action: None (transaction already processed)
   - Display: Gray "Already Synced" badge

**Manual Intervention:**

```bash
# Staff Action: Retry Failed Sync
1. Navigate to POS → Offline Queue
2. Locate failed transaction
3. Click "View Details" → "Retry Sync"
4. If retry fails again, click "Export for Support"
```

```bash
# Support Action: Replay from Dead Letter Queue
# See: docs/operations/job_runbook.md §2.2
kubectl logs -l app=village-storefront-workers | grep "pos.offline_sync"
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     https://api.villagecompute.com/admin/jobs/dlq?queue=pos.offline_sync
```

---

## Queue Management

### Viewing Queue Status

**Dashboard Widget:**
- Queued: Pending upload (orange)
- Syncing: Currently processing (blue)
- Synced: Completed successfully (green)
- Failed: Requires attention (red)

**Detailed Queue View:**
- Navigate to **POS → Offline Queue**
- Filter by status, date range, staff member
- Export queue as JSON for debugging

### Queue Capacity

- **Local Storage Limit:** 50 MB per device (IndexedDB quota)
- **Queue Depth Alert:** Warning at 100 transactions
- **Critical Alert:** Auto-suspend device at 500 transactions

⚠️ **If queue fills up:**
1. Ensure device is online and syncing
2. Check for network connectivity issues
3. Review sync errors and resolve data issues
4. Contact support if queue won't drain

### Exporting Queue (Support Debugging)

1. Navigate to **POS → Offline Queue**
2. Click **Export Queue**
3. Save `offline-queue-[timestamp].json`
4. Send to support with incident ticket

⚠️ **Warning:** Exported file contains encrypted payment data. Handle securely and delete after support resolves issue.

---

## Security & Encryption

### Encryption Details

- **Algorithm:** AES-256-GCM (Galois/Counter Mode)
- **Key Length:** 256 bits
- **IV Length:** 12 bytes (random per transaction)
- **Key Storage:** Browser IndexedDB (never sent to server)
- **Key Hash:** SHA-256 hash stored server-side for verification

### Key Rotation

Keys rotate on device re-pairing:
1. Unpair device in admin dashboard
2. Re-initiate pairing workflow
3. New encryption key generated (version increments)
4. Old queue entries processed with old key version
5. New transactions use new key

### Data Protection

| Data Element | Encryption | Storage |
|--------------|------------|---------|
| Cart items | ✅ Encrypted | IndexedDB |
| Payment method ID | ✅ Encrypted | IndexedDB |
| Customer PII | ✅ Encrypted | IndexedDB |
| Transaction amount | ❌ Plaintext (for metrics) | IndexedDB |
| Idempotency key | ❌ Plaintext (for dedup) | IndexedDB |

### Compliance Notes

- **PCI-DSS:** Encrypted payment data at rest complies with requirement 3.4
- **GDPR:** Customer data encrypted per Art. 32 technical measures
- **Data Retention:** Synced queue entries deleted after 5 minutes
- **Audit Trail:** All sync activity logged to `pos_activity_log`

---

## Monitoring & Observability

### Metrics (Prometheus)

```promql
# Queue depth per device
pos.offline_queue.depth{device_id="123"}

# Sync success rate
rate(pos.offline_sync.sync.success[5m]) / rate(pos.offline_sync.sync.started[5m])

# Sync failures by error type
sum(rate(pos.offline_sync.sync.failed[5m])) by (error_type)

# Average sync duration
histogram_quantile(0.95, rate(pos.offline_sync.job.duration_bucket[5m]))
```

### Alerts

| Alert | Threshold | Severity | Action |
|-------|-----------|----------|--------|
| Queue depth high | >100 for 10 min | P2 | Check network, review sync errors |
| Sync failure rate | >10% for 5 min | P2 | Investigate error logs, check payment provider |
| Device offline | >4 hours | P3 | Contact store, verify device status |
| Queue capacity | >80% full | P1 | Immediate sync required, may need manual intervention |

### Log Tags

All log entries tagged with:
- `device_id`: POS device identifier
- `location`: Physical store location
- `tenant_id`: Store tenant
- `staff_user_id`: Staff member (if authenticated)

**Example Log Query:**
```bash
kubectl logs -l app=village-storefront | \
  grep "device_id=123" | \
  grep "SYNC_FAILED"
```

---

## Common Issues & Resolutions

### Issue: Sync Stuck at "Syncing..."

**Symptoms:**
- Progress bar frozen
- No errors displayed
- Queue count not decreasing

**Resolution:**
1. Check browser console for JavaScript errors
2. Verify network connectivity (open browser DevTools → Network)
3. Hard refresh page (Ctrl+Shift+R / Cmd+Shift+R)
4. If persists, clear IndexedDB and re-sync:
   ```javascript
   // Browser console
   indexedDB.deleteDatabase('pos-offline-db')
   location.reload()
   ```

### Issue: Duplicate Charges

**Symptoms:**
- Customer charged twice for same transaction
- Duplicate order IDs in system

**Root Cause:**
- Idempotency key collision (rare)
- Manual payment re-attempt after timeout

**Resolution:**
1. Locate duplicate transactions via order search
2. Issue refund for duplicate charge
3. Review idempotency key generation logic
4. File bug report if collision confirmed

**Prevention:**
- Idempotency keys are deterministic: `{deviceId}:{localTxId}`
- Local transaction IDs are UUIDs (collision probability: 1 in 2^122)

### Issue: Encryption Key Lost

**Symptoms:**
- Error: "Device encryption keys not found"
- Cannot queue offline transactions

**Resolution:**
1. Re-pair device using pairing workflow
2. New encryption key issued
3. Previous queued transactions (if any) cannot be decrypted
4. Contact support to manually recover old queue entries

**Prevention:**
- Backup IndexedDB data regularly (browser-level backup)
- Avoid clearing browser storage/cache
- Use browser profiles for dedicated POS devices

---

## Staff Training Checklist

- [ ] Device pairing process demonstrated
- [ ] Offline indicator location and meaning explained
- [ ] How to process sale in offline mode
- [ ] Queue visibility and status interpretation
- [ ] When to escalate sync errors to support
- [ ] Receipt handling for offline transactions ("PENDING SYNC" watermark)
- [ ] Customer communication about offline sales
- [ ] Emergency fallback procedure (manual credit card terminal)

---

## Support Contacts

| Role | Contact | Hours |
|------|---------|-------|
| Store Manager | [Store-specific] | Business hours |
| POS Support Hotline | 1-800-POS-HELP | 24/7 |
| Technical Support | support@villagecompute.com | M-F 9am-5pm PST |
| Emergency (P1 incidents) | PagerDuty rotation | 24/7 |

---

## Appendix: Technical Architecture

### Data Flow

```
┌─────────────┐       ┌──────────────┐       ┌────────────────┐
│  POS Client │──────▶│  IndexedDB   │──────▶│  Sync Service  │
│  (Browser)  │       │  (Encrypted) │       │  (Background)  │
└─────────────┘       └──────────────┘       └────────────────┘
       │                                              │
       │ Offline Transaction                          │
       │ (Encrypt with AES-GCM)                      │
       ▼                                              ▼
┌──────────────┐                            ┌────────────────┐
│ Offline Queue│                            │  Server Queue  │
│   (Local)    │──── Network Restored ─────▶│ (pos_offline_ │
│              │      Upload Batch          │     queue)     │
└──────────────┘                            └────────────────┘
                                                     │
                                                     │ Background Job
                                                     ▼
                                            ┌────────────────┐
                                            │  Job Processor │
                                            │  (Decrypt +    │
                                            │   Checkout)    │
                                            └────────────────┘
                                                     │
                                                     ▼
                                            ┌────────────────┐
                                            │ Payment Provider│
                                            │    (Stripe)    │
                                            └────────────────┘
```

### Database Schema

**pos_devices:**
- Device registration and encryption key metadata
- Pairing codes and expiration
- Last seen / last synced timestamps

**pos_offline_queue:**
- Encrypted transaction payloads
- Sync status tracking
- Idempotency keys

**pos_offline_transactions:**
- Audit trail of synced transactions
- Server-side order/payment references

**pos_activity_log:**
- Complete audit log (login, sync, errors)
- Device state changes

---

## Changelog

**v1.0 (2026-01-10):**
- Initial release
- Device pairing workflow
- Offline queue encryption
- Automatic sync on reconnect
- Comprehensive monitoring

---

**Document Owner:** POS Engineering Team
**Review Cycle:** Quarterly
**Next Review:** 2026-04-10
