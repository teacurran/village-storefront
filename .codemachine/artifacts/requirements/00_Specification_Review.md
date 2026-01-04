# Specification Review & Recommendations: Village Storefront SaaS Platform

**Date:** 2026-01-02
**Status:** Awaiting Specification Enhancement

### **1.0 Executive Summary**

This document is an automated analysis of the provided project specifications. It has identified critical decision points that require explicit definition before architectural design can proceed.

**Required Action:** The user is required to review the assertions below and **update the original specification document** to resolve the ambiguities. This updated document will serve as the canonical source for subsequent development phases.

### **2.0 Synthesized Project Vision**

*Based on the provided data, the core project objective is to engineer a system that:*

Delivers a multi-tenant SaaS ecommerce platform enabling merchants to operate independent online stores with consignment vendor management, leveraging Java 21/Quarkus for backend services, Qute templates for customer-facing storefronts, and Vue.js for administrative interfaces, all deployed as GraalVM native images on Kubernetes infrastructure.

### **3.0 Critical Assertions & Required Clarifications**

---

#### **Assertion 1: Platform Super-User Access Strategy & Data Access Boundaries**

*   **Observation:** The specification defines platform admin capabilities including store suspension, analytics access, and impersonation features, but does not clarify the architectural isolation model between platform-level operations and tenant-level data access.
*   **Architectural Impact:** This is a security-critical decision affecting database access patterns, audit logging depth, regulatory compliance posture, and the design of the multi-tenant data isolation layer.
    *   **Path A (Shared Isolation Layer):** Platform admins access tenant data through the same RLS/Panache filter mechanisms as regular users, with context overrides for impersonation. Simpler implementation, but requires careful audit trail architecture.
    *   **Path B (Separate Access Plane):** Platform admin operations use dedicated database roles with explicit cross-tenant query capabilities, bypassing RLS entirely. More complex, but provides clearer security boundaries and compliance audit separation.
    *   **Path C (API-Mediated):** Platform admins never query tenant tables directly; all cross-tenant operations proxied through dedicated service layer with explicit authorization checks. Maximum auditability, highest development overhead.
*   **Default Assumption & Required Action:** To balance security and development velocity, the system will assume **Path A (Shared Isolation Layer)** with mandatory impersonation audit logging to separate tables outside RLS scope. **The specification must be updated** to explicitly define the platform admin data access model, required audit granularity for compliance, and any regulatory frameworks the platform must satisfy (SOC2, GDPR, etc.).

---

#### **Assertion 2: Custom Domain SSL Certificate Provisioning & Renewal Architecture**

*   **Observation:** The specification states "automatic SSL via Let's Encrypt (ACME HTTP-01 challenge)" for custom domains but does not define the certificate storage strategy, renewal orchestration, or ingress controller integration model.
*   **Architectural Impact:** This decision fundamentally affects infrastructure dependencies, certificate lifecycle management complexity, and the coupling between application logic and Kubernetes ingress configuration.
    *   **Path A (Application-Managed):** Quarkus application performs ACME challenges, stores certificates in database, programmatically updates Kubernetes Ingress/TLS secrets. Tightly coupled, requires elevated RBAC permissions for pods.
    *   **Path B (Operator-Delegated):** External cert-manager operator handles ACME challenges and certificate lifecycle, application only registers domain records. Loosely coupled, standard k8s pattern, but adds infrastructure dependency.
    *   **Path C (Cloudflare Proxy):** All custom domains proxied through Cloudflare with Universal SSL, application only manages DNS CNAME records. Simplest implementation, vendor lock-in, requires Cloudflare paid plan for multiple domains.
*   **Default Assumption & Required Action:** The architecture will assume **Path B (Operator-Delegated)** using cert-manager with HTTP-01 challenges, as it aligns with Kubernetes best practices and minimizes application security surface. **The specification must be updated** to define the acceptable infrastructure dependencies, certificate storage requirements, and whether vendor-managed SSL (Cloudflare) is architecturally permissible.

