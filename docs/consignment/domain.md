# Consignment Domain Documentation

## Overview

The Consignment domain enables merchant stores to manage consignment inventory from external vendors (consignors). This feature provides ConsignCloud-level parity for vendor onboarding, item intake, commission tracking, and automated payout processing.

**References:**
- Task I3.T1: Consignment domain implementation
- ERD: `docs/diagrams/datamodel_erd.puml` (Consignment package)
- ADR-001: Multi-tenant data isolation
- ADR-003: Checkout saga and payment processing

---

## Domain Entities

### Consignor

Represents a vendor who provides items for consignment sale.

**Entity:** `villagecompute.storefront.data.models.Consignor`

**Key Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Foreign key to tenant for multi-tenant isolation
- `name` (String): Consignor business or individual name
- `contact_info` (JSONB): Contact details (email, phone, address)
- `payout_settings` (JSONB): Payment preferences and tax information (encrypted in production)
- `status` (String): `active`, `suspended`, or `deleted`
- `created_at`, `updated_at` (OffsetDateTime): Audit timestamps

**Business Rules:**
- All consignors are scoped to a single tenant
- Soft delete via status transition to `deleted`
- Contact and payout settings stored as flexible JSONB for extensibility

---

### ConsignmentItem

Links a product to a consignor with commission tracking.

**Entity:** `villagecompute.storefront.data.models.ConsignmentItem`

**Key Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Foreign key to tenant
- `product_id` (UUID): Foreign key to product being consigned
- `consignor_id` (UUID): Foreign key to consignor
- `commission_rate` (NUMERIC 5,2): Commission percentage (e.g., 15.00 for 15%)
- `status` (String): `active`, `sold`, `returned`, or `deleted`
- `sold_at` (OffsetDateTime): Timestamp when item transitioned to `sold` status

**Business Rules:**
- Commission rates range from 0.00% to 100.00%
- Items marked `sold` trigger payout batch inclusion
- Each product can have multiple consignment items (different consignors)

**Data Flow:**
1. Merchant creates consignment item during intake
2. Item status is `active` until purchased
3. On order completion, item transitions to `sold` with `sold_at` timestamp
4. Sold items are included in next payout batch for the consignor

---

### PayoutBatch

Aggregates consignment sales for a specific period and consignor.

**Entity:** `villagecompute.storefront.data.models.PayoutBatch`

**Key Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Foreign key to tenant
- `consignor_id` (UUID): Foreign key to consignor
- `period_start`, `period_end` (LocalDate): Payout period (inclusive)
- `total_amount` (NUMERIC 19,4): Total payout amount in specified currency
- `currency` (String): Currency code (default: USD)
- `status` (String): `pending`, `processing`, `completed`, or `failed`
- `processed_at` (OffsetDateTime): Timestamp when payout completed
- `payment_reference` (String): External payment provider transaction ID (e.g., Stripe payout ID)

**Business Rules:**
- One batch per consignor per period (enforced via unique constraint)
- Batches are created manually or via scheduled jobs
- Status transitions: `pending` → `processing` → `completed`
- Payment reference links to Stripe Connect payout for audit trail

**Integration Points:**
- Future: Stripe Connect payouts via ADR-003 payment saga
- Reporting projections aggregate pending payout amounts per tenant

---

### PayoutLineItem

Individual line item within a payout batch, representing one sold consignment item.

**Entity:** `villagecompute.storefront.data.models.PayoutLineItem`

**Key Fields:**
- `id` (UUID): Primary key
- `tenant_id` (UUID): Foreign key to tenant
- `batch_id` (UUID): Foreign key to payout batch
- `order_line_item_id` (UUID): Reference to sold order line item (TODO: convert to FK when OrderLineItem entity is implemented)
- `item_subtotal` (NUMERIC 19,4): Item price before commission
- `commission_amount` (NUMERIC 19,4): Store's commission deduction
- `net_payout` (NUMERIC 19,4): Amount paid to consignor (subtotal - commission)

**Business Rules:**
- Commission calculation: `commission_amount = item_subtotal * (commission_rate / 100)`
- Net payout: `net_payout = item_subtotal - commission_amount`
- All calculations use 4 decimal places (NUMERIC 19,4) per Money schema
- Rounding uses HALF_UP mode for fair calculation

---

## Commission & Tax Logic

