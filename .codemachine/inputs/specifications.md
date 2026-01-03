# Village Storefront - Project Specifications

## Overview

Village Storefront is a **SaaS multi-tenant ecommerce platform** that enables merchants to create and manage their own online stores. The platform is designed for small-to-medium businesses, including those with consignment-based inventory models. It combines the flexibility of platforms like Spree and Medusa with the consignment-specific features of ConsignCloud.

## Business Model

- **Multi-tenant SaaS**: Multiple merchants operate independent stores on a shared platform
- **Subdomain access**: Each store is accessible at `{storename}.{platform-domain}.com`
- **Custom domains**: Merchants can configure their own domains to point to their store
- **Consignment support**: Full consignment vendor management with commission tracking and automated payouts

## Technology Stack (Mandated)

Per VillageCompute Java Project Standards (see `docs/java-project-standards.adoc`):

### Backend
- **Runtime**: Java 21 + Quarkus framework
- **Native compilation**: GraalVM for native executables
- **Database**: PostgreSQL 17
- **Build**: Maven with Spotless formatting, JaCoCo coverage (80% required, enforced by SonarCloud)
- **API**: OpenAPI 3.0 spec-first REST API design
- **Migrations**: MyBatis Migrations
- **Payments**: Stripe (including Stripe Connect for platform fees)
- **Email**: Quarkus Mailer with domain filtering for non-production environments
- **Object Storage**: AWS SDK S3 client (compatible with Cloudflare R2)
- **Media Processing**:
  - Images: Java ImageIO + Thumbnailator for resizing/compression
  - Video: FFmpeg (via process execution) for transcoding

### Frontend (Customer-Facing Storefront)
- **Templating**: Qute templates for all customer-facing server-rendered HTML
- **JavaScript**: PrimeUI components for interactive elements (cart, checkout)
- **Styling**: Tailwind CSS
- **Routes**: All paths except `/admin/*` are rendered with Qute
- **Headless API**: REST endpoints for cart status, product data (for static site integration)

### Frontend (Admin Dashboard - `/admin/*` only)
- **Framework**: Vue.js 3 + Vite + TypeScript (via Quinoa)
- **UI Components**: PrimeVue
- **Styling**: Tailwind CSS
- **Routes**: All `/admin/*` paths served by Vue.js SPA
- **Users**: Store owners, staff, consignment vendors, and platform super-users

### Deployment
- **Container**: GraalVM native image in minimal Docker container
- **Orchestration**: Kubernetes (k3s target)
- **K8s manifests**: Generated via Quarkus Kubernetes extension
- **CI/CD**: GitHub Actions with native build

---

## Core Features

### 1. Multi-Tenancy & Store Management

#### Tenant Isolation Architecture
- **Database strategy**: Shared database with `tenant_id` discriminator column on all tenant-scoped tables
- **Row-Level Security**: PostgreSQL RLS policies enforce tenant isolation at database layer
- **Application-layer enforcement**: Defense-in-depth via Panache query filters

#### Tenant Resolution (Request Filter Pattern)
- **TenantContext**: `@RequestScoped` CDI bean holding current tenant ID
  ```java
  @RequestScoped
  public class TenantContext {
      private UUID tenantId;
      private Store store;
      // getters/setters
  }
  ```
- **TenantFilter**: `@Provider` JAX-RS `ContainerRequestFilter` executes before all requests
  - Extracts tenant from Host header subdomain (e.g., `acme.storefront.com` → `acme`)
  - Resolves subdomain to Store entity, populates TenantContext
  - For custom domains: lookup domain in `custom_domains` table
  - Returns 404 if tenant not found (invalid subdomain/domain)
- **Service injection**: All services `@Inject TenantContext` for tenant-aware operations
- **Panache integration**: Base repository class automatically applies `tenant_id` filter to all queries

#### Store Features
- **Store creation**: Merchants sign up and create a store with unique subdomain
- **Custom domains**: Merchants can add custom domains with automatic SSL via Let's Encrypt (ACME HTTP-01 challenge)
- **Store settings**: Business info, branding (logo, colors, fonts), policies
- **Tenant isolation**: All data strictly scoped to tenant; no cross-tenant data leakage
- **Store suspension/deletion**: Platform admin can manage store lifecycle
- **CORS configuration**: Enable cross-origin requests for subdomain-based access

### 2. User Authentication & Accounts

- **Merchant accounts**: Store owners with full admin access
- **Staff accounts**: Store employees with role-based permissions
  - **Roles**: Owner, Admin, Manager, Staff (with customizable permissions)
