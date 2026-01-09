# Multi-Location Inventory System

## Overview

The Village Storefront multi-location inventory system enables tenants to track and manage inventory across multiple physical or virtual locations (warehouses, retail stores, supplier facilities, etc.). The system supports stock transfers between locations, manual adjustments with reason codes, and barcode label generation for physical inventory operations.

**Key Features:**
- Track inventory levels at multiple locations per tenant
- Transfer stock between locations with audit trail
- Record manual adjustments with reason codes
- Background job coordination for barcode label printing
- Full tenant isolation and row-level security

**References:**
- Task ID: `I3.T2`
- Architecture: `docs/architecture/03_Behavior_and_Communication.md` (Multi-location communication patterns)
- OpenAPI Spec: `api/v1/openapi.yaml` (Inventory admin endpoints)

---

## Architecture

### Data Model

```
┌─────────────────────┐
│ Tenant              │
└──────┬──────────────┘
       │
       │ 1:N
       │
┌──────▼──────────────┐       ┌─────────────────────┐
│ InventoryLocation   │       │ ProductVariant      │
│ ─────────────────   │       │ ──────────────────  │
│ - id (UUID)         │       │ - id (UUID)         │
│ - code (string)     │       │ - sku               │
│ - name              │       │ - price             │
│ - type              │       │ ...                 │
│ - address (jsonb)   │       └──────┬──────────────┘
│ - active            │              │
└──────┬──────────────┘              │
       │                              │
       │ N:M                          │
       │                              │
┌──────▼──────────────────────────────▼──┐
│ InventoryLevel                         │
│ ────────────────────────────────────── │
│ - id (UUID)                            │
│ - variant_id → ProductVariant          │
│ - location (string, FK to code)        │
│ - quantity (integer)                   │
│ - reserved (integer)                   │
│ - version (optimistic lock)            │
└────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ InventoryTransfer                       │
│ ─────────────────────────────────────── │
│ - id (UUID)                             │
│ - source_location_id → InventoryLocation│
│ - dest_location_id → InventoryLocation  │
│ - status (enum: PENDING, IN_TRANSIT,    │
│           RECEIVED, CANCELLED)          │
│ - initiated_by (string)                 │
│ - expected_arrival_date                 │
│ - carrier, tracking_number              │
│ - shipping_cost (cents)                 │
│ - barcode_job_id (UUID, nullable)       │
│ - version (optimistic lock)             │
└──────┬──────────────────────────────────┘
       │
       │ 1:N
       │
┌──────▼──────────────────────────────────┐
│ InventoryTransferLine                   │
│ ─────────────────────────────────────── │
│ - id (UUID)                             │
│ - transfer_id → InventoryTransfer       │
│ - variant_id → ProductVariant           │
│ - quantity (integer)                    │
│ - received_quantity (integer, nullable) │
│ - notes                                 │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ InventoryAdjustment (Audit Log)         │
│ ─────────────────────────────────────── │
│ - id (UUID)                             │
│ - variant_id → ProductVariant           │
│ - location_id → InventoryLocation       │
│ - quantity_change (integer)             │
│ - quantity_before, quantity_after       │
│ - reason (enum: CYCLE_COUNT, DAMAGE,    │
│           RETURN, SHRINKAGE, FOUND,     │
│           OTHER)                        │
│ - adjusted_by (string)                  │
│ - notes                                 │
│ - created_at                            │
└─────────────────────────────────────────┘
```

### Tenant Isolation

All inventory operations are automatically scoped to the current tenant via `TenantContext`:

- Repository queries filter by `tenant_id` using named parameters
- JPA `@PrePersist` hooks inject tenant from context
- REST endpoints resolve tenant from HTTP `Host` header
- No cross-tenant data leakage possible (enforced at repository layer)

---

## Workflows

### 1. Direct Inventory Adjustment

Used for manual corrections, cycle counts, damages, returns, and shrinkage.

**Sequence:**

