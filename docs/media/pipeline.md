# Media Processing Pipeline

**Task:** I4.T3
**Status:** Implemented
**Last Updated:** 2026-01-08

## Overview

The media processing pipeline handles upload negotiation, image resizing, video transcoding with HLS, presigned URL generation, and tenant quota enforcement for the Village Storefront platform.

## Architecture

### Components

1. **MediaStorageClient** - Abstraction for Cloudflare R2 object storage
2. **MediaProcessor** - Image and video processing (Thumbnailator + FFmpeg)
3. **MediaJobService** - Background job orchestration with scheduled draining (`media.processing.dispatch-interval`)
4. **MediaAsset/Derivative** - JPA entities for metadata persistence
5. **MediaQuota** - Tenant storage quota tracking

### Storage Layout

Object keys follow a structured tenant-scoped pattern:

```
{tenantId}/media/{assetType}/{assetId}/original/{filename}
{tenantId}/media/{assetType}/{assetId}/derivatives/{derivativeType}/{filename}
```

**Example:**
```
550e8400-e29b-41d4-a716-446655440000/media/image/7f3d2e1a-8b4c-4f5e-9c6d-1a2b3c4d5e6f/original/product.jpg
550e8400-e29b-41d4-a716-446655440000/media/image/7f3d2e1a-8b4c-4f5e-9c6d-1a2b3c4d5e6f/derivatives/thumbnail/product_thumbnail.jpg
550e8400-e29b-41d4-a716-446655440000/media/video/9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d/original/demo.mp4
550e8400-e29b-41d4-a716-446655440000/media/video/9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d/derivatives/hls_master/master.m3u8
```

This structure provides:
- **Tenant isolation** via prefix
- **Type organization** for lifecycle policies
- **Asset grouping** for efficient deletion
- **Derivative tracking** for cache invalidation

## Upload Flow

### 1. Upload Negotiation

Client requests a presigned upload URL:

```http
POST /api/v1/media/upload/negotiate
Content-Type: application/json

{
  "filename": "product.jpg",
  "contentType": "image/jpeg",
  "fileSize": 2048576,
  "assetType": "image"
}
```

Server response:

```json
{
  "uploadUrl": "https://village-media.r2.cloudflarestorage.com/...",
  "assetId": "7f3d2e1a-8b4c-4f5e-9c6d-1a2b3c4d5e6f",
  "storageKey": "550e8400-.../original/product.jpg",
  "expiresAt": "2026-01-08T12:00:00Z"
}
```

### 2. Client Upload

Client uploads directly to R2 using the presigned URL (bypasses application server).

### 3. Processing Trigger

Client notifies completion:

```http
POST /api/v1/media/{assetId}/complete
```

Server enqueues background processing job with priority based on asset type:
- Images: DEFAULT priority (< 30s latency target)
- Videos: LOW priority (< 2m latency target)

### 4. Background Processing

`MediaJobService` processes the job:

1. Download original from R2 to temp directory
2. Generate derivatives based on asset type
3. Upload derivatives to R2
4. Update `MediaAsset` status: `pending` → `processing` → `ready`
5. Update tenant quota usage
6. Clean up temp files

### Asset Status Lifecycle

1. **uploading** – Asset metadata created and quota reserved; waiting on client upload.
2. **pending** – Client signaled completion and the processing job is queued.
3. **processing** – Background worker is generating derivatives.
4. **ready** – All derivatives persisted and signed URLs can be issued.
5. **failed** – Processing error recorded for manual intervention.

## Image Processing

Uses **Thumbnailator** library for high-quality resizing.

### Derivative Sizes

| Type | Max Dimension | Use Case |
|------|---------------|----------|
| thumbnail | 150px | Grid previews, small icons |
| small | 400px | Mobile devices, thumbnails |
| medium | 800px | Tablet displays, detail views |
| large | 1600px | Desktop displays, zoom views |

### Processing Steps

1. Load original image
2. Extract metadata (dimensions, format)
3. For each size tier:
   - Calculate aspect-ratio-preserving dimensions
   - Resize with bicubic interpolation
   - Compress to JPEG (quality 0.85)
   - Write to temp file
4. Upload all derivatives to R2
5. Persist metadata to `media_derivatives` table

**Output format:** JPEG for all derivatives (consistent format)

## Video Processing

Uses **FFmpeg** for transcoding and HLS packaging.

### HLS Variant Streams

| Variant | Resolution | Target Bitrate | Use Case |
|---------|-----------|----------------|----------|
| hls_720p | 1280x720 | 2 Mbps | High-quality desktop/tablet |
| hls_480p | 854x480 | 1 Mbps | Standard quality mobile |
| hls_360p | 640x360 | 500 Kbps | Low-bandwidth mobile |

### HLS Segment Configuration

- **Segment duration:** 6 seconds (configurable via `media.processing.video.hls.segment-duration`)
- **Playlist type:** VOD (video-on-demand)
- **Codec:** H.264 video, AAC audio
- **Container:** MPEG-TS segments (.ts)