- **Customer accounts**: Shoppers can register, save addresses, view order history
- **Guest checkout**: Customers can purchase without creating an account
- **Social login**: Optional OAuth with Google, Facebook, Apple

#### Session Management
- **JWT tokens**: Stateless authentication with short-lived access tokens + refresh tokens
- **Session activity logging**: All sessions written to database with:
  - Login timestamp, IP address, user agent
  - Last activity timestamp
  - Logout or expiration
  - Device/browser fingerprinting
- **Active session management**: Users can view and revoke active sessions

#### Platform Admin (SaaS Super-Users)
- **Platform admin accounts**: Super-users who manage the entire SaaS platform
- **Capabilities**:
  - View all stores and their status
  - Suspend/unsuspend stores
  - Access platform-wide analytics
  - Customer service tools across all stores
- **Impersonation**:
  - Impersonate any store's admin/staff to troubleshoot
  - Impersonate any store's customers for support
  - All impersonation is logged with:
    - Who impersonated whom
    - Timestamp and duration
    - Actions taken during impersonation
    - Reason/ticket reference (required field)
  - Visual indicator shown during impersonation ("Acting as X")
  - Exit impersonation returns to admin session

#### Session & Activity Reports
- **Store admin reports**:
  - Customer login activity (frequency, devices, locations)
  - Staff login history and session duration
  - Failed login attempts
- **Platform admin reports**:
  - Impersonation audit log
  - Cross-store login patterns
  - Suspicious activity detection
  - Customer service session history

### 3. Product Catalog

#### Product Types
- **Physical products**: Traditional inventory with shipping
- **Digital products**: Downloadable files with secure delivery after purchase
- **Subscriptions**: Recurring billing (weekly, monthly, yearly, custom intervals)
- **Services/Bookings**: Appointments, classes, consultations with calendar integration

#### Product Features
- **Unlimited products** per store
- **Product variants**: Shopify-level support
  - Unlimited option types (Size, Color, Material, etc.)
  - Up to 2,000 variants per product
  - Per-variant SKU, pricing, images, inventory
- **Categories**: Hierarchical product categories with unlimited depth
- **Collections**: Curated product groups (manual or rule-based)
- **Product visibility**: Draft, Active, Scheduled, Archived states
- **Product scheduling**: Set future publish/unpublish dates
- **Bulk import/export**: CSV/Excel for mass product management
- **Custom attributes**: Store-defined fields for product metadata
- **SEO metadata**: Title, description, URL slug per product

#### Media Management
- **Supported formats**:
  - **Images**: JPEG, PNG, WebP, GIF (uploaded in any format)
  - **Video**: MP4, MOV, WebM (uploaded in any format)
- **Object storage**: Configurable S3-compatible storage (Cloudflare R2 default)
  - All original uploads stored permanently
  - Processed variants stored alongside originals
  - Tenant-isolated storage paths
- **Image processing**:
  - Automatic recompression to WebP for web delivery
  - Multiple size variants generated:
    - Thumbnail (150px)
    - Small (300px)
    - Medium (600px)
    - Large (1200px)
    - Original (preserved)
  - Aspect ratio preserved, longest edge sized
  - EXIF data stripped for privacy
  - Lazy generation: create on first request, cache permanently
- **Video processing**:
  - Transcode to H.264/MP4 for universal playback
  - Generate HLS segments for adaptive streaming (optional)
  - Extract poster frame as thumbnail image
  - Compress to configurable quality/bitrate targets
- **Processing modes**:
  - **Background job**: Large uploads queued for async processing
  - **On-demand**: Variants generated on first request if not cached
  - **Results cached**: Processed media saved to object storage, never reprocessed
- **CDN integration**: Signed URLs with configurable expiration for private content
- **Upload limits**: Configurable per-tenant (default: 50MB images, 500MB video)

### 4. Inventory Management

- **Stock tracking**: Real-time quantity tracking per variant
- **Multi-location inventory**: Track stock across warehouses, stores, suppliers
- **Stock transfers**: Move inventory between locations with transfer records
- **Low-stock alerts**: Configurable thresholds with email notifications
- **Inventory adjustments**: Manual adjustments with reason codes and audit trail
- **Item aging/expiration**: Track days-in-store, set expiration policies by category
- **Barcode management**: Generate, print, and scan barcodes (Code 128, QR)
- **Inventory valuation**: Track cost and calculate margins

### 5. Consignment Management (Full ConsignCloud Parity)

- **Consignor registration**: Add vendors with contact info, tax details
- **Commission rates**: Per-consignor or per-category commission percentages
- **Product assignment**: Link products/variants to consignor with cost basis
- **Balance tracking**: Automatic calculation as items sell
- **Consignor portal**: Web-based portal where vendors can:
  - View current balance and pending payouts
  - See item status (in-store, sold, expired, returned)
  - Review sales history and commission statements
  - Update contact information