```
┌────────┐                  ┌────────────────┐              ┌────────────────┐
│ Admin  │                  │ REST API       │              │ Service Layer  │
│  User  │                  │ (JAX-RS)       │              │                │
└───┬────┘                  └───┬────────────┘              └───┬────────────┘
    │                           │                               │
    │ POST /admin/inventory/    │                               │
    │   adjustments             │                               │
    ├──────────────────────────►│                               │
    │                           │                               │
    │                           │ recordAdjustment()            │
    │                           ├──────────────────────────────►│
    │                           │                               │
    │                           │                               │ Apply delta
    │                           │                               │ to InventoryLevel
    │                           │                               │
    │                           │                               │ Create audit
    │                           │                               │ record (reason,
    │                           │                               │ before/after)
    │                           │                               │
    │                           │                               │ Emit metrics
    │                           │                               │
    │                           │◄──────────────────────────────┤
    │                           │                               │
    │  201 Created              │                               │
    │  (adjustment DTO)         │                               │
    │◄──────────────────────────┤                               │
    │                           │                               │
```

**API Usage:**

```bash
POST /api/v1/admin/inventory/adjustments
Content-Type: application/json

{
  "variantId": "550e8400-e29b-41d4-a716-446655440000",
  "locationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "quantityChange": -5,
  "reason": "DAMAGE",
  "adjustedBy": "admin@store.com",
  "notes": "Water damage from roof leak"
}
```

**Response:**

```json
{
  "id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "variantId": "550e8400-e29b-41d4-a716-446655440000",
  "locationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "quantityChange": -5,
  "quantityBefore": 100,
  "quantityAfter": 95,
  "reason": "DAMAGE",
  "adjustedBy": "admin@store.com",
  "notes": "Water damage from roof leak",
  "createdAt": "2026-01-03T12:00:00Z"
}
```

### 2. Inventory Transfer Workflow

Used to move stock between locations (warehouse-to-store, store-to-store, etc.).

**Sequence:**

```
┌────────┐         ┌──────────┐         ┌──────────┐         ┌──────────┐
│ Admin  │         │ REST API │         │ Service  │         │ Job Queue│
│  User  │         │          │         │  Layer   │         │          │
└───┬────┘         └────┬─────┘         └────┬─────┘         └────┬─────┘
    │                   │                    │                    │
    │ POST /admin/      │                    │                    │
    │   inventory/      │                    │                    │
    │   transfers       │                    │                    │
    ├──────────────────►│                    │                    │
    │                   │                    │                    │
    │                   │ createTransfer()   │                    │
    │                   ├───────────────────►│                    │
    │                   │                    │                    │
    │                   │                    │ Validate source    │
    │                   │                    │ & destination      │
    │                   │                    │                    │
    │                   │                    │ Check stock        │
    │                   │                    │ availability       │
    │                   │                    │                    │
    │                   │                    │ Reserve inventory  │
    │                   │                    │ at source          │
    │                   │                    │                    │
    │                   │                    │ Enqueue barcode    │
    │                   │                    │ job                │
    │                   │                    ├───────────────────►│
    │                   │                    │                    │
    │                   │                    │ jobId              │
    │                   │                    │◄───────────────────┤
    │                   │                    │                    │
    │                   │◄───────────────────┤                    │
    │                   │                    │                    │
    │  201 Created      │                    │                    │
    │  (transfer DTO    │                    │                    │
    │   + jobId)        │                    │                    │
    │◄──────────────────┤                    │                    │
    │                   │                    │                    │
    │                   │                    │                    │
    │  [Time passes - goods ship]            │                    │
    │                   │                    │                    │
    │ POST /admin/      │                    │                    │
    │   inventory/      │                    │                    │
    │   transfers/{id}/ │                    │                    │
    │   receive         │                    │                    │
    ├──────────────────►│                    │                    │
    │                   │                    │                    │
    │                   │ receiveTransfer()  │                    │
    │                   ├───────────────────►│                    │
    │                   │                    │                    │
    │                   │                    │ Commit source      │
    │                   │                    │ reservation        │
    │                   │                    │ (reduce qty &      │
    │                   │                    │  reserved)         │
    │                   │                    │                    │
    │                   │                    │ Add inventory      │
    │                   │                    │ to destination     │
    │                   │                    │                    │
    │                   │                    │ Update status      │
    │                   │                    │ to RECEIVED        │
    │                   │                    │                    │
    │                   │◄───────────────────┤                    │
    │                   │                    │                    │
    │  200 OK           │                    │                    │
    │  (updated         │                    │                    │
    │   transfer DTO)   │                    │                    │
    │◄──────────────────┤                    │                    │
    │                   │                    │                    │
```

