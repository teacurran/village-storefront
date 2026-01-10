package villagecompute.storefront.services;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.PayoutLineItem;
import villagecompute.storefront.data.repositories.PayoutLineItemRepository;
import villagecompute.storefront.reporting.ReportStorageClient;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for generating and storing consignment payout statements.
 *
 * <p>
 * Generates PDF statements summarizing payout batches with line item details, commission calculations, and totals.
 * Statements are stored in R2 object storage with tenant-scoped paths for security and multi-tenancy.
 *
 * <p>
 * Statement structure:
 * <ul>
 * <li>Header: Batch ID, period, consignor details, payout date</li>
 * <li>Line Items: Item description, sale price, commission rate, commission amount, net payout</li>
 * <li>Summary: Total sales, total commission, total payout, Stripe payout reference</li>
 * </ul>
 *
 * <p>
 * Storage path pattern: {@code {tenantId}/consignment/statements/{batchId}.pdf}
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Automated consignment payout implementation</li>
 * <li>Acceptance Criteria: "statements stored to R2"</li>
 * <li>docs/consignment/domain.md: Payout batch and line item specifications</li>
 * </ul>
 */
@ApplicationScoped
public class PayoutStatementService {

    private static final Logger LOG = Logger.getLogger(PayoutStatementService.class);

    @Inject
    ReportStorageClient storageClient;

    @Inject
    PayoutLineItemRepository lineItemRepository;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Generate and store a PDF statement for a payout batch.
     *
     * <p>
     * This method creates a simple text-based statement (PDF generation would require iText or similar library in
     * production). The statement is uploaded to R2 and a signed download URL is generated.
     *
     * @param batch
     *            payout batch to generate statement for
     * @return signed download URL valid for 30 days
     */
    public String generateAndStoreStatement(PayoutBatch batch) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Generating payout statement - tenantId=%s, batchId=%s", tenantId, batch.id);

        try {
            // Fetch line items for batch
            List<PayoutLineItem> lineItems = lineItemRepository.findByBatch(batch.id);

            // Generate statement content (simplified - production would use PDF library)
            String statementContent = generateStatementContent(batch, lineItems);
            byte[] pdfData = statementContent.getBytes();

            // Upload to R2
            String storageKey = String.format("%s/consignment/statements/%s.pdf", tenantId, batch.id);
            InputStream dataStream = new ByteArrayInputStream(pdfData);

            storageClient.uploadReport(storageKey, dataStream, "application/pdf", pdfData.length);

            LOG.infof("Payout statement uploaded - tenantId=%s, batchId=%s, storageKey=%s", tenantId, batch.id,
                    storageKey);
            meterRegistry.counter("consignment.payout.statement.generated", "tenant", tenantId.toString()).increment();

            // Generate signed download URL (valid for 30 days)
            String downloadUrl = storageClient.getSignedDownloadUrl(storageKey, Duration.ofDays(30));

            return downloadUrl;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to generate payout statement - tenantId=%s, batchId=%s", tenantId, batch.id);
            meterRegistry.counter("consignment.payout.statement.failed", "tenant", tenantId.toString(), "reason",
                    e.getClass().getSimpleName()).increment();
            throw new RuntimeException("Failed to generate payout statement: " + e.getMessage(), e);
        }
    }

    /**
     * Generate statement content as formatted text.
     *
     * <p>
     * Production implementation would use a PDF library (e.g., iText, Apache PDFBox) to create properly formatted PDF
     * with tables, branding, and styling. This simplified version generates plain text for demonstration.
     *
     * @param batch
     *            payout batch
     * @param lineItems
     *            line items for the batch
     * @return statement content as text
     */
    private String generateStatementContent(PayoutBatch batch, List<PayoutLineItem> lineItems) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("========================================\n");
        sb.append("CONSIGNMENT PAYOUT STATEMENT\n");
        sb.append("========================================\n\n");
        sb.append(String.format("Batch ID: %s\n", batch.id));
        sb.append(String.format("Consignor: %s\n", batch.consignor.name));
        sb.append(String.format("Period: %s to %s\n", batch.periodStart, batch.periodEnd));
        sb.append(String.format("Payout Date: %s\n", batch.processedAt != null ? batch.processedAt : "Pending"));
        sb.append(String.format("Status: %s\n", batch.status));
        if (batch.paymentReference != null) {
            sb.append(String.format("Payment Reference: %s\n", batch.paymentReference));
        }
        sb.append("\n");

        // Line Items
        sb.append("LINE ITEMS:\n");
        sb.append("----------------------------------------\n");
        sb.append(
                String.format("%-40s %12s %8s %12s %12s\n", "Item", "Sale Price", "Rate", "Commission", "Net Payout"));
        sb.append("----------------------------------------\n");

        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalPayout = BigDecimal.ZERO;

        for (PayoutLineItem item : lineItems) {
            // PayoutLineItem doesn't have description field - use generic label
            String itemDesc = String.format("Item #%s", item.orderLineItemId.toString().substring(0, 8));

            BigDecimal salePrice = item.itemSubtotal;
            BigDecimal commission = item.commissionAmount;
            BigDecimal netPayout = item.netPayout;

            // Calculate commission rate from amounts
            BigDecimal commissionRate = salePrice.compareTo(BigDecimal.ZERO) > 0
                    ? commission.divide(salePrice, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            sb.append(String.format("%-40s $%11.2f %7.1f%% $%11.2f $%11.2f\n", itemDesc, salePrice, commissionRate,
                    commission, netPayout));

            totalSales = totalSales.add(salePrice);
            totalCommission = totalCommission.add(commission);
            totalPayout = totalPayout.add(netPayout);
        }

        // Summary
        sb.append("----------------------------------------\n");
        sb.append(String.format("%-40s $%11.2f\n", "Total Sales:", totalSales));
        sb.append(String.format("%-40s $%11.2f\n", "Total Commission:", totalCommission));
        sb.append(String.format("%-40s $%11.2f\n", "TOTAL PAYOUT:", totalPayout));
        sb.append("========================================\n");

        return sb.toString();
    }
}
