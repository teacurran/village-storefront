package villagecompute.storefront.config;

import java.math.BigDecimal;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration properties for consignment payout automation.
 *
 * <p>
 * Maps properties from the {@code consignment.payout} namespace. All values have sensible defaults and can be
 * overridden via environment variables or application.properties.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Automated consignment payout implementation</li>
 * <li>ADR-004: Consignment payout state machine and approval logic</li>
 * </ul>
 *
 * @param automation
 *            automated batch creation configuration
 * @param settlement
 *            balance settlement configuration
 * @param autoApprove
 *            auto-approval threshold configuration
 * @param processing
 *            payout processing retry configuration
 */
@ConfigMapping(
        prefix = "consignment.payout")
public interface ConsignmentPayoutConfig {

    /**
     * Automated batch creation settings.
     */
    Automation automation();

    /**
     * Balance settlement settings (pending to available).
     */
    Settlement settlement();

    /**
     * Auto-approval threshold settings.
     */
    AutoApprove autoApprove();

    /**
     * Payout processing retry settings.
     */
    Processing processing();

    interface Automation {
        /**
         * Whether automated batch creation is enabled (default: false).
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Cron expression for batch creation job (default: monthly on 1st at midnight).
         */
        @WithDefault("0 0 1 * * ?")
        String batchCreationCron();
    }

    interface Settlement {
        /**
         * Whether settlement job is enabled (default: true).
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Cron expression for settlement job (default: every 6 hours).
         */
        @WithDefault("0 0 */6 * * ?")
        String cron();

        /**
         * Hold period in days before pending balance becomes available (default: 7).
         */
        @WithDefault("7")
        int holdPeriodDays();
    }

    interface AutoApprove {
        /**
         * Whether auto-approval is enabled (default: false).
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Maximum batch amount for auto-approval (default: 500.00 USD).
         */
        @WithDefault("500.00")
        BigDecimal amountLimit();

        /**
         * Minimum consignor tenure in months for auto-approval (default: 3).
         */
        @WithDefault("3")
        int minConsignorTenureMonths();

        /**
         * Lookback period in months for failure history check (default: 6).
         */
        @WithDefault("6")
        int failureLookbackMonths();
    }

    interface Processing {
        /**
         * Maximum number of retry attempts for failed payouts (default: 3).
         */
        @WithDefault("3")
        int maxRetries();

        /**
         * Retry backoff schedule in hours (default: 1,4,24).
         */
        @WithDefault("1,4,24")
        String retryBackoffHours();
    }
}
