#!/usr/bin/env node
/**
 * lint.cjs
 *
 * Linting script for Village Storefront (Java/Maven/Quarkus)
 * Uses Spotless to check code formatting and style issues.
 * Outputs results in JSON format for consumption by automated tools.
 *
 * Exit codes:
 *   0 - No issues found
 *   Non-zero - Issues found or error occurred
 */

const { execSync, spawnSync } = require('child_process');
const path = require('path');
const fs = require('fs');

/**
 * Get the Maven wrapper command based on platform
 */
function getMavenCommand() {
  const isWindows = process.platform === 'win32';
  return isWindows ? 'mvnw.cmd' : './mvnw';
}

/**
 * Run install script silently to ensure dependencies are up to date
 */
function runInstall() {
  try {
    execSync('node tools/install.cjs', {
      stdio: 'ignore',
      cwd: path.resolve(__dirname, '..')
    });
    return true;
  } catch (error) {
    console.error('Environment setup failed');
    return false;
  }
}

/**
 * Parse Spotless error output and convert to JSON format
 */
function parseSpotlessOutput(output) {
  const errors = [];

  // Spotless output format varies, but typically includes:
  // - File paths with formatting violations
  // - Error messages about what needs to be fixed

  // Split by lines and look for file references
  const lines = output.split('\n');
  let currentFile = null;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();

    // Look for file paths (typically starts with path separators or contains .java)
    if (line.includes('.java') || line.includes('.xml')) {
      // Try to extract file path
      const fileMatch = line.match(/([^\s]+\.(?:java|xml))/);
      if (fileMatch) {
        currentFile = fileMatch[1];

        // Extract relative path from project root
        const projectRoot = path.resolve(__dirname, '..');
        if (currentFile.startsWith(projectRoot)) {
          currentFile = path.relative(projectRoot, currentFile);
        }

        errors.push({
          type: 'formatting',
          path: currentFile,
          obj: '',
          message: 'Code formatting violation detected by Spotless',
          line: 0,
          column: 0
        });
      }
    } else if (line.includes('[ERROR]') && currentFile) {
      // Extract error details if available
      const errorMsg = line.replace('[ERROR]', '').trim();
      if (errorMsg && errorMsg.length > 0) {
        errors[errors.length - 1].message = errorMsg;
      }
    }
  }

  // If no specific files found but there was an error, add a general error
  if (errors.length === 0 && output.includes('[ERROR]')) {
    errors.push({
      type: 'formatting',
      path: '',
      obj: '',
      message: 'Code formatting violations detected. Run ./mvnw spotless:apply to fix.',
      line: 0,
      column: 0
    });
  }

  return errors;
}

/**
 * Run Spotless check to validate code formatting
 */
function runSpotlessCheck() {
  const mvnCmd = getMavenCommand();

  try {
    // Run Spotless check
    // -B: batch mode (non-interactive)
    // -q: quiet mode (reduce output)
    const result = spawnSync(
      mvnCmd,
      ['spotless:check', '-B'],
      {
        encoding: 'utf8',
        cwd: path.resolve(__dirname, '..'),
        shell: process.platform === 'win32'
      }
    );

    // Spotless check returns non-zero if formatting issues are found
    if (result.status === 0) {
      // No issues found
      console.log(JSON.stringify([]));
      return 0;
    } else {
      // Parse output and convert to JSON
      const output = result.stdout + '\n' + result.stderr;
      const errors = parseSpotlessOutput(output);
      console.log(JSON.stringify(errors));
      return 1;
    }
  } catch (error) {
    // Error running the command
    const errors = [{
      type: 'error',
      path: '',
      obj: '',
      message: `Failed to run linting: ${error.message}`,
      line: 0,
      column: 0
    }];
    console.log(JSON.stringify(errors));
    return 1;
  }
}

/**
 * Main execution
 */
function main() {
  // Silently ensure environment and dependencies are set up
  if (!runInstall()) {
    const errors = [{
      type: 'error',
      path: '',
      obj: '',
      message: 'Failed to set up environment',
      line: 0,
      column: 0
    }];
    console.log(JSON.stringify(errors));
    process.exit(1);
  }

  // Run linting
  const exitCode = runSpotlessCheck();
  process.exit(exitCode);
}

// Run if executed directly
if (require.main === module) {
  main();
}

module.exports = { runSpotlessCheck, parseSpotlessOutput };