- **Batch inventory intake**: Streamlined entry for receiving consignment batches
  - Auto-generate purchase orders
  - Bulk pricing and categorization
- **Aging reports**: Track how long items have been in store
- **Expiration policies**: Auto-expire items after configurable periods
- **Automated payouts**: Integration with payment service for vendor payments
- **Consignor notifications**: Email on item receipt, sale, expiration, payout
- **Payout reports**: Generate statements for consignor payments

### 6. Shopping Cart & Checkout

- **Persistent cart**: Saved to database for logged-in users
- **Guest cart**: Session-based with optional email capture
- **Cart operations**: Add, update quantity, remove, save for later
- **One-page checkout**: Streamlined single-page flow
  1. Cart review with quantity adjustments
  2. Customer info (guest email or login)
  3. Shipping address with validation
  4. Shipping method selection
  5. Payment (Stripe Elements)
  6. Order confirmation
- **Address validation**: Integration with postal service APIs
- **Shipping calculator**: Real-time rates in cart
- **Discount codes**: Apply at checkout
- **Gift cards & store credit**: Redeem as payment method
- **Order notes**: Customer can add notes to order

### 7. Orders & Fulfillment

#### Order Management
- **Order dashboard**: View, search, filter by status/date/customer/product
- **Order statuses**: Pending Payment, Paid, Processing, Partially Shipped, Shipped, Delivered, Cancelled, Refunded
- **Order editing**: Modify orders before fulfillment (add/remove items, change shipping)
- **Order notes**: Internal notes and customer communication log

#### Shipping
- **Rate calculation**: Real-time rates from USPS, UPS, FedEx
- **Label generation**: Generate and print shipping labels
- **Shipment tracking**: Automatic tracking updates from carriers
- **Split shipments**: Ship order in multiple packages/shipments
- **Shipping profiles**: Different rates/methods by product or destination

#### Returns & Refunds
- **RMA management**: Create and track return authorizations
- **Return reasons**: Configurable reason codes
- **Refund processing**: Full or partial refunds via Stripe
- **Restocking**: Automatic or manual inventory adjustment on return
- **Store credit option**: Issue credit instead of refund

### 8. Payment Processing

#### Pluggable Architecture
- **Payment Provider Interface**: Abstract interface for all payment processors
  - `PaymentProvider` - core payment operations (charge, refund, capture)
  - `PaymentMethodProvider` - payment method management (cards, wallets)
  - `MarketplaceProvider` - optional interface for platform fee splitting
  - `WebhookHandler` - processor-specific webhook handling
- **Provider registration**: Providers registered at startup, selectable per-store
- **Multi-provider support**: Stores can enable multiple providers simultaneously
- **Provider-agnostic models**: Internal payment/refund models map to provider-specific APIs

#### Stripe (Primary Implementation)
- Stripe Connect for platform fee collection
- Each store connects their own Stripe account
- Configurable platform percentage fee
- Payment methods: Credit/debit cards, Apple Pay, Google Pay, Link

#### Future Providers (Interface-Ready)
- **PayPal**: PayPal Checkout, Venmo integration
- **CashApp**: CashApp Pay for younger demographics
- **Square**: Alternative card processing
- Additional providers implementable via `PaymentProvider` interface

#### Common Features (All Providers)
- **Gift cards**: Sell and redeem store-branded gift cards (internal, provider-agnostic)
- **Store credit**: Issue credit for returns, goodwill, promotions (internal ledger)
- **Refunds**: Full and partial refund processing (delegated to provider)
- **Payment status**: Pending, Completed, Failed, Refunded, Disputed
- **Webhook processing**: Provider-specific webhooks mapped to common events
- **Audit logging**: All payment operations logged with provider details

### 9. Loyalty & Rewards Program

- **Points earning**: Configurable points per dollar spent
- **Points redemption**: Convert points to discount at checkout
- **Tier levels**: Bronze, Silver, Gold, Platinum (or custom names)
- **Tier benefits**: Bonus point multipliers, exclusive discounts
- **Points expiration**: Optional expiration after inactivity
- **Points history**: Customer can view earning/redemption history

### 10. Point of Sale (POS)

- **Web-based POS**: Works on tablets and computers
- **Hardware support**:
  - Barcode scanners (USB, Bluetooth)
  - Receipt printers (thermal, network)
  - Card readers (Stripe Terminal)
  - Cash drawers
