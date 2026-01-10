#!/usr/bin/env node

/**
 * E2E Test Data Seeding Script
 * Seeds multi-tenant test data into the database via REST API
 *
 * Usage:
 *   npm run seed:e2e
 *   DATABASE_URL=... API_BASE_URL=... node seed-e2e-data.js
 *
 * Features:
 * - Idempotent (safe to run multiple times)
 * - Seeds via REST API for reliability
 * - Supports multi-tenant data
 * - Creates deterministic test data
 */

import fetch from 'node-fetch';
import { tenants, platformAdmin } from './tenants.js';

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';
const ADMIN_API_KEY = process.env.ADMIN_API_KEY || 'test-admin-key-12345';

/**
 * API client with authentication
 */
class APIClient {
  constructor(baseURL, tenantSubdomain = null) {
    this.baseURL = baseURL;
    this.tenantSubdomain = tenantSubdomain;
    this.accessToken = null;
  }

  async request(method, path, data = null, headers = {}) {
    const url = `${this.baseURL}${path}`;
    const requestHeaders = {
      'Content-Type': 'application/json',
      ...headers,
    };

    if (this.tenantSubdomain) {
      requestHeaders['X-Tenant-Subdomain'] = this.tenantSubdomain;
    }

    if (this.accessToken) {
      requestHeaders['Authorization'] = `Bearer ${this.accessToken}`;
    }

    const options = {
      method,
      headers: requestHeaders,
    };

    if (data && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
      options.body = JSON.stringify(data);
    }

    const response = await fetch(url, options);

    if (!response.ok && response.status !== 409) {
      // 409 Conflict is OK (already exists)
      const errorText = await response.text();
      throw new Error(
        `API request failed: ${method} ${path} - ${response.status} ${errorText}`
      );
    }

    if (response.status === 204) {
      return null;
    }

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      return await response.json();
    }

    return null;
  }

  async login(email, password) {
    const response = await this.request('POST', '/api/v1/auth/login', {
      email,
      password,
    });
    this.accessToken = response.accessToken;
    return response;
  }

  async get(path, headers = {}) {
    return this.request('GET', path, null, headers);
  }

  async post(path, data, headers = {}) {
    return this.request('POST', path, data, headers);
  }

  async put(path, data, headers = {}) {
    return this.request('PUT', path, data, headers);
  }

  async delete(path, headers = {}) {
    return this.request('DELETE', path, null, headers);
  }
}

/**
 * Seed platform admin account
 */
async function seedPlatformAdmin() {
  console.log('Seeding platform admin account...');
  const client = new APIClient(API_BASE_URL);

  try {
    await client.post('/api/v1/platform/admin/users', {
      email: platformAdmin.email,
      password: platformAdmin.password,
      firstName: platformAdmin.firstName,
      lastName: platformAdmin.lastName,
      role: 'PLATFORM_ADMIN',
    });
    console.log('✓ Platform admin created');
  } catch (error) {
    if (error.message.includes('409')) {
      console.log('✓ Platform admin already exists');
    } else {
      console.error('✗ Failed to create platform admin:', error.message);
    }
  }
}

/**
 * Seed tenant store configuration
 */
async function seedTenant(tenantKey, tenantData) {
  console.log(`\nSeeding tenant: ${tenantData.name} (${tenantData.subdomain})...`);
  const client = new APIClient(API_BASE_URL);

  try {
    // Create tenant store
    await client.post('/api/v1/platform/stores', {
      id: tenantData.id,
      subdomain: tenantData.subdomain,
      customDomain: tenantData.customDomain,
      name: tenantData.name,
      status: 'ACTIVE',
    });
    console.log(`✓ Tenant store created: ${tenantData.subdomain}`);
  } catch (error) {
    if (error.message.includes('409')) {
      console.log(`✓ Tenant store already exists: ${tenantData.subdomain}`);
    } else {
      console.error('✗ Failed to create tenant:', error.message);
      return; // Skip rest of tenant seeding if tenant creation fails
    }
  }

  // Switch to tenant context
  const tenantClient = new APIClient(API_BASE_URL, tenantData.subdomain);

  // Seed admin user
  await seedUser(tenantClient, tenantData.admin, 'ADMIN');

  // Seed customer user
  await seedUser(tenantClient, tenantData.customer, 'CUSTOMER');

  // Seed consignor user (if configured)
  if (tenantData.consignor) {
    await seedUser(tenantClient, tenantData.consignor, 'CONSIGNOR');
  }

  // Seed loyalty member (if configured)
  if (tenantData.loyaltyMember) {
    await seedUser(tenantClient, tenantData.loyaltyMember, 'CUSTOMER');
  }

  // Login as admin to seed catalog data
  await tenantClient.login(tenantData.admin.email, tenantData.admin.password);

  // Seed products
  for (const product of tenantData.products) {
    await seedProduct(tenantClient, product);
  }

  // Seed gift cards
  for (const giftCard of tenantData.giftCards) {
    await seedGiftCard(tenantClient, giftCard);
  }

  // Seed loyalty program
  if (tenantData.loyaltyProgram.enabled) {
    await seedLoyaltyProgram(tenantClient, tenantData.loyaltyProgram);

    // Enroll loyalty member and seed points
    if (tenantData.loyaltyMember) {
      await seedLoyaltyMembership(tenantClient, tenantData.loyaltyMember);
    }
  }

  // Seed OAuth clients
  for (const oauthClient of tenantData.oauthClients) {
    await seedOAuthClient(tenantClient, oauthClient);
  }
}

/**
 * Seed user account
 */
