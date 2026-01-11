# Specification Review & Recommendations: Village Storefront SaaS Platform

**Date:** 2026-01-10
**Updated:** 2026-01-11
**Status:** ✅ Resolved - All clarifications recorded in specifications.md

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a production-grade, multi-tenant SaaS ecommerce platform enabling merchants to operate independent online stores with consignment vendor support, built on Java 21/Quarkus with native compilation targeting Kubernetes deployment, featuring dual frontends (Qute-templated storefronts and Vue.js admin dashboards) with Stripe-based payment processing and comprehensive inventory, order, and loyalty management capabilities.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: Tenant Resolution Strategy for Custom Domains**

*   **Observation:** The specification mandates both subdomain-based (`store.platform.com`) and custom domain (`shop.merchant.com`) routing, with tenant resolution occurring in a `@Provider` JAX-RS filter that inspects the Host header. The custom domain resolution mechanism mentions a `custom_domains` table lookup, but the architecture for handling SSL termination, routing precedence, and DNS validation timing is underspecified.
*   **Architectural Impact:** This directly affects ingress controller configuration, certificate management complexity, and the boundary between application-level and infrastructure-level routing logic.
    *   **Path A (Application-First Routing):** The JAX-RS filter performs both subdomain parsing and custom domain table lookup. Ingress controller uses wildcard routing (`*.platform.com` + catch-all for custom domains). Application returns 404 for unrecognized hosts.
    *   **Path B (Ingress-Managed Routing):** Custom domains are registered as explicit Ingress rules when validated. Application filter only handles subdomain parsing; custom domain requests arrive pre-routed with tenant context injected via header.
    *   **Path C (Hybrid with Edge Routing):** Cloudflare Workers or similar edge compute layer resolves custom domains to tenant IDs, injects `X-Tenant-ID` header before request reaches k8s cluster. Application trusts injected header for custom domains, falls back to subdomain parsing for platform domains.
*   **Default Assumption & Required Action:** To minimize infrastructure dependencies and maintain application control, the system will assume **Path A (Application-First Routing)** with wildcard Ingress rules and application-layer tenant resolution for all request types. **The specification must be updated** to explicitly define the tenant resolution architecture, including how custom domain Ingress rules are created (cert-manager Certificate resources vs dynamic Ingress patching), whether DNS validation occurs synchronously during domain addition or asynchronously via background jobs, and the intended behavior for DNS propagation delays (immediate activation vs validation-gated activation).

---

#### **Assertion 2: Media Processing Execution Environment & Resource Isolation**

*   **Observation:** The specification mandates FFmpeg-based video transcoding with a "Dedicated Worker Pods (Tier 2)" execution model, where media worker pods subscribe only to the CRITICAL queue. However, the mechanism for FFmpeg installation in native GraalVM images, subprocess execution constraints within distroless containers, and the pod autoscaling trigger metrics source are undefined.
*   **Architectural Impact:** This is a critical operational decision affecting container image size, build complexity, runtime security posture, and the feasibility of the entire video processing feature.
    *   **Path A (FFmpeg Native Binary in Image):** Include FFmpeg static binary in Docker image at build time. Increases image size by ~100MB, requires non-distroless base (Alpine or similar). ProcessBuilder execution is straightforward.
    *   **Path B (FFmpeg Sidecar Container):** Run FFmpeg as a sidecar container in worker pods. Main application container communicates via shared volume. Maintains distroless main image, adds pod scheduling complexity.
    *   **Path C (External Processing Service):** Delegate video transcoding to external API (e.g., Cloudflare Stream, AWS Elemental). Eliminates FFmpeg dependency entirely, introduces per-transcode cost and external service dependency.
    *   **Path D (Defer Video Transcoding to Post-MVP):** Launch with image processing only, add video support in Phase 2 after validating native compilation constraints with simpler media operations.
*   **Default Assumption & Required Action:** To preserve the native compilation benefits while maintaining operational simplicity, the system will assume **Path A (FFmpeg Native Binary in Alpine-based Image)** with explicit acknowledgment that the container size target increases from 50-100MB to 150-200MB for media worker pods. Web pods remain distroless without FFmpeg. **The specification must be updated** to explicitly define the FFmpeg integration strategy, including container base image choice for worker pods, FFmpeg version and build flags, subprocess timeout enforcement mechanism, and whether video processing is a hard requirement for Phase 1 MVP or can be deferred.

---

#### **Assertion 3: Background Job Queue Persistence & Failure Recovery Strategy**

