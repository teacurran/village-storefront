# Village Storefront Automation Scripts

This directory contains cross-platform Node.js automation scripts for the Village Storefront project (Java/Maven/Quarkus).

## Prerequisites

- **Node.js** (any recent version)
- **Java 21** or higher
- **Maven** (Maven wrapper included in project)

## Scripts

### `install.cjs`

Environment setup and dependency installation script.

**Usage:**
```bash
node tools/install.cjs
```

**What it does:**
1. Checks Java 21+ is installed
2. Verifies Maven availability (wrapper or system)
3. Resolves all Maven dependencies
4. Compiles the project

**Exit codes:**
- `0` - Success
- `1` - Error (missing prerequisites or build failure)

**Idempotent:** Yes - can be run multiple times safely

---

### `run.cjs`

Runs the Quarkus development server with hot reload.

**Usage:**
```bash
node tools/run.cjs
```

**What it does:**
1. Runs `install.cjs` to ensure environment is ready
2. Starts Quarkus in development mode (`mvnw quarkus:dev`)
3. Enables hot reload for live coding
4. Handles graceful shutdown on Ctrl+C

**Exit codes:**
- `0` - Clean shutdown
- `1` - Error starting or running server

**Dev mode features:**
- Hot reload (code changes apply automatically)
- Dev UI available at http://localhost:8080/q/dev
- Continuous testing mode

---

### `lint.cjs`

Code formatting validation using Spotless.

**Usage:**
```bash
node tools/lint.cjs
```

**What it does:**
1. Silently runs `install.cjs` to ensure environment is ready
2. Executes Spotless check (`mvnw spotless:check`)
3. Outputs results in JSON format

**Output format:**
```json
[
  {
    "type": "formatting",
    "path": "src/main/java/Example.java",
    "obj": "",
    "message": "Code formatting violation detected by Spotless",
    "line": 0,
    "column": 0
  }
]
```

**Exit codes:**
- `0` - No issues found
- `Non-zero` - Issues found or error occurred

**Output:** JSON to stdout, errors to stderr

**To fix issues:**
```bash
./mvnw spotless:apply
```

---

### `test.cjs`

Runs all tests with code coverage reporting.

**Usage:**
```bash
node tools/test.cjs
```

**What it does:**
1. Runs `install.cjs` to ensure environment is ready
2. Executes all tests (`mvnw test`)
3. Generates JaCoCo coverage report
4. Checks against 80% coverage threshold (warning only)

**Exit codes:**
- `0` - All tests passed
- `Non-zero` - Tests failed or error occurred

**Coverage report location:**
- HTML: `target/site/jacoco/index.html`
- XML: `target/site/jacoco/jacoco.xml`

**Coverage threshold:** 80% line and branch coverage (enforced by SonarCloud)

---

## Cross-Platform Compatibility

All scripts are designed to work on:
- **Windows** (using `mvnw.cmd`)
- **macOS** (using `./mvnw`)
- **Linux** (using `./mvnw`)

Platform detection is automatic based on `process.platform`.

## Integration with Build Tools

These scripts can be integrated with:
- CI/CD pipelines
- Git hooks
- IDE run configurations
- npm scripts (if you add a package.json)

## Troubleshooting

### Java not found
Install Java 21+ from: https://adoptium.net/

### Maven not found
The project includes a Maven wrapper (`mvnw` / `mvnw.cmd`). If it's missing, install Maven from: https://maven.apache.org/download.cgi

### Compilation errors
Run `./mvnw clean compile` to get detailed error messages.

### Spotless formatting violations
Run `./mvnw spotless:apply` to automatically fix formatting issues.

## Additional Maven Commands

```bash
# Apply code formatting
./mvnw spotless:apply

# Build native image
./mvnw package -Pnative

# Build container image
./mvnw package -Pnative -Dquarkus.container-image.build=true

# Generate Kubernetes manifests
./mvnw package -Dquarkus.kubernetes.deploy=true

# Run database migrations (from migrations directory)
cd migrations && mvn migration:up -Dmigration.env=development
```
