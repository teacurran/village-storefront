# VillageCompute Java Project Standards

## Overview

This document defines the standard technology stack and practices for Java web application projects at VillageCompute. These standards are derived from the village-calendar reference implementation and should be followed for all new Java projects.

## Build System

### Maven

All projects MUST use Maven as the build system.

#### Required POM Structure

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>villagecompute</groupId>
  <artifactId>project-name</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <compiler-plugin.version>3.13.0</compiler-plugin.version>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    <quarkus.platform.version>3.26.2</quarkus.platform.version>
  </properties>
  <!-- ... -->
</project>
```

#### Java Version

* Use Java 21 (LTS) as the minimum version
* Configure both `maven.compiler.release` and explicit source/target in compiler plugin

## Base Technology Stack

### Quarkus Framework

Quarkus is the standard framework for all Java web applications.

#### Required Dependencies

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.quarkus.platform</groupId>
      <artifactId>quarkus-bom</artifactId>
      <version>${quarkus.platform.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- Core -->
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-arc</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-jackson</artifactId>
  </dependency>

  <!-- Database -->
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-orm-panache</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-postgresql</artifactId>
  </dependency>

  <!-- Health & Metrics -->
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-health</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
  </dependency>
</dependencies>
```

### PostgreSQL Database

PostgreSQL is the standard database for all applications.

* Use PostGIS image when geographic data is needed: `postgis/postgis:17-3.4`
* Standard PostgreSQL for other projects: `postgres:17`

## Frontend Options

### Option 1: Qute Templates

Use Qute for server-rendered HTML pages with minimal JavaScript requirements.

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-qute</artifactId>
</dependency>
```

### Option 2: Vue.js with Quinoa

Use Vue.js with Quinoa for rich single-page applications.

#### Maven Configuration

```xml
<dependency>
  <groupId>io.quarkiverse.quinoa</groupId>
  <artifactId>quarkus-quinoa</artifactId>
  <version>2.6.2</version>
</dependency>
```

#### Frontend Stack

* **Framework**: Vue 3 with Composition API
* **Build Tool**: Vite
* **Type Safety**: TypeScript
* **State Management**: Pinia
* **UI Components**: PrimeVue with Tailwind CSS
* **Testing**: Playwright for E2E tests

#### package.json Template

```json
{
  "name": "project-frontend",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "lint": "eslint . --fix",
    "format": "prettier --write \"src/**/*.{js,jsx,ts,tsx,vue,css,scss,json,md}\"",
    "test:e2e": "playwright test"
  },
  "dependencies": {
    "vue": "^3.5.13",
    "vue-router": "^4.5.0",
    "pinia": "^3.0.3",
    "primevue": "^4.3.2",
    "tailwindcss": "^4.0.15"
  }
}
```

## Database Migrations

### MyBatis Migrations

All database schema changes MUST be managed through MyBatis Migrations.

#### Directory Structure

```
migrations/
├── pom.xml
├── README.md
└── src/main/resources/
    ├── environments/
    │   ├── development.properties
    │   ├── beta.properties
    │   └── production.properties
    └── scripts/
        ├── 001_initial_schema.sql
        ├── 002_add_feature.sql
        └── ...
```

#### migrations/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>villagecompute</groupId>
  <artifactId>project-migrations</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <migrations.plugin.version>1.2.0</migrations.plugin.version>
    <postgresql.driver.version>42.7.4</postgresql.driver.version>
    <migration.env>development</migration.env>
    <migration.path>${project.basedir}/src/main/resources</migration.path>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <version>${postgresql.driver.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.mybatis.maven</groupId>
        <artifactId>migrations-maven-plugin</artifactId>
        <version>${migrations.plugin.version}</version>
        <configuration>
          <repository>${migration.path}</repository>
          <environment>${migration.env}</environment>
        </configuration>
        <dependencies>
          <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>${postgresql.driver.version}</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
</project>
```

#### Migration Script Format

```sql
-- //
-- Description: Add user preferences table
-- //

CREATE TABLE user_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    preferences JSONB NOT NULL DEFAULT '{}',
    created TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- //@UNDO

DROP TABLE IF EXISTS user_preferences;
```

#### Running Migrations

```bash
# Check status
cd migrations && mvn migration:status -Dmigration.env=development

# Apply pending migrations
cd migrations && mvn migration:up -Dmigration.env=development

# Rollback last migration
cd migrations && mvn migration:down -Dmigration.env=development
```

## Local Development Environment

### docker-compose.yml

Every project MUST include a docker-compose.yml for local development.

#### Required Services

