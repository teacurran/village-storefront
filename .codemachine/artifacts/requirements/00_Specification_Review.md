# Specification Review & Recommendations: Village Storefront - Multi-Tenant SaaS Ecommerce Platform

**Date:** 2026-01-02
**Status:** Awaiting Specification Enhancement

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a multi-tenant SaaS ecommerce platform enabling small-to-medium merchants to operate independent online stores with subdomain/custom domain access, consignment vendor management, and comprehensive product/inventory/order management capabilities built on Java 21/Quarkus with GraalVM native compilation targeting Kubernetes deployment.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: Tenant Isolation Strategy & Database Architecture**

*   **Observation:** The specification mandates strict tenant isolation with all data tenant-scoped, but does not define the isolation implementation pattern at the database layer.
*   **Architectural Impact:** This is a foundational decision affecting database schema design, query performance, migration complexity, and operational safety.
    *   **Path A (Shared Database, Discriminator Column):** Single PostgreSQL database with `tenant_id` on all tables. Simplest deployment, but requires rigorous row-level security policies and application-layer guards to prevent cross-tenant data leakage. Highest performance density, lowest operational overhead.
    *   **Path B (Schema-per-Tenant):** Single PostgreSQL instance with dedicated schema per tenant. Moderate isolation, schema-level access control, simpler backup/restore per tenant. Migration complexity increases linearly with tenant count.
    *   **Path C (Database-per-Tenant):** Separate PostgreSQL database per tenant. Maximum isolation and security, independent scaling per tenant, but significantly higher operational complexity and infrastructure cost.
*   **Default Assumption & Required Action:** To optimize for MVP velocity and operational simplicity while maintaining security, the system will be architected assuming **Path A (Shared Database with Discriminator Column)** enforced via PostgreSQL Row-Level Security policies + application-layer tenant context injection. **The specification must be updated** to explicitly define the tenant isolation pattern, acceptable cross-tenant data leakage risk tolerance, and whether per-tenant database backup/restore is a hard requirement.

---

#### **Assertion 2: Custom Domain SSL Certificate Provisioning & Renewal Architecture**