**API Usage (Create Transfer):**

```bash
POST /api/v1/admin/inventory/transfers
Content-Type: application/json

{
  "sourceLocationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "destinationLocationId": "9f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "initiatedBy": "warehouse-manager@store.com",
  "expectedArrivalDate": "2026-01-10T14:00:00Z",
  "carrier": "FedEx",
  "trackingNumber": "1234567890",
  "shippingCost": 2500,
  "notes": "Replenishing retail location",
  "lines": [
    {
      "variantId": "550e8400-e29b-41d4-a716-446655440000",
      "quantity": 50,
      "notes": "Fragile - handle with care"
    }
  ]
}
```

**Response:**

```json
{
  "transferId": "2b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "sourceLocationId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "destinationLocationId": "9f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "status": "PENDING",
  "initiatedBy": "warehouse-manager@store.com",
  "expectedArrivalDate": "2026-01-10T14:00:00Z",
  "carrier": "FedEx",
  "trackingNumber": "1234567890",
  "shippingCost": 2500,
  "notes": "Replenishing retail location",
  "lines": [
    {
      "variantId": "550e8400-e29b-41d4-a716-446655440000",
      "quantity": 50,
      "receivedQuantity": null,
      "notes": "Fragile - handle with care"
    }
  ],
  "barcodeJobId": "8f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "createdAt": "2026-01-03T12:00:00Z",
  "updatedAt": "2026-01-03T12:00:00Z"
}
```

**API Usage (Receive Transfer):**

```bash
POST /api/v1/admin/inventory/transfers/2b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d/receive
```

---

## Barcode Label Generation

### Job Coordination

When a transfer is created, the service automatically enqueues a background job for barcode label generation. This allows warehouse staff to print labels for tracking shipments without blocking the API response.

The current implementation uses an application-scoped `BarcodeLabelJobQueue` backed by an in-memory queue. Each payload is captured via `BarcodeLabelJobPayload.fromTransfer(...)`, which snapshots the tenant ID, transfer metadata, and line items (variant ID, SKU, requested and received quantities). Enqueueing updates the Micrometer gauge `inventory.barcode.queue.depth`, giving operations visibility into pending label work even before the distributed job workers are wired up.

**Job Payload Structure** (Future Implementation):

```json
{
  "jobId": "8f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "transferId": "2b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "tenantId": "3c9e6679-7425-40de-944b-e07fc1f90ae7",
  "lines": [
    {
      "variantId": "550e8400-e29b-41d4-a716-446655440000",
      "sku": "TEST-VAR-001",
      "quantity": 50,
      "barcode": "CODE128:TEST-VAR-001"
    }
  ],
  "destination": {
    "locationId": "9f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "code": "store-main",
    "name": "Main Retail Store"
  }
}
```

### Barcode Formats

The system supports multiple barcode formats based on product type and warehouse requirements:

- **CODE128**: Default format for alphanumeric SKUs (high density, supports full ASCII)
- **EAN-13**: For products with GTIN/UPC codes
- **QR Code**: For serialized items requiring more data (serial numbers, batch codes)
- **Data Matrix**: For small items requiring compact 2D codes

**Example Barcode Data:**

```
Product SKU:      TEST-VAR-001
Barcode Format:   CODE128
Encoded Data:     TEST-VAR-001
Human Readable:   TEST-VAR-001 (Main Retail Store)
```

### SSE Updates (Future)

Admin SPA can subscribe to Server-Sent Events (SSE) for real-time job status updates:

```bash
GET /api/v1/admin/jobs/8f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d/stream
Accept: text/event-stream
```

**Event Stream:**

```
event: job.started
data: {"jobId":"8f1deb4d-...","status":"processing","progress":0}

event: job.progress
data: {"jobId":"8f1deb4d-...","status":"processing","progress":50}

event: job.completed
data: {"jobId":"8f1deb4d-...","status":"completed","labelUrl":"https://..."}
```

---

## Validation Rules

### Transfer Validations

1. **Source/Destination Validation:**
   - Source and destination must be different locations
   - Both locations must exist and belong to current tenant
   - Both locations must be active (`active = true`)

2. **Quantity Validation:**
   - Transfer quantity must be positive (> 0)
   - Available stock at source must be sufficient: `quantity - reserved >= transfer_quantity`