```yaml
version: "3.8"

services:
  app-db:
    image: postgres:17
    restart: always
    environment:
      POSTGRES_USER: appuser
      POSTGRES_PASSWORD: apppass
      POSTGRES_DB: appdb
    volumes:
      - app-db-data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: 'pg_isready -U appuser --dbname=appdb'
      interval: 10s
      timeout: 5s
      retries: 5

  # Email testing (local only)
  mailpit:
    image: axllent/mailpit:latest
    restart: unless-stopped
    ports:
      - "8025:8025"  # Web UI
      - "1025:1025"  # SMTP
    environment:
      MP_MAX_MESSAGES: 5000
      MP_SMTP_AUTH_ACCEPT_ANY: 1
      MP_SMTP_AUTH_ALLOW_INSECURE: 1

  # Distributed tracing
  jaeger:
    image: jaegertracing/all-in-one:1.57
    restart: unless-stopped
    ports:
      - "16686:16686"  # Web UI
      - "4317:4317"    # OTLP gRPC
    environment:
      COLLECTOR_OTLP_ENABLED: "true"

  # Database migrations
  migrations:
    image: maven:3.9-eclipse-temurin-21-alpine
    working_dir: /migrations
    depends_on:
      app-db:
        condition: service_healthy
    volumes:
      - ./migrations:/migrations
      - maven-repo:/root/.m2
    environment:
      MAVEN_OPTS: "-Dmigration.env=docker"
    entrypoint: ["mvn"]
    command: ["migration:up"]
    profiles:
      - tools

volumes:
  app-db-data:
  maven-repo:
```

## Code Formatting

### Spotless Maven Plugin

All projects MUST use Spotless for consistent code formatting.

#### Maven Configuration

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.43.0</version>
  <configuration>
    <java>
      <eclipse>
        <version>4.29</version>
        <file>${project.basedir}/eclipse-formatter.xml</file>
      </eclipse>
      <removeUnusedImports/>
      <importOrder>
        <order>java,javax,jakarta,org,com,villagecompute</order>
      </importOrder>
      <formatAnnotations/>
    </java>
    <pom>
      <sortPom>
        <expandEmptyElements>false</expandEmptyElements>
        <sortDependencies>scope,groupId,artifactId</sortDependencies>
        <sortPlugins>groupId,artifactId</sortPlugins>
      </sortPom>
    </pom>
  </configuration>
</plugin>
```

#### Import Organization

Imports MUST be organized in the following order:

1. `java.*`
2. `javax.*`
3. `jakarta.*`
4. `org.*`
5. `com.*`
6. `villagecompute.*`

Unused imports MUST be removed automatically by Spotless.

#### POM Formatting

POM files are automatically formatted with:

* Dependencies sorted by: scope, groupId, artifactId
* Plugins sorted by: groupId, artifactId
* Empty elements collapsed (not expanded)

#### Running Spotless

```bash
# Check formatting (CI/pre-commit)
./mvnw spotless:check

