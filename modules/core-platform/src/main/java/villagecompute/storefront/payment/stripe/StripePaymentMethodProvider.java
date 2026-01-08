package villagecompute.storefront.payment.stripe;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.PaymentMethod;
import com.stripe.model.PaymentMethodCollection;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentMethodAttachParams;
import com.stripe.param.PaymentMethodListParams;
import com.stripe.param.PaymentMethodUpdateParams;

import villagecompute.storefront.payment.PaymentMethodProvider;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Stripe implementation of {@link PaymentMethodProvider}. Wraps Stripe PaymentMethod APIs to attach methods, enumerate
 * stored payment details, and manage defaults for a customer.
 */
@ApplicationScoped
public class StripePaymentMethodProvider implements PaymentMethodProvider {

    private static final Logger LOGGER = Logger.getLogger(StripePaymentMethodProvider.class);

    @Inject
    StripeConfig stripeConfig;

    @Inject
    MeterRegistry meterRegistry;

    private void init() {
        Stripe.apiKey = stripeConfig.apiSecretKey();
        Stripe.setMaxNetworkRetries(stripeConfig.maxRetries());
    }

    @Override
    public PaymentMethodResult createPaymentMethod(CreatePaymentMethodRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            PaymentMethod paymentMethod = retrieveOrCreatePaymentMethod(request);

            if (request.customerId() != null && !request.customerId().isBlank()) {
                attachToCustomer(paymentMethod, request.customerId());
            }

            paymentMethod = updatePaymentMethodDetails(paymentMethod, request);

            PaymentMethodInfo info = toPaymentMethodInfo(paymentMethod, null, request.customerId());

            meterRegistry.counter("payments.methods.created", "tenant", tenantTag, "provider", "stripe").increment();

            return new PaymentMethodResult(paymentMethod.getId(), info.type(), info);

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to create payment method: %s", tenantTag, e.getMessage());
            throw new PaymentMethodProviderException("Failed to create payment method: " + e.getMessage(), e);
        } finally {
            sample.stop(
                    meterRegistry.timer("payments.methods.create.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public List<PaymentMethodInfo> listPaymentMethods(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID is required to list payment methods");
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            init();

            String defaultPaymentMethodId = resolveDefaultPaymentMethod(customerId);

            PaymentMethodListParams params = PaymentMethodListParams.builder().setCustomer(customerId)
                    .setType(PaymentMethodListParams.Type.CARD).build();

            PaymentMethodCollection collection = PaymentMethod.list(params);

            meterRegistry.counter("payments.methods.listed", "tenant", tenantTag, "provider", "stripe").increment();

            return collection.getData().stream().map(pm -> toPaymentMethodInfo(pm, defaultPaymentMethodId, customerId))
                    .collect(Collectors.toList());

        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to list payment methods for customer %s: %s", tenantTag, customerId,
                    e.getMessage());
            throw new PaymentMethodProviderException("Failed to list payment methods: " + e.getMessage(), e);
        } finally {
            sample.stop(
                    meterRegistry.timer("payments.methods.list.duration", "tenant", tenantTag, "provider", "stripe"));
        }
    }

    @Override
    public PaymentMethodInfo getPaymentMethod(String paymentMethodId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();

        try {
            init();
            PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId);
            return toPaymentMethodInfo(paymentMethod, null, paymentMethod.getCustomer());
        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to retrieve payment method %s: %s", tenantTag, paymentMethodId,
                    e.getMessage());
            throw new PaymentMethodProviderException("Failed to retrieve payment method: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deletePaymentMethod(String paymentMethodId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();

        try {
            init();
            PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId);
            paymentMethod.detach();

            meterRegistry.counter("payments.methods.deleted", "tenant", tenantTag, "provider", "stripe").increment();
            return true;
        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to delete payment method %s: %s", tenantTag, paymentMethodId,
                    e.getMessage());
            throw new PaymentMethodProviderException("Failed to delete payment method: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean setDefaultPaymentMethod(String customerId, String paymentMethodId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId.toString();

        try {
            init();
            Customer customer = Customer.retrieve(customerId);

            CustomerUpdateParams.InvoiceSettings invoiceSettings = CustomerUpdateParams.InvoiceSettings.builder()
                    .setDefaultPaymentMethod(paymentMethodId).build();

            CustomerUpdateParams params = CustomerUpdateParams.builder().setInvoiceSettings(invoiceSettings).build();

            customer.update(params);

            meterRegistry.counter("payments.methods.default.set", "tenant", tenantTag, "provider", "stripe")
                    .increment();
            return true;
        } catch (StripeException e) {
            LOGGER.errorf(e, "[Tenant: %s] Failed to set default payment method %s for customer %s: %s", tenantTag,
                    paymentMethodId, customerId, e.getMessage());
            throw new PaymentMethodProviderException("Failed to set default payment method: " + e.getMessage(), e);
        }
    }

    private PaymentMethod retrieveOrCreatePaymentMethod(CreatePaymentMethodRequest request) throws StripeException {
        if (request.token() == null || request.token().isBlank()) {
            throw new IllegalArgumentException("Payment method token or ID is required");
        }

        if (!request.token().startsWith("pm_")) {
            throw new PaymentMethodProviderException("Only payment method IDs (pm_*) can be attached server-side");
        }

        return PaymentMethod.retrieve(request.token());
    }

    private void attachToCustomer(PaymentMethod paymentMethod, String customerId) throws StripeException {
        if (customerId.equals(paymentMethod.getCustomer())) {
            return;
        }

        PaymentMethodAttachParams attachParams = PaymentMethodAttachParams.builder().setCustomer(customerId).build();

        paymentMethod.attach(attachParams);
    }

    private PaymentMethod updatePaymentMethodDetails(PaymentMethod paymentMethod, CreatePaymentMethodRequest request)
            throws StripeException {

        boolean needsUpdate = (request.billingDetails() != null && !request.billingDetails().isEmpty())
                || (request.metadata() != null && !request.metadata().isEmpty());

        if (!needsUpdate) {
            return paymentMethod;
        }

        PaymentMethodUpdateParams.Builder builder = PaymentMethodUpdateParams.builder();

        if (request.billingDetails() != null && !request.billingDetails().isEmpty()) {
            PaymentMethodUpdateParams.BillingDetails.Builder billingBuilder = PaymentMethodUpdateParams.BillingDetails
                    .builder();

            if (request.billingDetails().get("name") != null) {
                billingBuilder.setName(request.billingDetails().get("name"));
            }
            if (request.billingDetails().get("email") != null) {
                billingBuilder.setEmail(request.billingDetails().get("email"));
            }
            if (request.billingDetails().get("phone") != null) {
                billingBuilder.setPhone(request.billingDetails().get("phone"));
            }

            builder.setBillingDetails(billingBuilder.build());
        }

        if (request.metadata() != null && !request.metadata().isEmpty()) {
            builder.putAllMetadata(request.metadata());
        }

        return paymentMethod.update(builder.build());
    }

    private PaymentMethodInfo toPaymentMethodInfo(PaymentMethod paymentMethod, String defaultPaymentMethodId,
            String customerId) {
        PaymentMethod.Card card = paymentMethod.getCard();

        boolean isDefault = defaultPaymentMethodId != null && defaultPaymentMethodId.equals(paymentMethod.getId());
        if (!isDefault && customerId != null) {
            isDefault = isDefaultCustomerPaymentMethod(customerId, paymentMethod.getId(), defaultPaymentMethodId);
        }

        return new PaymentMethodInfo(paymentMethod.getId(), mapType(paymentMethod.getType()),
                card != null ? card.getLast4() : null, card != null ? card.getBrand() : null,
                card != null ? toInteger(card.getExpMonth()) : null, card != null ? toInteger(card.getExpYear()) : null,
                isDefault,
                paymentMethod.getMetadata() != null ? Collections.unmodifiableMap(paymentMethod.getMetadata())
                        : Map.of());
    }

    private Integer toInteger(Long value) {
        return value != null ? value.intValue() : null;
    }

    private PaymentMethodType mapType(String stripeType) {
        if (stripeType == null) {
            return PaymentMethodType.CARD;
        }
        return switch (stripeType) {
            case "card" -> PaymentMethodType.CARD;
            case "us_bank_account", "ach_credit_transfer", "ach_debit" -> PaymentMethodType.BANK_ACCOUNT;
            case "alipay", "wechat_pay", "cashapp", "paypal" -> PaymentMethodType.DIGITAL_WALLET;
            case "afterpay_clearpay", "klarna" -> PaymentMethodType.BUY_NOW_PAY_LATER;
            default -> PaymentMethodType.CARD;
        };
    }

    private String resolveDefaultPaymentMethod(String customerId) throws StripeException {
        Customer customer = Customer.retrieve(customerId);
        if (customer.getInvoiceSettings() != null) {
            return customer.getInvoiceSettings().getDefaultPaymentMethod();
        }
        return null;
    }

    private boolean isDefaultCustomerPaymentMethod(String customerId, String paymentMethodId,
            String defaultPaymentMethodId) {
        if (defaultPaymentMethodId != null) {
            return defaultPaymentMethodId.equals(paymentMethodId);
        }
        return false;
    }

    /**
     * Runtime exception for payment method failures.
     */
    public static class PaymentMethodProviderException extends RuntimeException {
        public PaymentMethodProviderException(String message, Throwable cause) {
            super(message, cause);
        }

        public PaymentMethodProviderException(String message) {
            super(message);
        }
    }
}