*   **Observation:** The specification requires custom domain support with "automatic SSL handling" but does not define the certificate acquisition, validation, and renewal mechanism.
*   **Architectural Impact:** This decision affects infrastructure dependencies, DNS configuration requirements, certificate storage strategy, and operational monitoring needs.
    *   **Path A (ACME Protocol with Let's Encrypt):** Implement ACME client within the application to request certificates via HTTP-01 or DNS-01 challenge. Requires either HTTP server on port 80 for validation or DNS API integration. Certificate storage in database or object storage. Auto-renewal via scheduled job.
    *   **Path B (Cloudflare SSL for SaaS):** Delegate SSL management to Cloudflare's SSL for SaaS product. Merchant adds CNAME, platform issues certificate via Cloudflare API. Zero application-level certificate management, but introduces Cloudflare dependency and potential cost per custom domain.
    *   **Path C (AWS Certificate Manager + CloudFront):** Use ACM for certificate provisioning with CloudFront distribution per custom domain. Managed renewal, but requires AWS infrastructure and CloudFront configuration per domain.
*   **Default Assumption & Required Action:** To minimize external dependencies and operational cost for MVP, the system will assume **Path A (ACME Protocol with Let's Encrypt)** using HTTP-01 challenge with certificate storage in object storage and a background job for renewal monitoring. **The specification must be updated** to define acceptable certificate provisioning latency (immediate vs. minutes), DNS control requirements for merchants, and whether third-party SSL management services are acceptable.

---

#### **Assertion 3: Background Job Processing & Async Task Architecture**

*   **Observation:** The specification references background jobs for media processing, consignment payouts, email notifications, and certificate renewal, but does not define the job queue/scheduler architecture.
*   **Architectural Impact:** This variable dictates infrastructure dependencies, failure recovery strategies, job persistence, and horizontal scaling characteristics.
    *   **Path A (Quarkus Scheduler with Database Persistence):** Use Quarkus @Scheduled annotations with job state persisted to PostgreSQL. Simple, zero external dependencies, but limited to single-node execution without distributed locking. Suitable for MVP with moderate job volume.
    *   **Path B (Quarkus + Quartz Scheduler):** Integrate Quartz for distributed job scheduling with database-backed job store. Multi-node execution with clustering support, but adds complexity and requires Quartz schema management.
    *   **Path C (External Message Queue - Redis/RabbitMQ/Kafka):** Dedicated message broker for async job distribution. Highest scalability and resilience, but introduces external infrastructure dependency counter to "No Redis" constraint.
*   **Default Assumption & Required Action:** Adhering to the "No Redis" constraint and minimizing infrastructure complexity, the system will assume **Path A (Quarkus Scheduler with Database Persistence)** with optimistic locking for job claim semantics. **The specification must be updated** to define expected job volume (jobs/hour), acceptable job execution latency, and whether multi-node job distribution is required for MVP.

---

#### **Assertion 4: Real-Time Shipping Rate Calculation Integration Scope**

*   **Observation:** The specification mandates real-time shipping rates from USPS, UPS, and FedEx in cart and at checkout, but does not define carrier API integration depth or fallback strategies.
*   **Architectural Impact:** This decision affects third-party API dependencies, rate accuracy, checkout abandonment risk, and operational cost (carrier API fees).
    *   **Path A (Direct Carrier API Integration):** Implement native integrations with USPS Web Tools, UPS Rating API, and FedEx Web Services. Maximum rate accuracy and control, but requires managing three separate API contracts, credential sets, and error handling strategies.
    *   **Path B (Third-Party Aggregator - EasyPost/Shippo):** Integrate with shipping aggregation service providing unified API for all carriers. Simplified integration, single credential set, built-in fallbacks, but introduces monthly/per-label fees and external dependency.
    *   **Path C (Hybrid - Flat Rate with Optional Real-Time):** Merchants configure flat-rate or table-rate shipping by default, with optional real-time carrier integration for stores requiring it. Reduces critical path dependencies for MVP.
*   **Default Assumption & Required Action:** To de-risk MVP delivery and minimize integration surface area, the system will assume **Path C (Hybrid Model)** with flat-rate/table-rate shipping as baseline and Phase 2 delivery of real-time carrier integration via aggregator (Path B). **The specification must be updated** to define whether real-time carrier rates are hard requirements for MVP launch or acceptable as post-launch enhancement.

---

#### **Assertion 5: Consignment Vendor Automated Payout Mechanism & Compliance**

*   **Observation:** The specification requires "automated payouts" and "integration with payment service for vendor payments" but does not define the payout rail, compliance requirements, or vendor onboarding workflow.
*   **Architectural Impact:** This decision affects payment processor selection (Stripe Connect Platform vs. Express), vendor tax reporting obligations (1099-K generation), KYC/identity verification requirements, and payout timing.
    *   **Path A (Stripe Connect - Express Accounts):** Vendors create Stripe Express accounts, platform uses Stripe transfers to pay out commissions. Stripe handles 1099-K reporting and compliance. Requires vendor SSN/EIN collection and identity verification. Payout timing controlled by Stripe (2-7 days).
    *   **Path B (Stripe Connect - Custom Accounts):** Platform has full control over payout UX and timing, but assumes 1099-K reporting and compliance burden. Requires building identity verification workflow and annual tax form generation.
    *   **Path C (Manual Payout with ACH Integration):** Platform generates payout reports, merchant initiates manual ACH transfers via bank portal. Zero automation, but eliminates payment processor dependency for payouts. Acceptable only if payout volume is very low.
*   **Default Assumption & Required Action:** To leverage Stripe's compliance infrastructure and minimize regulatory risk, the system will assume **Path A (Stripe Connect Express Accounts)** with vendor onboarding collecting tax details and triggering Stripe identity verification. **The specification must be updated** to define acceptable payout frequency (daily, weekly, monthly), whether platform or vendor bears payout fees, and whether 1099-K generation must be handled by platform or can be delegated to Stripe.

---

#### **Assertion 6: Media Processing Execution Environment & Resource Limits**

*   **Observation:** The specification mandates FFmpeg video transcoding and Thumbnailator image processing but does not define execution environment (in-process vs. isolated), resource limits, or timeout policies.
*   **Architectural Impact:** This decision affects application pod resource allocation, crash risk from malformed media, job execution latency, and infrastructure cost.
    *   **Path A (In-Process Execution):** FFmpeg invoked via ProcessBuilder within Quarkus application pods. Simplest architecture, but risks pod OOM/crash from large files and consumes application pod CPU during processing. Requires generous pod memory limits and careful timeout enforcement.
    *   **Path B (Kubernetes Job per Media File):** Spawn dedicated K8s Job for each video transcode operation. Isolated resource limits, no risk to application pods, but introduces job orchestration complexity and requires pod autoscaling for job workers.
    *   **Path C (External Processing Service):** Delegate transcoding to external service (e.g., AWS MediaConvert, Cloudflare Stream). Zero application resource impact, optimized transcoding quality, but introduces per-minute processing fees and external dependency.
*   **Default Assumption & Required Action:** Balancing MVP simplicity with pod stability, the system will assume **Path A (In-Process Execution)** with strict timeout enforcement (5 minutes for video, 30 seconds for images) and configurable per-tenant upload size limits (default 50MB images, 500MB video as specified). **The specification must be updated** to define acceptable media processing latency, whether transcoding failures should block upload or queue for retry, and maximum expected concurrent processing load.

---

#### **Assertion 7: Session Activity Logging & Impersonation Audit Storage Strategy**

*   **Observation:** The specification requires comprehensive session logging, impersonation audit trails, and activity reports, but does not define the data retention policy, storage tier, or query access patterns.
*   **Architectural Impact:** This decision affects database growth rate, query performance for reporting, compliance with data retention regulations, and long-term storage cost.
    *   **Path A (PostgreSQL with Partitioning):** Store all session/impersonation logs in PostgreSQL using time-based partitioning (monthly). Query via SQL for reports. Simple architecture, but unlimited retention leads to unbounded database growth. Requires partition maintenance job.
    *   **Path B (PostgreSQL + Archival to Object Storage):** Store recent logs (90 days) in PostgreSQL, archive older records to S3/R2 as compressed JSON. Requires dual query path for historical reports (SQL for recent, object scan for archive). Balances query performance and cost.
    *   **Path C (Dedicated Audit Log Store - Elasticsearch/Loki):** Stream audit events to dedicated log aggregation system optimized for time-series data. Maximum query flexibility and retention scalability, but introduces external infrastructure dependency.
*   **Default Assumption & Required Action:** To maintain simplicity while controlling database growth, the system will assume **Path B (PostgreSQL + Archival to Object Storage)** with 90-day retention in database and annual archival to object storage. **The specification must be updated** to define regulatory retention requirements (e.g., SOC2/GDPR audit log retention periods), acceptable query latency for historical reports, and whether real-time session activity monitoring is required.

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase.