# Apply formatting fixes
./mvnw spotless:apply
```

### Eclipse Formatter Configuration

Include an `eclipse-formatter.xml` file in the project root with the following settings:

#### Core Settings

| Setting | Value |
|---------|-------|
| Line length | 120 characters |
| Tab character | Spaces (not tabs) |
| Tab/indent size | 4 spaces |
| Continuation indent | 8 spaces (2 units) |

#### Brace Positions (K&R Style)

All braces MUST be placed at the end of the line (K&R style):

* Type declarations
* Method declarations
* Constructor declarations
* Blocks (if, for, while, etc.)
* Switch statements
* Anonymous type declarations
* Enum declarations
* Annotation type declarations
* Record declarations
* Lambda bodies
* Array initializers

#### Blank Lines

| Location | Blank Lines |
|----------|-------------|
| Before package | 0 |
| After package | 1 |
| Before imports | 1 |
| After imports | 1 |
| Between type declarations | 1 |
| Before first class body declaration | 0 |
| After last class body declaration | 0 |
| Before member type | 1 |
| Before field | 0 |
| Before method | 1 |
| Maximum preserved empty lines | 1 |

#### Spacing Rules

* Space before opening parenthesis for control statements (`if`, `for`, `while`, `switch`, `try`, `catch`, `synchronized`)
* No space before opening parenthesis for method declarations and invocations
* Space before opening brace for all declarations
* Space after commas in method arguments and parameters
* No space before commas
* Space around binary and assignment operators

#### Wrapping Rules

* Wrap before binary operators (not after)
* Join wrapped lines when possible
* Join lines in comments
* Annotations on separate lines for types, methods, fields, and local variables
* Annotations on same line for parameters

#### New Lines

* No new line before `else`, `catch`, `finally`, or `while` (in do-while)
* Insert new line at end of file if missing
* Keep `else if` compact (no blank line between)

#### Comment Formatting

* Comment line length: 120 characters
* Format Javadoc, block, and line comments
* Indent parameter descriptions in Javadoc
* Indent root tags in Javadoc
* New line before root tags in Javadoc

#### Complete eclipse-formatter.xml

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<profiles version="21">
    <profile kind="CodeFormatterProfile" name="VillageCompute" version="21">
        <!-- Line length -->
        <setting id="org.eclipse.jdt.core.formatter.lineSplit" value="120"/>

        <!-- Indentation: 4 spaces -->
        <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="space"/>
        <setting id="org.eclipse.jdt.core.formatter.tabulation.size" value="4"/>
        <setting id="org.eclipse.jdt.core.formatter.indentation.size" value="4"/>
        <setting id="org.eclipse.jdt.core.formatter.continuation_indentation" value="2"/>
        <setting id="org.eclipse.jdt.core.formatter.continuation_indentation_for_array_initializer" value="2"/>

        <!-- Brace positions (K&R style - same line) -->
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_type_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_method_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_constructor_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_block" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_switch" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_anonymous_type_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_enum_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_enum_constant" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_annotation_type_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_record_declaration" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_record_constructor" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_lambda_body" value="end_of_line"/>
        <setting id="org.eclipse.jdt.core.formatter.brace_position_for_array_initializer" value="end_of_line"/>

        <!-- Blank lines -->
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_before_package" value="0"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_after_package" value="1"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_before_imports" value="1"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_after_imports" value="1"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_between_type_declarations" value="1"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_before_first_class_body_declaration" value="0"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_after_last_class_body_declaration" value="0"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_before_member_type" value="1"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_before_field" value="0"/>
        <setting id="org.eclipse.jdt.core.formatter.blank_lines_before_method" value="1"/>
        <setting id="org.eclipse.jdt.core.formatter.number_of_empty_lines_to_preserve" value="1"/>

        <!-- Spaces -->
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_method_declaration" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_method_invocation" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_if" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_for" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_while" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_switch" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_try" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_catch" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_paren_in_synchronized" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_brace_in_type_declaration" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_brace_in_method_declaration" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_opening_brace_in_block" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_after_comma_in_method_invocation_arguments" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_after_comma_in_method_declaration_parameters" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_comma_in_method_invocation_arguments" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_comma_in_method_declaration_parameters" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_after_binary_operator" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_binary_operator" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_after_assignment_operator" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_space_before_assignment_operator" value="insert"/>

        <!-- Wrapping -->
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_arguments_in_method_invocation" value="16"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_parameters_in_method_declaration" value="16"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_arguments_in_annotation" value="49"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_after_annotation_on_type" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_after_annotation_on_method" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_after_annotation_on_field" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_after_annotation_on_parameter" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_after_annotation_on_local_variable" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_throws_clause_in_method_declaration" value="16"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_binary_expression" value="16"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_conditional_expression" value="80"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_enum_constants" value="0"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_superclass_in_type_declaration" value="16"/>
        <setting id="org.eclipse.jdt.core.formatter.alignment_for_superinterfaces_in_type_declaration" value="16"/>
        <setting id="org.eclipse.jdt.core.formatter.wrap_before_binary_operator" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.wrap_before_or_operator_multicatch" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.join_wrapped_lines" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.join_lines_in_comments" value="true"/>

        <!-- Comments -->
        <setting id="org.eclipse.jdt.core.formatter.comment.line_length" value="120"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.format_javadoc_comments" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.format_block_comments" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.format_line_comments" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.format_header" value="false"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.indent_parameter_description" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.indent_root_tags" value="true"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.insert_new_line_before_root_tags" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.comment.new_lines_at_javadoc_boundaries" value="true"/>

        <!-- New lines -->
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_before_else_in_if_statement" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_before_catch_in_try_statement" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_before_finally_in_try_statement" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_before_while_in_do_statement" value="do not insert"/>
        <setting id="org.eclipse.jdt.core.formatter.insert_new_line_at_end_of_file_if_missing" value="insert"/>
        <setting id="org.eclipse.jdt.core.formatter.compact_else_if" value="true"/>

        <!-- Parentheses -->
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_method_declaration" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_method_invocation" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_if_while_statement" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_for_statement" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_switch_statement" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_try_clause" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_catch_clause" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_annotation" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_lambda_declaration" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_enum_constant_declaration" value="common_lines"/>
        <setting id="org.eclipse.jdt.core.formatter.parentheses_positions_in_record_declaration" value="common_lines"/>
    </profile>
</profiles>
```

### Git Pre-commit Hook

Create `.git/hooks/pre-commit`:

```bash
#!/bin/sh
# Pre-commit hook to fix imports and formatting with Spotless

STAGED_JAVA_FILES=$(git diff --cached --name-only --diff-filter=ACM | grep '\.java$')

if [ -z "$STAGED_JAVA_FILES" ]; then
    exit 0
fi

echo "Running Spotless to fix imports and formatting..."

./mvnw spotless:apply -q

for FILE in $STAGED_JAVA_FILES; do
    if [ -f "$FILE" ]; then
        git add "$FILE"
    fi
done

echo "Spotless formatting applied."
```

