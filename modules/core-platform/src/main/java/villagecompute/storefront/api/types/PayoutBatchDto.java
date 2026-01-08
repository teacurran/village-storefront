package villagecompute.storefront.api.types;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PayoutBatchDto for payout batch API responses.
 *
 * <p>
 * Represents a batch payout to a consignor for a specific period.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: PayoutBatchDto component schema</li>
 * <li>Endpoint: GET /admin/payouts</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
public class PayoutBatchDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("consignorId")
    private UUID consignorId;

    @JsonProperty("consignorName")
    private String consignorName;

    @JsonProperty("periodStart")
    private LocalDate periodStart;

    @JsonProperty("periodEnd")
    private LocalDate periodEnd;

    @JsonProperty("totalAmount")
    private Money totalAmount;

    @JsonProperty("status")
    private String status;

    @JsonProperty("processedAt")
    private OffsetDateTime processedAt;

    @JsonProperty("paymentReference")
    private String paymentReference;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getConsignorId() {
        return consignorId;
    }

    public void setConsignorId(UUID consignorId) {
        this.consignorId = consignorId;
    }

    public String getConsignorName() {
        return consignorName;
    }

    public void setConsignorName(String consignorName) {
        this.consignorName = consignorName;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Money totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(OffsetDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public void setPaymentReference(String paymentReference) {
        this.paymentReference = paymentReference;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
