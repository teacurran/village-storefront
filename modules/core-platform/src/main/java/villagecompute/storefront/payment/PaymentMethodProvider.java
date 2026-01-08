package villagecompute.storefront.payment;

import java.util.List;
import java.util.Map;

/**
 * Payment method management abstraction for saving, retrieving, and deleting customer payment methods (credit cards,
 * bank accounts, digital wallets).
 *
 * Implementations handle provider-specific tokenization and PCI compliance.
 */
public interface PaymentMethodProvider {

    /**
     * Create or attach a payment method to a customer.
     *
     * @param request
     *            Payment method creation parameters
     * @return Result containing provider payment method ID
     */
    PaymentMethodResult createPaymentMethod(CreatePaymentMethodRequest request);

    /**
     * Retrieve all payment methods for a customer.
     *
     * @param customerId
     *            Provider-specific customer identifier
     * @return List of payment methods
     */
    List<PaymentMethodInfo> listPaymentMethods(String customerId);

    /**
     * Retrieve a specific payment method.
     *
     * @param paymentMethodId
     *            Provider-specific payment method identifier
     * @return Payment method details
     */
    PaymentMethodInfo getPaymentMethod(String paymentMethodId);

    /**
     * Detach or delete a payment method.
     *
     * @param paymentMethodId
     *            Provider-specific payment method identifier
     * @return Deletion result
     */
    boolean deletePaymentMethod(String paymentMethodId);

    /**
     * Set a payment method as the default for a customer.
     *
     * @param customerId
     *            Provider-specific customer identifier
     * @param paymentMethodId
     *            Provider-specific payment method identifier
     * @return Success status
     */
    boolean setDefaultPaymentMethod(String customerId, String paymentMethodId);

    /**
     * Payment method creation request.
     */
    record CreatePaymentMethodRequest(String customerId, PaymentMethodType type, String token, // Provider-specific
                                                                                               // tokenized payment
                                                                                               // method
            Map<String, String> billingDetails, Map<String, String> metadata) {
    }

    /**
     * Result of payment method creation.
     */
    record PaymentMethodResult(String paymentMethodId, PaymentMethodType type, PaymentMethodInfo info) {
    }

    /**
     * Payment method information (sanitized, no sensitive data).
     */
    record PaymentMethodInfo(String paymentMethodId, PaymentMethodType type, String last4, // Last 4 digits of
                                                                                           // card/account
            String brand, // Visa, Mastercard, etc.
            Integer expiryMonth, Integer expiryYear, boolean isDefault, Map<String, String> metadata) {
    }

    /**
     * Supported payment method types.
     */
    enum PaymentMethodType {
        CARD, BANK_ACCOUNT, DIGITAL_WALLET, BUY_NOW_PAY_LATER
    }
}
