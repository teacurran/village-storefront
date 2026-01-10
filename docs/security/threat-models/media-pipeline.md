# Media Pipeline Threat Model

**Version:** 1.0
**Date:** 2026-01-10
**Status:** Active
**Owner:** Platform Security Team

## 1. Executive Summary

This threat model analyzes security risks in the Village Storefront media pipeline, which handles image and video uploads for product galleries, merchant branding, and digital product distribution. The pipeline uses S3 for storage, FFmpeg for video transcoding, and presigned URLs for direct uploads. Media processing is performed by background workers, making it a high-risk attack surface for malicious file uploads.

## 2. Assets at Risk

### 2.1 Critical Assets
- **Application Server Infrastructure:** Web servers, background workers, FFmpeg processing nodes
- **Customer Media Content:** Product images, brand logos, marketing videos
- **S3 Storage Buckets:** Media files, CDN-served content
- **Processing Pipeline State:** Job queues, worker logs, transcoding configurations
- **System Availability:** API uptime, storefront performance, background job capacity

### 2.2 Asset Value Assessment
- **Application Server Infrastructure:** CRITICAL - Compromise enables platform-wide attacks, data theft, outages
- **Customer Media Content:** HIGH - Unauthorized access/modification damages merchant trust
- **S3 Storage Buckets:** HIGH - Data breach or manipulation impacts all tenants
- **Processing Pipeline State:** MEDIUM - Disruption delays media availability, affects customer experience
- **System Availability:** HIGH - Outages impact revenue, merchant satisfaction

## 3. Threat Actors

### 3.1 External Attackers
- **Motivation:** Server compromise via exploit execution, resource exhaustion for ransom
- **Capabilities:** FFmpeg CVE research, malicious file crafting, API enumeration
- **Target:** Video processing workers, file upload endpoints, FFmpeg vulnerabilities

### 3.2 Malicious Merchants
- **Motivation:** Store illegal content, perform XSS attacks on customer browsers
- **Capabilities:** Admin dashboard access, knowledge of media upload workflows
- **Target:** Image/video upload forms, HTML/SVG file uploads, metadata injection

### 3.3 Competitor Sabotage
- **Motivation:** Disrupt platform availability to damage reputation
- **Capabilities:** Massive file uploads, resource-intensive video transcoding requests
- **Target:** Upload API endpoints, FFmpeg processing capacity, S3 storage quotas

### 3.4 Insider Threats
- **Motivation:** Data theft (merchant media), platform disruption
- **Capabilities:** Direct S3 access, worker node access, presigned URL generation
- **Target:** Merchant media files, customer data in image metadata (EXIF)

## 4. Attack Vectors & Existing Controls

### 4.1 Malicious File Uploads (FFmpeg Code Execution)
**Attack:** Attacker uploads crafted video file exploiting FFmpeg vulnerability (e.g., CVE-2016-1897 heap corruption) to execute arbitrary code on worker node.

**Existing Controls:**
- **FILE TYPE VALIDATION REQUIRED (NOT YET IMPLEMENTED):**
  - Validate file MIME type via magic byte inspection (not just file extension)
  - Allowlist: `image/jpeg`, `image/png`, `image/gif`, `image/webp`, `video/mp4`, `video/quicktime`
  - Reject dangerous formats: `image/svg+xml`, `text/html`, `application/x-shockwave-flash`

- **FFMPEG SANDBOXING REQUIRED (NOT YET IMPLEMENTED):**
  - Run FFmpeg in containerized environment with restricted capabilities
  - Network isolation: FFmpeg workers cannot make outbound connections
  - Filesystem isolation: Read-only access to input file, write-only to output directory
  - Resource limits: CPU/memory caps per job (e.g., max 2GB RAM, 4 CPU cores, 10-minute timeout)

- **FILE SIZE LIMITS REQUIRED (LIKELY IMPLEMENTED BUT NEEDS VERIFICATION):**
  - Max upload size: 100MB per file (enforced by presigned URL parameters)
  - Max video duration: 10 minutes (enforced by FFmpeg job validation)

**Implementation References:**
- Architecture: Section "Media Security" - FFmpeg worker sandboxing requirement
- Task I4.T7 (inferred): Docker-based media worker implementation

**Residual Risk:** CRITICAL - Without sandboxing, FFmpeg exploit = full worker compromise. THIS IS THE HIGHEST-PRIORITY MITIGATION.

**Mitigation Backlog (URGENT):**
- **[P0] Implement FFmpeg sandboxing:** Docker container with `--security-opt=no-new-privileges`, `--cap-drop=ALL`, `--network=none`
- **[P0] File type validation:** Magic byte inspection before FFmpeg processing
- **[P1] FFmpeg version updates:** Automated CVE monitoring + quarterly FFmpeg upgrades
- **[P1] Input validation:** Reject files with suspicious metadata (e.g., EXIF with shell commands)