- **POS features**:
  - Quick product search and barcode scanning
  - Custom sale items (for unlisted items)
  - Split payments
  - Hold/retrieve transactions
  - Cash management (open/close register, cash counts)
  - Receipt printing and email
- **Offline mode**: Queue transactions when connection lost
- **Staff login**: PIN-based quick login for register

### 11. Admin Dashboard

#### Dashboard Home
- Sales overview (today, week, month, year)
- Recent orders requiring attention
- Low-stock alerts
- Key metrics and trends

#### Products
- CRUD products with rich editor
- Variant management with matrix view
- Category and collection management
- Bulk operations (update prices, archive, etc.)
- Import/export tools

#### Orders
- Order list with filters and search
- Order detail view with fulfillment actions
- Batch fulfillment tools
- Returns/refund processing

#### Customers
- Customer list with search and filters
- Customer profile with order history
- Customer groups/segments
- Loyalty points management

#### Consignment
- Consignor list and management
- Inventory by consignor
- Sales and commission reports
- Payout generation and history
- Consignor communication

#### Inventory
- Stock levels across locations
- Transfer creation and tracking
- Adjustment history
- Aging and expiration reports
- Barcode printing

#### Reports (Pre-defined)
- Sales by period, product, category, customer
- Product performance (views, conversion, revenue)
- Inventory valuation and movement
- Consignor sales and commissions
- Customer acquisition and retention
- Tax collected by jurisdiction

#### Settings
- Store profile and branding
- Payment configuration (Stripe Connect)
- Shipping methods and profiles
- Tax settings
- Email templates
- Staff accounts and permissions
- Loyalty program configuration
- POS settings

### 14. Platform Admin (SaaS Management)

#### Dashboard
- Total stores, active stores, new signups
- Platform revenue (fees collected)
- System health and performance

#### Store Management
- List all stores with status, plan, revenue
- Suspend/unsuspend stores
- View store details and activity

#### Customer Service
- Search customers across all stores
- Impersonate users (with required reason/ticket)
- View impersonation audit log

#### User Management
- Platform admin accounts
- Role assignments (Super Admin, Support, Finance)

#### Reports
- Platform revenue by period
- Store growth and churn
- Impersonation audit trail
- Session activity across platform
- Support ticket correlation

### 12. Storefront (Customer-Facing)

#### Theme & Customization
- **Fixed theme**: Clean, professional design
- **Branding**: Logo, colors, fonts customizable
- **Custom navigation**: Merchant can add custom links to header
- **Footer customization**: Links, social media, policies
- **Designed for familiarity**: Help stores match their main website look

#### Pages
- **Home page**: Featured products, categories, promotions
- **Category pages**: Product grid with filters and sorting
- **Product pages**: Images, description, variants, add to cart
- **Search**: Full-text search with filters
- **Cart**: Review items, apply discounts, proceed to checkout
- **Checkout**: One-page checkout flow
- **Account pages**: Login, register, order history, addresses, loyalty points
- **Static pages**: About, Contact, Policies (merchant-editable)

#### Responsive Design
- Mobile-first approach
- Touch-friendly on all devices
- Fast loading with optimized images

### 13. Headless API

For static site integration and custom frontends:

- **Cart API**: Get cart contents, item count, subtotal
- **Product API**: List products, get product details
- **Search API**: Search products with filters
- **Customer API**: Authentication, account management
- **Order API**: Order history, order details

---

## Non-Functional Requirements

### Security
- Passwords hashed with bcrypt (work factor 12)
- JWT-based authentication with refresh tokens
- CSRF protection on all forms
- Input validation and sanitization
- SQL injection prevention via Panache parameterized queries
- XSS prevention via Qute auto-escaping
- Rate limiting on authentication endpoints
- PCI-DSS compliance via Stripe Elements (no card data touches server)

### Performance
- Page load < 2 seconds (server-rendered)
- API response < 200ms for standard operations
- Native compilation for fast cold starts (< 100ms)
- Efficient database queries with proper indexing
- Image optimization and lazy loading
- CDN support for static assets

### Scalability
- Stateless application servers (horizontal scaling via JWT)
- Database connection pooling
- Background job processing for heavy operations
- Caffeine in-memory caching for product catalog, rate limiting
- Designed for multi-pod Kubernetes deployment

### Observability
- Structured JSON logging
- OpenTelemetry tracing with Jaeger
- Prometheus metrics endpoint
- Health check endpoints (/q/health/live, /q/health/ready)
- Kubernetes liveness/readiness probes

### Multi-Currency
- **Display**: Show prices in customer's preferred currency
- **Conversion**: Real-time or daily exchange rates
- **Settlement**: All payments in store's base currency via Stripe

---