### EditorConfig

Include `.editorconfig` in the project root for IDE consistency. This ensures consistent formatting regardless of IDE:

```ini
# EditorConfig - https://editorconfig.org
root = true

# Default settings for all files
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

# Java files
[*.java]
indent_size = 4
max_line_length = 120

# XML files (pom.xml, etc.)
[*.xml]
indent_size = 2

# YAML files
[*.{yml,yaml}]
indent_size = 2

# JSON files
[*.json]
indent_size = 2

# Properties files
[*.properties]
indent_size = 4

# Markdown files
[*.md]
trim_trailing_whitespace = false

# Web files (Vue, TypeScript, JavaScript, CSS)
[*.{vue,ts,tsx,js,jsx,css,scss}]
indent_size = 2

# HTML files
[*.html]
indent_size = 2

# Shell scripts
[*.sh]
indent_size = 2

# Makefiles require tabs
[Makefile]
indent_style = tab

# Dockerfile
[Dockerfile*]
indent_size = 4
```

## Code Coverage

### JaCoCo Configuration

All projects MUST maintain 95% code coverage on all Java classes.

#### Coverage Requirements

* **Minimum Coverage**: 95% line and branch coverage on all Java classes
* **Branch Coverage Priority**: Special focus on testing all possible branches of user input, including:
  * Validation edge cases (empty strings, null values, boundary values)
  * Error handling paths (invalid input, malformed data, constraint violations)
  * Conditional logic based on user-provided data
  * All enum values and switch cases for user-controlled inputs
* **No Exceptions**: Coverage requirements apply to all production code, including services, resources, and utility classes

#### Maven Configuration

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>0.8.11</version>
  <executions>
    <execution>
      <id>prepare-agent</id>
      <goals>
        <goal>prepare-agent</goal>
      </goals>
      <configuration>
        <propertyName>surefire.jacoco.args</propertyName>
      </configuration>
    </execution>
    <execution>
      <id>report</id>
      <goals>
        <goal>report</goal>
      </goals>
      <phase>test</phase>
      <configuration>
        <formats>
          <format>XML</format>
          <format>HTML</format>
        </formats>
      </configuration>
    </execution>
  </executions>
</plugin>
```

#### Pre-commit Coverage Check

Before committing, run:

```bash
./mvnw test jacoco:report
```

Review `target/site/jacoco/index.html` to ensure 95% coverage on all Java classes.

## Code Quality

### SonarQube / SonarCloud

All projects MUST be configured for SonarQube analysis.

#### sonar-project.properties

```properties
sonar.projectKey=organization_project-name
sonar.organization=organization

sonar.projectName=project-name

sonar.sources=src/main/java,src/main/webui/src
sonar.tests=src/test/java

sonar.sourceEncoding=UTF-8

sonar.java.source=21
sonar.java.binaries=target/classes
sonar.java.test.binaries=target/test-classes

sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml

