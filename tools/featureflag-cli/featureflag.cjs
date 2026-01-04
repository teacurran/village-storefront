#!/usr/bin/env node
/**
 * Feature Flag CLI Tool
 *
 * Command-line interface for managing feature flags and governance.
 *
 * Usage:
 *   node featureflag.cjs list [--stale]
 *   node featureflag.cjs get <flag-id>
 *   node featureflag.cjs set <flag-id> --enabled=true|false [--reason="..."]
 *   node featureflag.cjs review <flag-id> --reason="..."
 *   node featureflag.cjs stale-report
 *   node featureflag.cjs history <flag-id>
 *
 * Environment variables:
 *   API_BASE_URL - Base URL of the API (default: http://localhost:8080)
 *   API_TOKEN - Platform admin authentication token (required)
 *
 * Exit codes:
 *   0 - Success
 *   1 - Error (invalid arguments, network failure, API error)
 *
 * References:
 *   - Task I5.T7: Feature flag governance CLI
 *   - Architecture: 05_Rationale_and_Future.md Section 4.1.12
 */

const https = require('https');
const http = require('http');

// Configuration
const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
const API_TOKEN = process.env.API_TOKEN;

if (!API_TOKEN && process.argv[2] !== 'help') {
    console.error('Error: API_TOKEN environment variable is required');
    console.error('Usage: export API_TOKEN=<your-platform-admin-token>');
    process.exit(1);
}

// Parse command-line arguments
const args = process.argv.slice(2);
const command = args[0];

/**
 * Make HTTP request to API.
 */
function apiRequest(method, path, body = null) {
    return new Promise((resolve, reject) => {
        const url = new URL(path, API_BASE_URL);
        const isHttps = url.protocol === 'https:';
        const client = isHttps ? https : http;

        const options = {
            method: method,
            headers: {
                'Authorization': `Bearer ${API_TOKEN}`,
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            }
        };

        const req = client.request(url, options, (res) => {
            let data = '';

            res.on('data', (chunk) => {
                data += chunk;
            });

            res.on('end', () => {
                if (res.statusCode >= 200 && res.statusCode < 300) {
                    try {
                        resolve(JSON.parse(data));
                    } catch (e) {
                        resolve(data);
                    }
                } else {
                    reject(new Error(`API error ${res.statusCode}: ${data}`));
                }
            });
        });

        req.on('error', (e) => {
            reject(e);
        });

        if (body) {
            req.write(JSON.stringify(body));
        }

        req.end();
    });
}

/**
 * Parse --key=value arguments.
 */
function parseArgs(args) {
    const parsed = {};
    for (const arg of args) {
        if (arg.startsWith('--')) {
            const [key, value] = arg.substring(2).split('=');
            parsed[key] = value === undefined ? true : value;
        } else {
            parsed.positional = parsed.positional || [];
            parsed.positional.push(arg);
        }
    }
    return parsed;
}

/**
 * Format flag for display.
 */
function formatFlag(flag) {
    const staleIndicator = flag.stale ? ' [STALE]' : '';
    const enabledIndicator = flag.enabled ? '✓' : '✗';

    return `
ID:          ${flag.id}
Key:         ${flag.flagKey}${staleIndicator}
Enabled:     ${enabledIndicator} ${flag.enabled}
Owner:       ${flag.owner}
Risk Level:  ${flag.riskLevel}
Description: ${flag.description || 'N/A'}
Review:      Every ${flag.reviewCadenceDays || 'N/A'} days (last: ${flag.lastReviewedAt || 'never'})
Expiry:      ${flag.expiryDate || 'No expiry set'}
${flag.stale ? `Stale Reason: ${flag.staleReason}` : ''}
Created:     ${flag.createdAt}
Updated:     ${flag.updatedAt}
`.trim();
}

/**
 * Format table for list view.
 */
function formatTable(flags) {
    const headers = ['KEY', 'ENABLED', 'OWNER', 'RISK', 'STALE'];
    const rows = flags.map(f => [
        f.flagKey,
        f.enabled ? '✓' : '✗',
        f.owner,
        f.riskLevel,
        f.stale ? 'YES' : 'no'
    ]);

    const colWidths = headers.map((h, i) =>
        Math.max(h.length, ...rows.map(r => String(r[i]).length))
    );

    const formatRow = (row) => row.map((cell, i) =>
        String(cell).padEnd(colWidths[i])
    ).join('  ');

    console.log(formatRow(headers));
    console.log(colWidths.map(w => '-'.repeat(w)).join('  '));
    rows.forEach(row => console.log(formatRow(row)));
}

// Command handlers
async function listFlags(options) {
    const queryParams = options.stale ? '?stale_only=true' : '';
    const flags = await apiRequest('GET', `/api/v1/platform/feature-flags${queryParams}`);

    if (flags.length === 0) {
        console.log('No feature flags found');
        return;
    }

    formatTable(flags);
    console.log(`\nTotal: ${flags.length} flag(s)`);
}

async function getFlag(flagId) {
    const flag = await apiRequest('GET', `/api/v1/platform/feature-flags/${flagId}`);
    console.log(formatFlag(flag));
}

