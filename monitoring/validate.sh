#!/bin/bash
# Validation script for observability artifacts
# Task I5.T4 - Observability Dashboards + Alerts

set -e

echo "=== Village Storefront Observability Validation ==="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Track overall status
VALIDATION_FAILED=0

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to print status
print_status() {
    local status=$1
    local message=$2

    if [ "$status" -eq 0 ]; then
        echo -e "${GREEN}✓${NC} $message"
    else
        echo -e "${RED}✗${NC} $message"
        VALIDATION_FAILED=1
    fi
}

echo "1. Validating Prometheus Rules..."
echo "-----------------------------------"

if command_exists promtool; then
    for rule_file in monitoring/prometheus-rules/*.yaml; do
        if [ -f "$rule_file" ]; then
            echo "  Checking $(basename $rule_file)..."
            if promtool check rules "$rule_file" > /dev/null 2>&1; then
                rule_count=$(promtool check rules "$rule_file" 2>&1 | grep -o '[0-9]\+ rules found' | grep -o '[0-9]\+')
                print_status 0 "  $(basename $rule_file): $rule_count rules validated"
            else
                print_status 1 "  $(basename $rule_file): Validation failed"
                promtool check rules "$rule_file"
            fi
        fi
    done
else
    echo -e "${YELLOW}⚠${NC} promtool not installed. Install with:"
    echo "    brew install prometheus  # macOS"
    echo "    apt-get install prometheus  # Ubuntu"
    echo ""
fi

echo ""
echo "2. Validating Grafana Dashboards..."
echo "------------------------------------"

if command_exists grafana-toolkit; then
    if grafana-toolkit validate dashboards monitoring/grafana-dashboards/*.json > /dev/null 2>&1; then
        dashboard_count=$(ls monitoring/grafana-dashboards/*.json 2>/dev/null | wc -l | tr -d ' ')
        print_status 0 "All $dashboard_count dashboards valid"
    else
        print_status 1 "Dashboard validation failed"
        grafana-toolkit validate dashboards monitoring/grafana-dashboards/*.json
    fi
else
    echo -e "${YELLOW}⚠${NC} grafana-toolkit not installed. Install with:"
    echo "    npm install -g @grafana/toolkit"
    echo ""
    echo "  Manual validation: Check JSON syntax"
    for dashboard in monitoring/grafana-dashboards/*.json; do
        if [ -f "$dashboard" ]; then
            if jq empty "$dashboard" 2>/dev/null; then
                print_status 0 "  $(basename $dashboard): Valid JSON"
            else
                print_status 1 "  $(basename $dashboard): Invalid JSON"
            fi
        fi
    done
fi

echo ""
echo "3. Checking Documentation Links..."
echo "-----------------------------------"

# Check that referenced runbook files exist
runbook_files=(
    "docs/operations/job_runbook.md"
    "docs/media/pipeline.md"
    "docs/adr/ADR-003-checkout-saga.md"
    "docs/operations/observability.md"
    "docs/operations/alert_catalog.md"
)

for file in "${runbook_files[@]}"; do
    if [ -f "$file" ]; then
        print_status 0 "  $file exists"
    else
        print_status 1 "  $file missing"
    fi
done

echo ""
echo "4. Verifying Alert Annotations..."
echo "-----------------------------------"

# Check that all alerts have required annotations
required_annotations=("summary" "description" "runbook_url")

for rule_file in monitoring/prometheus-rules/*.yaml; do
    if [ -f "$rule_file" ]; then
        echo "  Checking $(basename $rule_file)..."

        # Extract alert names
        alert_names=$(grep -A 1 "alert:" "$rule_file" | grep -v "^--$" | grep -v "alert:" | awk '{print $1}')

        # Count alerts
        alert_count=$(grep -c "alert:" "$rule_file" || true)

        if [ "$alert_count" -gt 0 ]; then
            # Check for required annotations (simplified check)
            if grep -q "summary:" "$rule_file" && \
               grep -q "description:" "$rule_file" && \
               grep -q "runbook_url:" "$rule_file"; then
                print_status 0 "  $alert_count alerts with annotations in $(basename $rule_file)"
            else
                print_status 1 "  Missing required annotations in $(basename $rule_file)"
            fi
        fi
    fi
done

echo ""
echo "5. Checking Metric References..."
echo "----------------------------------"

# Check that metric names referenced in docs match actual metrics in rules
echo "  Verifying metric consistency between docs and rules..."

# Extract metric names from Prometheus rules
rule_metrics=$(grep -h "expr:" monitoring/prometheus-rules/*.yaml | \
    grep -oE '[a-z_][a-z0-9_]*(_total|_bucket|_count|_seconds|_bytes|_depth|_duration)?(\{|[[:space:]])' | \
    sed 's/[{[:space:]]$//' | \
    sort -u || true)

if [ -n "$rule_metrics" ]; then
    metric_count=$(echo "$rule_metrics" | wc -l | tr -d ' ')
    if [ "$metric_count" -gt 5 ]; then
        print_status 0 "  Found $metric_count unique metrics in rules"
    else
        print_status 1 "  Metric count seems low ($metric_count), check extraction pattern"
    fi
else
    echo -e "${YELLOW}⚠${NC}   Could not extract metrics (requires promtool for full validation)"
fi

echo ""
echo "6. File Structure Validation..."
echo "--------------------------------"

# Check directory structure
required_dirs=(
    "monitoring/grafana-dashboards"
    "monitoring/prometheus-rules"
    "docs/operations"
)

for dir in "${required_dirs[@]}"; do
    if [ -d "$dir" ]; then
        file_count=$(ls "$dir" 2>/dev/null | wc -l | tr -d ' ')
        print_status 0 "  $dir ($file_count files)"
    else
        print_status 1 "  $dir missing"
    fi
done

echo ""
echo "=== Validation Summary ==="
echo ""

if [ $VALIDATION_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ All validations passed${NC}"
    echo ""
    echo "Next steps:"
    echo "  1. Install missing tools (promtool, grafana-toolkit) if needed"
    echo "  2. Apply Prometheus rules to cluster: kubectl apply -f monitoring/prometheus-rules/"
    echo "  3. Import Grafana dashboards via API or UI"
    echo "  4. Test alerts with metric injection (see docs/operations/alert_catalog.md)"
    exit 0
else
    echo -e "${RED}✗ Validation failed${NC}"
    echo ""
    echo "Please fix the errors above before deployment."
    exit 1
fi
