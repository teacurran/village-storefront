#!/usr/bin/env node
/**
 * install.cjs
 *
 * Environment setup and dependency installation for Village Storefront (Java/Maven/Quarkus)
 * This script ensures Maven dependencies are properly installed and the project is ready to run.
 *
 * Project type: Java Maven Quarkus (multi-module)
 * Build system: Maven
 * Runtime: Java 21
 *
 * IMPORTANT: This script is idempotent - it can be run multiple times safely.
 */

const { execSync, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

process.env.QUARKUS_AWS_DEVSERVICES_LOCALSTACK_ENABLED = 'false';
process.env.QUARKUS_AMAZON_S3_DEVSERVICES_ENABLED = 'false';

const MODULES = ['modules/core-platform'];

function getModuleSelector() {
  return MODULES.join(',');
}

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
 * Get project root directory
 */
function getProjectRoot() {
  return path.resolve(__dirname, '..');
}

/**
 * Get the Maven wrapper command based on platform
 */
function getMavenCommand() {
  const isWindows = process.platform === 'win32';
  const mvnWrapper = isWindows ? 'mvnw.cmd' : './mvnw';
  const projectRoot = getProjectRoot();
  const mvnPath = path.join(projectRoot, isWindows ? 'mvnw.cmd' : 'mvnw');

  // Check if Maven wrapper exists
  if (fs.existsSync(mvnPath)) {
    return mvnWrapper;
  }

  // Fallback to system Maven
  logWarning('Maven wrapper not found, using system Maven');
  return 'mvn';
}

/**
 * Check if Java is installed and meets version requirements
 */
function checkJava() {
  logInfo('Checking Java installation...');

  try {
    // Try to get version from stdout first, then stderr
    let version;
    try {
      version = execSync('java -version 2>&1', {
        encoding: 'utf8',
        stdio: ['pipe', 'pipe', 'pipe']
      });
    } catch (error) {
      // If that fails, try without redirection
      version = error.stdout || error.stderr || '';
    }

    // Extract version number (e.g., "21.0.3" from various formats)
    // Supports: "21.0.3", "openjdk version 21", etc.
    const versionMatch = version.match(/version "?(\d+)\.?(\d+)?\.?(\d+)?/i) ||
                         version.match(/openjdk (\d+)/i);

    if (versionMatch) {
      const majorVersion = parseInt(versionMatch[1]);

      if (majorVersion >= 21) {
        logSuccess(`Java ${majorVersion} detected`);
        return true;
      } else {
        logError(`Java 21 or higher is required (found: ${majorVersion})`);
        logError('Please install Java 21+ from: https://adoptium.net/');
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
    execSync(`${mvnCmd} --version`, {
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
      cwd: getProjectRoot(),
      shell: process.platform === 'win32'
    });

    logSuccess('Maven is available');
    return true;
  } catch (error) {
    logError('Maven is not available');
    if (mvnCmd.includes('mvnw')) {
      logError('Maven wrapper found but failed to execute');
      logError('Try running: chmod +x mvnw (on Unix systems)');
    } else {
      logError('Please install Maven from: https://maven.apache.org/download.cgi');
    }
    return false;
  }
}

/**
 * Check if dependencies need to be updated by comparing timestamps
 */
function needsDependencyUpdate() {
  const projectRoot = getProjectRoot();
  const pomFile = path.join(projectRoot, 'pom.xml');
  const moduleEntries = MODULES.map((module) => ({
    pomFile: path.join(projectRoot, module, 'pom.xml'),
    targetDir: path.join(projectRoot, module, 'target')
  }));
  const targetDirs = [path.join(projectRoot, 'target'), ...moduleEntries.map((entry) => entry.targetDir)];

  try {
    const targetStats = targetDirs
      .filter((dir) => fs.existsSync(dir))
      .map((dir) => fs.statSync(dir).mtimeMs);

    if (targetStats.length === 0) {
      return true;
    }

    const latestTarget = Math.max(...targetStats);
    const pomStats = [pomFile, ...moduleEntries.map((entry) => entry.pomFile)]
      .filter((file) => fs.existsSync(file))
      .map((file) => fs.statSync(file).mtimeMs);

    if (pomStats.length === 0) {
      return true;
    }

    return pomStats.some((mtime) => mtime > latestTarget);
  } catch (error) {
    return true;
  }
}

/**
 * Install/update Maven dependencies
 */
function installDependencies() {
  const mvnCmd = getMavenCommand();
  const moduleSelector = getModuleSelector();

  // Check if update is needed for performance
  if (!needsDependencyUpdate()) {
    logInfo('Dependencies are up to date (skipping)');
    return true;
  }

  logInfo('Installing/updating Maven dependencies...');

  try {
    // Run Maven dependency resolution
    // Using -B for batch mode (non-interactive)
    // Using -T 1C for parallel builds (1 thread per CPU core)
    const baseArgs = ['-pl', moduleSelector, '-am', '-B', '-T', '1C'];
    const result = spawnSync(
      mvnCmd,
      [...baseArgs, 'dependency:resolve'],
      {
        stdio: 'inherit',
        shell: process.platform === 'win32',
        encoding: 'utf8',
        cwd: getProjectRoot()
      }
    );

    if (result.status !== 0) {
      logError('Failed to resolve Maven dependencies');
      return false;
    }

    // Also resolve test dependencies
    const testResult = spawnSync(
      mvnCmd,
      [...baseArgs, 'dependency:resolve', '-Dclassifier=tests'],
      {
        stdio: 'inherit',
        shell: process.platform === 'win32',
        encoding: 'utf8',
        cwd: getProjectRoot()
      }
    );

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
    const result = spawnSync(
      mvnCmd,
      ['-pl', getModuleSelector(), '-am', '-B', '-T', '1C', 'compile'],
      {
        stdio: 'inherit',
        shell: process.platform === 'win32',
        encoding: 'utf8',
        cwd: getProjectRoot()
      }
    );

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

module.exports = { getMavenCommand, checkJava, checkMaven, getProjectRoot };