3. **Variant Validation:**
   - All variants in transfer lines must exist and belong to current tenant
   - Variants must be active (`status = 'active'`)

4. **Status Validation:**
   - Only PENDING or IN_TRANSIT transfers can be received
   - Already received transfers return HTTP 409 Conflict

### Adjustment Validations

1. **Reason Code:**
   - Must be one of: `CYCLE_COUNT`, `DAMAGE`, `RETURN`, `SHRINKAGE`, `FOUND`, `OTHER`
   - Logged to database and emitted as metric tag

2. **Location/Variant:**
   - Location and variant must exist and belong to current tenant
   - If inventory level doesn't exist, it will be created with initial quantity 0

3. **Adjustment Magnitude:**
   - No hard limits on negative adjustments (warnings logged if result is negative)
   - Admin SPA should confirm large adjustments (> 100 units)

---

## Observability

### Metrics

The system emits the following Micrometer metrics:

- `inventory.transfer.started` (counter, tags: `tenant_id`, `source_location`, `destination_location`)
- `inventory.transfer.completed` (counter, tags: `tenant_id`, `source_location`, `destination_location`)
- `inventory.adjustment.count` (counter, tags: `tenant_id`, `location`, `reason`)
- `inventory.barcode.queue.depth` (gauge, value: number of pending jobs)

### Logging

Structured logs include:

- `tenantId`: Current tenant UUID
- `variantId`: Product variant UUID
- `locationId` / `location`: Location identifier
- `transferId`: Transfer UUID (for transfer operations)
- `adjustmentId`: Adjustment UUID (for adjustment operations)
- `reason`: Adjustment reason code

**Example Log Entry:**

```
2026-01-03 12:00:00 INFO  [InventoryTransferService] Creating inventory transfer - tenantId=3c9e6679-7425-40de-944b-e07fc1f90ae7, sourceLocationId=7c9e6679-7425-40de-944b-e07fc1f90ae7, destinationLocationId=9f1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d, lineCount=1
```

---

## Testing

### Integration Test Coverage

The multi-location inventory system has comprehensive integration test coverage across two test suites:

#### 1. InventoryTransferIT (Service Layer Tests)

Tests end-to-end workflows including transfers, adjustments, and domain event publishing.

**Coverage:**

1. **Transfer Creation:**
   - Validates inventory reservation at source location
   - Verifies barcode job enqueue with job ID returned
   - Enforces source ≠ destination validation
   - Rejects transfers exceeding available stock (`quantity - reserved`)
   - Rejects inactive locations
   - Publishes `TRANSFER_INITIATED` domain event with JSON payload

2. **Transfer Receipt:**
   - Commits reservation at source (reduces `quantity` and `reserved`)
   - Adds inventory at destination location
   - Updates transfer status to `RECEIVED`
   - Publishes `TRANSFER_RECEIVED` domain event
   - Rejects already-received transfers (HTTP 409 Conflict)

3. **Manual Adjustments:**
   - Creates audit log with before/after quantities
   - Supports all reason codes: `CYCLE_COUNT`, `DAMAGE`, `RETURN`, `SHRINKAGE`, `FOUND`, `OTHER`
   - Allows negative inventory quantities (logs WARNING)
   - Publishes `INVENTORY_ADJUSTED` domain event with complete payload

4. **Domain Events:**
   - Verifies events persisted to `domain_events` table
   - Validates JSON payload structure contains all required fields:
     - Adjustment: `variantId`, `locationId`, `quantityChange`, `quantityBefore`, `quantityAfter`, `reason`, `adjustedBy`, `adjustmentId`
     - Transfer Initiated: `transferId`, `sourceLocation`, `destinationLocation`, `lineItems`
     - Transfer Received: `transferId`, `sourceLocation`, `destinationLocation`, `receivedQuantities`
   - Verifies metadata includes tenant ID

5. **Concurrency Safety:**
   - Tests optimistic locking via `@Version` field
   - Simulates concurrent adjustments using `CompletableFuture`
   - Verifies all adjustments applied without data loss
   - Confirms retry logic handles `OptimisticLockException`

6. **Tenant Isolation:**
   - Prevents cross-tenant transfers (different tenant locations)
   - Rejects variants from other tenants
   - Enforces tenant matching on all operations

