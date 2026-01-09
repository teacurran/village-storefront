package villagecompute.storefront.services.jobs;

import java.io.FileReader;
import java.io.Reader;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;

import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.validation.CatalogValidator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Job handler for importing catalog data from CSV files.
 *
 * <p>
 * Processes CSV rows idempotently using SKU-based upsert logic. Validates each row independently and logs errors
 * without failing the entire import. Batches database operations for throughput.
 *
 * <p>
 * CSV Format:
 * <ul>
 * <li>sku (required)</li>
 * <li>name (required)</li>
 * <li>description</li>
 * <li>type (physical|digital)</li>
 * <li>status (draft|active|archived)</li>
 * <li>price</li>
 * <li>compare_at_price</li>
 * <li>cost</li>
 * <li>track_inventory (true|false)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * <li>KPI: 5k products/minute throughput target</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogImportJobHandler {

    private static final Logger LOG = Logger.getLogger(CatalogImportJobHandler.class);
    private static final int BATCH_SIZE = 100;

    @Inject
    CatalogService catalogService;

    @Inject
    ProductRepository productRepository;

    @Inject
    CatalogValidator catalogValidator;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Process catalog import job payload.
     *
     * @param payload
     *            import job payload
     * @return import result with success/error counts
     */
    @Transactional
    public CatalogImportResult process(CatalogImportJobPayload payload) {
        Timer.Sample sample = Timer.start(meterRegistry);

        UUID tenantId = payload.getTenantId();
        Map<String, String> options = payload.getOptions() != null ? payload.getOptions() : Collections.emptyMap();
        boolean skipValidation = Boolean.parseBoolean(options.getOrDefault("skipValidation", "false"));

        LOG.infof("Processing catalog import - jobId=%s, tenantId=%s, file=%s, skipValidation=%s", payload.getJobId(),
                tenantId, payload.getFileLocation(), skipValidation);

        meterRegistry.counter("catalog.import.job.started", "tenant_id", tenantId.toString()).increment();

        CatalogImportResult result = new CatalogImportResult();
        List<CatalogImportRow> batch = new ArrayList<>();

        try (Reader reader = new FileReader(payload.getFileLocation()); CSVReader csvReader = createCsvReader(reader)) {

            String[] header = csvReader.readNext();
            if (header == null || header.length == 0) {
                throw new IllegalArgumentException("CSV file is empty");
            }

            Map<String, Integer> columnMap = buildColumnMap(header);
            validateRequiredColumns(columnMap);

            String[] row;
            int rowNumber = 1; // Start at 1 (header is row 0)

            while ((row = csvReader.readNext()) != null) {
                rowNumber++;

                try {
                    CatalogImportRow parsedRow = parseRow(rowNumber, row, columnMap, skipValidation);
                    batch.add(parsedRow);

                    if (batch.size() >= BATCH_SIZE) {
                        processBatch(batch, result, tenantId);
                        batch.clear();
                    }

                } catch (Exception e) {
                    String rawSku = row.length > 0 ? row[0] : null;
                    String sku = rawSku != null && !rawSku.isBlank() ? rawSku : "unknown";
                    result.addError(rowNumber, sku, e.getMessage());
                    LOG.warnf("Row %d (sku=%s) validation failed - error=%s", rowNumber, sku, e.getMessage());
                    meterRegistry.counter("catalog.import.row.error", "tenant_id", tenantId.toString()).increment();
                }
            }

            // Process remaining batch
            if (!batch.isEmpty()) {
                processBatch(batch, result, tenantId);
            }

            LOG.infof("Catalog import completed - jobId=%s, processed=%d, success=%d, errors=%d", payload.getJobId(),
                    result.getTotalProcessed(), result.getSuccessCount(), result.getErrorCount());

            sample.stop(meterRegistry.timer("catalog.import.job.duration", "tenant_id", tenantId.toString()));

        } catch (Exception e) {
            LOG.errorf(e, "Catalog import failed - jobId=%s", payload.getJobId());
            meterRegistry.counter("catalog.import.job.failed", "tenant_id", tenantId.toString()).increment();
            throw new RuntimeException("Catalog import failed: " + e.getMessage(), e);
        }

        return result;
    }

    private CSVReader createCsvReader(Reader reader) {
        CSVParser parser = new CSVParserBuilder().withSeparator(',').withIgnoreQuotations(false).build();
        return new CSVReaderBuilder(reader).withCSVParser(parser).build();
    }

    private Map<String, Integer> buildColumnMap(String[] header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            map.put(header[i].trim().toLowerCase(), i);
        }
        return map;
    }

    private void validateRequiredColumns(Map<String, Integer> columnMap) {
        if (!columnMap.containsKey("sku")) {
            throw new IllegalArgumentException("Required column 'sku' not found in CSV header");
        }
        if (!columnMap.containsKey("name")) {
            throw new IllegalArgumentException("Required column 'name' not found in CSV header");
        }
    }

    private CatalogImportRow parseRow(int rowNumber, String[] row, Map<String, Integer> columnMap,
            boolean skipValidation) {
        String sku = getColumnValue(row, columnMap, "sku");
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU is required");
        }

        String name = getColumnValue(row, columnMap, "name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }

        String description = getColumnValue(row, columnMap, "description");
        String type = getColumnValueOrDefault(row, columnMap, "type", "physical");
        String status = getColumnValueOrDefault(row, columnMap, "status", "draft");

        if (!skipValidation) {
            catalogValidator.validateProductType(type);
        }

        Product existingProduct = productRepository.findBySku(sku).orElse(null);
        UUID existingProductId = existingProduct != null ? existingProduct.id : null;
        String existingStatus = existingProduct != null ? existingProduct.status : "draft";
        String slug = existingProduct != null && existingProduct.slug != null ? existingProduct.slug
                : generateSlug(name);

        if (!skipValidation) {
            catalogValidator.validateStatusTransition(existingStatus, status);
        }

        return new CatalogImportRow(rowNumber, sku, name, description, type, status, slug, existingProductId);
    }

    private void processBatch(List<CatalogImportRow> batch, CatalogImportResult result, UUID tenantId) {
        for (CatalogImportRow row : batch) {
            try {
                Product mappedProduct = mapRowToProduct(row);
                if (row.existingProductId == null) {
                    catalogService.createProduct(mappedProduct);
                    result.incrementSuccess();
                    meterRegistry.counter("catalog.import.row.created", "tenant_id", tenantId.toString()).increment();
                } else {
                    catalogService.updateProduct(row.existingProductId, mappedProduct);
                    result.incrementSuccess();
                    meterRegistry.counter("catalog.import.row.updated", "tenant_id", tenantId.toString()).increment();
                }
            } catch (Exception e) {
                result.addError(row.rowNumber, row.sku, "Persistence failed: " + e.getMessage());
                LOG.warnf("Failed to persist row %d (sku=%s) - error=%s", row.rowNumber, row.sku, e.getMessage());
                meterRegistry.counter("catalog.import.row.error", "tenant_id", tenantId.toString()).increment();
            }
        }
    }

    private Product mapRowToProduct(CatalogImportRow row) {
        Product product = new Product();
        product.sku = row.sku;
        product.name = row.name;
        product.description = row.description;
        product.slug = row.slug;
        product.type = row.type;
        product.status = row.status;
        product.updatedAt = OffsetDateTime.now();
        return product;
    }

    private String getColumnValue(String[] row, Map<String, Integer> columnMap, String columnName) {
        Integer index = columnMap.get(columnName.toLowerCase());
        if (index == null || index >= row.length) {
            return null;
        }
        String value = row[index];
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String getColumnValueOrDefault(String[] row, Map<String, Integer> columnMap, String columnName,
            String defaultValue) {
        String value = getColumnValue(row, columnMap, columnName);
        return value != null ? value : defaultValue;
    }

    private String generateSlug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /**
     * Result of catalog import operation.
     */
    public static class CatalogImportResult {
        private int successCount = 0;
        private final List<ImportError> errors = new ArrayList<>();

        public void incrementSuccess() {
            successCount++;
        }

        public void addError(int rowNumber, String sku, String message) {
            errors.add(new ImportError(rowNumber, sku, message));
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getErrorCount() {
            return errors.size();
        }

        public int getTotalProcessed() {
            return successCount + errors.size();
        }

        public List<ImportError> getErrors() {
            return errors;
        }

        public static class ImportError {
            private final int rowNumber;
            private final String sku;
            private final String message;

            public ImportError(int rowNumber, String sku, String message) {
                this.rowNumber = rowNumber;
                this.sku = sku;
                this.message = message;
            }

            public int getRowNumber() {
                return rowNumber;
            }

            public String getSku() {
                return sku;
            }

            public String getMessage() {
                return message;
            }
        }
    }

    private static final class CatalogImportRow {
        private final int rowNumber;
        private final String sku;
        private final String name;
        private final String description;
        private final String type;
        private final String status;
        private final String slug;
        private final UUID existingProductId;

        private CatalogImportRow(int rowNumber, String sku, String name, String description, String type, String status,
                String slug, UUID existingProductId) {
            this.rowNumber = rowNumber;
            this.sku = sku;
            this.name = name;
            this.description = description;
            this.type = type;
            this.status = status;
            this.slug = slug;
            this.existingProductId = existingProductId;
        }
    }
}