---

#### **Assertion 3: Multi-Currency Settlement & Exchange Rate Authority**

*   **Observation:** The specification mandates "multi-currency display with real-time or daily exchange rates" but does not specify the authoritative exchange rate source, settlement currency conversion strategy, or handling of rate fluctuations between display and payment capture.
*   **Architectural Impact:** This variable affects payment processor integration complexity, revenue recognition accuracy, merchant financial reporting, and potential forex risk exposure.
    *   **Path A (Display-Only Conversion):** Exchange rates used solely for customer-facing price display; all transactions settled in store base currency at Stripe's market rate at time of charge. Simple, zero forex risk for platform, rate mismatch potential between display and charge.
    *   **Path B (Locked Rate Conversion):** Exchange rate locked at cart creation, stored with order, settlement uses locked rate. Accurate customer experience, requires manual reconciliation with Stripe settlement amounts, forex risk absorbed by merchant.
    *   **Path C (Stripe Multi-Currency):** Leverage Stripe's presentment currency feature to charge customer in display currency, auto-settle to merchant base currency. Stripe handles conversion, fees applied, cleanest UX, highest transaction cost.
*   **Default Assumption & Required Action:** The system will implement **Path A (Display-Only Conversion)** using a daily-refreshed exchange rate cache from a free API (e.g., exchangerate-api.com), with prominent disclaimer that final charge is in base currency. **The specification must be updated** to define the acceptable exchange rate source, tolerance for display/settlement variance, and whether Stripe multi-currency fees are acceptable for premium merchants.

---

#### **Assertion 4: Consignment Vendor Payout Timing & Platform Fee Collection Point**

*   **Observation:** The specification describes Stripe Connect for vendor payouts with "platform fee collection" but does not define whether fees are deducted at point-of-sale or at payout, the timing of vendor balance settlement, or handling of refunds/chargebacks affecting vendor commissions.
*   **Architectural Impact:** This decision affects cash flow modeling, vendor portal balance accuracy, accounting complexity, and the implementation of the commission calculation engine.
    *   **Path A (POS Fee Deduction):** Platform fee deducted from each transaction via Stripe Connect application fee at charge time; vendor balance credited with net commission immediately. Real-time vendor balance accuracy, but complicates partial refunds and chargeback reconciliation.
    *   **Path B (Payout Fee Deduction):** Vendor credited full commission on sale; platform fee deducted during payout generation. Simpler refund handling, but vendor sees inflated balance until payout, requires separate fee accounting.
    *   **Path C (Hybrid Settlement):** Commission tracked as pending until order fulfillment confirmed, then settled to vendor balance; platform fee deducted at confirmed settlement. Accurate for refund scenarios, highest complexity, delayed vendor visibility.
*   **Default Assumption & Required Action:** The architecture will assume **Path A (POS Fee Deduction)** with a deferred commission model where vendor balance only increments after the order's refund window expires (e.g., 30 days post-fulfillment). **The specification must be updated** to define the merchant's preferred vendor payout timing, acceptable delay between sale and vendor credit, and policy for handling chargebacks affecting consignor earnings.

---

#### **Assertion 5: Session Activity Log Query Performance & Reporting Scope**

*   **Observation:** The specification mandates comprehensive session logging with 90-day hot storage and archival to R2, but does not define the query patterns for session reports, the indexing strategy for high-cardinality searches, or the performance SLA for admin-facing analytics.
*   **Architectural Impact:** This variable dictates table partitioning strategy, index overhead, archive query implementation complexity, and whether a separate OLAP datastore is required.
    *   **Tier 1 (Transactional Queries Only):** Session logs optimized for real-time operational queries (e.g., "show active sessions for user X"), minimal indexing, reports pre-aggregated via scheduled jobs. Low index overhead, limited ad-hoc reporting flexibility.
    *   **Tier 2 (Moderate Analytics):** Full-text and composite indexes on session logs for admin-initiated searches (e.g., "sessions from IP range Y in last 30 days"), archive queries delegated to manual export. Balanced performance, some query latency on complex filters.
    *   **Tier 3 (Advanced Analytics):** Session data replicated to columnar store (e.g., Parquet on R2 + DuckDB queries) for complex analytics, transactional DB used only for operational lookups. Highest query flexibility, requires ETL pipeline and additional infrastructure.