**Test File:** `modules/core-platform/src/test/java/villagecompute/storefront/services/InventoryTransferIT.java`

**Run Tests:**

```bash
./mvnw test -Dtest=InventoryTransferIT
```

#### 2. InventoryLevelRepositoryRLSTest (Repository Layer Tests)

Tests Row-Level Security (RLS) enforcement at the repository query level to ensure complete tenant data isolation.

**Coverage:**

1. **findByVariant() Isolation:**
   - Tenant A sees only its own inventory levels
   - Tenant A queries for Tenant B's variant return empty results
   - Tenant B sees only its own inventory levels
   - Verifies tenant_id filtering at query level

2. **findByVariantAndLocation() Isolation:**
   - Tenant A can query its own variant+location combinations
   - Cross-tenant queries return empty Optional
   - Tenant context switching correctly filters results

3. **findByLocation() Isolation:**
   - Location-based queries scoped to current tenant
   - Tenant A cannot query Tenant B's location code
   - Empty results for cross-tenant location queries

4. **getTotalAvailableQuantity() Isolation:**
   - Aggregation respects tenant boundaries
   - Cross-tenant variant queries return 0
   - Multi-location aggregation works within tenant

5. **Multi-Location Aggregation:**
   - Verifies totals sum across multiple locations for same tenant
   - Confirms other tenants unaffected by new location creation

**Test Profile:** Uses `@TestProfile(TenantPostgresRlsProfile.class)` to enable:
- PostgreSQL via Testcontainers (real database)
- RLS policy enforcement
- Multi-tenant seeding and context switching

**Test File:** `modules/core-platform/src/test/java/villagecompute/storefront/data/repositories/InventoryLevelRepositoryRLSTest.java`

**Run Tests:**

```bash
./mvnw test -Dtest=InventoryLevelRepositoryRLSTest
```

### Test Execution Requirements

All inventory tests run against **real PostgreSQL** (not H2 or in-memory DB) via Quarkus Dev Services (Testcontainers). This ensures:

- RLS policies enforced as in production
- JSONB column support for domain events
- Optimistic locking behavior matches production
- Tenant isolation verified with actual row-level filtering

**Dependencies:**
- Docker running (for Testcontainers)
- PostgreSQL 16.3-alpine image pulled
- Sufficient memory for concurrent test execution

### Concurrency Safety

The inventory system uses **optimistic locking** to prevent lost updates during concurrent modifications:

**Mechanism:**
- `@Version` field on `InventoryLevel` entity
- Hibernate increments version on each update
- Concurrent updates trigger `OptimisticLockException`
- Service layer retries failed operations

**Tested Scenarios:**
- Two threads adjusting same inventory level simultaneously
- Both adjustments applied (130 = 100 + 10 + 20)
- No data loss or inconsistency
- Version conflicts detected and resolved

**Example:**
```java
@Version
public Long version;  // Incremented automatically by JPA
```

### Domain Event Publishing

All inventory operations publish **domain events** to the `domain_events` table for audit and reporting:

**Event Types:**
- `INVENTORY_ADJUSTED` (aggregate: `INVENTORY_LEVEL`)
- `TRANSFER_INITIATED` (aggregate: `INVENTORY_TRANSFER`)
- `TRANSFER_RECEIVED` (aggregate: `INVENTORY_TRANSFER`)

**Payload Structure (JSON):**
```json
{
  "variantId": "550e8400-...",
  "locationId": "7c9e6679-...",
  "quantityChange": -5,
  "quantityBefore": 100,
  "quantityAfter": 95,
  "reason": "DAMAGE",
  "adjustedBy": "admin@store.com",
  "adjustmentId": "9b1deb4d-..."
}
```

**Metadata:**
```json
{
  "tenantId": "3c9e6679-...",
  "timestamp": "2026-01-03T12:00:00Z",
  "correlationId": "req-12345"
}
```

**Tests Verify:**
- Events persisted atomically with inventory changes
- JSON payload contains all required fields
- Metadata enriched with tenant and timestamp
- Events queryable for reporting/analytics

---

## Future Enhancements

1. **Actual Barcode Job Implementation:**
   - Integrate with label printer APIs (Zebra, Brother, etc.)
   - Generate PDF labels for batch printing
   - Support custom label templates per tenant

