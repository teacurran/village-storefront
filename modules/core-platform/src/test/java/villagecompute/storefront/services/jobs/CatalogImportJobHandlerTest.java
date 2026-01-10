package villagecompute.storefront.services.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.validation.CatalogValidator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit tests for CatalogImportJobHandler.
 *
 * <p>
 * Tests CSV parsing, validation, idempotency, and error handling.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * </ul>
 */
class CatalogImportJobHandlerTest {

    @Mock
    CatalogService catalogService;

    @Mock
    ProductRepository productRepository;

    @Mock
    CatalogValidator catalogValidator;

    CatalogImportJobHandler handler;

    @TempDir
    File tempDir;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        tenantId = UUID.randomUUID();
        handler = new CatalogImportJobHandler();
        handler.catalogService = catalogService;
        handler.productRepository = productRepository;
        handler.catalogValidator = catalogValidator;
        handler.meterRegistry = new SimpleMeterRegistry();

        doNothing().when(catalogValidator).validateProductType(anyString());
        doNothing().when(catalogValidator).validateStatusTransition(anyString(), anyString());
    }

    @Test
    void processValidCsvCreatesProducts() throws IOException {
        File csvFile = writeCsv("valid.csv", "sku,name,description,type,status",
                "WIDGET-001,Test Widget,A test product,physical,active",
                "WIDGET-002,Another Widget,Another test product,physical,draft");

        when(productRepository.findBySku("WIDGET-001")).thenReturn(Optional.empty());
        when(productRepository.findBySku("WIDGET-002")).thenReturn(Optional.empty());

        CatalogImportJobPayload payload = CatalogImportJobPayload.create(tenantId, csvFile.getAbsolutePath(),
                "test-user", Map.of());

        CatalogImportJobHandler.CatalogImportResult result = handler.process(payload);

        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getErrorCount());
        verify(catalogService, times(2)).createProduct(any(Product.class));
        verify(catalogService, never()).updateProduct(any(), any());
    }

    @Test
    void processCsvUpdatesExistingProduct() throws IOException {
        File csvFile = writeCsv("update.csv", "sku,name,description,type,status",
                "WIDGET-001,Updated Widget,Fresh description,physical,active");

        Product existingProduct = new Product();
        existingProduct.id = UUID.randomUUID();
        existingProduct.sku = "WIDGET-001";
        existingProduct.slug = "existing-slug";
        existingProduct.status = "draft";
        when(productRepository.findBySku("WIDGET-001")).thenReturn(Optional.of(existingProduct));

        CatalogImportJobPayload payload = CatalogImportJobPayload.create(tenantId, csvFile.getAbsolutePath(),
                "test-user", Map.of());
        CatalogImportJobHandler.CatalogImportResult result = handler.process(payload);

        assertEquals(1, result.getSuccessCount());
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(catalogService).updateProduct(eq(existingProduct.id), productCaptor.capture());
        Product mapped = productCaptor.getValue();
        assertEquals("existing-slug", mapped.slug); // ensures slug preserved
    }

    @Test
    void processCsvCapturesRowErrors() throws IOException {
        File csvFile = writeCsv("errors.csv", "sku,name,description,type,status",
                "WIDGET-001,Valid Widget,Good row,physical,active", ",Invalid Widget,Missing SKU,physical,active");

        when(productRepository.findBySku("WIDGET-001")).thenReturn(Optional.empty());

        CatalogImportJobPayload payload = CatalogImportJobPayload.create(tenantId, csvFile.getAbsolutePath(),
                "test-user", Map.of());

        CatalogImportJobHandler.CatalogImportResult result = handler.process(payload);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertEquals("unknown", result.getErrors().get(0).getSku());
    }

    private File writeCsv(String name, String... lines) throws IOException {
        File csvFile = new File(tempDir, name);
        try (FileWriter writer = new FileWriter(csvFile)) {
            for (String line : lines) {
                writer.write(line);
                writer.write("\n");
            }
        }
        return csvFile;
    }
}
