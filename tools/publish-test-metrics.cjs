#!/usr/bin/env node
/**
 * Publish test metrics to Grafana (or any HTTP endpoint)
 *
 * Usage:
 *   node tools/publish-test-metrics.cjs --suite "playwright" --status "passed" --metrics-file target/playwright-metrics.json
 *   node tools/publish-test-metrics.cjs --suite "cypress" --status "failed" --metrics '{"passed":10,"failed":2}'
 */

const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');
const { URL } = require('node:url');

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      continue;
    }

    const key = arg.slice(2);
    const next = argv[i + 1];
    if (!next || next.startsWith('--')) {
      args[key] = true;
    } else {
      args[key] = next;
      i += 1;
    }
  }
  return args;
}

function loadMetrics(args) {
  if (args['metrics-file']) {
    const filePath = args['metrics-file'];
    if (!fs.existsSync(filePath)) {
      throw new Error(`Metrics file not found: ${filePath}`);
    }
    const raw = fs.readFileSync(filePath, 'utf8');
    return JSON.parse(raw);
  }

  if (args.metrics) {
    return JSON.parse(args.metrics);
  }

  return {};
}

function postJson(urlString, payload, apiKey) {
  return new Promise((resolve, reject) => {
    const target = new URL(urlString);
    const isHttps = target.protocol === 'https:';
    const body = JSON.stringify(payload);

    const options = {
      hostname: target.hostname,
      port: target.port || (isHttps ? 443 : 80),
      path: `${target.pathname}${target.search}`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
      },
    };

    if (apiKey) {
      options.headers.Authorization = `Bearer ${apiKey}`;
    }

    const client = isHttps ? https : http;
    const req = client.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => {
        data += chunk;
      });
      res.on('end', () => {
        if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) {
          resolve();
        } else {
          const message = data || `Request failed with status ${res.statusCode}`;
          reject(new Error(message));
        }
      });
    });

    req.on('error', (err) => {
      reject(err);
    });

    req.write(body);
    req.end();
  });
}

async function main() {
  const args = parseArgs(process.argv);
  const suite = args.suite || args.name;

  if (!suite) {
    console.error('Missing --suite argument');
    process.exit(1);
  }

  const status = args.status || 'unknown';
  const metrics = loadMetrics(args);

  const url = process.env.GRAFANA_PUSH_URL;
  const apiKey = process.env.GRAFANA_API_KEY;

  if (!url || !apiKey) {
    console.log(`[metrics] Grafana env not configured. Skipping publish for ${suite}.`);
    process.exit(0);
  }

  const payload = {
    suite,
    status,
    metrics,
    workflow: process.env.GITHUB_WORKFLOW || null,
    repository: process.env.GITHUB_REPOSITORY || null,
    runId: process.env.GITHUB_RUN_ID || null,
    sha: process.env.GITHUB_SHA || null,
    timestamp: new Date().toISOString(),
  };

  await postJson(url, payload, apiKey);
  console.log(`[metrics] Published metrics for suite: ${suite}`);
}

main().catch((err) => {
  console.error(`[metrics] Failed to publish metrics: ${err.message}`);
  process.exit(1);
});