sonar.exclusions=**/target/**,**/node_modules/**,**/*.min.js,**/*.min.css
sonar.test.exclusions=**/test/**
```

#### Pre-commit Quality Check

Run SonarQube scanner before committing and fix any issues:

```bash
# Run tests with coverage
./mvnw test jacoco:report

# Run SonarQube scanner (requires sonar-scanner CLI or Maven plugin)
sonar-scanner
```

## Java Coding Standards

### Package Structure

```
src/main/java/villagecompute/projectname/
├── api/
│   ├── rest/         # REST resources
│   └── types/        # API DTOs (generated from OpenAPI)
├── config/           # Configuration classes
├── data/
│   ├── models/       # JPA entities
│   └── repositories/ # Data access layer
├── exceptions/       # Custom exceptions
├── integration/      # External service integrations
├── jobs/             # Background jobs and handlers
├── services/         # Business logic
└── util/             # Utilities
```

### Named Query Pattern

Use this pattern for all JPA named queries.  this code should be within the entity class:

```java
// 1. Define constant for query name (in the entity class)
public static final String QUERY_FIND_BY_EMAIL =
        "User.findByEmail";

// 2. NamedQuery annotation references the constant
@NamedQueries({
    @NamedQuery(
        name = User.QUERY_FIND_BY_EMAIL,
        query = "SELECT u FROM User u WHERE u.email = :email")
})
@Entity
public class User extends PanacheEntityBase {
    // ...

    // 3. Static finder method uses Panache find() with # prefix
    public static Optional<User> findByEmail(String email) {
        return find("#" + QUERY_FIND_BY_EMAIL,
                    Parameters.with("email", email))
               .firstResultOptional();
    }
}
```

Key points:

* Query name constant: `QUERY_` prefix, referenced in both annotation and finder
* Use `#` prefix with `find()` to invoke named queries
* Use `Parameters.with()` for type-safe parameter binding
* Use `JOIN FETCH` to eagerly load relationships and avoid N+1 queries
* Return `Optional` via `firstResultOptional()` for single results
* Fetching code should be within the Entity Class.
* All classes should extend PanacheEntityBase

### Application Exceptions

Never throw raw `RuntimeException`. Create domain-specific exceptions:

```java
// Base exception (extends RuntimeException - no throws declaration needed)
public class ApplicationException extends RuntimeException {
    public ApplicationException(String message) {
        super(message);
    }
    public ApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}

// Domain-specific exceptions
public class EmailException extends ApplicationException { ... }
public class PaymentException extends ApplicationException { ... }
public class ValidationException extends ApplicationException { ... }
```

Key points:

* All exceptions extend `RuntimeException` so they don't require throws declarations
* Always include the cause exception when wrapping: `new XxxException("message", cause)`
* Add new exception types to the `exceptions` package as needed

### Service Layer

```java
@ApplicationScoped
public class UserService {

    @Transactional
    public User createUser(CreateUserRequest request) {
        // Validation
        if (User.existsByEmail(request.email())) {
            throw new ValidationException("Email already exists");
        }

        // Business logic
        User user = new User();
        user.email = request.email();
        user.persist();

        return user;
    }
}
```

### JSON Marshalling

All JSON data MUST be marshalled in and out of defined Type classes. Direct `JsonNode` traversal and tree-based APIs are prohibited.

#### Requirements

* All JSON type classes MUST end with the `Type` suffix (e.g., `UserType`, `OrderPayloadType`, `ConfigType`)
* Use Jackson `ObjectMapper.readValue()` to deserialize JSON strings into Type classes
* Use Jackson `ObjectMapper.writeValueAsString()` to serialize Type classes to JSON strings
* NEVER use tree-based APIs: `readTree()`, `treeToValue()`, `valueToTree()`, `convertValue()`
* NEVER traverse `JsonNode` directly using methods like `get()`, `path()`, `findValue()`, etc.
* Type classes should be placed in the `api/types/` package or a domain-specific `types` subpackage

#### Why Typed JSON?

* **Type Safety**: Compile-time checks catch errors before runtime
* **Refactoring**: IDE refactoring tools work with typed fields
* **Documentation**: Type classes serve as self-documenting schemas
* **Validation**: Bean validation annotations can be applied to fields
* **Maintainability**: Changes to JSON structure are explicit and traceable

#### Correct Pattern

```java
// Define a Type class for the JSON payload
public class EmailJobPayloadType {
    public String to;
    public String subject;
    public String htmlBody;
    public String templateName;
    public Map<String, Object> templateData;
}

@Inject
ObjectMapper objectMapper;

// Serialize Type class to JSON string
public String toJson(EmailJobPayloadType payload) throws JsonProcessingException {
    return objectMapper.writeValueAsString(payload);
}

// Deserialize JSON string to Type class
public EmailJobPayloadType fromJson(String jsonString) throws JsonProcessingException {
    return objectMapper.readValue(jsonString, EmailJobPayloadType.class);
}

// Usage example
public void processPayload(String jsonString) throws JsonProcessingException {
    EmailJobPayloadType payload = objectMapper.readValue(jsonString, EmailJobPayloadType.class);

    // Use typed fields
    sendEmail(payload.to, payload.subject, payload.htmlBody);
}
```

#### Prohibited Patterns

```java
// DO NOT DO THIS - tree-based APIs
JsonNode node = objectMapper.readTree(jsonString);           // PROHIBITED
MyType obj = objectMapper.treeToValue(node, MyType.class);   // PROHIBITED
JsonNode node = objectMapper.valueToTree(myObject);          // PROHIBITED
OtherType other = objectMapper.convertValue(obj, OtherType.class); // PROHIBITED

// DO NOT DO THIS - direct JsonNode traversal
public void processPayload(JsonNode json) {
    String to = json.get("to").asText();           // PROHIBITED
    String subject = json.path("subject").asText(); // PROHIBITED
    JsonNode data = json.findValue("templateData"); // PROHIBITED

    if (json.has("htmlBody")) {                     // PROHIBITED
        // ...
    }
}
```

#### Records for Immutable Types

Prefer Java records for immutable JSON types:

```java
public record WebhookEventType(
    String eventId,
    String eventType,
    Instant timestamp,
    WebhookPayloadType payload
) {}

public record WebhookPayloadType(
    UUID resourceId,
    String action,
    Map<String, String> metadata
) {}
```

## API Design

### OpenAPI Spec-First REST API

All REST APIs MUST follow a spec-first approach using OpenAPI 3.0. This provides strongly-typed request/response objects generated from the API specification, similar to the contract-first approach used with XSD/JAXB.

#### Why Spec-First?

* **Contract-First**: Define the API before implementation, ensuring clear interface contracts
* **Strongly Typed**: Generated Java types provide compile-time safety
* **Documentation**: OpenAPI specs serve as living documentation
* **Client Generation**: Clients can be generated for any language from the same spec
* **Validation**: Request/response validation happens automatically

#### Directory Structure

```
src/main/resources/
└── openapi/
    ├── api.yaml           # Main API specification
    └── schemas/
        ├── user.yaml      # User-related schemas
        ├── order.yaml     # Order-related schemas
        └── common.yaml    # Shared schemas
```

#### Maven Configuration

```xml
<plugin>
  <groupId>org.openapitools</groupId>
  <artifactId>openapi-generator-maven-plugin</artifactId>
  <version>7.10.0</version>
  <executions>
    <execution>
      <id>generate-api-types</id>
      <goals>
        <goal>generate</goal>
      </goals>
      <configuration>
        <inputSpec>${project.basedir}/src/main/resources/openapi/api.yaml</inputSpec>
        <generatorName>jaxrs-spec</generatorName>
        <output>${project.build.directory}/generated-sources/openapi</output>
        <apiPackage>villagecompute.projectname.api.generated</apiPackage>
        <modelPackage>villagecompute.projectname.api.types</modelPackage>
        <configOptions>
          <dateLibrary>java8</dateLibrary>
          <useJakartaEe>true</useJakartaEe>
          <interfaceOnly>true</interfaceOnly>
          <useBeanValidation>true</useBeanValidation>
          <performBeanValidation>true</performBeanValidation>
          <serializationLibrary>jackson</serializationLibrary>
        </configOptions>
        <generateApiTests>false</generateApiTests>
        <generateModelTests>false</generateModelTests>
      </configuration>
    </execution>
  </executions>
</plugin>
```

#### OpenAPI Specification Example

```yaml
openapi: 3.0.3
info:
  title: Project API
  version: 1.0.0
  description: REST API for Project

servers:
  - url: /api/v1

paths:
  /users:
    get:
      operationId: listUsers
      summary: List all users
      tags: [Users]
      parameters:
        - name: page
          in: query
          schema:
            type: integer
            default: 0
        - name: size
          in: query
          schema:
            type: integer
            default: 20
      responses:
        '200':
          description: List of users
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserListResponse'

    post:
      operationId: createUser
      summary: Create a new user
      tags: [Users]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateUserRequest'
      responses:
        '201':
          description: User created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserResponse'
        '400':
          description: Invalid request
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

  /users/{id}:
    get:
      operationId: getUser
      summary: Get user by ID
      tags: [Users]
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
            format: uuid
      responses:
        '200':
          description: User found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserResponse'
        '404':
          description: User not found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

components:
  schemas:
    CreateUserRequest:
      type: object
      required:
        - email
        - name
      properties:
        email:
          type: string
          format: email
          maxLength: 255
        name:
          type: string
          minLength: 1
          maxLength: 100

    UserResponse:
      type: object
      required:
        - id
        - email
        - name
        - createdAt
      properties:
        id:
          type: string
          format: uuid
        email:
          type: string
        name:
          type: string
        createdAt:
          type: string
          format: date-time

    UserListResponse:
      type: object
      required:
        - items
        - totalCount
        - page
        - pageSize
      properties:
        items:
          type: array
          items:
            $ref: '#/components/schemas/UserResponse'
        totalCount:
          type: integer
          format: int64
        page:
          type: integer
        pageSize:
          type: integer

    ErrorResponse:
      type: object
      required:
        - code
        - message
      properties:
        code:
          type: string
        message:
          type: string
        details:
          type: object
          additionalProperties: true
```

#### REST Resource Implementation

Implement the generated interface in your REST resource:

```java
@Path("/api/v1/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource implements UsersApi {

    @Inject
    UserService userService;

    @Override
    public Response listUsers(Integer page, Integer size) {
        var users = userService.listUsers(page, size);
        var response = new UserListResponse()
            .items(users.stream().map(this::toResponse).toList())
            .totalCount(userService.countUsers())
            .page(page)
            .pageSize(size);
        return Response.ok(response).build();
    }

    @Override
    public Response createUser(CreateUserRequest request) {
        var user = userService.createUser(request);
        return Response.status(Response.Status.CREATED)
            .entity(toResponse(user))
            .build();
    }

    @Override
    public Response getUser(UUID id) {
        return userService.findById(id)
            .map(u -> Response.ok(toResponse(u)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(new ErrorResponse()
                    .code("NOT_FOUND")
                    .message("User not found"))
                .build());
    }

    private UserResponse toResponse(User user) {
        return new UserResponse()
            .id(user.id)
            .email(user.email)
            .name(user.name)
            .createdAt(user.created.atOffset(ZoneOffset.UTC));
    }
}
```

#### File Downloads and Binary Responses

For endpoints that return files or binary data (not covered by OpenAPI generation):

```java
@Path("/api/v1/orders")
public class OrderResource {

    @GET
    @Path("/{orderNumber}/items/{itemId}/pdf")
    @Produces("application/pdf")
    public Response downloadOrderItemPDF(
            @PathParam("orderNumber") String orderNumber,
            @PathParam("itemId") UUID itemId) {
        // ... generate PDF
        return Response.ok(pdfBytes)
            .type("application/pdf")
            .header("Content-Disposition",
                "attachment; filename=\"" + filename + "\"")
            .build();
    }
}
```

## Background Job Processing

### Delayed Job Pattern

Applications requiring background processing MUST implement the Delayed Job pattern for reliable, retryable job execution.

#### Job Entity

```java
@Entity
@Table(name = "delayed_jobs")
public class DelayedJob extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public DelayedJobQueue queue;

    @Column(name = "handler_class", nullable = false)
    public String handlerClass;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public JsonNode payload;

    @Column(name = "run_at", nullable = false)
    public Instant runAt;

    @Column(name = "locked_at")
    public Instant lockedAt;

    @Column(name = "locked_by")
    public String lockedBy;

    @Column(nullable = false)
    public int attempts = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "failed_at")
    public Instant failedAt;

    public Instant created;
    public Instant updated;
}
```

#### Job Queue with Priorities

```java
public enum DelayedJobQueue {
    CRITICAL(1),    // Payment confirmations, security alerts
    HIGH(2),        // Order confirmations, password resets
    DEFAULT(3),     // General notifications
    LOW(4),         // Marketing emails, reports
    BULK(5);        // Mass operations, data exports

    private final int priority;

    DelayedJobQueue(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}
```

#### Job Handler Interface

```java
public interface DelayedJobHandler<T> {

    /**
     * Execute the job with the given payload.
     * @throws Exception if job fails (will be retried based on retry strategy)
     */
    void execute(T payload) throws Exception;

    /**
     * Deserialize the JSON payload to the handler's type.
     */
    T deserializePayload(JsonNode payload);

    /**
     * Get the queue this handler processes jobs from.
     */
    DelayedJobQueue getQueue();
}
```

#### Retry Strategy

Implement exponential backoff with configurable limits:

```java
@ApplicationScoped
public class DelayedJobService {

