/**
 * Multi-tenant test fixtures
 * Defines test data for 3 tenants with complete user accounts, products, and configurations
 */

export interface UserCredentials {
  email: string;
  password: string;
  firstName?: string;
  lastName?: string;
}

export interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
  sku: string;
  inventory: number;
  variants?: ProductVariant[];
}

export interface ProductVariant {
  id: string;
  name: string;
  price: number;
  sku: string;
  inventory: number;
  attributes: Record<string, string>;
}

export interface GiftCard {
  code: string;
  balance: number;
  initialBalance: number;
}

export interface LoyaltyConfig {
  enabled: boolean;
  pointsPerDollar: number;
  redemptionRate: number; // dollars per 100 points
}

export interface OAuthClient {
  clientId: string;
  clientSecret: string;
  name: string;
  scopes: string[];
}

export interface Tenant {
  id: string;
  subdomain: string;
  customDomain?: string;
  name: string;
  admin: UserCredentials;
  customer: UserCredentials;
  consignor?: UserCredentials;
  loyaltyMember?: UserCredentials;
  products: Product[];
  giftCards: GiftCard[];
  loyaltyProgram: LoyaltyConfig;
  oauthClients: OAuthClient[];
}

/**
 * Test tenant fixtures
 */
export const tenants: Record<string, Tenant> = {
  tenantA: {
    id: 'tenant-a-001',
    subdomain: 'tenant-a.test.local',
    name: 'Tenant A Store',
    admin: {
      email: 'admin@tenant-a.com',
      password: 'AdminPass123!',
      firstName: 'Alice',
      lastName: 'Admin',
    },
    customer: {
      email: 'customer@tenant-a.com',
      password: 'CustomerPass123!',
      firstName: 'Charlie',
      lastName: 'Customer',
    },
    consignor: {
      email: 'consignor@tenant-a.com',
      password: 'ConsignorPass123!',
      firstName: 'Carl',
      lastName: 'Consignor',
    },
    loyaltyMember: {
      email: 'loyalty@tenant-a.com',
      password: 'LoyaltyPass123!',
      firstName: 'Larry',
      lastName: 'Loyal',
    },
    products: [
      {
        id: 'prod-a-001',
        name: 'Tenant A Premium T-Shirt',
        description: 'High quality cotton t-shirt',
        price: 29.99,
        sku: 'TA-TSHIRT-001',
        inventory: 100,
        variants: [
          {
            id: 'var-a-001-s-red',
            name: 'Small / Red',
            price: 29.99,
            sku: 'TA-TSHIRT-001-S-RED',
            inventory: 25,
            attributes: { size: 'S', color: 'Red' },
          },
          {
            id: 'var-a-001-m-blue',
            name: 'Medium / Blue',
            price: 29.99,
            sku: 'TA-TSHIRT-001-M-BLUE',
            inventory: 30,
            attributes: { size: 'M', color: 'Blue' },
          },
        ],
      },
      {
        id: 'prod-a-002',
        name: 'Tenant A Deluxe Jeans',
        description: 'Classic denim jeans',
        price: 79.99,
        sku: 'TA-JEANS-001',
        inventory: 50,
      },
      {
        id: 'prod-a-003',
        name: 'Tenant A Sneakers',
        description: 'Comfortable running shoes',
        price: 99.99,
        sku: 'TA-SHOES-001',
        inventory: 30,
      },
    ],
    giftCards: [
      {
        code: 'GIFT-A-100',
        balance: 100.0,
        initialBalance: 100.0,
      },
      {
        code: 'GIFT-A-50',
        balance: 50.0,
        initialBalance: 50.0,
      },
    ],
    loyaltyProgram: {
      enabled: true,
      pointsPerDollar: 10,
      redemptionRate: 1.0, // $1 per 100 points
    },
    oauthClients: [
      {
        clientId: 'test-headless-client-a',
        clientSecret: 'test-secret-a-12345',
        name: 'Headless API Client A',
        scopes: ['catalog:read', 'cart:write', 'orders:read', 'orders:create'],
      },
    ],
  },

  tenantB: {
    id: 'tenant-b-001',
    subdomain: 'tenant-b.test.local',
    name: 'Tenant B Store',
    admin: {
      email: 'admin@tenant-b.com',
      password: 'AdminPass456!',
      firstName: 'Bob',
      lastName: 'Boss',
    },
    customer: {
      email: 'customer@tenant-b.com',
      password: 'CustomerPass456!',
      firstName: 'David',
      lastName: 'Buyer',
    },
    consignor: {
      email: 'consignor@tenant-b.com',
      password: 'ConsignorPass456!',
      firstName: 'Cindy',
      lastName: 'Seller',
    },
    loyaltyMember: {
      email: 'loyalty@tenant-b.com',
      password: 'LoyaltyPass456!',
      firstName: 'Lisa',
      lastName: 'Member',
    },
    products: [
      {
        id: 'prod-b-001',
        name: 'Tenant B Laptop Bag',
        description: 'Durable laptop carrying case',
        price: 49.99,
        sku: 'TB-BAG-001',
        inventory: 75,
      },
      {
        id: 'prod-b-002',
        name: 'Tenant B Wireless Mouse',
        description: 'Ergonomic wireless mouse',
        price: 24.99,
        sku: 'TB-MOUSE-001',
        inventory: 150,
      },
      {
        id: 'prod-b-003',
        name: 'Tenant B Keyboard',
        description: 'Mechanical gaming keyboard',
        price: 129.99,
        sku: 'TB-KEYBOARD-001',
        inventory: 40,
      },
    ],
    giftCards: [
      {
        code: 'GIFT-B-75',
        balance: 75.0,
        initialBalance: 75.0,
      },
    ],
    loyaltyProgram: {
      enabled: true,
      pointsPerDollar: 5,
      redemptionRate: 0.5, // $0.50 per 100 points
    },
    oauthClients: [
      {
        clientId: 'test-headless-client-b',
        clientSecret: 'test-secret-b-67890',
        name: 'Headless API Client B',
        scopes: ['catalog:read', 'cart:write', 'orders:read', 'orders:create'],
      },
    ],
  },

  tenantC: {
    id: 'tenant-c-001',
    subdomain: 'tenant-c.test.local',
    customDomain: 'custom-store.example.com',
    name: 'Tenant C Boutique',
    admin: {
      email: 'admin@tenant-c.com',
      password: 'AdminPass789!',
      firstName: 'Carol',
      lastName: 'Chief',
    },
    customer: {
      email: 'customer@tenant-c.com',
      password: 'CustomerPass789!',
      firstName: 'Emily',
      lastName: 'Shopper',
    },
    products: [
      {
        id: 'prod-c-001',
        name: 'Tenant C Artisan Coffee',
        description: 'Premium roasted coffee beans',
        price: 18.99,
        sku: 'TC-COFFEE-001',
        inventory: 200,
      },
      {
        id: 'prod-c-002',
        name: 'Tenant C Tea Set',
        description: 'Complete tea brewing set',
        price: 45.00,
        sku: 'TC-TEA-001',
        inventory: 60,
      },
    ],
    giftCards: [
      {
        code: 'GIFT-C-25',
        balance: 25.0,
        initialBalance: 25.0,
      },
    ],
    loyaltyProgram: {
      enabled: false,
      pointsPerDollar: 0,
      redemptionRate: 0,
    },
    oauthClients: [],
  },
};

/**
 * Platform admin credentials (has access to all tenants)
 */
export const platformAdmin: UserCredentials = {
  email: 'platform@villagecompute.com',
  password: 'PlatformAdmin123!',
  firstName: 'Platform',
  lastName: 'Administrator',
};

/**
 * Get tenant by subdomain
 */
export function getTenantBySubdomain(subdomain: string): Tenant | undefined {
  return Object.values(tenants).find((t) => t.subdomain === subdomain);
}

/**
 * Get base URL for tenant (with subdomain or custom domain)
 */
export function getTenantBaseUrl(
  tenant: Tenant,
  baseUrl: string = 'http://localhost:8080'
): string {
  if (tenant.customDomain) {
    return `http://${tenant.customDomain}`;
  }
  // For local testing, subdomain resolution via /etc/hosts
  return `http://${tenant.subdomain}:8080`;
}
