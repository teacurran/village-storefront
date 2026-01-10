# Automation Scripts

This directory contains cross-platform Node.js automation scripts for the Village Storefront project. These scripts provide a consistent interface for development, testing, and CI/CD workflows across Windows, macOS, and Linux.

## Overview

All scripts are written as CommonJS modules (`.cjs` extension) and use only Node.js built-in modules and the Maven build system. They are designed to be:

- **Cross-platform**: Work on Windows, macOS, and Linux
- **Idempotent**: Safe to run multiple times
- **Self-contained**: No external npm dependencies required
- **Robust**: Proper error handling and exit codes

## Scripts

### 1. `install.cjs` - Environment Setup and Dependency Installation

**Purpose**: Ensures the development environment is correctly set up with all dependencies installed.

**Usage**:
```bash
node tools/install.cjs
# or via npm
npm install
```

**What it does**:
1. Verifies Java 21+ is installed and available
2. Verifies Maven is available (wrapper or system)
3. Resolves and installs all Maven dependencies (including test dependencies)
4. Compiles the project to ensure everything is ready

**Features**:
- Idempotent: Checks timestamps and skips unnecessary reinstallation
- Cross-platform: Detects OS and uses appropriate Maven wrapper
- Smart caching: Only updates dependencies when pom.xml changes
- Multi-module aware: Handles parent and child module structure

**Exit codes**:
- `0`: Success
- `1`: Failure (Java not found, Maven failed, compilation failed)

---

### 2. `run.cjs` - Development Server

**Purpose**: Starts the Quarkus development server with hot reload and live coding.

**Usage**:
```bash
node tools/run.cjs
# or via npm
npm run dev
```

**What it does**:
1. Runs `install.cjs` to ensure environment is ready
2. Starts Quarkus in development mode (`quarkus:dev`)
3. Enables hot reload for Java code changes
4. Streams output in real-time

**Features**:
- Graceful shutdown on Ctrl+C (SIGINT/SIGTERM)
- Automatic dependency installation before start
- Real-time output streaming
- Cross-platform signal handling

**Exit codes**:
- `0`: Server stopped normally
- Non-zero: Server failed to start or crashed

---

### 3. `lint.cjs` - Code Formatting Validation

**Purpose**: Validates code formatting using Spotless and outputs results in JSON format.

**Usage**:
```bash
node tools/lint.cjs
# or via npm
npm run lint
```

**What it does**:
1. Silently runs `install.cjs` to ensure Spotless plugin is available
2. Executes `mvnw spotless:check` to validate formatting
3. Parses output and converts to standardized JSON format
4. Outputs only JSON to stdout (no other text)

**Output format**:
```json
[
  {
    "type": "formatting",
    "path": "src/main/java/Example.java",
    "obj": "",
    "message": "Code formatting violation detected by Spotless",
    "line": "0",
    "column": "0"
  }
]
```

Empty array `[]` indicates no issues found.

**Features**:
- JSON-only stdout output (diagnostic messages go to stderr)
- Silent dependency installation
- Cross-platform path normalization
- Detailed error messages with fix instructions

**Exit codes**:
- `0`: No formatting issues found
- `1`: Formatting issues found or error occurred

**Fix issues**:
```bash
./mvnw spotless:apply
# or via npm
npm run format
```

---

### 4. `test.cjs` - Test Execution

**Purpose**: Runs all tests with coverage reporting using JaCoCo.

**Usage**:
```bash
node tools/test.cjs
# or via npm
npm test

# For native tests
npm run test:native
```

**What it does**:
1. Runs `install.cjs` to ensure test dependencies are ready
2. Executes Maven `verify` lifecycle (runs tests)
3. Generates JaCoCo coverage reports (HTML and XML)
4. Validates coverage meets 80% threshold
5. Streams test output in real-time

**Features**:
- Parallel test execution (1 thread per CPU core)
- Automatic coverage report generation
- Native compilation support (`--native` flag)
- Multi-module coverage aggregation
- Graceful shutdown on Ctrl+C

**Exit codes**:
- `0`: All tests passed, coverage meets threshold
- Non-zero: Tests failed or coverage below threshold

**Coverage reports**:
- Multi-module: `modules/core-platform/target/site/jacoco/index.html`
- Single module: `target/site/jacoco/index.html`

---

## Cross-Platform Considerations

### Path Handling
All scripts use `path.join()` and `path.resolve()` for cross-platform path construction. Windows backslashes are automatically normalized to forward slashes in JSON output.