    private static final int MAX_ATTEMPTS = 25;
    private static final Duration BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_DELAY = Duration.ofDays(7);

    public Duration calculateNextRunDelay(int attempts) {
        // Formula: 5 seconds + N^4 seconds
        // Attempt 1: 5s + 1s = 6s
        // Attempt 5: 5s + 625s = ~10 min
        // Attempt 10: 5s + 10000s = ~2.8 hours
        // Attempt 15: 5s + 50625s = ~14 hours
        long delaySeconds = BASE_DELAY.toSeconds() + (long) Math.pow(attempts, 4);
        Duration delay = Duration.ofSeconds(delaySeconds);
        return delay.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : delay;
    }

    @Transactional
    public void enqueue(DelayedJobHandler<?> handler, Object payload) {
        enqueue(handler, payload, Instant.now());
    }

    @Transactional
    public void enqueue(DelayedJobHandler<?> handler, Object payload, Instant runAt) {
        DelayedJob job = new DelayedJob();
        job.queue = handler.getQueue();
        job.handlerClass = handler.getClass().getName();
        job.payload = objectMapper.valueToTree(payload);
        job.runAt = runAt;
        job.persist();

        // Trigger immediate processing via EventBus
        eventBus.publish("delayed-job-ready", job.id);
    }
}
```

#### Job Processing with Locking

```java
@ApplicationScoped
public class DelayedJobProcessor {