2. **Transfer Status Transitions:**
   - Add IN_TRANSIT status when carrier tracking confirms pickup
   - Add CANCELLED status with inventory reservation rollback

3. **Partial Receives:**
   - Allow receiving different quantity than requested
   - Track discrepancies (damaged in transit, shortages)

4. **Inventory Counts:**
   - Periodic cycle count scheduling
   - Variance reporting (expected vs. actual)
   - Automated adjustment generation from count results

5. **Transfer Approval Workflow:**
   - Require manager approval for high-value transfers
   - Email notifications on status changes

---

## Testing

### Test Coverage

The inventory system includes comprehensive test coverage across multiple test classes:

**Integration Tests (`InventoryTransferIT`):**
- ✅ Transfer creation with inventory reservation
- ✅ Transfer receipt with inventory movement
- ✅ Validation: Same source/destination rejection
- ✅ Validation: Insufficient stock rejection
- ✅ Validation: Inactive location rejection
- ✅ Validation: Cross-tenant variant rejection
- ✅ Tenant isolation (cross-tenant transfer prevention)
- ✅ Manual adjustments with all reason codes (CYCLE_COUNT, DAMAGE, RETURN, SHRINKAGE, FOUND, OTHER)
- ✅ Adjustment audit trail verification
- ✅ Concurrent adjustments with optimistic locking
- ✅ Negative quantity handling with warning logs
- ✅ Transfer status transitions (reject already-received transfers)
- ✅ Domain event publishing (INVENTORY_ADJUSTED, TRANSFER_INITIATED, TRANSFER_RECEIVED)
- ✅ Event payload structure validation

**Repository-Level Tests (`InventoryLevelRepositoryRLSTest`):**
- ✅ Tenant isolation via `findByVariant()`
- ✅ Tenant isolation via `findByVariantAndLocation()`
- ✅ Tenant isolation via `findByLocation()`
- ✅ Tenant isolation via `getTotalAvailableQuantity()`
- ✅ Multi-location aggregation with tenant scoping

### Running Tests

**Prerequisites:**
- Docker installed and running (for Testcontainers PostgreSQL)
- Java 21+
- Maven 3.8+

**Run all inventory tests:**
```bash
./mvnw test -Dtest=InventoryTransferIT
./mvnw test -Dtest=InventoryLevelRepositoryRLSTest
```

**Run specific test:**
```bash
./mvnw test -Dtest=InventoryTransferIT#createTransfer_shouldReserveInventoryAtSource
```

**Run with coverage report:**
```bash
./mvnw test jacoco:report
```

### Concurrency Safety

The system uses JPA `@Version` field on `InventoryLevel` entity for optimistic locking:
- Concurrent updates to the same inventory level are serialized
- Lost update prevention via `OptimisticLockException`
- Automatic retry logic in service layer

### Domain Events

All inventory operations emit domain events for downstream processing:

| Event Type | Aggregate Type | Trigger | Payload Includes |
|-----------|----------------|---------|------------------|
| `INVENTORY_ADJUSTED` | `INVENTORY_LEVEL` | Manual adjustment | variant ID, location, qty before/after, change, reason, user, notes |
| `TRANSFER_INITIATED` | `INVENTORY_TRANSFER` | Transfer created | transfer ID, source/dest, line items, initiator, expected arrival |
| `TRANSFER_RECEIVED` | `INVENTORY_TRANSFER` | Transfer completed | transfer ID, source/dest, received quantities, timestamp |

Events are persisted to `domain_events` table with JSON payload and tenant metadata.

---

## References

- **Task Spec:** `.codemachine/artifacts/tasks/tasks_I3.json` (I3.T2)
- **Architecture:** `.codemachine/artifacts/architecture/03_Behavior_and_Communication.md`
- **ERD:** `docs/diagrams/datamodel_erd.puml`
- **OpenAPI:** `api/v1/openapi.yaml` (Inventory admin endpoints)
- **Code:**
  - Entities: `src/main/java/villagecompute/storefront/data/models/Inventory*.java`
  - Service: `src/main/java/villagecompute/storefront/services/InventoryTransferService.java`
  - REST: `src/main/java/villagecompute/storefront/api/rest/InventoryAdminResource.java`
  - Tests: `src/test/java/villagecompute/storefront/services/InventoryTransferIT.java`