### Processing Steps

1. Extract video metadata (dimensions, duration, codec)
2. Generate HLS master playlist (`master.m3u8`)
3. For each variant resolution:
   - Transcode video to target bitrate/resolution
   - Segment into 6-second chunks
   - Create variant playlist (e.g., `hls_720p.m3u8`)
   - Upload playlist + segments to R2
4. Extract poster frame (first frame at 1 second)
5. Upload poster as JPEG derivative
6. Persist all derivative metadata

**FFmpeg command example (720p variant):**
```bash
ffmpeg -i input.mp4 \
  -vf scale=1280:720 \
  -c:v libx264 -b:v 2M \
  -c:a aac -b:a 128k \
  -hls_time 6 \
  -hls_playlist_type vod \
  -hls_segment_filename "segment_%03d.ts" \
  output_720p.m3u8
```

## Tenant Quotas

### Default Limits

- **Default quota:** 10 GB (10,737,418,240 bytes)
- **Warning threshold:** 80% usage
- **Enforcement:** Enabled by default (can be disabled for premium tenants)

### Quota Tracking

Quota usage includes:
- Original uploaded files
- All generated derivatives (images + video segments)

Quota is updated atomically in `media_quotas` table after successful processing.

### Quota Exceeded Behavior

When quota is exceeded:
1. Upload negotiation returns HTTP 413 (Payload Too Large)
2. Error response includes remaining quota and usage percentage
3. Metric counter incremented: `media.quota.exceeded`
4. Admin notification triggered at warning threshold (80%)

### Quota Override

Platform admins can:
- Increase quota via Admin UI
- Disable enforcement (`enforce_quota = false`)
- View usage trends in reporting dashboard

## Signed URLs

Per **Architecture §1.4** and **Foundation §6.0** requirements.

### Download URL Generation

```http
GET /api/v1/media/{assetId}/download
```

Response:
```json
{
  "url": "https://village-media.r2.cloudflarestorage.com/...?signature=...",
  "expiresAt": "2026-01-09T12:00:00Z",
  "remainingAttempts": 4
}
```

### URL Properties

- **Expiry:** 24 hours (configurable via `media.signed-url.expiry-hours`)
- **Signature version:** Tracked in `media_access_logs` for invalidation
- **Download tracking:** Access recorded with IP + user agent

### Digital Product Constraints

For digital product downloads:
- **Max attempts:** 5 (configurable via `media.signed-url.max-download-attempts`)
- **Attempt tracking:** `MediaAsset.downloadAttempts` incremented on access
- **Limit enforcement:** Returns HTTP 403 when limit reached

### URL Invalidation

To invalidate all signed URLs for an asset:
1. Increment `signature_version` in database
2. New URLs generated with new version
3. Old URLs fail signature validation

## Failure Handling & Retries

### Retry Policy

Per **Architecture §3.6** background job governance:

| Priority | Max Attempts | Backoff Strategy | DLQ Threshold |
|----------|--------------|------------------|---------------|
| DEFAULT (images) | 3 | Exponential (1s, 2s, 4s) | After 3 failures |
| LOW (videos) | 3 | Exponential (1s, 2s, 4s) | After 3 failures |

### Failure Scenarios

**1. FFmpeg Process Crash**
- Retry with exponential backoff
- After max attempts, move to Dead Letter Queue (DLQ)
- Asset status set to `failed` with error message
- Manual investigation required (runbook: `docs/runbooks/media-processing-failures.md`)

**2. R2 Upload Failure**
- Transient network errors retry automatically (3 attempts)
- 4xx errors (auth, quota) fail immediately
- 5xx errors retry with backoff

**3. Quota Exceeded During Processing**
- Processing halted immediately
- Original file remains in storage
- Partial derivatives cleaned up
- Asset status: `failed` with error "Quota exceeded during processing"
- Admin notification sent

**4. Invalid Media File**
- FFmpeg/Thumbnailator rejects file (corrupt, unsupported format)
- Fail immediately (no retry)
- Asset status: `failed` with error details
- User notification with supported formats

### Dead Letter Queue (DLQ)

Failed jobs persist in `dead_letter_queue` table with:
- Original payload (JSON)
- All error messages from attempts
- Last attempt timestamp
- Owning module: `media.processing`

**Manual retry:**
```http
POST /admin/api/media/jobs/dlq/{jobId}/retry
```

### Monitoring & Alerts

**Key metrics:**

```
media.job.enqueued{tenant,priority}        # Jobs enqueued
media.job.success{tenant,type}             # Successfully processed
media.job.failed{tenant,type}              # Failed jobs
media.job.duration{tenant,type}            # Processing time
media.quota.exceeded{tenant}               # Quota rejections
media.queue.depth{priority}                # Current queue size
```

**Alert thresholds (from Architecture §3.2.9 KPIs):**

