# POS Hardware Pairing Guide

## Overview

This guide covers device pairing for the Village Storefront POS system, including supported hardware, setup procedures, and troubleshooting.

## Supported Hardware

### Tablets & Terminals

| Device | Screen Size | OS | Notes |
|--------|-------------|-----|-------|
| iPad Pro 11" | 11" | iPadOS 15+ | Recommended for mobility |
| iPad Pro 12.9" | 12.9" | iPadOS 15+ | Best for stationary registers |
| Microsoft Surface Pro 8+ | 13" | Windows 11 | Full desktop experience |
| Samsung Galaxy Tab S8+ | 12.4" | Android 12+ | Alternative to iPad |
| Android Tablets | 8"+ | Android 11+ | Minimum 4GB RAM required |

### Card Readers (Stripe Terminal)

| Model | Connection | Features |
|-------|-----------|----------|
| BBPOS WisePad 3 | Bluetooth | EMV chip, NFC, mag stripe |
| BBPOS Chipper 2X BT | Bluetooth | EMV chip, mag stripe |
| Verifone P400 | Ethernet/WiFi | Countertop, PIN pad |
| Stripe Reader M2 | Bluetooth | Mobile, battery-powered |

### Receipt Printers

| Model | Connection | Paper Width |
|-------|-----------|-------------|
| Star Micronics TSP143IV | Ethernet, Bluetooth, USB | 80mm |
| Epson TM-T88VI | Ethernet, USB | 80mm |
| Star mC-Print3 | Ethernet, Bluetooth, USB | 80mm, 58mm |

### Cash Drawers

