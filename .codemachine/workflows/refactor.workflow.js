/**
 * Iterative Refactoring Workflow
 *
 * Designed for daily refinement tasks - reads instructions from specifications.md
 * and applies them to the existing codebase.
 *
 * Usage:
 *   codemachine run .codemachine/workflows/refactor.workflow.js
 *
 * To reset for a new day:
 *   1. Update .codemachine/inputs/specifications.md with new instructions
 *   2. Delete .codemachine/template.json (or set completedSteps: [])
 *   3. Run again
 */

export default {
  name: 'Iterative Refactor',

  // No specification requirement - we use the existing one
  specification: false,

  steps: [
    // Single refactoring agent that reads instructions and applies them
    resolveStep('refactor-agent', {
      engine: 'claude',  // Use Claude for code analysis and refactoring
      modelReasoningEffort: 'high',
      // NOT executeOnce - can re-run with new instructions
    }),
  ],

  // No sub-agents needed - single agent handles everything
  subAgentIds: [],
};