async function seedUser(client, user, role) {
  try {
    await client.post('/api/v1/admin/users', {
      email: user.email,
      password: user.password,
      firstName: user.firstName,
      lastName: user.lastName,
      role: role,
    });
    console.log(`  ✓ User created: ${user.email} (${role})`);
  } catch (error) {
    if (error.message.includes('409')) {
      console.log(`  ✓ User already exists: ${user.email}`);
    } else {
      console.error(`  ✗ Failed to create user ${user.email}:`, error.message);
    }
  }
}

/**
 * Seed product with variants
 */
async function seedProduct(client, product) {
  try {
    const productData = {
      id: product.id,
      name: product.name,
      description: product.description,
      price: product.price,
      sku: product.sku,
      inventory: product.inventory,
      status: 'PUBLISHED',
      variants: product.variants || [],
    };

    await client.post('/api/v1/admin/products', productData);
    console.log(`  ✓ Product created: ${product.name} (${product.sku})`);
  } catch (error) {
    if (error.message.includes('409')) {
      console.log(`  ✓ Product already exists: ${product.sku}`);
    } else {
      console.error(`  ✗ Failed to create product ${product.sku}:`, error.message);
    }
  }
}

/**
 * Seed gift card
 */
async function seedGiftCard(client, giftCard) {
  try {
    await client.post('/api/v1/admin/giftcards', {
      code: giftCard.code,
      balance: giftCard.balance,
      initialBalance: giftCard.initialBalance,
      status: 'ACTIVE',
    });
    console.log(`  ✓ Gift card created: ${giftCard.code} ($${giftCard.balance})`);
  } catch (error) {
    if (error.message.includes('409')) {
      console.log(`  ✓ Gift card already exists: ${giftCard.code}`);
    } else {
      console.error(`  ✗ Failed to create gift card ${giftCard.code}:`, error.message);
    }
  }
}

/**
 * Seed loyalty program configuration
 */
async function seedLoyaltyProgram(client, loyaltyConfig) {
  try {
    await client.post('/api/v1/admin/loyalty/config', {
      enabled: loyaltyConfig.enabled,
      pointsPerDollar: loyaltyConfig.pointsPerDollar,
      redemptionRate: loyaltyConfig.redemptionRate,
    });
    console.log(`  ✓ Loyalty program configured (${loyaltyConfig.pointsPerDollar} pts/$)`);
  } catch (error) {
    console.error('  ✗ Failed to configure loyalty program:', error.message);
  }
}

/**
 * Seed loyalty membership with initial points
 */
async function seedLoyaltyMembership(client, loyaltyMember) {
  try {
    // Enroll member
    await client.post('/api/v1/loyalty/enroll', {
      email: loyaltyMember.email,
    });

    // Grant initial points for testing (e.g., 1000 points)
    await client.post('/api/v1/admin/loyalty/adjust-points', {
      email: loyaltyMember.email,
      points: 1000,
      reason: 'Initial test balance',
    });

    console.log(`  ✓ Loyalty member enrolled: ${loyaltyMember.email} (1000 pts)`);
  } catch (error) {
    if (error.message.includes('409')) {
      console.log(`  ✓ Loyalty member already enrolled: ${loyaltyMember.email}`);
    } else {
      console.error(`  ✗ Failed to enroll loyalty member:`, error.message);
    }
  }
}

/**
 * Seed OAuth client
 */
async function seedOAuthClient(client, oauthClient) {
  try {
    await client.post('/api/v1/admin/oauth/clients', {
      clientId: oauthClient.clientId,
      clientSecret: oauthClient.clientSecret,
      name: oauthClient.name,
      scopes: oauthClient.scopes,
      grantTypes: ['client_credentials'],
    });
    console.log(`  ✓ OAuth client created: ${oauthClient.clientId}`);
  } catch (error) {
    if (error.message.includes('409')) {
      console.log(`  ✓ OAuth client already exists: ${oauthClient.clientId}`);
    } else {
      console.error(`  ✗ Failed to create OAuth client:`, error.message);
    }
  }
}

/**
 * Main seeding function
 */
async function main() {
  console.log('===================================');
  console.log('E2E Test Data Seeding');
  console.log('===================================');
  console.log(`API Base URL: ${API_BASE_URL}`);
  console.log('');

  try {
    // Seed platform admin
    await seedPlatformAdmin();

    // Seed each tenant
    for (const [tenantKey, tenantData] of Object.entries(tenants)) {
      await seedTenant(tenantKey, tenantData);
    }

    console.log('\n===================================');
    console.log('✓ Seeding completed successfully');
    console.log('===================================\n');

    console.log('Test accounts:');
    console.log('  Platform Admin:');
    console.log(`    Email: ${platformAdmin.email}`);
    console.log(`    Password: ${platformAdmin.password}\n`);

    for (const [tenantKey, tenantData] of Object.entries(tenants)) {
      console.log(`  ${tenantData.name} (${tenantData.subdomain}):`);
      console.log(`    Admin: ${tenantData.admin.email} / ${tenantData.admin.password}`);
      console.log(`    Customer: ${tenantData.customer.email} / ${tenantData.customer.password}`);
      if (tenantData.consignor) {
        console.log(`    Consignor: ${tenantData.consignor.email} / ${tenantData.consignor.password}`);
      }
      if (tenantData.loyaltyMember) {
        console.log(`    Loyalty: ${tenantData.loyaltyMember.email} / ${tenantData.loyaltyMember.password}`);
      }
      console.log('');
    }
  } catch (error) {
    console.error('\n✗ Seeding failed:', error);
    process.exit(1);
  }
}

// Run main function
main();
