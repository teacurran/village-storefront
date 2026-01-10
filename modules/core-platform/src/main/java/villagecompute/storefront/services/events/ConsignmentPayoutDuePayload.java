package villagecompute.storefront.services.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payload for CONSIGNMENT_PAYOUT_DUE domain event.
 *
 * <p>
 * Signals that a consignor has pending balance eligible for payout (e.g., settled past hold period). Consumed by payout
 * sweep jobs to initiate batch processing and notifications.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Consignment domain event payloads</li>
 * <li>Architecture: Domain event payloads for reporting</li>
 * </ul>
 *
 * @param consignorId
 *            UUID of the consignor eligible for payout
 * @param consignorName
 *            name of the consignor (denormalized for reporting)
 * @param availableBalance
 *            available balance ready for payout
 * @param currency
 *            currency code (e.g., USD)
 * @param periodStart
 *            start date of the payout period (inclusive)
 * @param periodEnd
 *            end date of the payout period (inclusive)
 * @param triggeredBy
 *            reason or system that triggered the payout due event (e.g., "SETTLEMENT_JOB", "MANUAL")
 */
public record ConsignmentPayoutDuePayload(UUID consignorId, String consignorName, BigDecimal availableBalance,
        String currency, LocalDate periodStart, LocalDate periodEnd, String triggeredBy) {
}
