#!/bin/bash
# Reset CodeMachine state for a new day of refactoring
#
# Usage:
#   .codemachine/scripts/reset-for-new-day.sh
#
# This preserves your specifications.md (edit it with new instructions)
# and clears the execution state so you can run again.

cd "$(dirname "$0")/../.."

echo "Resetting CodeMachine state for new refactoring session..."

# Reset template.json to clear completed steps
cat > .codemachine/template.json << 'EOF'
{
  "activeTemplate": "",
  "lastUpdated": "",
  "completedSteps": [],
  "notCompletedSteps": [],
  "resumeFromLastStep": false
}
EOF

# Clear memory files (agent state from previous runs)
rm -rf .codemachine/memory/*

# Clear logs from previous runs
rm -rf .codemachine/logs/*

# Clear generated prompts (not the templates)
find .codemachine/prompts -name "*.md" -path "*/generated/*" -delete 2>/dev/null

# Reset behavior.json to continue
echo '{"action": "continue"}' > .codemachine/memory/behavior.json

echo ""
echo "Reset complete!"
echo ""
echo "Next steps:"
echo "  1. Edit .codemachine/inputs/specifications.md with today's tasks"
echo "  2. Run: codemachine run .codemachine/workflows/refactor.workflow.js"
echo ""