### Command Execution
- **Windows**: `mvnw.cmd` with `shell: true`
- **Unix**: `./mvnw` with `shell: false`

### Maven Wrapper Detection
Scripts automatically detect and use the Maven wrapper (`mvnw`/`mvnw.cmd`). If not found, they fall back to system Maven.

### Signal Handling
All long-running scripts (run, test) properly handle:
- `SIGINT` (Ctrl+C)
- `SIGTERM` (kill command)

## Integration with npm

The scripts are integrated into `package.json` for convenience:

```json
{
  "scripts": {
    "install": "node tools/install.cjs",
    "dev": "node tools/run.cjs",
    "lint": "node tools/lint.cjs",
    "test": "node tools/test.cjs",
    "test:native": "node tools/test.cjs --native"
  }
}
```

## CI/CD Integration

These scripts are designed for use in CI/CD pipelines:

### GitHub Actions Example
```yaml
- name: Setup environment
  run: node tools/install.cjs

- name: Run linting
  run: node tools/lint.cjs

- name: Run tests
  run: node tools/test.cjs
```

### Linting in CI
The `lint.cjs` script outputs JSON, making it easy to parse in CI:

```bash
# Get JSON output
output=$(node tools/lint.cjs)

# Parse with jq
issues=$(echo "$output" | jq 'length')

if [ "$issues" -gt 0 ]; then
  echo "$output" | jq '.[] | "\(.path):\(.line) - \(.message)"'
  exit 1
fi
```

## Troubleshooting

### Java not found
```
✗ Java is not installed or not in PATH
Please install Java 21 or higher from: https://adoptium.net/
```

**Solution**: Install Java 21+ and ensure it's in your PATH.

### Maven wrapper not executable (Unix)
```
✗ Maven wrapper found but failed to execute
Try running: chmod +x mvnw
```

**Solution**:
```bash
chmod +x mvnw
```

### Compilation fails
```
✗ Compilation failed
```

**Solution**: Check Maven output for specific errors. Common issues:
- Missing dependencies (try deleting `~/.m2/repository`)
- Java version mismatch (ensure Java 21+)
- Syntax errors in code

### Dependencies not updating

The install script caches dependency resolution based on pom.xml timestamps. If you need to force a refresh:

```bash
# Delete target directory
rm -rf target modules/*/target

# Re-run install
node tools/install.cjs
```

## Best Practices

1. **Always run install first**: While other scripts call install automatically, you can run it standalone to verify environment setup.

2. **Use npm scripts**: The npm script aliases are more memorable than the full paths.

3. **Check lint before commit**: Run `npm run lint` before committing to catch formatting issues early.

4. **Run tests locally**: Run `npm test` before pushing to catch test failures locally.

5. **Use format script**: Run `npm run format` (or `./mvnw spotless:apply`) to automatically fix formatting issues.

## Technical Details

### Dependency Resolution Strategy

The install script uses a smart caching strategy:

1. Checks if `target/` directory exists
2. Compares modification times of `pom.xml` files with `target/`
3. Skips dependency resolution if nothing changed
4. Runs `mvn dependency:resolve` for main and test dependencies
5. Compiles project to verify everything works

### Parallel Execution

For performance, scripts use Maven's parallel build capability:
- `-T 1C`: Uses 1 thread per CPU core
- Significantly faster on multi-core systems
- Safe for dependency resolution and compilation

### Error Handling

All scripts follow consistent error handling:
- Check prerequisites before proceeding
- Use proper exit codes (0 = success, non-zero = failure)
- Output errors to stderr, not stdout
- Provide actionable error messages

### JSON Output (lint.cjs)

The lint script outputs **only** valid JSON to stdout:
- Empty array `[]` if no issues
- Array of error objects if issues found
- All diagnostic messages go to stderr
- Compatible with JSON parsers in any language

## Extending the Scripts

These scripts can be extended for additional automation needs. Key extension points:

1. **Environment variables**: Scripts can read environment variables for configuration
2. **Command-line arguments**: Add argument parsing for additional options
3. **Hooks**: Add pre/post hooks for custom actions
4. **Additional linters**: Integrate other linting tools alongside Spotless

## Support

For issues with these scripts, check:
1. This documentation
2. Error messages (they include suggestions)
3. Project README.md
4. CLAUDE.md (project conventions)

## Version

These scripts were generated and optimized for:
- **Node.js**: 18.0.0+
- **Java**: 21+
- **Maven**: 3.8+
- **Quarkus**: 3.17.5

Last updated: 2026-01-08
