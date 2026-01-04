# Feature Flag CLI Tool

Command-line interface for managing Village Storefront feature flags and governance.

## Prerequisites

- **Node.js** (any recent version)
- **Platform admin token** with `PERMISSION_MANAGE_FEATURE_FLAGS`

## Installation

No installation required - use directly with Node.js:

```bash
node tools/featureflag-cli/featureflag.cjs <command>
```

Or add to PATH:

```bash
# macOS/Linux
ln -s $(pwd)/tools/featureflag-cli/featureflag.cjs /usr/local/bin/featureflag
chmod +x /usr/local/bin/featureflag
featureflag list
```

## Configuration

Set required environment variables:

```bash
export API_BASE_URL=http://localhost:8080  # Optional, defaults to localhost
export API_TOKEN=<your-platform-admin-token>  # Required
```

## Commands

### List Flags

List all feature flags:

```bash
node featureflag.cjs list
```

List only stale flags (expired or needs review):

```bash
node featureflag.cjs list --stale
```

**Output format:**

```
KEY                          ENABLED  OWNER              RISK      STALE
storefront.hero.beta         ✓        alice@example.com  MEDIUM    no
checkout.apple-pay           ✗        bob@example.com    HIGH      YES
media.ai-tagging             ✓        platform-team      LOW       no

Total: 3 flag(s)
```

### Get Flag Details

Get detailed information for a specific flag:

```bash
node featureflag.cjs get <flag-id>
```

**Example:**

```bash
node featureflag.cjs get a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Output:**

```
ID:          a1b2c3d4-e5f6-7890-abcd-ef1234567890
Key:         storefront.hero.beta
Enabled:     ✓ true
Owner:       alice@example.com
Risk Level:  MEDIUM
Description: New hero component with video background
Review:      Every 90 days (last: 2026-01-01T10:00:00Z)
Expiry:      2026-06-01T00:00:00Z
Created:     2025-12-01T15:30:00Z
Updated:     2026-01-01T10:00:00Z
```

### Update Flag

Update flag state or governance metadata:

```bash
node featureflag.cjs set <flag-id> [options]
```

**Options:**

- `--enabled=true|false` - Enable or disable the flag
- `--owner=<email>` - Change owner
- `--risk-level=LOW|MEDIUM|HIGH|CRITICAL` - Update risk level
- `--review-days=<number>` - Set review cadence in days
- `--expiry=<ISO-8601-date>` - Set expiry date
- `--reason="<text>"` - Justification for audit trail

**Examples:**

```bash
# Emergency disable
node featureflag.cjs set abc-123 --enabled=false --reason="Production incident #456"

# Update ownership
node featureflag.cjs set abc-123 --owner=carol@example.com --reason="Team transfer"

# Set expiry
node featureflag.cjs set abc-123 --expiry=2026-12-31T23:59:59Z --reason="Planned cleanup Q4"

# Increase risk level
node featureflag.cjs set abc-123 --risk-level=CRITICAL --reason="Now controls payment flow"
```

### Mark as Reviewed

Update the last reviewed timestamp:

```bash
node featureflag.cjs review <flag-id> --reason="<justification>"
```

**Example:**

```bash
node featureflag.cjs review abc-123 --reason="Reviewed Q1 2026 - still needed for A/B test"
```

### Stale Flag Report

Generate a comprehensive report of stale flags:

```bash
node featureflag.cjs stale-report
```

**Output:**

```
=== Stale Feature Flag Report ===
Generated: 2026-01-03T12:00:00Z

Expired Flags: 2
KEY                     ENABLED  OWNER              RISK    STALE
legacy.cart.v1          ✗        platform-team      LOW     YES
checkout.old-flow       ✗        bob@example.com    HIGH    YES

Needs Review: 1
KEY                     ENABLED  OWNER              RISK    STALE
storefront.hero.beta    ✓        alice@example.com  MEDIUM  YES
```

### View Change History

Show audit trail for a flag:

```bash
node featureflag.cjs history <flag-id>
```

**Output:**

```
=== Flag Change History ===

2026-01-03T10:30:00Z - update_feature_flag
  Metadata: {"changes": "enabled: true → false; ", "reason": "Emergency disable"}

2026-01-01T08:00:00Z - update_feature_flag
  Metadata: {"changes": "reviewed at 2026-01-01T08:00:00Z; ", "reason": "Q1 review"}
```

## Exit Codes

- `0` - Success
- `1` - Error (invalid arguments, network failure, API error)

## Automation Integration

### CI/CD Pipeline

Check for stale flags in CI:

```bash
#!/bin/bash
# .github/workflows/check-stale-flags.yml

export API_TOKEN=${{ secrets.PLATFORM_ADMIN_TOKEN }}
export API_BASE_URL=https://api.example.com

node tools/featureflag-cli/featureflag.cjs stale-report

# Fail if expired flags exist
EXPIRED=$(node tools/featureflag-cli/featureflag.cjs stale-report | grep "Expired Flags:" | awk '{print $3}')
if [ "$EXPIRED" -gt 0 ]; then
  echo "❌ Found $EXPIRED expired feature flags - please clean up"
  exit 1
fi
```

### Scheduled Review Reminder

```bash
#!/bin/bash
# cron: 0 9 * * MON (every Monday at 9 AM)

export API_TOKEN=<token>
node tools/featureflag-cli/featureflag.cjs list --stale | mail -s "Weekly Stale Flags" team@example.com
```

### Emergency Kill Switch

```bash
#!/bin/bash
# Disable a flag immediately

FLAG_KEY="checkout.new-flow"
FLAG_ID=$(node tools/featureflag-cli/featureflag.cjs list | grep "$FLAG_KEY" | awk '{print $1}')
node tools/featureflag-cli/featureflag.cjs set "$FLAG_ID" --enabled=false --reason="Emergency rollback - incident #789"
```

## Troubleshooting

### Authentication Error

```
Error: API error 401: Unauthorized
```

**Solution:** Verify `API_TOKEN` is set and has `PERMISSION_MANAGE_FEATURE_FLAGS`:

```bash
echo $API_TOKEN  # Should print token
# Get new token from platform admin console
```

### Network Error

```
Error: connect ECONNREFUSED 127.0.0.1:8080
```

**Solution:** Check `API_BASE_URL` and ensure the server is running:

```bash
curl $API_BASE_URL/q/health
```

### Invalid Flag ID

```
Error: API error 404: Flag not found
```

**Solution:** List flags first to get the correct UUID:

```bash
node featureflag.cjs list
```

## References

- Task I5.T7: Feature flag governance
- Architecture: `docs/feature_flags/governance.md`
- API Docs: Platform Admin REST API