| Model | Connection | Size |
|-------|-----------|------|
| APG Vasario 1616 | RJ11 (printer-driven) | 16" W x 16" D |
| Star mPOP | USB + Bluetooth printer | Compact (12" W) |
| MMF Advantage | RJ11 | 16" W x 16" D |

## Device Pairing Procedure

### Step 1: Generate Pairing Code (Admin Dashboard)

1. Log in to the admin dashboard at `https://[your-store].villagecompute.com/admin`
2. Navigate to **POS → Devices**
3. Click **Add New Device**
4. Fill in device details:
   - **Device Name**: Enter a descriptive name (e.g., "Front Register", "Mobile POS 1")
   - **Location**: Physical location (e.g., "Main Floor", "Checkout Counter 2")
   - **Hardware Model**: Select device type (e.g., "iPad Pro 12.9")
5. Click **Generate Pairing Code**
6. Copy the 8-character alphanumeric code (e.g., `ABCD1234`)
7. **Important**: Code expires in **15 minutes**

### Step 2: Complete Pairing (POS Terminal)

1. Open the POS application in a web browser:
   ```
   https://[your-store].villagecompute.com/admin/pos
   ```

2. If not paired, you'll see the **Complete Device Pairing** form

3. Enter the 8-character pairing code from Step 1

4. Click **Pair Device**

5. Upon successful pairing, you'll see:
   - ✅ Device paired confirmation
   - Device name and ID
   - Stripe Terminal connection token (for card reader setup)
   - Encryption key status (stored locally in browser)

### Step 3: Connect Card Reader (Optional)

If using a Stripe Terminal card reader:

1. After device pairing, locate the **Stripe Terminal Token** section in the POS view

2. Copy the connection token (or click **Refresh Token** if expired)

3. Power on your card reader

4. Follow the Stripe Terminal SDK pairing flow for your specific reader model:

   **BBPOS WisePad 3 / Chipper 2X BT (Bluetooth):**
   - Enable Bluetooth on your tablet
   - Press and hold the power button on the reader until LED flashes
   - The POS app will auto-discover the reader
   - Select your reader from the list
   - Connection token is used automatically

   **Verifone P400 (Ethernet/WiFi):**
   - Connect reader to network via Ethernet or WiFi
   - Note the reader's IP address (displayed on screen)
   - Enter IP address in POS settings
   - Click **Connect**

5. Verify connection status shows **Connected** with green indicator

6. Run a test transaction (e.g., $0.01 charge) to verify card reader functionality

### Step 4: Test Offline Mode

1. Disconnect from WiFi/network

2. Verify the **Offline** indicator appears (orange badge in header)

3. Create a test sale:
   - Search for a product
   - Add to cart
   - Select payment method: **Cash**
   - Click **Complete Sale**

4. Transaction should queue locally (check **Offline Queue** section)

5. Reconnect to network

6. Verify automatic sync completes:
   - Queue count decreases
   - "Last sync" timestamp updates
   - Transaction appears in admin dashboard orders

## Security & Encryption

### Encryption Key Management

- **Key Generation**: AES-256 encryption key generated during device pairing
- **Storage**: Key stored in browser IndexedDB (never transmitted after initial pairing)
- **Versioning**: Each key rotation increments version number
- **Algorithm**: AES-256-GCM with 12-byte random initialization vector (IV) per transaction

### Key Rotation

To rotate encryption keys (recommended annually or after security incident):

1. Navigate to **Admin → POS → Devices**
2. Select the device
3. Click **Rotate Encryption Key**
4. Generate new pairing code
5. Re-pair device on POS terminal
6. Old queued transactions will fail sync (export and manually process if needed)

### Stripe Terminal Token

- **Expiration**: 24 hours
- **Auto-Refresh**: POS app automatically refreshes token every 23 hours
- **Manual Refresh**: Click **Refresh Token** button if connection fails
- **Scope**: Token scoped to specific device and tenant

## Troubleshooting

### Pairing Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "Pairing code not found or expired" | Code expired (>15min) or already used | Generate new pairing code in admin dashboard |
| "Device already paired" | Browser storage contains old pairing data | Clear browser data (IndexedDB) or use incognito mode, then re-pair |
| "Network error during pairing" | Firewall blocking API calls | Check network connectivity, verify domain is whitelisted |

### Card Reader Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "Connection token expired" | Token older than 24 hours | Click **Refresh Token** in POS view |
| "Reader not found" | Bluetooth disabled or reader off | Enable Bluetooth, power on reader, hold power button 3 seconds |
| "Reader disconnected during transaction" | Battery low or out of range | Charge reader, move closer to tablet |
| "Payment declined" | Test card or insufficient funds | Use Stripe test cards: `4242 4242 4242 4242` (Visa), `5555 5555 5555 4444` (Mastercard) |

### Offline Queue Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "Encryption key not found" | Device not paired or key lost | Re-pair device (generates new key) |
| Queue not syncing | Network issues or server down | Check network, manually click **Sync Now**, verify server status |
| "Queue capacity exceeded" | >50MB or 500 transactions | Export queue as JSON, manually process, clear queue |
| Duplicate transactions | Sync retried multiple times | Server deduplicates via idempotency key (`{deviceId}:{localTxId}`), safe to ignore |

### Browser Storage Issues

| Issue | Cause | Solution |
|-------|-------|----------|
| "IndexedDB quota exceeded" | Browser storage limit reached | Clear old data: Settings → Clear Queue (after ensuring sync complete) |
| Pairing data lost after browser update | Browser cache cleared | Re-pair device |
| Encryption key not persisting | Private/incognito mode | Use normal browsing mode (pairing requires persistent storage) |

## Queue Management

### Queue Capacity

- **Storage Limit**: 50MB (browser IndexedDB quota)
- **Transaction Limit**: 500 queued transactions (alert at 100)
- **Alert Thresholds**:
  - 🟡 Warning at 100 transactions
  - 🔴 Critical at 400 transactions
  - 🚫 Suspend new offline sales at 500 transactions

### Exporting Queue for Support

If queue sync fails repeatedly:

1. Navigate to POS view
2. Click **Offline Queue** section
3. Click **Export Queue** (downloads `pos-queue-{deviceId}.json`)
4. Email JSON file to support: `support@villagecompute.com`
5. **Warning**: File contains encrypted payment data, use secure transfer methods only

### Manual Queue Cleanup

To manually clear synced entries:

1. Verify all transactions synced (check admin dashboard orders)
2. POS view → Offline Queue → **Clear Synced Entries**
3. Auto-cleanup runs 5 minutes after successful sync

## Device Management

### Unpairing Device

To unpair a device (removes from active devices list):

1. Admin dashboard → **POS → Devices**
2. Select device
3. Click **Unpair Device**
4. Confirm action
5. **Effect**: Device cannot sync queue, encryption key invalidated, must re-pair to use

### Multiple Devices

Each staff member or register should have its own paired device:

- **Benefits**: Separate audit trails, individual encryption keys, isolated offline queues
- **Naming Convention**: Use descriptive names (e.g., "Sarah's iPad", "Checkout Lane 3")
- **Limit**: No hard limit, but recommend <20 devices per tenant for manageability

### Device Activity Logs

All device actions logged in **Admin → POS → Activity Log**:

- Device pairing/unpairing
- Offline transaction queued
- Sync attempts (success/failure)
- Encryption key rotations
- Stripe Terminal connections

## Best Practices

### Daily Operations

1. **Morning Checklist**:
   - Verify device shows "Online" status (green indicator)
   - Check Stripe Terminal token expiration (should show >12 hours remaining)
   - Test card reader with $0.01 transaction
   - Verify offline queue is empty (all previous day's transactions synced)

2. **End of Day**:
   - Ensure all offline transactions synced
   - Export queue if any sync failures
   - Charge card reader battery overnight

### Network Considerations

- **WiFi**: Use 5GHz for faster sync, 2.4GHz for better range
- **Ethernet**: Recommended for stationary registers (more reliable)
- **Failover**: Configure mobile hotspot as backup network for critical operations

### Security Hardening

- Rotate encryption keys annually
- Use device PINs/passwords (not just browser access)
- Enable browser auto-lock after 5 minutes idle
- Disable browser extensions (can access IndexedDB)
- Use kiosk mode on dedicated POS tablets

## Metrics & Monitoring

### Key Metrics (Admin Dashboard)

- **Queue Depth**: Number of unsynced transactions per device
- **Sync Success Rate**: Percentage of successful sync attempts
- **Avg Sync Latency**: Time to process queue uploads
- **Device Uptime**: Percentage of time device is online

### Alerts

Configured in **Admin → Settings → Alerts**:

- 🟡 **P2 Alert**: Queue depth >100 for 10 minutes
- 🔴 **P1 Alert**: Queue capacity >80% (40MB)
- 🟣 **Info**: Stripe Terminal token expiring in <2 hours

## Support

For additional assistance:

- **Documentation**: https://docs.villagecompute.com/pos
- **Support Email**: support@villagecompute.com
- **Phone**: 1-800-VILLAGE (business hours 9am-5pm EST)
- **Live Chat**: Available in admin dashboard (bottom-right corner)

---

**Last Updated**: January 2026
**Version**: 1.0
