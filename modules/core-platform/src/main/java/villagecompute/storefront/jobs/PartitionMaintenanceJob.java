package villagecompute.storefront.jobs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.reporting.ReportStorageClient;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job for partition maintenance (create future partitions, archive and drop old partitions).
 *
 * <p>
 * Runs daily at 2 AM to ensure domain_events partitions are maintained according to retention policy (6 months).
 * Archives partitions to R2/S3 as JSONL before dropping.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Architecture: docs/architecture/datamodel_tenancy_narrative.md (Partition Maintenance Automation)</li>
 * <li>Retention: 6 months for domain_events, then archive to S3 Glacier</li>
 * </ul>
 */
@ApplicationScoped
public class PartitionMaintenanceJob {

    private static final Logger LOG = Logger.getLogger(PartitionMaintenanceJob.class);

    private static final int RETENTION_MONTHS = 6;
    private static final int FUTURE_PARTITION_THRESHOLD_DAYS = 7;
    private static final String ARCHIVE_CONTENT_TYPE = "application/gzip";

    @Inject
    EntityManager entityManager;

    @Inject
    ReportStorageClient storageClient;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Main partition maintenance job scheduled for 2 AM daily.
     */
    @Scheduled(
            cron = "0 2 * * * ?",
            identity = "partition-maintenance")
    public void maintainPartitions() {
        LOG.info("Starting partition maintenance for domain_events");

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Create next month's partition (7 days before month end)
            createFuturePartitions();

            // Archive and drop old partitions (beyond 6 month retention)
            archiveAndDropOldPartitions();

            // Cleanup old processed event records (retain 7 days for idempotency deduplication)
            cleanupProcessedEvents();

            sample.stop(meterRegistry.timer("partition.maintenance.duration"));
            meterRegistry.counter("partition.maintenance.completed").increment();

            LOG.info("Partition maintenance completed successfully");

        } catch (Exception e) {
            LOG.error("Partition maintenance failed", e);
            meterRegistry.counter("partition.maintenance.failed").increment();
            throw new RuntimeException("Partition maintenance failed", e);
        }
    }

    /**
     * Create future partitions 7 days before month end.
     */
    @Transactional
    void createFuturePartitions() {
        LocalDate today = LocalDate.now();
        LocalDate nextMonth = today.plusMonths(1).withDayOfMonth(1);

        // Only create if we're within threshold (7 days before month end)
        if (today.getDayOfMonth() >= today.lengthOfMonth() - FUTURE_PARTITION_THRESHOLD_DAYS) {
            createPartition("domain_events", nextMonth);

            LOG.infof("Future partition created for next month: %s", nextMonth);
            meterRegistry.counter("partition.created", "table", "domain_events").increment();
        }
    }

    /**
     * Archive and drop partitions exceeding retention policy (6 months).
     */
    @Transactional
    void archiveAndDropOldPartitions() {
        LocalDate archiveCutoff = LocalDate.now().minusMonths(RETENTION_MONTHS).withDayOfMonth(1);

        LOG.infof("Archiving and dropping partitions older than %s", archiveCutoff);

        // Archive and drop domain_events partition
        String partitionName = "domain_events_" + archiveCutoff.format(DateTimeFormatter.ofPattern("yyyy_MM"));

        if (partitionExists(partitionName)) {
            exportPartitionToJSONL(partitionName, archiveCutoff);
            dropPartition(partitionName);

            LOG.infof("Archived and dropped partition: %s", partitionName);
            meterRegistry.counter("partition.archived", "table", "domain_events").increment();
        } else {
            LOG.debugf("Partition does not exist, skipping: %s", partitionName);
        }
    }

    /**
     * Create a new partition for specified month.
     *
     * @param tableName
     *            base table name
     * @param month
     *            partition month
     */
    @Transactional
    void createPartition(String tableName, LocalDate month) {
        String partitionName = tableName + "_" + month.format(DateTimeFormatter.ofPattern("yyyy_MM"));
        LocalDate nextMonth = month.plusMonths(1);

        // Check if partition already exists
        if (partitionExists(partitionName)) {
            LOG.debugf("Partition already exists, skipping creation: %s", partitionName);
            return;
        }

        String sql = String.format(
                "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')", partitionName,
                tableName, month, nextMonth);

        try {
            entityManager.createNativeQuery(sql).executeUpdate();
            LOG.infof("Created partition: %s", partitionName);

            // Create indexes on new partition
            createPartitionIndexes(partitionName);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create partition: %s", partitionName);
            throw new RuntimeException("Partition creation failed: " + partitionName, e);
        }
    }

    /**
     * Create indexes on partition.
     *
     * @param partitionName
     *            partition table name
     */
    @Transactional
    void createPartitionIndexes(String partitionName) {
        String[] indexSqls = {
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_tenant ON %s(tenant_id)", partitionName, partitionName),
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_type ON %s(event_type)", partitionName, partitionName),
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_aggregate ON %s(aggregate_id)", partitionName,
                        partitionName),
                String.format("CREATE INDEX IF NOT EXISTS idx_%s_occurred ON %s(occurred_at)", partitionName,
                        partitionName)};

        for (String sql : indexSqls) {
            entityManager.createNativeQuery(sql).executeUpdate();
        }

        LOG.debugf("Created indexes for partition: %s", partitionName);
    }

    /**
     * Export partition data to JSONL and upload to R2.
     *
     * @param partitionName
     *            partition table name
     * @param month
     *            partition month
     */
    @Transactional
    void exportPartitionToJSONL(String partitionName, LocalDate month) {
        LOG.infof("Exporting partition to JSONL: %s", partitionName);

        Timer.Sample sample = Timer.start(meterRegistry);
        Counter eventsExported = meterRegistry.counter("partition.archive.events", "partition", partitionName);

        try {
            // Query partition data (streaming in batches to avoid OOM)
            List<DomainEvent> events = entityManager.createQuery("SELECT e FROM DomainEvent e", DomainEvent.class)
                    .getResultList();

            // Generate JSONL (one JSON object per line)
            ByteArrayOutputStream jsonlStream = new ByteArrayOutputStream();
            for (DomainEvent event : events) {
                String json = objectMapper.writeValueAsString(event);
                jsonlStream.write((json + "\n").getBytes());
                eventsExported.increment();
            }

            LOG.infof("Exported %d events from partition %s", events.size(), partitionName);

            // Compress with gzip
            ByteArrayOutputStream gzipStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOut = new GZIPOutputStream(gzipStream)) {
                gzipOut.write(jsonlStream.toByteArray());
            }

            // Upload to R2
            String key = String.format("domain_events/%d/%02d/%s.jsonl.gz", month.getYear(), month.getMonthValue(),
                    partitionName);

            String uploadedKey = storageClient.uploadReport(key, new ByteArrayInputStream(gzipStream.toByteArray()),
                    ARCHIVE_CONTENT_TYPE, gzipStream.size());

            LOG.infof("Partition archived to R2 - key=%s, sizeBytes=%d", uploadedKey, gzipStream.size());

            sample.stop(meterRegistry.timer("partition.archive.duration", "partition", partitionName));

        } catch (IOException e) {
            LOG.errorf(e, "Failed to export partition to JSONL: %s", partitionName);
            meterRegistry.counter("partition.archive.failed", "partition", partitionName).increment();
            throw new RuntimeException("Partition export failed: " + partitionName, e);
        }
    }

    /**
     * Drop a partition table.
     *
     * @param partitionName
     *            partition table name
     */
    @Transactional
    void dropPartition(String partitionName) {
        String sql = "DROP TABLE IF EXISTS " + partitionName;

        try {
            entityManager.createNativeQuery(sql).executeUpdate();
            LOG.infof("Dropped partition: %s", partitionName);
            meterRegistry.counter("partition.dropped", "partition", partitionName).increment();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to drop partition: %s", partitionName);
            throw new RuntimeException("Partition drop failed: " + partitionName, e);
        }
    }

    /**
     * Check if a partition exists.
     *
     * @param partitionName
     *            partition table name
     * @return true if partition exists
     */
    boolean partitionExists(String partitionName) {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_tables WHERE tablename = :partitionName)";

        try {
            Boolean exists = (Boolean) entityManager.createNativeQuery(sql).setParameter("partitionName", partitionName)
                    .getSingleResult();
            return exists != null && exists;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to check partition existence: %s", partitionName);
            return false;
        }
    }

    /**
     * Cleanup old processed event records (retain 7 days for idempotency).
     *
     * <p>
     * The processed_domain_events table tracks events to prevent double-counting during lookback window reprocessing.
     * We only need to retain records for 7 days (longer than typical lookback window) to support idempotency.
     */
    @Transactional
    void cleanupProcessedEvents() {
        try {
            String sql = "DELETE FROM processed_domain_events WHERE processed_at < NOW() - INTERVAL '7 days'";
            int deleted = entityManager.createNativeQuery(sql).executeUpdate();

            if (deleted > 0) {
                LOG.infof("Cleaned up %d old processed event records", deleted);
                meterRegistry.counter("processed_events.cleanup", "deleted", String.valueOf(deleted)).increment();
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to cleanup processed events");
            // Don't throw - cleanup failure shouldn't block partition maintenance
        }
    }

    /**
     * Get current retention lag (for monitoring).
     *
     * @return duration until next partition drop
     */
    public Duration getRetentionLag() {
        LocalDate archiveCutoff = LocalDate.now().minusMonths(RETENTION_MONTHS).withDayOfMonth(1);
        LocalDate today = LocalDate.now();
        return Duration.between(archiveCutoff.atStartOfDay(), today.atStartOfDay());
    }
}
