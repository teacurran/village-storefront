-- Migration: Add manifest metadata, TTL, and cancellation fields to report_jobs
--
-- Task: I4.T8 - Reporting Exports
--
-- Adds fields for:
-- - manifest_metadata: JSON blob storing retention policy, partition info, data source flags
-- - url_expires_at: Timestamp for signed URL expiration (TTL)
-- - cancelled: Boolean flag to support job cancellation
--
-- These fields enable compliance auditing, hot/cold data tracking, and job lifecycle management.

ALTER TABLE report_jobs
ADD COLUMN manifest_metadata TEXT,
ADD COLUMN url_expires_at TIMESTAMPTZ,
ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT FALSE;

-- Add index for expired URL cleanup jobs
CREATE INDEX idx_report_jobs_url_expiry
ON report_jobs(url_expires_at)
WHERE url_expires_at IS NOT NULL AND status = 'completed';

-- Add index for active job queries with cancellation filter
CREATE INDEX idx_report_jobs_active_cancelled
ON report_jobs(tenant_id, status, cancelled)
WHERE status IN ('pending', 'running');

COMMENT ON COLUMN report_jobs.manifest_metadata IS 'JSON metadata for archived data pulls, retention policy, and partition ranges';
COMMENT ON COLUMN report_jobs.url_expires_at IS 'Signed download URL expiry timestamp (TTL)';
COMMENT ON COLUMN report_jobs.cancelled IS 'Flag to mark job as cancelled by user';