### Commission Calculation

Commission rates are stored as percentages on `ConsignmentItem` entities.

**Formula:**
```
commission_amount = item_subtotal × (commission_rate ÷ 100)
net_payout = item_subtotal - commission_amount
```

**Example:**
- Item sells for $100.00
- Commission rate is 15.00%
- Commission amount = $100.00 × 0.15 = $15.00
- Net payout to consignor = $100.00 - $15.00 = $85.00

**Implementation:**
- Service method: `ConsignmentService.calculatePayout(BigDecimal itemSubtotal, BigDecimal commissionRate)`
- Returns `PayoutCalculation` record with `itemSubtotal`, `commissionAmount`, and `netPayout`

### Tax Handling

**Current Implementation:**
- Tax information is stored in `Consignor.payoutSettings` JSONB field
- Tax details should be encrypted in production (not yet implemented)
- Tax reporting responsibility depends on merchant/consignor agreement

**Future Enhancements:**
- 1099 form generation for consignors meeting IRS thresholds
- Automatic tax withholding based on consignor settings
- Integration with tax reporting services

---

## API Endpoints

### Admin Endpoints

**Base Path:** `/api/v1/admin/consignors`

**Consignor Management:**
- `GET /api/v1/admin/consignors` - List active consignors (paginated)
- `POST /api/v1/admin/consignors` - Create new consignor
- `GET /api/v1/admin/consignors/{id}` - Get consignor details
- `PUT /api/v1/admin/consignors/{id}` - Update consignor
- `DELETE /api/v1/admin/consignors/{id}` - Soft delete consignor

**Consignment Item Management:**
- `GET /api/v1/admin/consignors/{consignorId}/items` - List consignor's items
- `POST /api/v1/admin/consignors/{consignorId}/items` - Create consignment item (intake)

**Payout Management:**
- `GET /api/v1/admin/consignors/{consignorId}/payouts` - List payout batches
- `POST /api/v1/admin/consignors/{consignorId}/payouts` - Create payout batch

**Authentication:**
- Admin endpoints require authenticated user with admin role
- All operations are tenant-scoped via `X-Tenant-Subdomain` header

**Error Handling:**
- Returns RFC 7807 Problem Details for errors
- 404 for resource not found
- 400 for validation errors
- 409 for duplicate payout batches

---

### Vendor Portal Endpoints

**Base Path:** `/api/v1/vendor/portal`

**Read-Only Access for Consignors:**
- `GET /api/v1/vendor/portal/profile` - Get consignor profile
- `GET /api/v1/vendor/portal/items` - List consignor's items (paginated)
- `GET /api/v1/vendor/portal/payouts` - List payout batches (paginated)

**Authentication:**
- Requires JWT token with `vendor` role
- `consignor_id` claim is mandatory and extracted server-side (requests lacking the claim are rejected with 403)
- All queries are automatically filtered by the authenticated `consignor_id`; no IDs are accepted via query/body parameters

**Security Notes:**
- Vendor tokens are separate from admin/customer tokens
- Access attempts are logged via structured `AUDIT vendor_portal.*` log events for compliance
- The implementation reads the claim via `JsonWebToken` in production and falls back to Quarkus test-security attributes during tests

---

## Service Layer

**Service:** `villagecompute.storefront.services.ConsignmentService`

**Key Methods:**

**Consignor Operations:**
- `createConsignor(Consignor)` - Create new consignor
- `updateConsignor(UUID, Consignor)` - Update consignor
- `getConsignor(UUID)` - Get by ID with tenant verification
- `listActiveConsignors(int page, int size)` - List active consignors
- `deleteConsignor(UUID)` - Soft delete

**Consignment Item Operations:**
- `createConsignmentItem(UUID consignorId, UUID productId, BigDecimal commissionRate)` - Intake new item, validating both entities belong to the authenticated tenant
- `updateConsignmentItem(UUID, ConsignmentItem)` - Update item
- `markItemAsSold(UUID)` - Transition to sold status
- `getConsignorItems(UUID consignorId, int page, int size)` - List items

**Payout Operations:**
- `createPayoutBatch(UUID consignorId, LocalDate start, LocalDate end)` - Create batch
- `getPayoutBatch(UUID)` - Get batch by ID
- `getBatchLineItems(UUID)` - Get line items for batch
- `completePayoutBatch(UUID batchId, String paymentReference)` - Mark completed
- `calculatePayout(BigDecimal subtotal, BigDecimal rate)` - Calculate commission

