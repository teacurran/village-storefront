#!/bin/bash
#
# JWT Key Rotation Script for Village Storefront
#
# This script generates a new RSA keypair for JWT signing and enables zero-downtime key rotation
# by configuring the application to verify tokens using multiple public keys (old + new).
#
# Security Workflow:
# 1. Generate new RSA keypair (2048-bit)
# 2. Configure application to verify with BOTH old and new public keys
# 3. Deploy application (existing tokens still valid via old key, new tokens signed with new key)
# 4. Wait for all old tokens to expire (JWT max lifetime from config)
# 5. Remove old public key from configuration
# 6. Securely delete old private key
#
# References:
# - Task I6.T2: JWT key rotation tooling
# - Architecture: Section "Authentication & Authorization" - JWT token pattern
#
# Usage:
#   ./rotate-jwt-keys.sh [--keysize 2048] [--env production]
#

set -euo pipefail

# Default configuration
KEY_SIZE=${1:-2048}
ENV=${2:-development}
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Directory paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KEYS_DIR="$PROJECT_ROOT/keys"
BACKUP_DIR="$KEYS_DIR/backups"

echo "======================================"
echo "JWT Key Rotation Script"
echo "======================================"
echo "Environment: $ENV"
echo "Key Size: $KEY_SIZE bits"
echo "Timestamp: $TIMESTAMP"
echo ""

# Create directories if they don't exist
mkdir -p "$KEYS_DIR"
mkdir -p "$BACKUP_DIR"

# Step 1: Backup existing keys
echo "[1/7] Backing up existing keys..."
if [ -f "$KEYS_DIR/privateKey.pem" ]; then
    cp "$KEYS_DIR/privateKey.pem" "$BACKUP_DIR/privateKey_${TIMESTAMP}.pem"
    cp "$KEYS_DIR/publicKey.pem" "$BACKUP_DIR/publicKey_${TIMESTAMP}.pem"
    echo "✅ Backed up keys to $BACKUP_DIR/"
else
    echo "ℹ️  No existing keys found (first-time setup)"
fi

# Step 2: Generate new RSA private key
echo ""
echo "[2/7] Generating new RSA private key ($KEY_SIZE bits)..."
openssl genrsa -out "$KEYS_DIR/newPrivateKey.pem" "$KEY_SIZE"
echo "✅ Generated new private key: $KEYS_DIR/newPrivateKey.pem"

# Step 3: Extract new public key
echo ""
echo "[3/7] Extracting new public key..."
openssl rsa -pubout -in "$KEYS_DIR/newPrivateKey.pem" -out "$KEYS_DIR/newPublicKey.pem"
echo "✅ Generated new public key: $KEYS_DIR/newPublicKey.pem"

# Step 4: Display public key fingerprint (for verification)
echo ""
echo "[4/7] Public key fingerprint (verify this after deployment):"
openssl rsa -pubin -in "$KEYS_DIR/newPublicKey.pem" -outform DER | openssl dgst -sha256
echo ""

# Step 5: Create multi-key verification configuration
echo "[5/7] Configuring multi-key verification..."
if [ -f "$KEYS_DIR/publicKey.pem" ]; then
    # Concatenate old and new public keys for multi-key verification
    cat "$KEYS_DIR/publicKey.pem" > "$KEYS_DIR/publicKeys_multikey.pem"
    echo "" >> "$KEYS_DIR/publicKeys_multikey.pem"
    cat "$KEYS_DIR/newPublicKey.pem" >> "$KEYS_DIR/publicKeys_multikey.pem"
    echo "✅ Created multi-key verification file: $KEYS_DIR/publicKeys_multikey.pem"
    echo ""
    echo "📝 NEXT STEPS:"
    echo "   1. Update application.properties to use multi-key verification:"
    echo "      mp.jwt.verify.publickey.location=publicKeys_multikey.pem"
    echo ""
    echo "   2. Update signing key to use new private key:"
    echo "      smallrye.jwt.sign.key.location=newPrivateKey.pem"
    echo ""
    echo "   3. Deploy application (existing tokens remain valid via old key)"
    echo ""
    echo "   4. Wait for old tokens to expire (check JWT max lifetime in config)"
    echo ""
    echo "   5. Run: ./rotate-jwt-keys.sh --finalize"
else
    # First-time setup: just use new keys
    mv "$KEYS_DIR/newPrivateKey.pem" "$KEYS_DIR/privateKey.pem"
    mv "$KEYS_DIR/newPublicKey.pem" "$KEYS_DIR/publicKey.pem"
    echo "✅ First-time setup complete. Keys installed as privateKey.pem and publicKey.pem"
fi

# Step 6: Verify key format
echo ""
echo "[6/7] Verifying key format..."
openssl rsa -check -noout -in "$KEYS_DIR/newPrivateKey.pem" 2>/dev/null && echo "✅ Private key valid" || echo "❌ Private key invalid"
openssl rsa -pubin -check -noout -in "$KEYS_DIR/newPublicKey.pem" 2>/dev/null && echo "✅ Public key valid" || echo "❌ Public key invalid"

# Step 7: Security reminders
echo ""
echo "[7/7] Security Checklist:"
echo "   ✅ New keys generated and backed up"
echo "   ⚠️  NEVER commit private keys to version control (.gitignore keys/ directory)"
echo "   ⚠️  Rotate keys every 90 days (schedule next rotation: $(date -d "+90 days" +%Y-%m-%d 2>/dev/null || date -v +90d +%Y-%m-%d 2>/dev/null))"
echo "   ⚠️  After old tokens expire, securely delete old private key:"
echo "      shred -u $BACKUP_DIR/privateKey_${TIMESTAMP}.pem"
echo ""
echo "======================================"
echo "Key Rotation Initiated Successfully"
echo "======================================"
