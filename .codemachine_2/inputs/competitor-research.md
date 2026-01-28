# Competitor Research: Ecommerce Platform Features

## Platforms Analyzed

1. **Spree Commerce** - Open-source, multi-tenant, Ruby on Rails
2. **ConsignCloud** - Consignment-specific POS and inventory
3. **Gumroad** - Digital products for creators
4. **Squarespace Commerce** - Design-focused website builder + commerce
5. **Shopify** - Market leader, full-featured
6. **Medusa.js** - Headless, developer-first, Node.js

---

## Feature Comparison Matrix

### Multi-Tenancy & Store Management

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Multi-store from single admin | ✅ | ❌ | ❌ | ❌ | ✅ (Plus) | ✅ |
| Multi-tenant SaaS mode | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Custom domains | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| White-label/branding | ✅ | ❌ | Limited | ✅ | ✅ | ✅ |
| Multi-currency | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Multi-language | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |

### Product Catalog

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Unlimited products | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Product variants | ✅ | ✅ | ❌ | ✅ | ✅ (2000/product) | ✅ |
| Digital products | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Physical products | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Subscriptions | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Services/bookings | ❌ | ❌ | ❌ | ✅ | Via app | ❌ |
| Bundles/kits | ✅ | ❌ | ❌ | ❌ | Via app | ✅ |
| Gift cards | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Product scheduling | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Bulk import/export | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Custom attributes | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| SEO metadata | ✅ | ❌ | Limited | ✅ | ✅ | ✅ |

### Inventory Management

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Stock tracking | ✅ | ✅ | N/A | ✅ | ✅ | ✅ |
| Multi-location inventory | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Low-stock alerts | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Stock transfers | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Inventory adjustments | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Barcode/SKU management | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Item expiration tracking | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |

### Consignment-Specific (ConsignCloud Focus)

| Feature | ConsignCloud | Others |
|---------|--------------|--------|
| Consignor management | ✅ | ❌ |
| Automatic balance tracking | ✅ | ❌ |
| Consignor portal (web/mobile) | ✅ | ❌ |
| Commission rate per consignor | ✅ | ❌ |
| Consignor email notifications | ✅ | ❌ |
| Automated consignor payouts | ✅ | ❌ |
| Batch inventory intake | ✅ | ❌ |
| Item aging/days-in-store | ✅ | ❌ |
| Expiration by category | ✅ | ❌ |
| Consignor statements/reports | ✅ | ❌ |

### Shopping & Checkout

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Persistent cart | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Guest checkout | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| One-page checkout | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Customizable checkout | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Abandoned cart recovery | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Discount codes | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Automatic discounts | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Tax calculation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Address validation | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |

### Payments

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Stripe | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| PayPal | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Square | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Native payments | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| Stripe Connect (multi-vendor) | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Apple Pay/Google Pay | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Crypto payments | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Refunds | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Partial refunds | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |

### Orders & Fulfillment

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Order management | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Order status tracking | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Shipping rate calculation | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Label generation | ❌ | ❌ | ❌ | ✅ | ✅ | Via plugin |
| Shipment tracking | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Returns/RMA management | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Dropshipping support | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Split shipments | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |

### Marketing & Growth

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Email marketing integration | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Email broadcasts | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |
| Discount/promo engine | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Loyalty/rewards | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Referral program | ❌ | ❌ | ❌ | ❌ | Via app | ❌ |
| Affiliate system | ❌ | ❌ | ✅ | ❌ | Via app | ❌ |
| SEO tools | ✅ | ❌ | Limited | ✅ | ✅ | ✅ |
| Social selling (FB, IG) | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ |

### Customer Management

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Customer accounts | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Customer groups/segments | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Customer-specific pricing | ✅ | ❌ | ❌ | ❌ | ✅ (B2B) | ✅ |
| Saved addresses | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Order history | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Wishlists | ✅ | ❌ | ❌ | ❌ | Via app | ✅ |

### B2B Features

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Wholesale pricing | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Customer organizations | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Purchase orders | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Quote requests | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Net payment terms | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Minimum order quantities | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |

### Analytics & Reporting

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Sales reports | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Product performance | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Customer insights | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Inventory reports | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Custom reports | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Real-time analytics | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Consignor reports | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |

### Admin & Staff

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| Role-based permissions | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Staff accounts | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Activity logs/audit trail | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Bulk operations | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Keyboard shortcuts | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |

### Technical & Integration

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| REST API | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| GraphQL API | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Webhooks | ✅ | ❌ | ✅ | ✅ | ✅ | ✅ |
| Plugin/extension system | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Headless mode | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ |
| Mobile app | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| POS integration | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |

### AI & Automation (2025 Focus)

| Feature | Spree | ConsignCloud | Gumroad | Squarespace | Shopify | Medusa |
|---------|-------|--------------|---------|-------------|---------|--------|
| AI store builder | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| AI product descriptions | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ |
| AI-generated themes | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Automated workflows | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ |
| ChatGPT integration | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |

---

## Sources

- [Spree Commerce](https://spreecommerce.org/)
- [Spree 5 Multi-Store Management](https://spreecommerce.org/spree-5-open-source-ecommerce-multi-store-management/)
- [ConsignCloud Features](https://consigncloud.com/features)
- [ConsignCloud Key Features Guide](https://consigncloud.com/blog/8-key-features-your-consignment-software-should-have)
- [Gumroad Features](https://gumroad.com/features)
- [Gumroad 2025 Review](https://medium.com/@RiseLogan/gumroad-in-2025-fees-features-and-better-alternatives-fef48cecb31d)
- [Squarespace Ecommerce Review](https://tech.co/website-builders/squarespace-ecommerce-review)
- [Squarespace 2025 Updates](https://www.squareko.com/blog/squarespace-2025-updates-new-features-for-design-commerce-amp-automation)
- [Shopify Summer 2025 Editions](https://www.shopify.com/editions/summer2025)
- [Shopify Features Guide](https://litextension.com/blog/shopify-features/)
- [Medusa.js Overview](https://medusajs.com/)
- [Medusa.js Guide](https://www.linearloop.io/blog/medusa-js-headless-ecommerce-guide)
