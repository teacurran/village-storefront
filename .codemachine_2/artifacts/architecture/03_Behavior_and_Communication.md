<!-- anchor: 3-0-proposed-architecture -->
## 3. Proposed Architecture (Behavioral View)

* **3.7. API Design & Communication:**
    * **API Style:**
        - The platform adheres to the OpenAPI 3.0 spec-first RESTful paradigm mandated in the foundation, ensuring every endpoint across storefront, admin, headless, and platform scopes is versioned under `/api/v1/...` with explicit tenant-aware segments or hostnames so contracts remain stable as modules evolve.
        - REST-backed DTOs map closely to data entities such as Product, Variant, Order, PaymentIntent, Consignor, SessionLog, LoyaltyLedgerEntry, and FeatureFlag; persistence models stay encapsulated inside Panache repositories while MapStruct assemblers enforce separation between transport schemas and database columns like commission cost basis or encrypted tax identifiers.
        - HTTP verbs represent intent consistently: `GET` for catalog queries and reporting lookups, `POST` for command-style operations (checkout orchestration, consignment intake, payout initiation, media upload completion), `PUT` for idempotent replacements (inventory location definition), `PATCH` for partial updates (order editing, staff role changes), and `DELETE` for soft-deletion toggles that trigger archival policies.
        - JWT bearer tokens issued by the Identity & Session Service supply tenant, user, and scope claims; headless integrations additionally rely on OAuth client credentials that return the same JWT structure so REST controllers can uniformly enforce permissions via annotations referencing RBAC scopes.
        - Pagination and filtering employ shared query parameters (`page`, `page_size`, `sort`, `filter`) and responses wrap resource arrays within envelopes containing `items`, `total_count`, `page_count`, `links`, and `data_freshness_timestamp`, giving storefront templates and Admin SPA consistent mechanics for infinite scroll or table pagination.
        - Error handling follows RFC 7807 Problem Details with extended fields for `tenantId`, `traceId`, `impersonationId`, and `remediation`; RESTEasy reactive filters automatically populate correlation IDs from OpenTelemetry spans so debugging across pods stays deterministic.
        - Rate limiting is enforced at API gateway level per tenant and per scope, with response headers (`X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`) propagated through REST middleware; clients such as PrimeUI toasts interpret HTTP 429 payloads to show friendly throttling messages.
        - Every mutating endpoint that can execute twice (checkout, refund, payout, upload completion) accepts `Idempotency-Key` headers stored inside PostgreSQL tables to guarantee safe retries from browsers or POS devices when networks flap or offline queues replay transactions.
        - Hypermedia-style affordances appear via HATEOAS `links` arrays and `actions` metadata so Storefront Rendering Engine and Admin SPA can inspect allowed transitions (e.g., `canRefund`, `canSplitShipment`) without bundling business logic into JavaScript bundles.
        - Multi-tenant enforcement occurs through REST filters that inspect Host headers, confirm TenantContext, and inject row-level security hints; endpoints explicitly include `/tenants/{tenantId}` segments to make cross-tenant access impossible even if Host header spoofed.
        - CORS policies differentiate storefront, admin, headless, and platform origins; configuration stored in Tenant entity drives Quarkus HTTP filters so REST endpoints only accept fetches from registered subdomains or custom domains, fulfilling SaaS safety mandates.
        - Webhook ingress endpoints (Stripe, carrier callbacks, FFmpeg notifications) speak REST as well; they first persist raw JSON payloads plus signature headers into `webhook_events` tables before invoking module-specific handlers, enabling replay during incident response and satisfying audit requirements.
        - SSE (Server-Sent Events) endpoints for Admin SPA notifications reuse REST security and stream JSON lines within the same OpenAPI contract, ensuring Quarkus filters and TenantContext apply identically to traditional HTTP responses while enabling low-latency UI refreshes.
        - REST controllers expose `HEAD` and `OPTIONS` for caching and preflight support; CDN caches rely on ETag headers derived from resource version numbers maintained in Product, Order, or Media entities, while `Cache-Control` durations respect tenant-configured TTLs that differ between storefront and headless clients.
        - Media uploads follow REST handshake sequences: clients request presigned URLs via `POST /media/upload-request`, upload directly to R2/S3, then confirm completion via REST callbacks that kick off background jobs, allowing the backend to validate checksums before exposing URLs.
        - Reporting exports operate as REST job submission endpoints returning job IDs; clients poll `GET /reports/jobs/{id}` which surfaces status transitions and signed download URLs, aligning synchronous REST invocation with asynchronous execution through Background Job Scheduler.
        - Feature flag governance integrates with REST by requiring every endpoint description inside the OpenAPI document to declare dependent flags using `x-feature-flags`, making it explicit when responses change due to toggled capabilities or phased rollouts.
        - Observability hooks ensure each REST response includes headers `X-Trace-Id` and `X-Tenant-Id`, and JSON payloads echo the same data in Problem Details objects; this uniformity lets customer support correlate API logs with Jaeger traces quickly.
        - REST-friendly money handling uses integer minor units plus ISO currency codes in every DTO; rounding never occurs server-side outside Money value objects, preventing floating-point drift when clients reconstruct totals, multi-currency displays, or loyalty conversions.
        - All REST controllers obey Quarkus serialization policies forbidding null collections and requiring explicit empty arrays, which simplifies Vue state management and ensures PlantUML-modeled flows reflect actual payload expectations without ambiguous fields.
        - Address normalization APIs supply structured `AddressDTO` objects with validation status codes, letting downstream modules reuse sanitized addresses for shipping, billing, and tax calculations without repeating integrations.
        - Authentication endpoints expose refresh-token flows via REST while publishing session log entries synchronously so Security teams can trace device fingerprints tied to each bearer token issuance.
        - Search endpoints (`/api/v1/tenants/{tenantId}/search`) expose filters for categories, collections, price ranges, and custom attributes while returning facets metadata so storefront templates can render filter pills without extra round trips.
        - Concurrency control relies on optimistic locking fields (`version`, ETag headers) so Admin SPA inline editors can detect conflicting edits; REST responses include `ETag` values clients echo via `If-Match` headers.
        - Bulk operations (imports/exports) leverage REST endpoints backed by Background Job Scheduler; request payloads include CSV metadata and job priority, while responses supply job identifiers and SSE channels for progress.
        - Localization hooks reserve fields like `displayLocale` and `currencyDisplay` in DTOs even though v1 is English-only; this preserves forward compatibility while keeping the contract simple for MVP.
        - Contract testing harnesses (e.g., Pact-like) consume generated OpenAPI specs so downstream SDKs can validate serialization behavior before deploying clients, reinforcing the spec-first philosophy.

    * **Communication Patterns:**
        - Tenant Access Gateway front-loads TenantContext resolution via synchronous request filters that parse subdomains, custom domains, and platform admin overrides; CDI events broadcast tenant resolution outcomes so Identity, FeatureToggle, and Theme token services can hydrate caches before controllers execute.
        - Storefront Rendering Engine composes Qute templates by synchronously calling Catalog & Inventory Module for product data, Media Pipeline Controller for signed asset descriptors, Checkout & Order Orchestrator for cart fragments, Loyalty module for balance widgets, and FeatureToggle service for gating of beta features such as subscriptions or POS pickup.
        - Admin SPA Delivery Service renders Vue assets, injects bootstrapped tenant + feature data, and proxies API calls to domain modules via RESTEasy endpoints; behind the scenes it invokes Catalog, Consignment, Inventory, Orders, Payments, Loyalty, and POS modules through CDI interfaces while pushing audit data to SessionLog tables.
        - Checkout & Order Orchestrator coordinates multiple synchronous steps—address normalization via Integration Adapter Layer, shipping rate retrieval from USPS/UPS/FedEx adapters, loyalty redemption via Loyalty module, gift card validation via internal ledger service—before delegating payment capture to the Payment Integration Layer; compensating actions (inventory release, loyalty reversal) trigger asynchronous events when a downstream step fails.
        - Payment Integration Layer interacts synchronously with Stripe Connect over HTTPS for intents, captures, refunds, and onboarding flows; webhook responses arrive asynchronously through REST ingress endpoints that push work into the Background Job Scheduler for retry-friendly reconciliation pipelines.
        - Catalog & Inventory Module communicates with Consignment module through strongly typed service interfaces: inventory reservations happen inside the same transaction for regular sales, while consignment intake publishes `ConsignmentItemReceived` events that Inventory module subscribes to when computing location-level stock projections and low-stock alerts.
        - Multi-location inventory adjustments propagate via synchronous Admin SPA calls, but stock transfers generate background jobs for barcode printing or notification emails; Caffeine caches store frequently accessed inventory counts, with invalidation triggered by CDI events emitted after successful database commits.
        - Media Pipeline Controller handles short-lived image transformations synchronously inside REST requests; large assets queue asynchronous jobs handled by Background Job Scheduler workers that invoke FFmpeg via ProcessBuilder, record progress to Media metadata tables, and send SSE notifications to Admin SPA or Storefront for live status updates.
        - Consignment payouts combine synchronous approval flows with asynchronous reporting: Admin SPA calls run commission math inline, Payment Integration Layer schedules Stripe transfers, and Reporting & Analytics Projection Service picks up resulting domain events to update consignor statements and payout history dashboards.
        - Reporting & Analytics Projection Service subscribes to persisted domain events, running asynchronous ETL-style jobs to populate read-optimized aggregates; Admin SPA, Storefront widgets, and Platform Admin Console query these aggregates synchronously to avoid long-running OLTP scans during dashboard rendering.
        - POS & Offline Processor reuses the Checkout APIs via REST when online, but stores encrypted transaction bundles locally while offline; once the connection resumes, the offline client invokes `/pos/offline/batches`, and Background Job workers replay transactions sequentially through Checkout orchestrations while flagging duplicates via idempotency keys held in PostgreSQL.
        - Platform Admin Console Backend synchronously interfaces with Identity & Session Service for impersonation token issuance, writes immutable AuditEvent rows for every impersonation start/stop, and leverages Reporting aggregates asynchronously to display cross-store analytics, suspicious login patterns, and platform revenue metrics.
        - Integration Adapter Layer centralizes outbound HTTP calls with resilience policies: modules issue REST calls to adapters rather than external APIs directly, and adapters broadcast CDI events when SLA breaches occur so observability dashboards alert operators before customer impact grows.
        - Email delivery uses Quarkus Mailer asynchronously; domain modules enqueue email jobs (receipts, payouts, notifications) referencing templates stored in PostgreSQL, and Background Job Scheduler pulls them for dispatch while honoring domain filtering rules to avoid contacting real customers from non-production environments.
        - FeatureToggle service communicates synchronously with PostgreSQL via Panache to load tenant + platform level flags; the results are cached per request, and modules subscribe to toggle change events so SSE streams or POS clients can adapt in near real time when pilots expand.
        - Observability plumbing ensures every synchronous call emits OpenTelemetry spans annotated with tenant and impersonation IDs, while asynchronous job executions link back via `traceparent` metadata stored in job payloads; this preserves end-to-end trace continuity across REST controllers, adapters, and workers.
        - Background Job Scheduler coordinates certificate renewals, archival tasks, payout statement generation, and video transcodes via asynchronous loops; each job update posts SSE notifications or WebSocket messages to interested clients when necessary, bridging asynchronous execution with synchronous UI expectations.
        - Headless API integrations consume the same REST endpoints as storefront and admin clients but authenticate via OAuth client credential scopes (`catalog:read`, `cart:write`, `orders:read`); rate limiting and audit logging treat these clients as first-class tenants, and background jobs can still emit notifications for their actions.
        - Security-critical flows (payments, impersonation, media uploads) inject AuditEvent writes into synchronous transactions to avoid leaving gaps; asynchronous copies to archival storage happen later, but the initial log entry is part of the request/response contract mandated by the blueprint.
        - Loyalty & Rewards Module intercepts checkout orchestrations synchronously: the Checkout service consults loyalty configuration to compute accrual multipliers and ensures redemption eligibility before totals finalize, while nightly asynchronous jobs recalculate tiers and emit events consumed by Reporting.
        - Session & Activity logging pipeline writes to SessionLog tables through synchronous interceptors on Identity & Session Service; platform admin impersonation markers propagate through these logs so Platform Admin Console and Reporting can reconstruct who acted on behalf of which tenant at any time.
        - Shipping, Returns, and Refund operations rely on the Checkout & Order Orchestrator to coordinate Inventory, Payment Integration, and Integration Adapter Layer interactions; asynchronous jobs generate shipping labels via carrier adapters and track RMAs while synchronous admin actions update statuses so storefront order history reflects progress immediately.
        - Multi-currency display uses synchronous Money service calls to fetch cached FX data per tenant; if conversion misses, the service falls back to tenant base currency and logs events so Reporting knows when conversions were skipped; asynchronous jobs refresh FX rates daily using external providers permitted by the Standard Kit.
        - Data retention and archival flows rely on Background Job Scheduler scanning partitioned tables, streaming JSONL payloads to R2, and emitting events to notify Platform Ops dashboards; synchronous APIs that request historical data trigger retrieval jobs if requested ranges exceed hot storage windows, ensuring UI components always state data freshness.
        - Search indexing jobs consume `ProductPublished` and `InventoryAdjusted` events so storefront search and headless APIs stay in sync; Admin bulk imports trigger lower-priority indexing jobs to avoid starving checkout-critical queues.
        - Bulk product imports stream CSV rows into staging tables via REST-uploaded files, then Background Job workers validate each row, call Catalog services for upserts, and emit progress metrics for Admin SPA progress bars.
        - Session revocation flows propagate through Identity & Session Service, Admin SPA, and Storefront SRE, ensuring revoked tokens drop active WebSocket/SSE connections and remove POS devices from offline queues.
        - Custom domain and SSL automation uses Background Job Scheduler to respond to domain additions: Tenant Access Gateway emits events, Platform Ops module requests ACME challenges, and Admin SPA receives status updates until certificates validated.
        - Platform-level observability integrates with Prometheus exporters per module; Communication Architect perspective ensures each module tags metrics with tenant and module identifiers so cross-cutting dashboards can pivot by service.
        - Payment dispute handling sees Stripe webhooks captured asynchronously, Payment Integration Layer normalizing disputes, Checkout Orchestrator freezing inventory or loyalty adjustments, and Reporting flagging at-risk orders for support review.
        - Gift card and store credit flows unify Payment Integration Layer with Loyalty module: checkout orchestrator consults internal ledgers first, Payment provider handles remaining balance, and domain events capture ledger usage for audit.
        - Loyalty adjustments triggered by returns or manual admin actions cascade through Consignment payout calculations and Reporting aggregates so consignor commissions reflect post-return net sales.
        - Archived data retrieval requests route through Reporting, which spawns background jobs to fetch JSONL archives from R2, decrypt them, and stream to requesting admin clients via signed URLs respecting tenant isolation.
        - Automated certificate renewal for custom domains emits CDI events so Tenant Access Gateway refreshes TLS stores without restarts, while Admin SPA receives status notifications guaranteeing merchants stay informed.

    * **Key Interaction Flow (Sequence Diagram):**
        * **Flow A - Multi-Tenant Storefront Checkout:** Shopper journeys start at DNS, where Tenant Access Gateway extracts the tenant slug or custom domain, hydrates TenantContext, and pushes CDI events for feature flag hydration; Storefront Rendering Engine then orchestrates synchronous catalog queries, cart mutations, loyalty interactions, and Stripe PaymentIntent creation via Checkout & Order Orchestrator, culminating in domain events for reporting pipelines that keep dashboards fresh.
            - Tenant Access Gateway announces tenant resolution events so FeatureToggle snapshots remain accurate even during custom-domain routing that bypasses default subdomain parsing.
            - Checkout handles loyalty, gift cards, shipping, and payment orchestration while guaranteeing idempotent writes before domain events fire so retries cannot double-charge or oversell inventory.
            - Reporting projections subscribe to OrderInitiated and OrderPaid events to keep Admin dashboard charts real-time without slowing transactional writes handled by the Checkout module.
        * **Diagram (PlantUML):**
            ~~~plantuml
            @startuml
            actor Shopper
            participant "Tenant Access Gateway" as TAG
            participant "Feature Toggle Service" as FLAGS
            participant "Storefront Rendering Engine" as SRE
            participant "Identity & Session Service" as ID
            participant "Catalog & Inventory Module" as CAT
            participant "Checkout & Order Orchestrator" as COO
            participant "Integration Adapter Layer" as ADAPTER
            participant "Payment Integration Layer" as PAY
            participant "Stripe Connect Adapter" as STRIPE
            participant "Order Repository (PostgreSQL)" as DB
            participant "Reporting & Analytics Projection Service" as REPORT
            Shopper -> TAG: HTTPS request to {tenant}.platform-domain.com
            TAG -> TAG: Parse subdomain or resolve custom domain
            TAG -> TAG: Lookup Store + Tenant, enforce suspension rules
            TAG -> FLAGS: Publish TenantResolved CDI event
            FLAGS --> TAG: Per-request feature toggle snapshot
            TAG -> SRE: Forward request with TenantContext + flags
            SRE -> ID: Validate JWT / issue guest session
            ID --> SRE: Auth result + scope + impersonation marker
            SRE -> FLAGS: Confirm storefront feature gates (subscriptions, loyalty)
            FLAGS --> SRE: Allowed capability matrix
            SRE -> CAT: GET /api/v1/tenants/{t}/products?filters
            CAT -> DB: Run tenant-filtered query with RLS
            DB --> CAT: Product rows + variant availability
            CAT --> SRE: Product DTOs w/ pricing + SEO metadata
            SRE -> Shopper: Render Qute template w/ PrimeUI cart widget
            Shopper -> SRE: Add variant to cart via PrimeUI
            SRE -> COO: POST /api/v1/tenants/{t}/cart/items
            COO -> CAT: Reserve inventory + compute price overrides
            CAT --> COO: Reservation success + low stock status
            COO -> DB: Persist cart snapshot + session metadata
            DB --> COO: Cart version token
            COO --> SRE: Updated cart state for UI
            Shopper -> SRE: Begin checkout
            SRE -> COO: POST /api/v1/tenants/{t}/checkout
            COO -> ADAPTER: Normalize shipping address (USPS API)
            ADAPTER --> COO: Validated address + corrections
            COO -> CAT: Finalize inventory reservation + shipping options
            CAT --> COO: Shipping profiles + rate inputs
            COO -> ADAPTER: Fetch carrier rates from UPS/FedEx
            ADAPTER --> COO: Carrier rates + SLA info
            COO -> PAY: Create PaymentIntent request payload
            PAY -> STRIPE: HTTPS /v1/payment_intents (Stripe Connect)
            STRIPE --> PAY: Pending intent + client secret
            PAY -> DB: Persist PaymentIntent and application fee data
            DB --> PAY: Record committed with version
            PAY --> COO: Intent reference + client secret
            COO -> DB: Create Order record + audit trail
            DB --> COO: Order stored (Pending Payment)
            COO -> REPORT: Persist OrderInitiated domain event
            REPORT --> COO: Acknowledge event for projection
            COO --> SRE: Checkout payload (orderId, clientSecret, totals)
            Shopper -> STRIPE: Complete payment via Stripe Elements
            STRIPE --> PAY: Webhook payment_intent.succeeded
            PAY -> DB: Update PaymentIntent status to Completed
            PAY -> COO: Notify payment confirmation
            COO -> DB: Transition Order to Paid + record loyalty accrual
            COO -> FLAGS: Check loyalty flag to decide synchronous accrual
            FLAGS --> COO: Loyalty enabled => apply award
            COO -> REPORT: Publish OrderPaid + LoyaltyAccrued events
            REPORT --> COO: Aggregates scheduled for refresh
            SRE -> Shopper: Serve confirmation page + enqueue receipt email
            @enduml
            ~~~
        * **Flow B - Consignment Payout Authorization:** Store staff initiates a payout batch from the Admin SPA; the Identity & Session Service enforces role scopes, FeatureToggle service determines whether automated payouts are enabled for the tenant, Consignment module calculates commissions, Payment Integration Layer schedules Stripe Connect transfers, and Reporting service processes statements asynchronously to update dashboards and vendor portals.
            - Admin SPA enforces impersonation banners when payouts are triggered via platform admin, ensuring traceability across modules and immutable audit alignment.
            - Consignment module recalculates commissions per item and per consignor category, referencing Inventory data only via service methods to keep bounded contexts intact and maintain tenant isolation.
            - Statement generation and payout history updates rely on asynchronous background jobs so staff can continue working without waiting for PDF rendering or archival uploads.
        * **Diagram (PlantUML):**
            ~~~plantuml
            @startuml
            actor Staff
            participant "Admin SPA Delivery Service" as ADMIN
            participant "Identity & Session Service" as ID
            participant "Feature Toggle Service" as FLAGS
            participant "Consignment Domain Module" as CONS
            participant "Inventory Module" as INV
            participant "PostgreSQL (RLS)" as DB
            participant "Payment Integration Layer" as PAY
            participant "Stripe Connect Adapter" as STRIPE
            participant "Background Job Scheduler & Worker" as JOB
            participant "Reporting & Analytics Projection Service" as REPORT
            participant "AuditEvent Repository (PostgreSQL)" as AUDIT
            participant "Object Storage (R2)" as R2
            Staff -> ADMIN: Load /admin/consignment/payouts UI
            ADMIN -> ID: Validate JWT scope + impersonation context
            ID --> ADMIN: Auth ok + tenant_id + staff role
            ADMIN -> FLAGS: Request payout-related feature toggles
            FLAGS --> ADMIN: AutoPayouts=on, StatementTemplate=default
            ADMIN -> CONS: GET consignor balances + filters
            CONS -> INV: Fetch sold consignment items awaiting payout
            INV -> DB: Query InventoryLevel + ConsignmentItem tables
            DB --> INV: Item ledger result set
            INV --> CONS: Aggregated inventory + valuation data
            CONS --> ADMIN: Balance DTO list with commission schedules
            Staff -> ADMIN: Click "Create payout batch"
            ADMIN -> CONS: POST /api/v1/admin/consignors/{id}/payouts
            CONS -> FLAGS: Confirm autopayout feature for tenant
            FLAGS --> CONS: Enabled, continue automatically
            CONS -> PAY: Build payout payload with commission math
            PAY -> STRIPE: POST /v1/transfers via Stripe Connect
            STRIPE --> PAY: Transfer pending, reference id
            PAY -> AUDIT: Log payout intent + provider reference
            AUDIT --> PAY: Immutable record stored
            PAY --> CONS: Transfer token, ETA, failure webhook info
            CONS -> DB: Persist payout batch + ledger adjustments
            DB --> CONS: Batch stored with status Pending Settlement
            CONS -> JOB: Enqueue CONSIGNOR_PAYOUT_STATEMENT job
            JOB -> REPORT: Generate statement PDF + metrics
            REPORT -> R2: Store statement artifact + signed URL
            R2 --> REPORT: Upload success metadata
            REPORT --> JOB: Statement metadata (download url)
            JOB -> ADMIN: Push SSE to SPA with statement link
            ADMIN -> Staff: Update UI card with payout + download link
            STRIPE --> PAY: Webhook transfer.paid (async)
            PAY -> DB: Update payout batch status to Paid
            PAY -> CONS: Notify payout completion
            CONS -> AUDIT: Log payout completion + settlement details
            AUDIT --> CONS: Record persisted
            CONS -> REPORT: Publish ConsignorPayoutCompleted event
            REPORT --> CONS: Dashboard aggregates updated
            CONS -> ADMIN: Notify SPA for status refresh
            ADMIN -> Staff: Show success toast + audit reference
            @enduml
            ~~~
        * **Flow C - Media Upload and Variant Propagation:** Merchants use the Admin SPA to request presigned URLs, upload imagery or video to R2, and notify the backend upon completion; Media Pipeline Controller validates payloads, enqueues background jobs, invokes FFmpeg for heavy work, updates Catalog media references, and signals both Admin SPA and Storefront Rendering Engine so that optimized assets appear without manual refreshes.
            - Feature toggles determine whether inline resizing or asynchronous jobs run, letting the platform stage video processing per tenant plan tier or experimentation cohort.
            - Background jobs capture FFmpeg progress metrics, enforce timeout guardrails, and emit failure notifications when processing exceeds thresholds defined in the standards document.
            - Storefront Rendering Engine queries media metadata through REST even though running in-process, which preserves contract-first behavior and simplifies future service extraction.
        * **Diagram (PlantUML):**
            ~~~plantuml
            @startuml
            actor Merchant
            participant "Admin SPA Delivery Service" as ADMIN
            participant "Identity & Session Service" as ID
            participant "Feature Toggle Service" as FLAGS
            participant "Media Pipeline Controller" as MEDIA
            participant "Object Storage (R2)" as R2
            participant "Background Job Scheduler & Worker" as JOB
            participant "FFmpeg Processor Pod" as FFMPEG
            participant "Catalog & Inventory Module" as CAT
            participant "Metadata Repository (PostgreSQL)" as DB
            participant "Storefront Rendering Engine" as SRE
            participant "Reporting & Analytics Projection Service" as REPORT
            Merchant -> ADMIN: Start upload wizard
            ADMIN -> ID: Confirm token + upload scope
            ID --> ADMIN: Auth ok + tenant metadata
            ADMIN -> FLAGS: Query media feature toggles (video/HLS)
            FLAGS --> ADMIN: VideoProcessing=enabled, LazyVariants=on
            ADMIN -> MEDIA: POST /api/v1/admin/media/upload-request
            MEDIA -> R2: Create presigned URL for tenant/media hash
            R2 --> MEDIA: Presigned PUT metadata
            MEDIA -> DB: Persist upload session + constraints
            DB --> MEDIA: Upload session id
            MEDIA --> ADMIN: UploadId + presigned URL + headers
            Merchant -> R2: PUT binary using presigned data
            R2 --> Merchant: HTTP 200 success
            Merchant -> ADMIN: Notify completion with uploadId + checksum
            ADMIN -> MEDIA: POST /api/v1/admin/media/uploads/{id}/complete
            MEDIA -> MEDIA: Validate checksum, mime, byte size
            MEDIA -> FLAGS: Check if instant resize allowed
            FLAGS --> MEDIA: For images <=50MB run inline, else async
            MEDIA -> JOB: Enqueue IMAGE_VARIANT/V1 job
            JOB -> FFMPEG: For videos, run transcode pipeline
            FFMPEG -> R2: GET original asset via signed URL
            FFMPEG -> FFMPEG: Transcode/resample/generate thumbnails
            FFMPEG -> R2: PUT derived variants under tenant prefix
            R2 --> FFMPEG: Upload success ack
            FFMPEG --> JOB: Emit processing result payload
            JOB -> MEDIA: Callback with variant metadata + durations
            MEDIA -> CAT: PATCH product media list linking new variants
            CAT -> DB: Update Product + Variant media references
            DB --> CAT: Media references saved
            CAT --> MEDIA: Confirmation
            MEDIA -> REPORT: Publish MediaProcessed event
            REPORT -> DB: Append event to domain_events queue
            DB --> REPORT: Event persisted
            REPORT -> REPORT: Update processing metrics + Prometheus
            MEDIA -> ADMIN: Send SSE update w/ thumbnails & statuses
            ADMIN -> Merchant: Render UI with thumbnails + signed URLs
            Merchant -> SRE: Load storefront preview page
            SRE -> MEDIA: GET /api/v1/media/{id} metadata for Qute
            MEDIA --> SRE: Optimized variants + responsiveness hints
            SRE -> Merchant: Render storefront with srcset + lazy loading
            @enduml
            ~~~
        * **Flow D - Platform Admin Impersonation and Audit Trail:** Platform admins use the Platform Admin Console to impersonate tenant staff or shoppers; Identity & Session Service issues acting-as tokens, Tenant Access Gateway resolves tenant contexts, Admin SPA Delivery Service or Storefront Rendering Engine enforce impersonation banners, and AuditEvent plus Reporting services capture every action for compliance-grade trails.
            - Platform Admin Console enforces reason and ticket references before impersonation tokens issue, satisfying audit requirements documented in the foundation.
            - Admin SPA and Storefront rendering surfaces display persistent "Acting as" banners and block destructive actions if impersonation reason is missing or the feature flag disables such operations.
            - Reporting service aggregates impersonation duration, tenant impact, and action types so security teams can detect anomalies quickly and platform KPIs remain transparent.
        * **Diagram (PlantUML):**
            ~~~plantuml
            @startuml
            actor PlatformAdmin
            participant "Platform Admin Console Backend" as PAC
            participant "Identity & Session Service" as ID
            participant "Feature Toggle Service" as FLAGS
            participant "Tenant Access Gateway" as TAG
            participant "Admin SPA Delivery Service" as ADMIN
            participant "Storefront Rendering Engine" as SRE
            participant "AuditEvent Repository (PostgreSQL)" as AUDIT
            participant "Reporting & Analytics Projection Service" as REPORT
            PlatformAdmin -> PAC: POST /api/v1/platform/impersonations
            PAC -> ID: Validate super-user role + MFA token
            ID --> PAC: Auth success + session fingerprint
            PAC -> TAG: Resolve tenant by store slug/custom domain
            TAG --> PAC: TenantContext + suspension status
            PAC -> FLAGS: Fetch impersonation safety toggles
            FLAGS --> PAC: ImpersonationAllowed=true, BannerColor=red
            PAC -> AUDIT: Insert ImpersonationRequested event
            AUDIT --> PAC: Record persisted with reason
            PAC -> ID: Issue impersonation JWT (actingAs claims)
            ID --> PAC: Token + expiry + revocation handle
            PAC -> PlatformAdmin: Return actingAsJwt + display metadata
            PlatformAdmin -> ADMIN: Call /admin endpoints with token
            ADMIN -> ID: Introspect actingAs token
            ID --> ADMIN: Valid + actingAs user + impersonator id
            ADMIN -> AUDIT: Insert ImpersonationSessionStarted event
            AUDIT --> ADMIN: Confirmation
            ADMIN -> Store Modules: Execute requested action (orders, customers)
            Store Modules --> ADMIN: Tenant-scoped response data
            ADMIN -> PlatformAdmin: Response + "Acting as" banner instructions
            PlatformAdmin -> PAC: PATCH /impersonations/{id}/heartbeat
            PAC -> AUDIT: Update last activity timestamp
            AUDIT --> PAC: Stored
            PlatformAdmin -> PAC: DELETE /impersonations/{id}
            PAC -> ID: Revoke actingAs token + restore original session
            ID --> PAC: Revoked
            PAC -> AUDIT: Insert ImpersonationEnded event with duration + summary
            AUDIT --> PAC: Immutable record stored
            PAC -> REPORT: Publish ImpersonationAudit event
            REPORT -> DB: Persist domain event for analytics
            DB --> REPORT: Event recorded for aggregation
            REPORT --> PAC: Dashboard aggregates scheduled for refresh
            PlatformAdmin -> SRE: Optionally impersonate storefront customer
            SRE -> ID: Validate actingAs token + scope
            ID --> SRE: Approved with warning flag
            SRE -> AUDIT: Log storefront-level impersonation action
            AUDIT --> SRE: Confirmation
            SRE -> PlatformAdmin: Serve storefront view with overlay and exit CTA
            @enduml
            ~~~
        * **Flow E - Returns & Refund Saga:** Customers initiate return requests through the storefront experience while staff manage approvals via the Admin SPA; Checkout & Order Orchestrator coordinates RMA creation, background label generation, inventory restocking, and Stripe refunds so both shopper and merchant have synchronized status updates.
            - Storefront experience enforces feature-flag controlled return windows per tenant and captures structured reason codes required by reporting exports and compliance audits.
            - Refund execution only occurs after Admin SPA confirms physical receipt, triggering compensating adjustments for inventory, loyalty, and consignment payout balances so ledgers stay accurate.
        * **Diagram (PlantUML):**
            ~~~plantuml
            @startuml
            actor Customer
            actor Staff
            participant "Storefront Rendering Engine" as SRE
            participant "Identity & Session Service" as ID
            participant "Feature Toggle Service" as FLAGS
            participant "Checkout & Order Orchestrator" as COO
            participant "Order Repository (PostgreSQL)" as DB
            participant "Admin SPA Delivery Service" as ADMIN
            participant "Inventory Module" as INV
            participant "Integration Adapter Layer" as ADAPTER
            participant "Payment Integration Layer" as PAY
            participant "Stripe Connect Adapter" as STRIPE
            participant "Background Job Scheduler & Worker" as JOB
            participant "Reporting & Analytics Projection Service" as REPORT
            participant "AuditEvent Repository (PostgreSQL)" as AUDIT
            Customer -> SRE: Request return from order history
            SRE -> ID: Verify session + scope (customer)
            ID --> SRE: Auth OK + tenantId
            SRE -> FLAGS: Check returns feature + window
            FLAGS --> SRE: Window valid, proceed
            SRE -> COO: POST /api/v1/tenants/{t}/orders/{id}/returns
            COO -> DB: Persist ReturnAuthorization (Pending Approval)
            DB --> COO: ReturnAuthorizationId + status
            COO -> AUDIT: Log customer request + reason codes
            AUDIT --> COO: Immutable record stored
            COO -> ADMIN: SSE notify staff for approval
            Staff -> ADMIN: Open return task + review request
            ADMIN -> COO: PATCH return status = Approved
            COO -> JOB: Enqueue RETURN_LABEL job
            JOB -> ADAPTER: Request carrier return label\n(USPS/UPS/FedEx)
            ADAPTER --> JOB: Label PDF + tracking number
            JOB -> ADMIN: Provide label via SSE/Webhook
            ADMIN -> Customer: Present downloadable label
            Customer -> Carrier: Ship package back (out of scope)
            Staff -> ADMIN: Mark package received
            ADMIN -> COO: POST /returns/{id}/receive payload
            COO -> INV: Adjust inventory levels + restock statuses
            INV -> DB: Update InventoryLevel + ConsignmentItem
            DB --> INV: Updated quantities saved
            INV --> COO: Inventory adjustment result
            COO -> PAY: Initiate refund request
            PAY -> STRIPE: POST /v1/refunds referencing PaymentIntent
            STRIPE --> PAY: Refund pending response
            PAY -> AUDIT: Log refund initiation
            AUDIT --> PAY: Record committed
            PAY -> REPORT: Publish RefundInitiated event
            REPORT --> PAY: Event persisted for analytics
            STRIPE --> PAY: Webhook refund.succeeded
            PAY -> COO: Notify refund completion
            COO -> DB: Update ReturnAuthorization + Order status
            DB --> COO: Status persisted
            COO -> REPORT: Publish ReturnCompleted + InventoryRestocked
            REPORT --> COO: Dashboard refresh scheduled
            COO -> ADMIN: Notify staff of refund completion
            ADMIN -> Staff: Display final status + audit references
            SRE -> Customer: Update order timeline + confirmation
            @enduml
            ~~~
        * **Flow F - Headless API Catalog & Cart Integration:** External storefronts or marketing microsites call the headless REST APIs using OAuth client credentials; Tenant Access Gateway still resolves tenant context, FeatureToggle service limits exposed capabilities, and Checkout & Order Orchestrator plus Payment Integration Layer ensure carts and orders remain consistent even when the UI lives outside the bundled Qute/Vue experiences.
            - OAuth-scoped tokens embed tenantId, scopes, and throttling tiers so the platform can meter partner traffic without compromising tenant isolation or auditability.
            - Domain events still emit through Reporting to keep aggregated analytics aware of headless-origin orders, and background jobs reconcile inventory or payouts triggered via these channels without duplicating logic.
        * **Diagram (PlantUML):**
            ~~~plantuml
            @startuml
            actor HeadlessClient
            participant "Tenant Access Gateway" as TAG
            participant "Identity & Session Service" as ID
            participant "Feature Toggle Service" as FLAGS
            participant "Catalog & Inventory Module" as CAT
            participant "Checkout & Order Orchestrator" as COO
            participant "Integration Adapter Layer" as ADAPTER
            participant "Payment Integration Layer" as PAY
            participant "Stripe Connect Adapter" as STRIPE
            participant "Order Repository (PostgreSQL)" as DB
            participant "Reporting & Analytics Projection Service" as REPORT
            participant "Background Job Scheduler & Worker" as JOB
            HeadlessClient -> ID: POST /oauth/token (client credentials)
            ID -> DB: Validate client + tenant scopes
            DB --> ID: Client ok + rate limit tier
            ID --> HeadlessClient: accessToken + expiresIn + scope
            HeadlessClient -> TAG: GET /api/v1/tenants/{t}/products
            TAG -> TAG: Resolve tenant via host header mapping
            TAG -> FLAGS: Emit tenant resolved event
            FLAGS --> TAG: Feature set for headless tenant
            TAG -> CAT: Forward request with TenantContext
            CAT -> DB: Fetch catalog data via Panache filters
            DB --> CAT: Product/variant payload
            CAT --> HeadlessClient: JSON response + pagination meta
            HeadlessClient -> COO: POST /api/v1/tenants/{t}/cart/items
            COO -> DB: Persist cart (headless channel metadata)
            DB --> COO: Cart id + version
            COO --> HeadlessClient: Cart state
            HeadlessClient -> COO: POST /api/v1/tenants/{t}/checkout
            COO -> ADAPTER: Address validation + carrier rates
            ADAPTER --> COO: Results with SLA + rates
            COO -> PAY: Create payment intent (server-to-server)
            PAY -> STRIPE: HTTPS /payment_intents
            STRIPE --> PAY: Client secret + status
            PAY --> COO: PaymentIntent reference
            COO -> DB: Persist order + channel metadata
            DB --> COO: Order stored
            COO -> REPORT: Publish HeadlessOrderInitiated event
            REPORT --> COO: Event recorded
            HeadlessClient -> STRIPE: Present PaymentElement using secret
            STRIPE --> PAY: Webhook payment_intent.succeeded
            PAY -> COO: Notify success
            COO -> DB: Update order to Paid\nheadless_source=true
            DB --> COO: Status saved
            COO -> REPORT: Publish HeadlessOrderPaid event
            REPORT --> JOB: Trigger data sync if headless flag set
            JOB -> Reporting DB: Refresh aggregates + attribution
            JOB --> REPORT: Completion ack
            COO -> HeadlessClient: Send order confirmation payload
            HeadlessClient -> Customer: Render custom confirmation page
            @enduml
            ~~~
    * **Data Transfer Objects (DTOs):**
        - **Checkout Request DTO (`POST /api/v1/tenants/{tenantId}/checkout`)** contains `cartId`, `customerContext` (with either `customerId`, `guestEmail`, or `oauthProvider` info), `shippingAddress` normalized fields (`line1`, `line2`, `city`, `region`, `postalCode`, `country`, `latitude`, `longitude`, `validationStatus`), `billingAddress`, `shippingMethodId`, `deliveryPreferences`, `loyaltyRedemption` specifying `pointsToRedeem` and `currencyEquivalent`, `giftCardCodes[]`, `storeCreditAmount`, `payment` block referencing PaymentIntent placeholders, and `orderNotes`. Responses include `orderId`, `orderNumber`, `orderStatus`, `paymentIntentId`, `clientSecret`, `totals` (with `subtotal`, `tax`, `shipping`, `discounts`, `fees`, `grandTotal`, `currency`), `loyaltyBalanceAfter`, `giftCardBalances`, `fraudSignals`, and `featureFlagEcho` enumerating which optional flows ran (gift cards, subscriptions, preorder).
        - **Cart DTO (`GET /api/v1/tenants/{tenantId}/cart`)** exposes `cartId`, `version`, `customerContext`, `items[]` each with `itemId`, `productId`, `variantId`, `title`, `fulfilledBy`, `quantity`, `unitPrice`, `compareAtPrice`, `inventoryLocationId`, `consignmentInfo`, `customAttributes`, `lineDiscounts[]`, `lineTaxBreakdown[]`, plus cart-level `totals`, `lastActivityAt`, and `abandonmentTrackingId`. This DTO fuels Storefront Rendering Engine, Admin cart editors, POS, and headless integrations for persistent cart synchronization.
        - **Consignment Payout DTO (`POST /api/v1/admin/consignors/{consignorId}/payouts`)** receives `payoutItems[]` each with `consignmentItemId`, `orderId`, `saleAmount`, `commissionRate`, `commissionAmount`, `netAmount`, `approvedBy`, `notes`, and `evidenceLinks`. The payload also carries `payoutDestination` describing Stripe Connect account id, `batchMemo`, `expectedSettlementDate`, and `notifyConsignor` boolean. Responses return `payoutBatchId`, `transferReference`, `estimatedArrival`, `statementJobId`, `auditId`, `impersonationId` (if applicable), and `webhookEchoId` allowing platform automation to correlate downstream actions.
        - **Media Upload DTOs** include an initial `UploadRequestResponse` with `uploadId`, `tenantId`, `presignedUrl`, `headers`, `maxBytes`, `mimeWhitelist`, `expectedVariants`, and `lifecycleKey`. The completion payload expects `checksum`, `sourceFilename`, `mediaType` (`image`, `video`, `document`), `productId`, `variantBindings[]`, `altText`, `focalPoint`, and `accessLevel`. Media metadata responses deliver `mediaId`, `processingMode`, `variants[]` (size label, width, height, mime, storageKey, signedUrl, ttlSeconds, processingStatus, failureReason, `generatedByJobId`), `audit` (creator id, timestamps, impersonationId), and `cdnHints`.
        - **Platform Impersonation DTO (`POST /api/v1/platform/impersonations`)** accepts `targetTenantId`, `targetUserType` (`store_user`, `customer`), `targetUserId`, `reason`, `ticketReference`, `expectedDurationMinutes`, `scopes[]`, and `visibilityBanner`. The response echoes `impersonationId`, `actingAsJwt`, `expiresAt`, `visualIndicatorColor`, `exitUrl`, `auditId`, and `sessionLogId`. Subsequent admin or storefront calls include `impersonationId` headers so AuditEvent tables can stitch activity sequences across modules.
        - **Inventory Transfer DTO (`POST /api/v1/admin/inventory/transfers`)** carries `transferId`, `sourceLocationId`, `destinationLocationId`, `initiatedBy`, `lines[]` (variantId, quantity, reasonCode, consignorContext, expectedArrivalDate), and `shippingDetails` (carrier, tracking, cost). Responses provide `transferStatus`, `receivingInstructions`, `barcodeBatchId`, and `jobId` for label printing, enabling Admin SPA to subscribe to SSE updates when goods are received.
        - **POS Offline Batch DTO (`POST /api/v1/pos/offline/batches`)** includes `batchId`, `deviceId`, `encryptedPayload`, `encryptionVersion`, `generatedAt`, and `signature`. Backend responses include `replayJobId`, `acceptedCount`, `duplicateCount`, and `expectedCompletionTime`; once processed, an SSE message references `batchId` and each recreated `orderId` so register staff confirm reconciliation.
        - **Domain Event Envelope DTOs** stored in `domain_events` table share `eventId`, `eventType`, `occurredAt`, `tenantId`, `aggregateId`, `aggregateType`, `version`, `payload` (JSONB with schema per event), `metadata` (traceId, actorId, impersonationContext, originatingFeatureFlag, retryCount). Reporting, Background Jobs, and Integration Adapter hooks rely on this structure to process events idempotently, enabling safe retries and ensuring behavior sequences remain traceable under horizontal scaling.
        - **Error DTOs (RFC 7807 Problem Details)** include `type`, `title`, `status`, `detail`, `instance`, plus extensions for `tenantId`, `traceId`, `impersonationId`, `featureFlag`, and `nextSteps`. Clients such as PrimeUI toasts and Platform Admin alerts read these fields to show user-friendly copy while surfacing diagnostics to support teams; domain-specific errors (e.g., `https://docs.village/checkout/insufficient-inventory`) link directly to runbooks.
        - **Session Log DTO (`GET /api/v1/admin/sessions`)** exposes `sessionId`, `userType`, `userId`, `tenantId`, `ipAddress`, `userAgent`, `loginAt`, `lastActivityAt`, `logoutReason`, `deviceFingerprint`, and `impersonationContext`. Admin SPA and Platform Admin Console use this DTO to power security dashboards and user-facing session revocation features.
        - **Audit Event DTO (`GET /api/v1/admin/audit-events`)** returns `auditId`, `actorType`, `actorId`, `action`, `targetType`, `targetId`, `metadata`, `impersonationId`, `tenantId`, and `occurredAt`. Filtering by `action` or `targetType` allows staff to trace sensitive changes such as payment adjustments or consignment payouts.
        - **Background Job DTO (`GET /api/v1/admin/jobs/{id}`)** includes `jobId`, `queue`, `priority`, `payloadSchemaVersion`, `payload`, `attempts`, `lockedBy`, `lockedAt`, `runAt`, `finishedAt`, `status`, and `lastError`. This DTO underpins the reporting exports workflow as well as operational runbooks.
        - **Feature Flag DTO (`GET /api/v1/admin/feature-flags`)** lists `key`, `description`, `defaultState`, `allowedAudiences`, `tenantOverrides[]`, `owner`, `reviewCadence`, and `expiresAt`. Admins adjust overrides via PATCH requests to roll features forward or backward per tenant.
        - **Headless OAuth Token DTO (`POST /api/v1/oauth/token`)** responds with `accessToken`, `tokenType`, `expiresIn`, `scope`, `tenantId`, and `issuedAt`. Clients use the token to call catalog and cart APIs; revocation events log to SessionLog tables.
        - **Shipping Rate DTO (`POST /api/v1/tenants/{tenantId}/checkout/rates`)** receives `origin`, `destination`, `packageDetails`, and `cartItems` while responses provide `rates[]` each with `carrier`, `serviceLevel`, `estimatedDelivery`, `cost`, `surcharges`, and `featureFlagsApplied`. Checkout references this DTO to present shipping options consistently between storefront and admin fulfillment flows.
        - **Return Authorization DTO (`POST /api/v1/admin/orders/{orderId}/returns`)** carries `items[]` (orderLineId, quantity, condition, reasonCode), `photos[]`, `restockPreference`, `refundPreference`, and `notes`. Responses include `returnAuthorizationId`, `status`, `expectedReceiveBy`, `shippingLabelJobId`, and `auditId` so both staff and shoppers can track progress.
        - **Gift Card DTO (`POST /api/v1/admin/gift-cards`)** contains `code`, `initialBalance`, `currency`, `expiresAt`, `issuedToCustomerId`, and `notes`; the response echoes `giftCardId`, `balance`, `status`, and `activationUrl`. Checkout and loyalty modules consume this DTO when applying store credit or issuing refunds as credit.
        - **Customer Account DTO (`GET /api/v1/customers/me`)** includes `customerId`, `tenantId`, `email`, `name`, `defaultAddress`, `addresses[]`, `phone`, `loyaltyBalance`, `preferences`, `consentHistory[]`, and `featureFlags`. Storefront account pages and headless apps use this DTO to render profile data while respecting privacy toggles.
        - **Order Timeline DTO (`GET /api/v1/orders/{orderId}/timeline`)** returns `timelineEntries[]` each with `entryId`, `type` (status change, note, fulfillment, refund, return), `actorType`, `actorId`, `message`, `attachments[]`, `impersonationId`, and `occurredAt`. Both storefront and admin UIs leverage this DTO to present consistent order history narratives.
        - **Loyalty Ledger DTO (`GET /api/v1/admin/loyalty/ledgers`)** contains `entryId`, `customerId`, `tenantId`, `pointsDelta`, `reason`, `orderId`, `balanceAfter`, `expiresAt`, `tier`, and `source`. Checkout orchestrations consume this DTO to ensure redemptions stay within available balances, while reporting uses it to show accrual trends.
        - **POS Device DTO (`GET /api/v1/admin/pos/devices`)** surfaces `deviceId`, `tenantId`, `locationId`, `status`, `lastSeenAt`, `firmwareVersion`, `registeredBy`, `capabilities`, and `offlineQueueDepth`. POS management screens and support tools rely on this DTO to monitor hardware health and enforce access control.
        - **Platform Metrics DTO (`GET /api/v1/platform/metrics`)** aggregates `storesActive`, `storesSuspended`, `platformRevenue`, `ordersLast24h`, `impersonations`, `queueDepths`, and SLA indicators per module. Platform admins use this DTO to operate the SaaS business, and it maps directly to the Reporting & Analytics Projection Service read models.
        - **Shipment DTO (`GET /api/v1/orders/{orderId}/shipments`)** includes `shipmentId`, `carrier`, `serviceLevel`, `trackingNumber`, `packages[]` (weight, dimensions, contents), `labels[]`, `status`, `fulfilledBy`, `shippedAt`, `deliveredAt`, and `splitShipmentIndicator`. Admin fulfillment tools and storefront order tracking use this DTO to display accurate logistics states.
        - **Shipping Label DTO (`POST /api/v1/admin/shipments/{shipmentId}/labels`)** accepts `carrier`, `serviceLevel`, `packageDetails`, and `insuranceOptions`; responses return `labelUrl`, `labelPdf`, `trackingNumber`, `billingAccount`, and `jobId`. Integration Adapter Layer consumes this structure when negotiating with carrier APIs.
        - **Tax Configuration DTO (`GET /api/v1/admin/taxes`)** exposes `jurisdictions[]`, `nexusSettings`, `defaultRates`, `taxIncluded`, `overrideRules[]`, and `lastSyncedAt`, enabling Checkout orchestrations to compute taxes consistently and letting reporting reconcile collected tax by region.
        - **Subscription Plan DTO (`POST /api/v1/admin/subscriptions/plans`)** contains `planId`, `name`, `billingInterval`, `intervalCount`, `trialPeriodDays`, `price`, `currency`, `featureFlags`, and `status`; responses include `stripePriceId` when applicable so checkout can reference hosted plans via Payment Integration Layer.
        - **Discount Code DTO (`GET /api/v1/admin/discounts`)** returns `code`, `type`, `value`, `appliesTo`, `usageLimit`, `usageCount`, `startsAt`, `endsAt`, `stackingRules`, and `status`. Checkout references this DTO to evaluate eligibility while Admin SPA uses it for CRUD operations.
        - **Gift Card Redemption DTO (`POST /api/v1/tenants/{tenantId}/gift-cards/redeem`)** accepts `code`, `amount`, `currency`, `orderId`, and `actorContext`; responses include `remainingBalance`, `ledgerEntryId`, and `auditId`, ensuring ledger movements stay auditable.
        - **Carrier Credential DTO (`GET /api/v1/admin/shipping/carriers`)** includes `carrier`, `credentialsPresent`, `accountNumber`, `lastValidatedAt`, `sandboxMode`, and `featureFlags`. Integration adapters read this DTO to decide whether to enable certain service levels.
        - **Tenant Profile DTO (`GET /api/v1/admin/tenant-profile`)** bundles `tenantId`, `subdomain`, `customDomains[]`, `plan`, `status`, `branding`, `baseCurrency`, `configFlags`, `createdAt`, `suspendedAt`, and `opsContacts`. Admin SPA and Platform Console rely on this DTO for consistent store metadata displays.
        - **Platform Command DTO (`POST /api/v1/platform/commands`)** includes `commandId`, `commandType`, `targetTenantId`, `payload`, `requestedBy`, `reason`, `ticketReference`, and `rollbackPlan`. Responses echo `auditId`, `status`, and `jobId`, allowing platform ops to run maintenance tasks through well-audited channels.
        - **Consignor Portal DTO (`GET /api/v1/consignors/me`)** contains `consignorId`, `balance`, `pendingPayouts[]`, `inventorySummary`, `salesHistory[]`, `notifications[]`, and `portalSettings`. The dedicated portal consumes this DTO to display real-time balances without exposing internal admin endpoints.
        - **Payment Dispute DTO (`GET /api/v1/admin/payments/disputes`)** lists `disputeId`, `paymentIntentId`, `amount`, `currency`, `reason`, `status`, `evidenceDueAt`, `evidenceSubmittedAt`, and `attachments[]`. Support teams and Platform admins use it to coordinate evidence submission workflows.
        - **Media Processing Job DTO (`GET /api/v1/admin/media/jobs/{id}`)** provides `jobId`, `mediaId`, `type`, `status`, `attempts`, `lastError`, `queuedAt`, `startedAt`, `finishedAt`, `workerPod`, and `metrics` (duration, CPU time). This feeds monitoring dashboards and allows merchants to see why a video may be delayed.
        - **Report Filter DTO (`GET /api/v1/reports/filters`)** surfaces allowable `dimensions`, `metrics`, `timeGrains`, `comparisons`, and `featureFlagDependencies`, guiding Admin SPA when building filter UIs and ensuring only supported queries reach the reporting backend.
        - **Audit Export DTO (`POST /api/v1/admin/audit-exports`)** accepts `dateRange`, `actorTypes`, `actions`, `targets`, and `deliveryMode`; responses deliver `exportJobId`, `status`, `downloadUrl`, `expiresAt`, and `signature`, enabling compliance teams to extract snapshots without manual database access.
        - **Loyalty Tier DTO (`GET /api/v1/admin/loyalty/tiers`)** lists `tierId`, `name`, `threshold`, `benefits[]`, `multiplier`, `color`, and `status`. Checkout orchestrations consult this DTO to compute accrual multipliers, and Admin SPA uses it to manage tier rules.
        - **Endpoint Interaction Highlights:**
            ```text
            Endpoint                                      | Request DTO                   | Response DTO                   | Key Modules
            GET /api/v1/tenants/{t}/products              | ProductQueryDTO               | ProductListDTO                 | Storefront Rendering Engine, Catalog
            GET /api/v1/tenants/{t}/products/{id}         | N/A                           | ProductDetailDTO               | Storefront Rendering Engine, Headless API
            POST /api/v1/tenants/{t}/cart/items           | CartItemUpsertDTO             | CartDTO                        | Checkout & Order Orchestrator
            DELETE /api/v1/tenants/{t}/cart/items/{id}    | N/A                           | CartDTO                        | Checkout & Order Orchestrator
            GET /api/v1/tenants/{t}/cart                  | N/A                           | CartDTO                        | Storefront Rendering Engine, POS
            POST /api/v1/tenants/{t}/checkout             | CheckoutRequestDTO            | CheckoutResponseDTO            | Checkout & Order Orchestrator, Payment Layer
            POST /api/v1/admin/products                   | ProductWriteDTO               | ProductDetailDTO               | Admin SPA Delivery, Catalog
            PATCH /api/v1/admin/products/{id}             | ProductPatchDTO               | ProductDetailDTO               | Admin SPA Delivery, Catalog
            POST /api/v1/admin/bulk-imports/products      | BulkImportRequestDTO          | JobStatusDTO                   | Admin SPA Delivery, Background Job Scheduler
            GET /api/v1/admin/orders                      | OrderQueryDTO                 | OrderListDTO                   | Admin SPA Delivery, Checkout Orchestrator
            GET /api/v1/admin/orders/{id}                 | N/A                           | OrderDetailDTO                 | Admin SPA Delivery, Checkout Orchestrator
            POST /api/v1/admin/orders/{id}/notes          | OrderNoteDTO                  | OrderTimelineDTO               | Admin SPA Delivery, AuditEvent Repository
            POST /api/v1/admin/orders/{id}/shipments      | ShipmentCreateDTO             | ShipmentDTO                    | Admin SPA Delivery, Integration Adapter Layer
            POST /api/v1/admin/orders/{id}/refunds        | RefundRequestDTO              | RefundResponseDTO              | Admin SPA Delivery, Payment Integration Layer
            POST /api/v1/admin/consignors                 | ConsignorWriteDTO             | ConsignorDTO                   | Consignment Module
            POST /api/v1/admin/consignors/{id}/payouts    | ConsignmentPayoutDTO          | PayoutBatchDTO                 | Consignment, Payment Integration Layer
            GET /api/v1/admin/inventory/locations         | LocationQueryDTO              | InventoryLocationListDTO       | Inventory Module
            POST /api/v1/admin/media/upload-request       | MediaUploadRequestDTO         | UploadRequestResponse          | Media Pipeline Controller
            POST /api/v1/admin/media/uploads/{id}/complete| MediaUploadCompleteDTO        | MediaMetadataDTO               | Media Pipeline Controller, Catalog
            POST /api/v1/platform/impersonations          | ImpersonationRequestDTO       | ImpersonationResponseDTO       | Platform Admin Console, Identity Service
            GET /api/v1/platform/metrics                  | MetricsQueryDTO               | PlatformMetricsDTO             | Platform Admin Console, Reporting
            GET /api/v1/pos/offline/batches/{id}          | N/A                           | OfflineBatchStatusDTO          | POS Module, Background Job Scheduler
            GET /api/v1/tenants/{t}/headless/search       | SearchQueryDTO                | SearchResultDTO                | Headless API, Catalog Module
            POST /api/v1/reports/{reportType}             | ReportRequestDTO              | JobStatusDTO                   | Reporting Service, Background Jobs
            ```
        - **Domain Event Propagation Map:**
            ```text
            Event Name                    -> Producer Module               -> Consumers                                        -> Behavioral Outcome
            TenantResolved                -> Tenant Access Gateway         -> FeatureToggle, ThemeService                      -> Preload tenant-scoped caches
            ProductPublished              -> Catalog Module                -> Reporting, Search Indexer, Storefront Cache      -> Update aggregates + invalidate caches
            InventoryAdjusted             -> Inventory Module              -> Checkout Orchestrator, Reporting                  -> Recalculate availability + dashboards
            CartAbandoned                 -> Checkout Orchestrator         -> Background Email Jobs, Reporting                  -> Trigger abandonment emails + metrics
            OrderInitiated                -> Checkout Orchestrator         -> Reporting, Payment Layer, Fraud Analysis          -> Reserve inventory and start risk review
            OrderPaid                     -> Checkout Orchestrator         -> Reporting, Loyalty Module, Consignment            -> Accrue loyalty points, calculate commissions
            RefundInitiated               -> Checkout Orchestrator         -> Payment Layer, Reporting, Consignment             -> Hold payouts and notify accounting
            ReturnCompleted               -> Checkout Orchestrator         -> Inventory, Consignment, Reporting                 -> Restock inventory, adjust consignor balances
            MediaProcessed                -> Media Pipeline Controller     -> Catalog, Storefront Rendering Engine, Reporting   -> Publish optimized URLs and processing metrics
            ConsignorPayoutCompleted      -> Consignment Module            -> Reporting, AuditEvent Repository                  -> Update statements and compliance logs
            ImpersonationAudit            -> Platform Admin Console        -> Reporting, SessionLog Service                     -> Surface cross-tenant support activity
            JobFailed                     -> Background Job Scheduler      -> Ops Alerting, Retry Coordinator                   -> Trigger alerts and exponential backoff
            ```
        - **Queue Priority Catalogue:**
            ```text
            Queue      Priority   Example Jobs                                   Notes
            CRITICAL   Highest    Stripe webhook ingestion, Payment retries      Runs faster pods; failure alerts paging
            HIGH       High       Checkout orchestration compensations, POS sync Requires idempotent handlers
            DEFAULT    Medium     Catalog indexing, Consignor statements         Balanced throughput vs resource usage
            LOW        Low        Media thumbnail regeneration, Report exports   Can be throttled during peak checkout hours
            BULK       Lowest     CSV imports, Historical replays                Executed during maintenance windows only
            ```
        - **DTO Validation & Versioning Principles:** Requests include `dtoVersion` fields for payloads that evolve rapidly (checkout, payout, media); servers validate version compatibility, emit `409` with remediation instructions when deprecated structures arrive, and log mismatches for monitoring.
        - **Error Contract Examples:**
            ```json
            {
              "type": "https://docs.village/errors/checkout/insufficient-inventory",
              "title": "Inventory reservation failed",
              "status": 409,
              "detail": "Variant SKU-123 is no longer available in the requested quantity.",
              "instance": "/api/v1/tenants/acme/checkout/12345",
              "tenantId": "acme",
              "traceId": "2f0f5a",
              "impersonationId": null,
              "remediation": "Reduce quantity or select a different variant."
            }
            ```
        - **Sample Checkout Request Payload:**
            ```json
            {
              "cartId": "cart_123",
              "customerContext": { "customerId": "cus_90", "email": "shopper@example.com" },
              "shippingAddress": { "line1": "10 Main St", "city": "Portland", "region": "OR", "postalCode": "97201", "country": "US" },
              "billingAddress": { "line1": "10 Main St", "city": "Portland", "region": "OR", "postalCode": "97201", "country": "US" },
              "shippingMethodId": "ground",
              "loyaltyRedemption": { "pointsToRedeem": 500 },
              "giftCardCodes": ["HOLIDAY25"],
              "payment": { "paymentIntentId": "pi_abc" },
              "orderNotes": "Leave at front desk"
            }
            ```
        - **Sample Media Upload Completion Payload:**
            ```json
            {
              "checksum": "a1b2c3d4",
              "sourceFilename": "summer-lookbook.jpg",
              "mediaType": "image",
              "productId": "prod_123",
              "variantBindings": ["var_red_small"],
              "altText": "Model wearing summer dress",
              "focalPoint": { "x": 0.45, "y": 0.35 },
              "accessLevel": "public"
            }
            ```
        - **Sample Consignment Payout Response:**
            ```json
            {
              "payoutBatchId": "pay_batch_55",
              "transferReference": "tr_987",
              "estimatedArrival": "2024-07-12T00:00:00Z",
              "statementJobId": "job_456",
              "auditId": "audit_7788",
              "impersonationId": null,
              "webhookEchoId": "webhook_12"
            }
            ```
        - **Sample Reporting Job Status Payload:**
            ```json
            {
              "jobId": "rep_job_1001",
              "reportType": "sales-by-period",
              "status": "processing",
              "queuedAt": "2024-07-01T10:00:00Z",
              "startedAt": "2024-07-01T10:01:05Z",
              "progress": 42,
              "downloadUrl": null,
              "estimatedCompletion": "2024-07-01T10:03:00Z"
            }
            ```
        - **Sample OAuth Token Response Payload:**
            ```json
            {
              "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
              "tokenType": "bearer",
              "expiresIn": 3600,
              "scope": "catalog:read cart:write",
              "tenantId": "artisan-bakery"
            }
            ```
        - **DTO Lifecycle Guardrails:** Each DTO lists `createdAt` and `updatedAt` when relevant, uses snake_case for database columns but camelCase for JSON fields, and stores encrypted blobs (e.g., tax identifiers) as opaque tokens referencing pgcrypto columns.
