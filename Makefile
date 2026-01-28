# Village Storefront Makefile
# Run `make help` to see available targets

.PHONY: help refactor refactor-reset refactor-status dev test compile format

help:
	@echo "Village Storefront - Available targets:"
	@echo ""
	@echo "  Development:"
	@echo "    make dev          - Start Quarkus in dev mode"
	@echo "    make test         - Run all tests"
	@echo "    make compile      - Compile the project"
	@echo "    make format       - Apply Spotless formatting"
	@echo ""
	@echo "  CodeMachine Refactor Workflow:"
	@echo "    make refactor     - Run the refactor workflow"
	@echo "    make refactor-reset - Reset state for a new refactor cycle"
	@echo "    make refactor-status - Show current refactor state"
	@echo ""

# =============================================================================
# Development targets
# =============================================================================

dev:
	./mvnw quarkus:dev

test:
	./mvnw test

compile:
	./mvnw compile

format:
	./mvnw spotless:apply

# =============================================================================
# CodeMachine Refactor Workflow
# =============================================================================

# Run the refactor workflow
# Edit .codemachine/inputs/specifications.md first with your refactoring tasks
refactor:
	@echo "Running CodeMachine refactor workflow..."
	@echo "Specifications: .codemachine/inputs/specifications.md"
	@echo ""
	codemachine run .codemachine/workflows/refactor.workflow.js

# Reset state for a new refactor cycle
# This clears logs, memory, and template state while preserving specifications.md
refactor-reset:
	@echo "Resetting CodeMachine state for new refactor cycle..."
	.codemachine/scripts/reset-for-new-day.sh

# Show current refactor state
refactor-status:
	@echo "=== CodeMachine Refactor Status ==="
	@echo ""
	@echo "Template state:"
	@cat .codemachine/template.json 2>/dev/null || echo "  No template.json found"
	@echo ""
	@echo "Recent logs:"
	@ls -lt .codemachine/logs/*.log 2>/dev/null | head -5 || echo "  No logs found"
	@echo ""
	@echo "Memory files:"
	@ls -la .codemachine/memory/ 2>/dev/null || echo "  No memory files"