*   **Observation:** The specification mandates a DelayedJob-style database-persisted job queue with priority levels and exponential backoff retry logic, referencing `java-project-standards.adoc` for implementation details. However, the job table schema, lock acquisition strategy for concurrent pod execution, and the mechanism for detecting and recovering orphaned jobs (jobs claimed by pods that crash mid-execution) are undefined.
*   **Architectural Impact:** This is foundational to system reliability for critical operations like vendor payouts, email delivery, and media processing. Incorrect implementation risks duplicate job execution, lost jobs, or database lock contention.
    *   **Path A (Row-Level Locking with SELECT FOR UPDATE):** Jobs table includes `status` (pending/processing/completed/failed) and `locked_by` (pod identifier) columns. Workers use `SELECT FOR UPDATE SKIP LOCKED` to claim jobs atomically. Orphaned job detection via scheduled cleanup task that resets jobs locked by dead pods (detected via heartbeat timeout).
    *   **Path B (Advisory Locks with Timestamp Leases):** PostgreSQL advisory locks combined with `lease_expires_at` timestamp. Workers acquire advisory lock, set lease timestamp, execute job, release lock. Lease expiration allows automatic orphan recovery without explicit cleanup task.
    *   **Path C (Quarkus Scheduler with Database State Tracking):** Use `@Scheduled` methods to poll for pending jobs, update status via optimistic locking (version column). Simpler concurrency model, but lacks native priority queue support and requires custom retry logic.
*   **Default Assumption & Required Action:** To leverage PostgreSQL's native concurrency control while maintaining clean failure recovery semantics, the system will assume **Path A (Row-Level Locking with SELECT FOR UPDATE SKIP LOCKED)** with a dedicated orphan detection scheduled task running every 5 minutes. **The specification must be updated** to explicitly define the job queue table schema (including all status values, priority enum, retry count, error logging columns), the lock acquisition query pattern, the orphan detection criteria (e.g., jobs in `processing` state for >10 minutes with no heartbeat), and whether job execution history is retained indefinitely or pruned after a retention period.

---

#### **Assertion 4: Consignment Vendor Balance Crediting Trigger & Refund Window Definition**

*   **Observation:** The specification defines a Two-Phase Balance model where vendor balances are credited "after refund window expires (30 days post-fulfillment)." However, the triggering event for the 30-day countdown (order creation, payment capture, or shipment tracking confirmation) and the behavior for orders with split shipments or partial fulfillment are undefined.
*   **Architectural Impact:** This directly affects vendor payout timing accuracy, customer refund policy alignment, and the complexity of balance sweep job queries.
    *   **Path A (Trigger on Payment Capture):** 30-day countdown starts when order payment is captured. Simple to implement, but misaligns with actual product delivery timing for slow shipping methods.
    *   **Path B (Trigger on Shipment Confirmation):** 30-day countdown starts when order status changes to "Shipped" and carrier tracking number is recorded. Aligns with physical product delivery, requires shipment tracking integration to be operational.
    *   **Path C (Trigger on Delivered Status):** 30-day countdown starts when carrier reports "Delivered" status via webhook. Most accurate for customer satisfaction window, introduces dependency on carrier API reliability.
    *   **Path D (Configurable Per-Store Policy):** Store owners configure refund window trigger in settings (payment/shipment/delivery). Maximum flexibility, adds configuration complexity and requires clear documentation of implications.
*   **Default Assumption & Required Action:** To balance implementation simplicity with customer-centric refund alignment, the system will assume **Path B (Trigger on Shipment Confirmation)** with the 30-day window starting when an order's status transitions to "Shipped." For split shipments, each shipment triggers independent 30-day windows for its line items. Orders never shipped remain in `pending_balance` indefinitely until fulfilled or cancelled. **The specification must be updated** to explicitly define the refund window trigger event, the handling of split shipments and partial fulfillment, the behavior for digital products and services (which have no shipment event), and whether stores can customize the 30-day duration or if it is platform-wide policy.

---

#### **Assertion 5: Platform Admin Impersonation Session Security & Audit Granularity**

*   **Observation:** The specification mandates impersonation logging with "who impersonated whom, timestamp and duration, actions taken during impersonation, reason/ticket reference." However, the mechanism for tracking "actions taken" (all HTTP requests, only mutations, or specific high-risk operations), the storage location for impersonation audit logs (within tenant-scoped tables or separate audit schema), and the enforcement strategy for mandatory reason field are undefined.
*   **Architectural Impact:** This affects compliance readiness (SOC2, GDPR Article 32), database schema design, query performance for audit reports, and the feasibility of forensic investigations.
    *   **Path A (Request-Level Audit Logging):** Every HTTP request during an impersonation session is logged to a separate `impersonation_audit_log` table outside RLS scope, including method, path, request body hash, and response status. Comprehensive but high write volume.
    *   **Path B (Mutation-Only Audit Logging):** Only POST/PUT/PATCH/DELETE requests logged during impersonation. Reduces volume by ~80-90%, loses visibility into data access patterns (GET requests).
    *   **Path C (High-Risk Operation Tracking):** Application code explicitly logs impersonation context for sensitive operations (refunds, customer data exports, password resets). Minimal overhead, requires disciplined instrumentation.
    *   **Path D (Session Summary with Sampling):** Log impersonation session start/end with reason, sample 10% of requests for detailed logging. Balances compliance with performance, may not satisfy strict audit requirements.