---

### 4.2 Path Traversal Attacks (S3 Key Manipulation)
**Attack:** Attacker manipulates S3 object key to overwrite other tenant's media or access restricted files (e.g., upload with key `../../other-tenant/logo.png`).

**Existing Controls:**
- Tenant-scoped S3 keys: Object keys include tenant ID prefix: `{tenantId}/media/{assetId}/{variant}`
- Presigned URL generation: Server-side validation of key format before generating presigned URL
- S3 bucket policies: Bucket configured to deny writes outside tenant prefix

**Implementation References:**
- Architecture: Section "Media Security" - tenant-scoped object keys

**Residual Risk:** LOW - Strong controls prevent path traversal. Assumes presigned URL generation validates key format correctly.

**Mitigation Backlog:**
- Integration test: Attempt upload with `../` in key, verify rejection
- Static analysis: Flag S3 key construction from user input without validation

---

### 4.3 Resource Exhaustion (Storage Quota Abuse)
**Attack:** Attacker uploads massive files or large number of files to exhaust S3 storage quota, causing outages or cost overruns.

**Existing Controls (PARTIAL):**
- File size limits: Max 100MB per file (enforced by presigned URL)
- **QUOTA ENFORCEMENT REQUIRED (NOT YET IMPLEMENTED):**
  - Per-tenant storage quota (e.g., 10GB for basic plan, 100GB for premium)
  - Daily upload limits (e.g., max 100 file uploads per merchant per day)
  - S3 bucket lifecycle policies: Auto-delete unused media after 90 days

**Implementation References:**
- Architecture: Section "Media Security" - resource exhaustion prevention (mentioned but not detailed)

**Residual Risk:** HIGH - Without quotas, attacker can cause unlimited S3 costs. Current 100MB per-file limit insufficient.

**Mitigation Backlog:**
- **[P0] Per-tenant storage quotas:** Track storage usage in `tenant_media_quota` table, reject uploads exceeding quota
- **[P1] Upload rate limiting:** Token bucket pattern (max 100 uploads/day per tenant)
- **[P2] S3 lifecycle policies:** Auto-expire media files in `temp/` prefix after 7 days

---

### 4.4 XSS via Media Metadata (EXIF/IPTC Injection)
**Attack:** Attacker uploads image with malicious JavaScript in EXIF/IPTC metadata (e.g., `<script>alert('XSS')</script>` in EXIF Artist field), which is then rendered in merchant admin dashboard.

**Existing Controls:**
- **CSP HEADERS REQUIRED (TO BE IMPLEMENTED IN THIS TASK):**
  - Content-Security-Policy prevents inline scripts from executing
  - Blocks script execution even if metadata rendered unsafely

- **METADATA SCRUBBING REQUIRED (NOT YET IMPLEMENTED):**
  - Strip all EXIF/IPTC metadata from uploaded images (using ImageMagick `-strip` or similar)
  - Alternative: Allowlist safe metadata fields (e.g., GPS coordinates, camera model) and sanitize values

**Implementation References:**
- Task I6.T2: CSP header implementation (this task)
- Architecture: Section "Media Security" - XSS prevention via CSP

**Residual Risk:** MEDIUM - CSP provides defense-in-depth, but proper metadata handling is primary control.

**Mitigation Backlog:**
- **[P1] EXIF metadata scrubbing:** Strip all metadata on upload (except allowlisted fields)
- **[P1] Content-Type validation:** Serve images with `Content-Type: image/jpeg` header (prevent browser interpreting as HTML)
- **[P2] Output encoding:** HTML-escape metadata values when displayed in admin UI

---

### 4.5 Unauthorized Media Access (Presigned URL Leakage)
**Attack:** Attacker obtains presigned URL for another tenant's media file (e.g., via URL guessing, log file leakage, SSRF).

**Existing Controls:**
- Presigned URL expiration: 15-minute validity window (prevents long-term URL sharing)
- Tenant validation: Presigned URLs generated only for media owned by current tenant
- S3 bucket policies: Deny public access, all reads require presigned URL

**Implementation References:**
- Architecture: Section "Media Security" - presigned URLs with 15-minute expiry

**Residual Risk:** LOW - Short expiration limits damage window. Risk primarily from URL sharing within 15-minute window.

**Mitigation Backlog:**
- **[P2] IP binding for presigned URLs:** Include requester IP in S3 signature (prevent URL sharing to different IP)
- **[P2] CloudFront signed URLs:** Use CloudFront signed cookies for CDN-served media (more secure than S3 presigned URLs)

---

### 4.6 Media Processing Job Validation Bypass
**Attack:** Attacker bypasses client-side validation to submit invalid media processing job (e.g., request transcoding for 10-hour video when limit is 10 minutes).