async function setFlag(flagId, options) {
    const body = {};

    if (options.enabled !== undefined) {
        body.enabled = options.enabled === 'true';
    }
    if (options.owner) {
        body.owner = options.owner;
    }
    if (options['risk-level']) {
        body.riskLevel = options['risk-level'];
    }
    if (options['review-days']) {
        body.reviewCadenceDays = parseInt(options['review-days']);
    }
    if (options.expiry) {
        body.expiryDate = options.expiry;
    }
    if (options.reason) {
        body.reason = options.reason;
    }

    const flag = await apiRequest('PUT', `/api/v1/platform/feature-flags/${flagId}`, body);
    console.log('Flag updated successfully:');
    console.log(formatFlag(flag));
}

async function reviewFlag(flagId, options) {
    if (!options.reason) {
        console.error('Error: --reason is required for review command');
        process.exit(1);
    }

    const body = {
        markReviewed: true,
        reason: options.reason
    };

    const flag = await apiRequest('PUT', `/api/v1/platform/feature-flags/${flagId}`, body);
    console.log('Flag reviewed successfully:');
    console.log(formatFlag(flag));
}

async function staleReport() {
    const report = await apiRequest('GET', '/api/v1/platform/feature-flags/stale-report');

    console.log('=== Stale Feature Flag Report ===');
    console.log(`Generated: ${report.generatedAt}\n`);

    console.log(`Expired Flags: ${report.expiredCount}`);
    if (report.expiredFlags.length > 0) {
        formatTable(report.expiredFlags);
        console.log();
    }

    console.log(`Needs Review: ${report.needsReviewCount}`);
    if (report.needsReviewFlags.length > 0) {
        formatTable(report.needsReviewFlags);
    }
}

async function flagHistory(flagId) {
    const history = await apiRequest('GET', `/api/v1/platform/feature-flags/${flagId}/history`);

    if (history.length === 0) {
        console.log('No history found for this flag');
        return;
    }

    console.log('=== Flag Change History ===\n');
    history.forEach(entry => {
        const timestamp = entry.occurredAt || entry.executedAt || entry.createdAt;
        const action = entry.action || entry.commandType || 'unknown_action';
        console.log(`${timestamp} - ${action}`);
        if (entry.reason) {
            console.log(`  Reason: ${entry.reason}`);
        }
        if (entry.metadata) {
            const metadataValue = typeof entry.metadata === 'string' ? entry.metadata : JSON.stringify(entry.metadata);
            console.log(`  Metadata: ${metadataValue}`);
        }
        console.log();
    });
}

function showHelp() {
    console.log(`
Feature Flag CLI Tool

Usage:
  featureflag.cjs list [--stale]
    List all feature flags (or only stale flags)

  featureflag.cjs get <flag-id>
    Get details for a specific flag

  featureflag.cjs set <flag-id> [options]
    Update flag configuration
    Options:
      --enabled=true|false
      --owner=<email>
      --risk-level=LOW|MEDIUM|HIGH|CRITICAL
      --review-days=<number>
      --expiry=<ISO-8601-date>
      --reason="<justification>"

  featureflag.cjs review <flag-id> --reason="<justification>"
    Mark flag as reviewed (updates lastReviewedAt)

  featureflag.cjs stale-report
    Generate report of stale flags

  featureflag.cjs history <flag-id>
    Show change history for a flag

Environment:
  API_BASE_URL - API endpoint (default: http://localhost:8080)
  API_TOKEN    - Platform admin token (required)

Exit Codes:
  0 - Success
  1 - Error

Examples:
  export API_TOKEN=eyJhbGc...
  featureflag.cjs list --stale
  featureflag.cjs set abc-123 --enabled=false --reason="Emergency disable"
  featureflag.cjs review abc-123 --reason="Reviewed Q1 2026"
`);
}

// Main command router
async function main() {
    try {
        const options = parseArgs(args.slice(1));

        switch (command) {
            case 'list':
                await listFlags(options);
                break;
            case 'get':
                if (!options.positional || options.positional.length === 0) {
                    console.error('Error: flag-id required');
                    process.exit(1);
                }
                await getFlag(options.positional[0]);
                break;
            case 'set':
                if (!options.positional || options.positional.length === 0) {
                    console.error('Error: flag-id required');
                    process.exit(1);
                }
                await setFlag(options.positional[0], options);
                break;
            case 'review':
                if (!options.positional || options.positional.length === 0) {
                    console.error('Error: flag-id required');
                    process.exit(1);
                }
                await reviewFlag(options.positional[0], options);
                break;
            case 'stale-report':
                await staleReport();
                break;
            case 'history':
                if (!options.positional || options.positional.length === 0) {
                    console.error('Error: flag-id required');
                    process.exit(1);
                }
                await flagHistory(options.positional[0]);
                break;
            case 'help':
            case undefined:
                showHelp();
                break;
            default:
                console.error(`Error: Unknown command '${command}'`);
                showHelp();
                process.exit(1);
        }
    } catch (error) {
        console.error('Error:', error.message);
        process.exit(1);
    }
}

main();
