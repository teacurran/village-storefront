package villagecompute.storefront.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.GiftCardTransaction;
import villagecompute.storefront.data.models.PaymentTender;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service responsible for persisting multi-tender payment splits for checkout orders.
 *
 * <p>
 * Gift card and store credit redemptions call into this service to ensure the order has a ledger of each tender portion
 * alongside Stripe card payments.
 * </p>
 */
@ApplicationScoped
public class CheckoutTenderService {

    private static final Logger LOG = Logger.getLogger(CheckoutTenderService.class);

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Record a gift card tender for an order if an order id is available.
     *
     * @param orderId
     *            order identifier (nullable when cart not yet converted to order)
     * @param transaction
     *            persisted gift card transaction
     * @return persisted {@link PaymentTender} or {@code null} when orderId missing
     */
    @Transactional
    public PaymentTender recordGiftCardTender(Long orderId, GiftCardTransaction transaction) {
        if (orderId == null || transaction == null) {
            LOG.debug("Skipping gift card tender recording - missing orderId or transaction");
            return null;
        }

        PaymentTender tender = new PaymentTender();
        tender.orderId = orderId;
        tender.tenderType = "gift_card";
        tender.amount = transaction.amount.abs();
        tender.currency = transaction.giftCard.currency;
        tender.giftCard = transaction.giftCard;
        tender.transactionId = transaction.id;
        tender.status = "captured";
        tender.persist();

        LOG.infof("Recorded gift card tender - tenantId=%s, orderId=%s, giftCardId=%s, amount=%s",
                TenantContext.getCurrentTenantId(), orderId, transaction.giftCard.id, tender.amount);
        meterRegistry.counter("checkout.tender.recorded", "tenant_id", TenantContext.getCurrentTenantId().toString(),
                "tender_type", "gift_card").increment();
        return tender;
    }

    /**
     * Record a store credit tender for an order if order id available.
     */
    @Transactional
    public PaymentTender recordStoreCreditTender(Long orderId, StoreCreditTransaction transaction) {
        if (orderId == null || transaction == null) {
            LOG.debug("Skipping store credit tender recording - missing orderId or transaction");
            return null;
        }

        PaymentTender tender = new PaymentTender();
        tender.orderId = orderId;
        tender.tenderType = "store_credit";
        tender.amount = transaction.amount.abs();
        tender.currency = transaction.account.currency;
        tender.storeCreditAccount = transaction.account;
        tender.transactionId = transaction.id;
        tender.status = "captured";
        tender.persist();

        LOG.infof("Recorded store credit tender - tenantId=%s, orderId=%s, accountId=%s, amount=%s",
                TenantContext.getCurrentTenantId(), orderId, transaction.account.id, tender.amount);
        meterRegistry.counter("checkout.tender.recorded", "tenant_id", TenantContext.getCurrentTenantId().toString(),
                "tender_type", "store_credit").increment();
        return tender;
    }
}
