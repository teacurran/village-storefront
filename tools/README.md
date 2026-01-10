# Village Storefront Automation Scripts

This directory contains cross-platform Node.js automation scripts for the Village Storefront project (Java/Maven/Quarkus).

> **📖 For detailed documentation**, see [AUTOMATION_SCRIPTS.md](./AUTOMATION_SCRIPTS.md)

## Prerequisites

- **Node.js** 18.0.0+ (for running automation scripts)
- **Java 21** or higher (LTS)
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
3. Resolves all Maven dependencies (main and test)
4. Compiles the project

**Exit codes:**
- `0` - Success
- `1` - Error (missing prerequisites or build failure)

**Features:**
- **Idempotent**: Can be run multiple times safely
- **Smart caching**: Only updates dependencies when pom.xml changes
- **Multi-module aware**: Handles parent/child module structure
- **Cross-platform**: Automatic platform detection

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
2. Executes all tests with Maven verify lifecycle
3. Generates JaCoCo coverage reports (HTML and XML)
4. Validates coverage meets 80% threshold

**Exit codes:**
- `0` - All tests passed and coverage threshold met
- `Non-zero` - Tests failed or coverage below threshold

**Coverage report location:**
- Multi-module: `modules/core-platform/target/site/jacoco/index.html`
- Single module: `target/site/jacoco/index.html`

**Features:**
- **Parallel execution**: 1 thread per CPU core
- **Native support**: Use `--native` flag for native tests
- **Coverage enforcement**: 80% line and branch coverage (enforced by JaCoCo and SonarCloud)

---

## Cross-Platform Compatibility

All scripts are designed to work on:
- **Windows** (using `mvnw.cmd`)
- **macOS** (using `./mvnw`)
- **Linux** (using `./mvnw`)

Platform detection is automatic based on `process.platform`.

## npm Integration

These scripts are integrated with npm for convenience:

```bash
npm install    # Run install.cjs
npm run dev    # Run run.cjs
npm run lint   # Run lint.cjs
npm test       # Run test.cjs
npm run format # Apply code formatting
```

See `package.json` for all available scripts.

## Integration with Build Tools

These scripts can be integrated with:
- **CI/CD pipelines** (GitHub Actions, GitLab CI, etc.)
- **Git hooks** (pre-commit, pre-push)
- **IDE run configurations** (VS Code, IntelliJ)
- **npm scripts** (see package.json)

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
