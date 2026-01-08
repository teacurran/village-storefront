#!/usr/bin/env node
/**
 * Compares the current OpenAPI spec against a base ref and fails when breaking changes are introduced.
 */
const { execSync, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const yaml = require('yaml');

const repoRoot = process.cwd();
const specPath = path.join(repoRoot, 'api', 'v1', 'openapi.yaml');
const baseRef = process.env.OPENAPI_DIFF_BASE_REF || process.argv[2];

if (!baseRef) {
  console.log('Skipping OpenAPI diff check (no base ref provided).');
  process.exit(0);
}

let baseSpecRaw;
try {
  baseSpecRaw = execSync(`git show ${baseRef}:api/v1/openapi.yaml`, {
    encoding: 'utf8',
    stdio: ['pipe', 'pipe', 'pipe']
  });
} catch (error) {
  console.warn(`Unable to read spec from ${baseRef}:api/v1/openapi.yaml (${error.message}). Skipping diff.`);
  process.exit(0);
}

if (!fs.existsSync(specPath)) {
  console.error('Current OpenAPI spec not found at api/v1/openapi.yaml.');
  process.exit(1);
}
const headSpecRaw = fs.readFileSync(specPath, 'utf8');

const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'openapi-diff-'));
const baseJsonPath = path.join(tmpDir, 'base.json');
const headJsonPath = path.join(tmpDir, 'head.json');

try {
  fs.writeFileSync(baseJsonPath, JSON.stringify(yaml.parse(baseSpecRaw)));
  fs.writeFileSync(headJsonPath, JSON.stringify(yaml.parse(headSpecRaw)));
} catch (error) {
  console.error(`Failed to parse OpenAPI specs: ${error.message}`);
  process.exit(1);
}

const runDiff = (useLocal) =>
  spawnSync('npx', useLocal ? ['--no-install', 'openapi-diff', baseJsonPath, headJsonPath] : ['openapi-diff', baseJsonPath, headJsonPath], {
    stdio: 'inherit'
  });

let diffResult = runDiff(true);
if (diffResult.error && diffResult.error.code === 'ENOENT') {
  diffResult = runDiff(false);
}

if ((diffResult.status ?? 1) !== 0) {
  console.error('OpenAPI diff detected breaking changes.');
  process.exit(diffResult.status ?? 1);
}

console.log('OpenAPI diff check passed (no breaking changes detected).');