**Existing Controls (INFERRED):**
- **SERVER-SIDE VALIDATION REQUIRED (IMPLEMENTATION STATUS UNKNOWN):**
  - Validate video duration via FFprobe before starting transcoding job
  - Reject jobs exceeding duration/resolution/bitrate limits
  - Validate job payload schema (prevent SQL injection via JSON fields)

**Implementation References:**
- Task I4.T7 (inferred): Media processing job handler

**Residual Risk:** MEDIUM - Assumes server-side validation is implemented. If missing, attacker can waste worker resources.

**Mitigation Backlog:**
- **[P1] Server-side job validation:** FFprobe inspection before job submission
- **[P1] Job schema validation:** JSON schema validation for job payloads
- **[P2] Job throttling:** Max 10 concurrent jobs per tenant (prevent worker starvation)

---

### 4.7 SVG File Uploads (Embedded JavaScript)
**Attack:** Attacker uploads SVG file containing embedded `<script>` tags, which execute when SVG is rendered in browser (stored XSS).

**Existing Controls:**
- **FILE TYPE BLOCKLIST REQUIRED (NOT YET IMPLEMENTED):**
  - Reject `image/svg+xml` uploads entirely (or allow only for premium merchants with manual review)
  - Alternative: Server-side SVG sanitization (strip `<script>`, `<foreignObject>`, event handlers)

- **CSP HEADERS (TO BE IMPLEMENTED IN THIS TASK):**
  - CSP with `script-src 'self'` blocks inline scripts in SVG files
  - Defense-in-depth: Even if SVG uploaded, scripts won't execute

**Implementation References:**
- Task I6.T2: CSP header implementation

**Residual Risk:** HIGH - SVG uploads are extremely dangerous without sanitization. CSP alone insufficient (bypasses exist).

**Mitigation Backlog:**
- **[P0] Block SVG uploads:** Reject `image/svg+xml` in file type allowlist
- **[P1] SVG sanitization (if SVGs needed):** Use DOMPurify or similar library to strip dangerous elements
- **[P2] Serve SVGs from separate domain:** Host user-uploaded SVGs on `media.example.com` (separate from `app.example.com`)

---

### 4.8 FFmpeg Denial of Service (Resource-Intensive Files)
**Attack:** Attacker uploads file designed to consume excessive CPU/RAM during transcoding (e.g., "zip bomb" video, high-resolution 8K video).

**Existing Controls (PARTIAL):**
- **RESOURCE LIMITS REQUIRED (IMPLEMENTATION STATUS UNKNOWN):**
  - FFmpeg timeout: Kill jobs running >10 minutes
  - Memory limit: Max 2GB RAM per FFmpeg process
  - CPU limit: Max 4 CPU cores per process
  - Input validation: Reject videos >1920×1080 resolution, >60fps framerate

**Implementation References:**
- Architecture: Section "Media Security" - FFmpeg worker sandboxing (includes resource limits)

**Residual Risk:** MEDIUM - Controls prevent complete resource exhaustion but allow some waste.

**Mitigation Backlog:**
- **[P1] Input validation:** FFprobe checks for resolution/framerate limits
- **[P1] Worker auto-scaling:** Add workers during high load (prevent queue backup)
- **[P2] Cost alerts:** Alert on S3 egress >$100/day (detect abuse early)

---

## 5. Residual Risks Summary

| Risk ID | Description | Likelihood | Impact | Risk Level | Mitigation Status |
|---------|-------------|------------|--------|------------|-------------------|
| MED-001 | FFmpeg code execution via malicious files | High | Critical | **CRITICAL** | **[P0] URGENT: Sandboxing + file validation** |
| MED-002 | SVG uploads with embedded scripts (stored XSS) | High | High | **HIGH** | **[P0] Block SVG uploads** |
| MED-003 | Resource exhaustion (storage quota abuse) | Medium | High | **HIGH** | **[P0] Tenant storage quotas** |
| MED-004 | XSS via image metadata (EXIF injection) | Medium | Medium | MEDIUM | [P1] EXIF scrubbing |
| MED-005 | Media processing job validation bypass | Medium | Medium | MEDIUM | [P1] Server-side validation |
| MED-006 | FFmpeg DOS (resource-intensive files) | Medium | Medium | MEDIUM | [P1] Input validation + timeouts |
| MED-007 | Path traversal via S3 key manipulation | Low | High | LOW | [P2] Integration tests |
| MED-008 | Presigned URL leakage | Low | Medium | LOW | [P2] IP binding |

## 6. Security Controls Inventory

### Implemented Controls
1. **Tenant-scoped S3 keys** - Prevents cross-tenant media access
2. **Presigned URL expiration** - 15-minute validity window limits URL sharing
3. **S3 bucket policies** - Deny public access, require presigned URLs
4. **File size limits** - Max 100MB per file (enforced by presigned URL)
5. **HTTPS enforcement** - Prevents man-in-the-middle attacks on uploads