    @Inject
    DelayedJobService jobService;

    @Scheduled(every = "30s")
    @Transactional
    public void processPendingJobs() {
        // Process jobs in priority order
        for (DelayedJobQueue queue : DelayedJobQueue.values()) {
            processQueue(queue);
        }
    }

    private void processQueue(DelayedJobQueue queue) {
        String lockId = UUID.randomUUID().toString();

        // Atomically lock a batch of jobs
        int locked = DelayedJob.update(
            "lockedAt = ?1, lockedBy = ?2 " +
            "WHERE queue = ?3 AND lockedAt IS NULL AND runAt <= ?4 " +
            "AND failedAt IS NULL",
            Instant.now(), lockId, queue, Instant.now());

        if (locked == 0) return;

        // Process locked jobs
        List<DelayedJob> jobs = DelayedJob.find(
            "lockedBy = ?1 ORDER BY runAt", lockId).list();

        for (DelayedJob job : jobs) {
            processJob(job);
        }
    }
}
```

## Email Service

### Email Service with Domain Filtering

Applications that send email MUST implement domain filtering to prevent accidental sends to real users in non-production environments.

#### Configuration

```properties
# application.properties

# Production profile sends to all addresses
%prod.email.safe-test-domains=

# Non-production profiles restrict to safe domains
email.safe-test-domains=yourcompany.com,test.com,example.com,mailinator.com
```

#### Email Service Implementation

```java
@ApplicationScoped
public class EmailService {

