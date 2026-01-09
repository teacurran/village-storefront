package villagecompute.storefront.services.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opencsv.CSVReader;

import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.reporting.StubReportStorageClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit tests for CatalogExportJobHandler.
 *
 * <p>
 * Tests CSV generation, storage upload, and signed URL generation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CatalogExportJobHandlerTest {

    @Mock
    ProductRepository productRepository;

    CatalogExportJobHandler handler;
    StubReportStorageClient storageClient;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        storageClient = new StubReportStorageClient();
        handler = new CatalogExportJobHandler();
        handler.productRepository = productRepository;
        handler.storageClient = storageClient;
        handler.meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void processWritesCsvToStorage() throws Exception {
        Product product = new Product();
        product.sku = "WIDGET-001";
        product.name = "Widget";
        product.description = "A test product";
        product.type = "physical";
        product.status = "active";

        when(productRepository.findByCurrentTenant(0, 100)).thenReturn(List.of(product));

        CatalogExportJobPayload payload = CatalogExportJobPayload.create(tenantId, "tester", Map.of(), "csv");

        String url = handler.process(payload);

        assertNotNull(url);
        String key = String.format("%s/catalog-exports/%s.csv", tenantId, payload.getJobId());
        StubReportStorageClient.StoredReport stored = storageClient.getStoredReport(key);
        assertNotNull(stored);
        byte[] data = stored.getData();
        String csv = new String(data, StandardCharsets.UTF_8);
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            List<String[]> rows = reader.readAll();
            assertEquals("sku", rows.get(0)[0]);
            assertEquals("WIDGET-001", rows.get(1)[0]);
            assertEquals("A test product", rows.get(1)[2]);
        }
    }

    @Test
    void processAppliesStatusFilter() {
        Product product = new Product();
        product.sku = "ACTIVE-1";
        product.name = "Active product";
        product.status = "active";
        product.type = "physical";

        when(productRepository.findByStatusForCurrentTenant("active", 0, 100)).thenReturn(List.of(product));

        CatalogExportJobPayload payload = CatalogExportJobPayload.create(tenantId, "tester", Map.of("status", "active"),
                "csv");

        handler.process(payload);

        verify(productRepository, never()).findByCurrentTenant(anyInt(), anyInt());
    }

    @Test
    void processRejectsUnsupportedFormat() {
        CatalogExportJobPayload payload = CatalogExportJobPayload.create(tenantId, "tester", Map.of(), "json");
        IllegalArgumentException exception = org.junit.jupiter.api.Assertions
                .assertThrows(IllegalArgumentException.class, () -> handler.process(payload));
        assertTrue(exception.getMessage().contains("Unsupported export format"));
    }
}