- Queue depth > 100: Scale worker pods
- Success rate < 99.5%: Investigate
- Job duration p95 > 60s (images) / 300s (videos): Performance degradation

## Storage TTLs & Cleanup

### Lifecycle Policies

Applied at R2 bucket level:

| Path Pattern | Retention | Policy |
|--------------|-----------|--------|
| `*/media/*/pending/*` | 7 days | Delete if asset status still `pending` |
| `*/media/*/failed/*` | 30 days | Archive then delete |
| `*/media/*/derivatives/*` | Indefinite | Delete only when parent asset deleted |

### Orphaned Derivative Cleanup

Background job runs daily:
1. Find `media_derivatives` records where parent `media_asset` deleted
2. Delete derivatives from R2
3. Delete derivative records from database
4. Update quota usage

## Testing

### Unit Tests

**Stub Components:**
- `StubMediaStorageClient` - In-memory storage
- `StubMediaProcessor` - Fake derivative generation (no FFmpeg)

**Test Coverage:**

```java
@QuarkusTest
class MediaPipelineTest {
    @Inject MediaJobService mediaJobService;
    @Inject StubMediaStorageClient storageClient;
    @Inject StubMediaProcessor processor;

    @Test
    void testImageProcessingPipeline() {
        // Upload fake image
        // Trigger processing
        // Assert derivatives created
        // Assert quota updated
        // Assert asset status = ready
    }

    @Test
    void testQuotaEnforcement() {
        // Set tenant quota to 1 MB
        // Attempt upload of 2 MB file
        // Assert HTTP 413
        // Assert metric incremented
    }
}
```

### Integration Tests

Require actual FFmpeg installation:

```bash
# Install FFmpeg locally
brew install ffmpeg  # macOS
apt-get install ffmpeg  # Ubuntu

# Run integration tests
./mvnw verify -Pintegration-tests
```

Tests use small fixture media files (< 1 MB):
- `test-image-1920x1080.jpg` (200 KB)
- `test-video-720p-10s.mp4` (500 KB)

## Configuration Reference

See `application.properties` for full configuration:

```properties
# R2 Storage
media.storage.r2.endpoint=https://account-id.r2.cloudflarestorage.com
media.storage.r2.bucket=village-media
media.storage.r2.access-key-id=${R2_ACCESS_KEY}
media.storage.r2.secret-access-key=${R2_SECRET_KEY}

# Processing
media.processing.ffmpeg.path=/usr/bin/ffmpeg
media.processing.thumbnailator.quality=0.85
media.processing.video.hls.segment-duration=6
media.processing.image.sizes=thumbnail:150,small:400,medium:800,large:1600
media.processing.dispatch-interval=3s
media.upload-url.expiry-minutes=15

# Quotas
media.quota.default-bytes=10737418240  # 10 GB
media.quota.warn-threshold=0.8         # 80%

# Signed URLs
media.signed-url.expiry-hours=24
media.signed-url.max-download-attempts=5

# Job Queues
jobs.queue.capacity.default=500
jobs.retry.max_attempts.default=3
```

## API Endpoints

### Upload Negotiation
```
POST /api/v1/media/upload/negotiate
```

### Complete Upload
```
POST /api/v1/media/{assetId}/complete
```

### Get Signed Download URL
```
GET /api/v1/media/{assetId}/download
```

### List Assets
```
GET /api/v1/media?type={image|video}&status={pending|processing|ready|failed}&page=0&size=20
```

### Delete Asset
```
DELETE /api/v1/media/{assetId}
```

## Security Considerations

1. **Tenant Isolation:** All storage keys prefixed with tenant ID
2. **Presigned URL Expiry:** 15-minute upload URLs, 24-hour download URLs
3. **Signature Validation:** R2 validates signatures, prevents tampering
4. **Quota Enforcement:** Prevents storage abuse
5. **Content-Type Validation:** Reject non-image/video uploads
6. **File Size Limits:** Enforced at upload negotiation (max 5 GB per file)

## Future Enhancements

1. **Lazy Derivative Generation:** Generate only requested sizes on-demand
2. **CDN Integration:** Cloudflare CDN for derivative delivery
3. **AVIF/WebP Support:** Modern image formats for better compression
4. **Adaptive Bitrate:** Auto-detect optimal HLS variants
5. **Thumbnail Sprites:** Video timeline preview strips
6. **Metadata Extraction:** EXIF/GPS for images, subtitle tracks for video

## References

- **Task:** I4.T3 - Media Pipeline Implementation
- **Architecture:** `docs/architecture/04_Operational_Architecture.md` §3.6
- **Foundation:** §6.0 - Digital Product Download Constraints
- **ERD:** `docs/diagrams/datamodel_erd.puml` (MediaAsset, MediaDerivative)
- **Job Framework:** `src/main/java/villagecompute/storefront/services/jobs/config/`

---

**Document Version:** 1.0
**Author:** AI Code Implementation Agent
**Review Status:** Pending technical review