**Observability:**
- All public methods log tenant_id and entity IDs
- Metrics counters for created consignors, items, and batches
- Gauge `consignment.payout.pending.amount` publishes the latest pending payout total per tenant (BigDecimal value cached per tenant to avoid duplicate registrations)

---

## Repository Layer

All repositories extend `PanacheRepositoryBase` and enforce tenant isolation.

**Repositories:**
- `ConsignorRepository` - Consignor queries with tenant scoping
- `ConsignmentItemRepository` - Item queries by consignor, product, status
- `PayoutBatchRepository` - Batch queries by consignor, status, period
- `PayoutLineItemRepository` - Line item queries by batch

**Tenant Isolation Pattern:**
```java
UUID tenantId = TenantContext.getCurrentTenantId();
return list("tenant.id = :tenantId and status = :status",
    Parameters.with("tenantId", tenantId).and("status", status));
```

**Pre-Defined Queries:**
- Use named constants prefixed with `QUERY_` (e.g., `QUERY_FIND_BY_TENANT_AND_STATUS`)
- All queries include `tenant.id = :tenantId` for RLS alignment

---

## Testing

**Unit Tests:**
- `ConsignmentServiceTest` - Service layer CRUD, payout calculations, tenant isolation
- Coverage: Create, read, update, delete operations for all entities
- Payout calculation edge cases (zero commission, high rates)

**Integration Tests:**
- `ConsignmentResourceTest` - HTTP contract compliance, error handling
- Tests all admin endpoints with RestAssured
- Validates Problem Details responses for errors

**Test Data Setup:**
- Uses `@BeforeEach` with `EntityManager` to create isolated test tenants
- Cleans up data in `@AfterEach` to prevent test pollution
- Sets `TenantContext` for multi-tenant test scenarios

**Running Tests:**
```bash
./mvnw test -Dtest=ConsignmentServiceTest
./mvnw test -Dtest=ConsignmentResourceTest
./mvnw test jacoco:report  # With coverage report
```

---

## Future Enhancements

### Integration with Order Processing

Currently, `PayoutLineItem.orderLineItemId` is a UUID field with a TODO to convert to a `@ManyToOne` relationship when the OrderLineItem entity is implemented (Task I2.T3).

**Future Integration:**
1. Create `OrderLineItem` entity with consignment tracking
2. Update `PayoutLineItem` to use FK relationship
3. Automatically create payout line items when consignment orders complete
4. Query sold items by period for batch generation

### Stripe Connect Integration

Per ADR-003, payout batches will trigger Stripe Connect payouts:

1. Batch created with `status = pending`
2. Background job picks up pending batches
3. Initiates Stripe Connect payout via `PaymentProvider` interface
4. Updates batch with `payment_reference` (Stripe payout ID)
5. Transitions to `completed` status with `processed_at` timestamp

### Reporting & Analytics

Future reporting projections will aggregate:
- Total payouts per consignor per period
- Commission revenue by category/product
- Pending payout liabilities per tenant
- Consignor performance metrics (items sold, average commission)

### Automated Batch Generation

Scheduled job to auto-create payout batches:
- Weekly/monthly batch generation based on tenant settings
- Filter sold items by `sold_at` within period
- Aggregate line items and calculate totals
- Notify consignors of pending payouts

---

## Migration Path

**Database Schema:**
- Tables defined in ERD: `consignors`, `consignment_items`, `payout_batches`, `payout_line_items`
- Migration script location: `migrations/src/main/resources/db/migration/`
- Baseline migration includes all four tables with tenant_id FK and RLS placeholder comments

**Backward Compatibility:**
- No breaking changes to existing catalog/cart APIs
- Consignment features are opt-in per tenant via feature flags (future)

---

## Glossary

- **Consignor:** Vendor who provides items for consignment sale
- **Consignment Item:** Product linked to a consignor with commission tracking
- **Commission Rate:** Percentage of sale price retained by the store
- **Payout Batch:** Aggregated payout for a consignor over a specific period
- **Payout Line Item:** Individual sale included in a payout batch
- **Net Payout:** Amount paid to consignor after commission deduction
- **Intake:** Process of adding consignment items to inventory
