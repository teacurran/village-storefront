#!/usr/bin/env node
/**
 * run.cjs
 *
 * Runs the Village Storefront application in Quarkus development mode
 * Ensures dependencies are installed before starting the dev server.
 *
 * Project type: Java Maven Quarkus
 * Dev mode: quarkus:dev (hot reload, live coding)
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
 * Start Quarkus in development mode
 */
function runDevServer() {
  const mvnCmd = getMavenCommand();
  logInfo('Starting Quarkus development server...');
  logInfo('Press Ctrl+C to stop\n');

  // Spawn Maven process for Quarkus dev mode
  // Using spawn instead of exec to stream output in real-time
  const isWindows = process.platform === 'win32';
  const child = spawn(mvnCmd, ['quarkus:dev', '-B'], {
    stdio: 'inherit',
    shell: isWindows,
    cwd: path.resolve(__dirname, '..')
  });

  // Handle process termination
  child.on('error', (error) => {
    logError(`Failed to start dev server: ${error.message}`);
    process.exit(1);
  });

  child.on('exit', (code) => {
    if (code !== 0 && code !== null) {
      logError(`Dev server exited with code ${code}`);
      process.exit(code);
    }
    process.exit(0);
  });

  // Handle SIGINT (Ctrl+C) gracefully
  process.on('SIGINT', () => {
    logInfo('\nStopping dev server...');
    child.kill('SIGINT');
  });

  // Handle SIGTERM gracefully
  process.on('SIGTERM', () => {
    logInfo('\nStopping dev server...');
    child.kill('SIGTERM');
  });
}

/**
 * Main execution
 */
function main() {
  logInfo('=== Village Storefront - Run Development Server ===\n');

  // Ensure environment and dependencies are set up
  if (!runInstall()) {
    process.exit(1);
  }

  // Start the development server
  runDevServer();
}

// Run if executed directly
if (require.main === module) {
  main();
}

module.exports = { runDevServer };