    private static final Logger LOG = Logger.getLogger(EmailService.class);

    @ConfigProperty(name = "email.safe-test-domains", defaultValue = "")
    String safeTestDomains;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String profile;

    @Inject
    Mailer mailer;

    /**
     * Send an email, respecting domain filtering in non-production environments.
     *
     * @return true if email was sent, false if filtered
     */
    public boolean sendEmail(String to, String subject, String htmlBody) {
        if (!isEmailDomainSafe(to)) {
            LOG.warnf("Email to %s blocked - domain not in safe list for %s profile",
                to, profile);
            return false;
        }

        Mail mail = Mail.withHtml(to, subject, htmlBody);
        mailer.send(mail);
        LOG.infof("Email sent to %s: %s", to, subject);
        return true;
    }

    /**
     * Check if an email address is safe to send to in the current environment.
     */
    public boolean isEmailDomainSafe(String email) {
        // Production sends to everyone
        if ("prod".equals(profile) || safeTestDomains.isBlank()) {
            return true;
        }

        String domain = email.substring(email.lastIndexOf("@") + 1).toLowerCase();
        List<String> safeDomains = Arrays.stream(safeTestDomains.split(","))
            .map(String::trim)
            .map(String::toLowerCase)
            .toList();

        return safeDomains.contains(domain);
    }

    /**
     * Get list of safe domains for the current environment.
     * Useful for UI to show which domains will receive emails.
     */
    public List<String> getSafeTestDomains() {
        if (safeTestDomains.isBlank()) {
            return List.of();
        }
        return Arrays.stream(safeTestDomains.split(","))
            .map(String::trim)
            .toList();
    }
}
```

#### Email Job Handler

Combine with the Delayed Job pattern for reliable email delivery:

```java
@ApplicationScoped
public class EmailJobHandler implements DelayedJobHandler<EmailJobPayload> {

    @Inject
    EmailService emailService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public void execute(EmailJobPayload payload) throws Exception {
        boolean sent = emailService.sendEmail(
            payload.to(),
            payload.subject(),
            payload.htmlBody()
        );

        if (!sent) {
            // Don't retry if domain was filtered
            throw new EmailFilteredException(
                "Email to " + payload.to() + " filtered by domain policy");
        }
    }

    @Override
    public EmailJobPayload deserializePayload(JsonNode payload) {
        return objectMapper.treeToValue(payload, EmailJobPayload.class);
    }

    @Override
    public DelayedJobQueue getQueue() {
        return DelayedJobQueue.HIGH;
    }
}

public record EmailJobPayload(
    String to,
    String subject,
    String htmlBody,
    String templateName,
    Map<String, Object> templateData
) {}
```

## Testing

### Required Test Dependencies

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-junit5</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-junit5-mockito</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.rest-assured</groupId>
  <artifactId>rest-assured</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-jacoco</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-jdbc-h2</artifactId>
  <scope>test</scope>
</dependency>
```

### Test Structure

```java
@QuarkusTest
public class UserServiceTest {

    @Inject
    UserService userService;

    @Test
    @Transactional
    void testCreateUser_Success() {
        // Given
        CreateUserRequest request = new CreateUserRequest("test@example.com");

        // When
        User result = userService.createUser(request);

        // Then
        assertNotNull(result.id);
        assertEquals("test@example.com", result.email);
    }

    @Test
    void testCreateUser_DuplicateEmail_ThrowsException() {
        // ...
    }
}
```

## Observability

### OpenTelemetry

Configure OpenTelemetry for distributed tracing:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

### Health Checks

Include health endpoints for load balancer integration:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-health</artifactId>
</dependency>
```

## Appendix: Suggestions (Optional Patterns)

> **Note:** The following patterns are observed in the reference implementation and may be valuable, but are not strictly required. Consider adopting them based on project needs.

### Container Image Building with Jib

For projects deploying to Kubernetes or container platforms, Jib provides build-time container image creation without requiring Docker:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-container-image-jib</artifactId>
</dependency>
```

> **Note:** This dependency is available in the reference implementation but is not actively configured. Additional configuration is required to enable Jib builds.

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.1 | 2026-01-01 | Replaced GraphQL with OpenAPI spec-first REST API design. Moved Delayed Job and Email Service to required sections. |
| 1.0 | 2026-01-01 | Initial document based on village-calendar reference implementation |