### Planned Controls (Backlog)
1. **[P0 CRITICAL] FFmpeg sandboxing** - Docker isolation with no network, restricted capabilities (MED-001)
2. **[P0 CRITICAL] File type validation** - Magic byte inspection, blocklist for SVG/HTML (MED-001, MED-002)
3. **[P0 CRITICAL] Block SVG uploads** - Reject `image/svg+xml` files (MED-002)
4. **[P0 CRITICAL] Per-tenant storage quotas** - Prevent unlimited S3 costs (MED-003)
5. **[P1] EXIF metadata scrubbing** - Strip dangerous metadata on upload (MED-004)
6. **[P1] Server-side job validation** - FFprobe checks before transcoding (MED-005)
7. **[P1] FFmpeg resource limits** - Timeout, memory, CPU caps (MED-006)
8. **[P1] Input validation (resolution/duration)** - Reject oversized videos (MED-006)
9. **[P1] Upload rate limiting** - Token bucket pattern per tenant (MED-003)
10. **[P2] IP binding for presigned URLs** - Prevent URL sharing (MED-008)
11. **[P2] S3 lifecycle policies** - Auto-delete unused media (MED-003)
12. **[P2] CloudFront signed URLs** - Replace S3 presigned URLs (MED-008)
13. **[P2] SVG sanitization (if needed)** - DOMPurify integration (MED-002)
14. **[P2] Separate domain for media** - Host uploads on `media.example.com` (MED-004)

### Controls Implemented in This Task (I6.T2)
- **CSP headers** - Blocks inline scripts in SVG/EXIF metadata (defense-in-depth for MED-002, MED-004)

## 7. Compliance Mapping

### OWASP Top 10 2021
- **A03:2021 Injection** - FFmpeg code execution (MED-001), EXIF injection (MED-004)
  - Mitigation: Input validation, sandboxing, metadata scrubbing
- **A04:2021 Insecure Design** - Missing sandboxing (MED-001)
  - Mitigation: FFmpeg containerization, resource limits
- **A05:2021 Security Misconfiguration** - Missing file type validation (MED-002)
  - Mitigation: Allowlist/blocklist for MIME types
- **A07:2021 XSS** - SVG uploads (MED-002), EXIF injection (MED-004)
  - Mitigation: CSP headers, metadata scrubbing, SVG blocking

### GDPR Compliance
- **Article 32:** Security of processing - Tenant isolation for media, presigned URL expiration
- **Article 25:** Data protection by design - Sandboxing, resource limits, quota enforcement

## 8. Testing & Validation

### Security Test Coverage
- **Unit tests:** S3 key format validation, presigned URL generation
- **Integration tests (required):** Path traversal attempts, oversized file uploads, SVG upload rejection
- **Manual testing:** FFmpeg exploit testing in sandboxed environment

### Penetration Testing Scope
- **Phase 1 (Post-Launch):** FFmpeg exploit attempts, SVG XSS, metadata injection
- **Phase 2 (6 months):** Resource exhaustion attacks, presigned URL leakage

## 9. Incident Response

### Detection Mechanisms
- **Metrics:** `media.ffmpeg.error` counter (alert on rate >10/min)
- **Metrics:** `media.upload.quota_exceeded` counter (alert on rate >5/min per tenant)
- **Logs:** FFmpeg stderr output logged for forensic analysis
- **Alerts:** S3 egress cost >$100/day (potential abuse)

### Response Procedures
1. **FFmpeg exploit detected:** Kill all FFmpeg workers immediately, inspect logs, patch vulnerability
2. **Storage quota abuse:** Suspend tenant uploads, contact merchant, enforce quota retroactively
3. **XSS via media:** Delete malicious files from S3, purge CDN cache, notify affected users

## 10. References

- **Architecture:** `docs/architecture_overview.md` - Section "Media Security"
- **Task I4.T7:** Docker-based media worker implementation (inferred)
- **OWASP:** https://owasp.org/www-community/attacks/Server-Side_Includes_%28SSI%29_Injection
- **FFmpeg CVEs:** https://www.cvedetails.com/vulnerability-list/vendor_id-3611/Ffmpeg.html

## 11. Change History

| Date | Version | Author | Changes |
|------|---------|--------|---------|
| 2026-01-10 | 1.0 | Security Team | Initial threat model (Task I6.T2) |

---

**Review Cadence:** Quarterly (or after media pipeline changes / FFmpeg upgrades)
**Next Review:** 2026-04-10

**URGENT ACTION REQUIRED:** Threat MED-001 (FFmpeg code execution) is CRITICAL risk. Sandboxing and file type validation must be implemented immediately.