*   **Default Assumption & Required Action:** To meet SOC2 Type II audit expectations while maintaining system performance, the system will assume **Path B (Mutation-Only Audit Logging)** where all state-changing HTTP requests during impersonation sessions are logged to a dedicated `platform_impersonation_audit` table with columns for `platform_admin_id`, `impersonated_user_id`, `tenant_id`, `timestamp`, `http_method`, `request_path`, `request_body_hash`, `response_status`, and `session_id`. Session metadata (reason, ticket reference, start/end times) stored in separate `platform_impersonation_sessions` table. **The specification must be updated** to explicitly define the audit logging granularity, the retention period for impersonation logs (currently states 7 years, confirm this applies to all audit data or only session metadata), the enforcement mechanism for mandatory reason field (database constraint, application validation, or UI-only), and whether audit logs are queryable by store admins (to see when they were impersonated) or restricted to platform admins only.

---

#### **Assertion 6: Session Activity Partitioning & Archive Compression Strategy**

*   **Observation:** The specification mandates time-based partitioning with monthly partitions, 90-day hot storage, and archival to R2 in JSONL/gzip format. However, the partition boundary definition (calendar month vs 30-day rolling window), the archival job execution timing (end of month, daily incremental, or on-demand), and the query strategy for date ranges spanning partition boundaries are undefined.
*   **Architectural Impact:** This affects query performance, storage costs, archival job complexity, and the user experience for reports that cross partition boundaries.
    *   **Path A (Calendar Month Partitions with Monthly Archive Job):** Partitions aligned to calendar months (`sessions_2026_01`, `sessions_2026_02`). Archive job runs on 1st of each month to archive partitions >90 days old. Simple boundary logic, but creates large batch operations.
    *   **Path B (30-Day Rolling Partitions with Daily Incremental Archive):** Partitions span exactly 30 days from creation. Archive job runs daily, archives partitions >90 days old incrementally. Smoother resource usage, more complex partition management.
    *   **Path C (Weekly Partitions with Weekly Archive Cadence):** Partitions aligned to ISO weeks. Archive job runs weekly. Balances batch size with operational complexity.
*   **Default Assumption & Required Action:** To align with operational intuition and simplify archive retrieval semantics, the system will assume **Path A (Calendar Month Partitions with Monthly Archive Job)** where partitions are named `sessions_YYYY_MM` and `audit_logs_YYYY_MM`, and a scheduled job executes on the 1st of each month to archive and drop partitions older than 90 days. Queries spanning partition boundaries will be handled via UNION ALL across relevant partitions for hot data, with archived data accessible only via CSV export. **The specification must be updated** to explicitly define partition boundary alignment, archive job execution schedule, the partition naming convention, whether partition creation is automated or requires manual intervention during deployment, and the expected query pattern for multi-partition reporting (application-layer union vs database view).

---

#### **Assertion 7: Loyalty Points Redemption Reservation Timeout & Concurrent Checkout Handling**

*   **Observation:** The specification mandates a Two-Phase Commit model for points redemption with a 15-minute reservation timeout and automatic release via scheduled job. However, the scheduled job execution frequency (every 1 minute, 5 minutes, 15 minutes), the locking strategy to prevent race conditions between checkout completion and timeout expiration, and the user experience when a reservation expires mid-checkout are undefined.
*   **Architectural Impact:** This affects reservation accuracy, database transaction contention, and customer frustration during checkout abandonment recovery.
    *   **Path A (1-Minute Scheduled Job with Optimistic Locking):** Job runs every 1 minute, releases reservations with `reserved_at < NOW() - INTERVAL '15 minutes'`. Uses optimistic locking (version column) to prevent conflicts with checkout completion. Tight timeout enforcement, higher job execution frequency.
    *   **Path B (5-Minute Scheduled Job with Pessimistic Locking):** Job runs every 5 minutes, acquires row locks before releasing. Looser timeout window (reservations may persist up to 20 minutes), lower database load.
    *   **Path C (Database Trigger with Lazy Cleanup):** PostgreSQL trigger on `orders` table automatically releases reservation on order creation. Scheduled job only handles abandoned carts (no order created). Real-time cleanup, more complex trigger logic.
    *   **Path D (Application-Layer Timeout Check on Redemption Attempt):** No scheduled job. When user attempts to redeem points, application checks if any expired reservations exist for that user and releases them synchronously before processing new redemption. Simplest implementation, tolerates stale reservations until next redemption attempt.
*   **Default Assumption & Required Action:** To balance reservation accuracy with system load, the system will assume **Path A (1-Minute Scheduled Job with Optimistic Locking)** where a Quarkus `@Scheduled` method executes every 60 seconds, queries for expired reservations, and releases them using optimistic locking via JPA version column to prevent conflicts with concurrent checkout completion transactions. **The specification must be updated** to explicitly define the scheduled job execution interval, the locking strategy for reservation release, the user experience when checkout attempts to complete after reservation expiration (graceful error with option to re-reserve or hard failure), and whether reservation timeout is configurable per-store or platform-wide policy.

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase.
