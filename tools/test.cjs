#!/usr/bin/env node
/**
 * test.cjs
 *
 * Test execution script for Village Storefront (Java/Maven/Quarkus)
 * Runs all unit tests and generates coverage reports with JaCoCo.
 *
 * Project conventions:
 * - Tests are run with Maven Surefire
 * - Coverage is collected with JaCoCo
 * - Target: 80% line and branch coverage
 */

const { execSync, spawn } = require('child_process');
const path = require('path');

// ANSI color codes for output
const colors = {
  reset: '\x1b[0m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  red: '\x1b[31m',
  blue: '\x1b[36m'
};

function log(message, color = colors.reset) {
  console.error(`${color}${message}${colors.reset}`);
}

function logSuccess(message) {
  log(`✓ ${message}`, colors.green);
}

function logInfo(message) {
  log(`ℹ ${message}`, colors.blue);
}

function logError(message) {
  log(`✗ ${message}`, colors.red);
}

/**
 * Get the Maven wrapper command based on platform
 */
function getMavenCommand() {
  const isWindows = process.platform === 'win32';
  return isWindows ? 'mvnw.cmd' : './mvnw';
}

/**
 * Run install script to ensure dependencies are up to date
 */
function runInstall() {
  logInfo('Running environment setup...');

  try {
    execSync('node tools/install.cjs', {
      stdio: 'inherit',
      cwd: path.resolve(__dirname, '..')
    });
    logSuccess('Environment setup complete\n');
    return true;
  } catch (error) {
    logError('Environment setup failed');
    return false;
  }
}

/**
 * Run tests with Maven and JaCoCo coverage
 */
function runTests() {
  const mvnCmd = getMavenCommand();
  const isNative = process.argv.includes('--native');

  logInfo(`Running ${isNative ? 'native' : 'JVM'} tests with coverage...\n`);

  // Spawn Maven process for tests
  // Using spawn instead of exec to stream output in real-time
  const isWindows = process.platform === 'win32';
  const child = spawn(
    mvnCmd,
    [
      '-B',              // Batch mode (non-interactive)
      ...(isNative ? ['-Pnative'] : []),
      'verify',          // Run full verification lifecycle (includes JaCoCo check)
      'jacoco:report'    // Generate coverage report artifacts
    ],
    {
      stdio: 'inherit',
      shell: isWindows,
      cwd: path.resolve(__dirname, '..')
    }
  );

  // Handle process completion
  child.on('error', (error) => {
    logError(`Failed to run tests: ${error.message}`);
    process.exit(1);
  });

  child.on('exit', (code) => {
    if (code === 0) {
      logSuccess('\n=== Tests passed ===');
      logInfo('Coverage report generated at: target/site/jacoco/index.html');
      process.exit(0);
    } else {
      logError(`\n=== Tests failed with exit code ${code} ===`);
      process.exit(code || 1);
    }
  });

  // Handle SIGINT (Ctrl+C) gracefully
  process.on('SIGINT', () => {
    logInfo('\nStopping tests...');
    child.kill('SIGINT');
  });

  // Handle SIGTERM gracefully
  process.on('SIGTERM', () => {
    logInfo('\nStopping tests...');
    child.kill('SIGTERM');
  });
}

/**
 * Main execution
 */
function main() {
  logInfo('=== Village Storefront - Run Tests ===\n');

  // Ensure environment and dependencies are set up
  if (!runInstall()) {
    process.exit(1);
  }

  // Run tests
  runTests();
}

// Run if executed directly
if (require.main === module) {
  main();
}

module.exports = { runTests };