## Constraints & Assumptions

1. **Single base currency per store**: Payments processed in one currency, display in multiple
2. **English-only for v1**: Internationalization deferred to future version
3. **Stripe-only payments for v1**: Pluggable provider architecture ready for PayPal, CashApp, etc.
4. **US shipping focus**: USPS, UPS, FedEx direct API integrations
5. **No marketplace features**: Stores are independent; no cross-store discovery
6. **No AI features in v1**: Focus on core functionality first
7. **No B2B features in v1**: Wholesale pricing, purchase orders deferred
8. **No Redis**: Stateless JWT auth + Caffeine caching; add distributed cache later if needed

---

## Technical Architecture Decisions

### Background Job Processing
- **Async tasks (emails, media processing, payouts)**: DelayedJob pattern (see `java-project-standards.adoc`)
  - Database-persisted job queue with retry logic
  - Priority queues (CRITICAL, HIGH, DEFAULT, LOW, BULK)
  - Exponential backoff retry strategy
- **Recurring batch jobs (cleanup, reports, cert renewal)**: Quarkus `@Scheduled` annotations
- **No external message broker**: Adheres to "No Redis" constraint

### Shipping Rate Integration
- **Direct carrier API integrations**: USPS Web Tools, UPS Rating API, FedEx Web Services
- **Per-carrier credential management**: Store-level API keys
- **Fallback strategy**: Table-rate/flat-rate shipping if carrier API unavailable
- **Rate caching**: Cache rates for identical origin/destination/weight for 15 minutes

### Consignment Vendor Payouts
- **Stripe Connect Express accounts**: Vendors onboard via Stripe-hosted flow
- **Compliance delegation**: Stripe handles 1099-K reporting and identity verification
- **Payout timing**: Controlled by Stripe (2-7 day settlement)
- **Platform fee collection**: Deducted at time of charge via Stripe Connect
- **Vendor tax details**: SSN/EIN collected during Stripe onboarding

### Media Processing Execution
- **Short operations (image resize, thumbnail)**: In-process via Thumbnailator, immediate response
- **Long operations (video transcode)**: Queued via DelayedJob, processed asynchronously
- **Execution environment**: FFmpeg invoked via ProcessBuilder within application pods
- **Resource limits**:
  - Image processing timeout: 30 seconds
  - Video processing timeout: 10 minutes
  - Upload limits enforced before processing begins
- **Failure handling**: Failed transcodes logged, original preserved, retry via DelayedJob

### Session & Audit Log Storage
- **Hot storage**: PostgreSQL with time-based partitioning (monthly partitions)
- **Retention in database**: 90 days of session activity and audit logs
- **Archival**: Records older than 90 days compressed and archived to R2 object storage
- **Archive format**: JSONL (newline-delimited JSON) with gzip compression
- **Historical queries**: Application queries archive via S3 API when date range exceeds 90 days
- **Partition maintenance**: Scheduled job creates new partitions, archives and drops old ones

---

## Deployment Architecture

### Container Build
```
Quarkus Native Build (GraalVM)
  → Minimal Docker image (distroless or Alpine)
  → ~50-100MB container size
  → <100ms cold start
```

### Kubernetes (k3s)
- Quarkus Kubernetes extension generates manifests
- Deployment, Service, Ingress resources
- ConfigMaps for environment-specific config
- Secrets for sensitive data (DB credentials, Stripe keys)
- HorizontalPodAutoscaler for scaling

### Infrastructure
- **Database**: PostgreSQL (managed or in-cluster)
- **Cache**: Caffeine (in-memory, per-pod)
- **Object Storage**: Cloudflare R2 (S3-compatible) for product images and video
- **Email**: SMTP or SES for transactional email
- **DNS**: Wildcard subdomain for tenant stores

---

## Related Documentation

- `docs/java-project-standards.adoc` - VillageCompute Java coding standards
- `.codemachine/inputs/competitor-research.md` - Feature comparison research

---

## Feature Priority for MVP

### Phase 1: Core Platform
1. Multi-tenancy with subdomain routing
2. User authentication (merchants, staff, customers)
3. Product catalog (physical products, variants)
4. Basic inventory management
5. Shopping cart and checkout
6. Stripe payments
7. Order management
8. Basic admin dashboard
9. Basic storefront

### Phase 2: Enhanced Features
1. Digital products and subscriptions
2. Consignment management
3. Multi-location inventory
4. Shipping integrations
5. Gift cards and store credit
6. Full reporting suite

### Phase 3: Advanced Features
1. Services/bookings
2. Loyalty program
3. POS system
4. Custom domains with SSL
5. Headless API
6. Multi-currency display
