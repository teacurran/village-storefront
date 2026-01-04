#!/usr/bin/env node
/**
 * install.cjs
 *
 * Environment setup and dependency installation for Village Storefront (Java/Maven/Quarkus)
 * This script ensures Maven dependencies are properly installed and the project is ready to run.
 *
 * Project type: Java Maven Quarkus
 * Build system: Maven
 * Runtime: Java 21
 */

const { execSync, spawnSync } = require('child_process');
const fs = require('fs');
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

function logWarning(message) {
  log(`⚠ ${message}`, colors.yellow);
}

/**
 * Get the Maven wrapper command based on platform
 */
function getMavenCommand() {
  const isWindows = process.platform === 'win32';
  const mvnWrapper = isWindows ? 'mvnw.cmd' : './mvnw';
  const projectRoot = path.resolve(__dirname, '..');
  const mvnPath = path.join(projectRoot, mvnWrapper);

  // Check if Maven wrapper exists
  if (fs.existsSync(mvnPath)) {
    return isWindows ? mvnWrapper : mvnWrapper;
  }

  // Fallback to system Maven
  return 'mvn';
}

/**
 * Check if Java is installed and meets version requirements
 */
function checkJava() {
  logInfo('Checking Java installation...');

  try {
    const output = execSync('java -version', {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe']
    });

    // Java version is printed to stderr
    const version = output || execSync('java -version 2>&1', { encoding: 'utf8' });

    // Extract version number (e.g., "21.0.3" from various formats)
    const versionMatch = version.match(/version "(\d+)\.?(\d+)?\.?(\d+)?/);

    if (versionMatch) {
      const majorVersion = parseInt(versionMatch[1]);

      if (majorVersion >= 21) {
        logSuccess(`Java ${majorVersion} detected`);
        return true;
      } else {
        logError(`Java 21 or higher is required (found: ${majorVersion})`);
        return false;
      }
    }

    logWarning('Could not determine Java version, proceeding anyway');
    return true;
  } catch (error) {
    logError('Java is not installed or not in PATH');
    logError('Please install Java 21 or higher from: https://adoptium.net/');
    return false;
  }
}

/**
 * Check if Maven is available
 */
function checkMaven() {
  const mvnCmd = getMavenCommand();
  logInfo(`Checking Maven availability (${mvnCmd})...`);

  try {
    const output = execSync(`${mvnCmd} --version`, {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe']
    });

    logSuccess('Maven is available');
    return true;
  } catch (error) {
    logError('Maven is not available');
    logError('Please install Maven from: https://maven.apache.org/download.cgi');
    return false;
  }
}

/**
 * Install/update Maven dependencies
 */
function installDependencies() {
  const mvnCmd = getMavenCommand();
  logInfo('Installing/updating Maven dependencies...');

  try {
    // Run Maven dependency resolution
    // Using -B for batch mode (non-interactive)
    // Using -q for quiet mode (less verbose output)
    const result = spawnSync(mvnCmd, ['dependency:resolve', '-B'], {
      stdio: 'inherit',
      shell: process.platform === 'win32',
      encoding: 'utf8'
    });

    if (result.status !== 0) {
      logError('Failed to resolve Maven dependencies');
      return false;
    }

    // Also resolve test dependencies
    const testResult = spawnSync(mvnCmd, ['dependency:resolve', '-Dclassifier=tests', '-B'], {
      stdio: 'inherit',
      shell: process.platform === 'win32',
      encoding: 'utf8'
    });

    if (testResult.status !== 0) {
      logWarning('Warning: Some test dependencies may not be resolved');
      // Don't fail on test dependency issues
    }

    logSuccess('Maven dependencies installed/updated');
    return true;
  } catch (error) {
    logError(`Error installing dependencies: ${error.message}`);
    return false;
  }
}

/**
 * Compile the project to ensure everything is ready
 */
function compileProject() {
  const mvnCmd = getMavenCommand();
  logInfo('Compiling project...');

  try {
    const result = spawnSync(mvnCmd, ['compile', '-B', '-q'], {
      stdio: 'inherit',
      shell: process.platform === 'win32',
      encoding: 'utf8'
    });

    if (result.status !== 0) {
      logError('Compilation failed');
      return false;
    }

    logSuccess('Project compiled successfully');
    return true;
  } catch (error) {
    logError(`Error compiling project: ${error.message}`);
    return false;
  }
}

/**
 * Main installation flow
 */
function main() {
  logInfo('=== Village Storefront - Environment Setup ===\n');

  // Check prerequisites
  if (!checkJava()) {
    process.exit(1);
  }

  if (!checkMaven()) {
    process.exit(1);
  }

  // Install dependencies
  if (!installDependencies()) {
    process.exit(1);
  }

  // Compile project to ensure it's ready
  if (!compileProject()) {
    process.exit(1);
  }

  logSuccess('\n=== Environment setup complete ===');
  process.exit(0);
}

// Run if executed directly
if (require.main === module) {
  main();
}

module.exports = { getMavenCommand, checkJava, checkMaven };
