package villagecompute.storefront.services.jobs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.opencsv.CSVWriter;

import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.reporting.ReportStorageClient;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Job handler for exporting catalog data to CSV files.
 *
 * <p>
 * Streams catalog products and variants to CSV format and uploads to R2/MinIO object storage. Uses pagination to avoid
 * memory bloat on large catalogs.
 *
 * <p>
 * CSV Format (matches import schema):
 * <ul>
 * <li>sku</li>
 * <li>name</li>
 * <li>description</li>
 * <li>type</li>
 * <li>status</li>
 * <li>price</li>
 * <li>compare_at_price</li>
 * <li>cost</li>
 * <li>track_inventory</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogExportJobHandler {

    private static final Logger LOG = Logger.getLogger(CatalogExportJobHandler.class);
    private static final int PAGE_SIZE = 100;
    private static final Duration SIGNED_URL_EXPIRY = Duration.ofHours(24);

    @Inject
    ProductRepository productRepository;

    @Inject
    ReportStorageClient storageClient;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Process catalog export job payload.
     *
     * @param payload
     *            export job payload
     * @return signed download URL for exported CSV
     */
    @Transactional
    public String process(CatalogExportJobPayload payload) {
        Timer.Sample sample = Timer.start(meterRegistry);

        UUID tenantId = payload.getTenantId();
        Map<String, String> filters = payload.getFilters() != null ? payload.getFilters() : Map.of();

        LOG.infof("Processing catalog export - jobId=%s, tenantId=%s, format=%s", payload.getJobId(), tenantId,
                payload.getFormat());

        meterRegistry.counter("catalog.export.job.started", "tenant_id", tenantId.toString()).increment();

        try {
            if (!"csv".equalsIgnoreCase(payload.getFormat())) {
                throw new IllegalArgumentException("Unsupported export format: " + payload.getFormat());
            }

            String objectKey = String.format("%s/catalog-exports/%s.csv", tenantId, payload.getJobId());
            String contentType = "text/csv";

            Path tempFile = Files.createTempFile("catalog-export-", ".csv");
            long rowCount = 0;

            try {
                rowCount = writeCsvToFile(tempFile, filters);
                long fileSize = Files.size(tempFile);

                try (InputStream inputStream = Files.newInputStream(tempFile, StandardOpenOption.READ)) {
                    storageClient.uploadReport(objectKey, inputStream, contentType, fileSize);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }

            String signedUrl = storageClient.getSignedDownloadUrl(objectKey, SIGNED_URL_EXPIRY);

            LOG.infof("Catalog export completed - jobId=%s, tenantId=%s, url=%s", payload.getJobId(), tenantId,
                    signedUrl);

            sample.stop(meterRegistry.timer("catalog.export.job.duration", "tenant_id", tenantId.toString()));
            meterRegistry.counter("catalog.export.job.completed", "tenant_id", tenantId.toString()).increment();
            meterRegistry.counter("catalog.export.rows", "tenant_id", tenantId.toString()).increment(rowCount);
            LOG.infof("Generated %d catalog rows for tenant %s", rowCount, tenantId);

            return signedUrl;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            LOG.errorf(e, "Catalog export failed - jobId=%s, tenantId=%s", payload.getJobId(), tenantId);
            meterRegistry.counter("catalog.export.job.failed", "tenant_id", tenantId.toString()).increment();
            throw new RuntimeException("Catalog export failed: " + e.getMessage(), e);
        }
    }

    private long writeCsvToFile(Path tempFile, Map<String, String> filters) throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                Files.newOutputStream(tempFile, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                StandardCharsets.UTF_8); CSVWriter csvWriter = new CSVWriter(writer)) {

            csvWriter.writeNext(new String[]{"sku", "name", "description", "type", "status"});

            long totalRows = 0;
            int page = 0;
            List<Product> products;
            do {
                products = fetchProducts(filters, page);
                if (products.isEmpty()) {
                    break;
                }

                for (Product product : products) {
                    csvWriter.writeNext(new String[]{product.sku, product.name,
                            product.description != null ? product.description : "",
                            product.type != null ? product.type : "physical",
                            product.status != null ? product.status : "draft"});
                    totalRows++;
                }

                page++;
            } while (products.size() == PAGE_SIZE);

            csvWriter.flush();
            return totalRows;
        }
    }

    private List<Product> fetchProducts(Map<String, String> filters, int page) {
        String statusFilter = filters != null ? filters.get("status") : null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            return productRepository.findByStatusForCurrentTenant(statusFilter, page, PAGE_SIZE);
        }
        return productRepository.findByCurrentTenant(page, PAGE_SIZE);
    }
}