*   **Default Assumption & Required Action:** The system will implement **Tier 1 (Transactional Queries Only)** with monthly pre-aggregated session reports stored as materialized views, and a CSV export tool for ad-hoc archive analysis. **The specification must be updated** to define the required session report types, acceptable query response times for admin dashboards, and whether real-time cross-tenant session analytics are a platform admin requirement.

---

#### **Assertion 6: Video Transcoding Resource Allocation & Processing SLA**

*   **Observation:** The specification describes FFmpeg-based video transcoding within application pods with a 10-minute timeout, but does not specify the expected video upload volume, concurrent processing limits, or pod resource reservations required to prevent transcode jobs from starving application requests.
*   **Architectural Impact:** This decision affects Kubernetes resource quotas, pod autoscaling configuration, job queue priority implementation, and whether video processing should be architecturally separated from the main application.
    *   **Path A (In-Process Processing):** Video transcodes executed within web application pods using DelayedJob, resource limits enforced via JVM heap constraints. Simplest deployment, risk of OOM under high upload volume, limits horizontal scaling efficiency.
    *   **Path B (Dedicated Worker Pods):** Separate Deployment for video processing workers consuming DelayedJob queue, isolated resource quotas. Clean separation, requires additional pod orchestration, increases infrastructure complexity.
    *   **Path C (External Processing Service):** Offload transcoding to external service (e.g., AWS MediaConvert, Cloudflare Stream). Zero in-cluster resource impact, introduces external dependency and per-minute processing costs.
*   **Default Assumption & Required Action:** The architecture will assume **Path B (Dedicated Worker Pods)** with a separate Deployment for media workers (CPU-optimized pods) consuming the DelayedJob CRITICAL priority queue, auto-scaling based on queue depth. **The specification must be updated** to define expected peak video upload volume, acceptable transcode completion SLA (e.g., "95% of videos processed within 15 minutes"), and budget constraints for external processing services.

---

#### **Assertion 7: Loyalty Points Redemption Mechanics & Discount Interaction Model**

*   **Observation:** The specification describes a points-based loyalty program with "convert points to discount at checkout" but does not define the redemption granularity, interaction with other promotions, or handling of partial refunds affecting redeemed points.
*   **Architectural Impact:** This decision affects checkout calculation logic complexity, promotion stacking rules engine design, and order adjustment/refund workflows.
    *   **Path A (Fixed Redemption Tiers):** Points redeemable only in fixed increments (e.g., 100 points = $5 discount), applied as order-level discount code, stackable with one other promotion. Simple implementation, limited customer flexibility.
    *   **Path B (Flexible Point Currency):** Points converted to store credit at dynamic rate (e.g., 1 point = $0.01), applied as payment method, combinable with all discounts. Maximum flexibility, requires separate payment method handling and refund complexity.
    *   **Path C (Product-Level Redemption):** Points redeemable for specific reward products or percentage discounts on eligible items, non-stackable with sales. Gamification-friendly, highest promotional control, requires reward catalog management.
*   **Default Assumption & Required Action:** The system will implement **Path A (Fixed Redemption Tiers)** with points-to-discount codes generated at 100-point increments, exclusive with automatic promotions but stackable with manually-entered coupon codes. **The specification must be updated** to define the desired loyalty program mechanics, acceptable redemption constraints for merchants, and policy for points reinstatement on partial/full refunds.

---

### **4.0 Next Steps**

Upon the user's update of the original specification document, the development process will be unblocked and can proceed to the architectural design phase.
