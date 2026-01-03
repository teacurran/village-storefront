package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.InventoryAgingAggregate;
import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.SalesByPeriodAggregate;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.CartItemRepository;
import villagecompute.storefront.data.repositories.CartRepository;
import villagecompute.storefront.data.repositories.ConsignmentItemRepository;
import villagecompute.storefront.data.repositories.ConsignmentPayoutAggregateRepository;
import villagecompute.storefront.data.repositories.ConsignorRepository;
import villagecompute.storefront.data.repositories.InventoryAgingAggregateRepository;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.data.repositories.InventoryLocationRepository;
import villagecompute.storefront.data.repositories.SalesByPeriodAggregateRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Service for building and refreshing reporting projection aggregates.
 *
 * <p>
 * Consumes domain events (via direct repository access in MVP) and builds read-optimized aggregate tables for sales,
 * consignment payouts, and inventory aging analysis. All operations are tenant-scoped and emit observability metrics.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 02_System_Structure_and_Data.md (Module-to-Data Stewardship)</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.2.9)</li>
 * </ul>
 */
@ApplicationScoped
public class ReportingProjectionService {

    private static final Logger LOG = Logger.getLogger(ReportingProjectionService.class);
    private static final String JOB_NAME_PREFIX = "ReportingProjectionService.";

    @Inject
    SalesByPeriodAggregateRepository salesAggregateRepo;

    @Inject
    ConsignmentPayoutAggregateRepository payoutAggregateRepo;

    @Inject
    InventoryAgingAggregateRepository agingAggregateRepo;

    @Inject
    CartRepository cartRepository;

    @Inject
    CartItemRepository cartItemRepository;

    @Inject
    ConsignorRepository consignorRepository;

    @Inject
    ConsignmentItemRepository consignmentItemRepository;

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    InventoryLocationRepository inventoryLocationRepository;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Refresh sales aggregates for the current tenant and specified period.
     *
     * <p>
     * Computes total sales amount, item count, and order count from cart data (proxy for orders in MVP).
     *
     * @param periodStart
     *            start date (inclusive)
     * @param periodEnd
     *            end date (inclusive)
     */
    @Transactional
    public void refreshSalesAggregates(LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String jobName = JOB_NAME_PREFIX + "refreshSalesAggregates";

        LOG.infof("Refreshing sales aggregates - tenantId=%s, period=%s to %s", tenantId, periodStart, periodEnd);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Find or create aggregate record
            Optional<SalesByPeriodAggregate> existingOpt = salesAggregateRepo.findByExactPeriod(periodStart, periodEnd);
            SalesByPeriodAggregate aggregate;

            if (existingOpt.isPresent()) {
                aggregate = existingOpt.get();
            } else {
                aggregate = new SalesByPeriodAggregate();
                aggregate.tenant = Tenant.findById(tenantId);
                aggregate.periodStart = periodStart;
                aggregate.periodEnd = periodEnd;
                aggregate.createdAt = OffsetDateTime.now();
            }

            // Compute metrics from cart data (MVP: carts as proxy for orders)
            List<Cart> carts = cartRepository.findByCurrentTenant();
            BigDecimal totalAmount = BigDecimal.ZERO;
            int itemCount = 0;
            int orderCount = 0;

            for (Cart cart : carts) {
                if (cart.createdAt != null) {
                    LocalDate cartDate = cart.createdAt.toLocalDate();
                    if (!cartDate.isBefore(periodStart) && !cartDate.isAfter(periodEnd)) {
                        orderCount++;
                        // Fetch cart items for this cart
                        List<CartItem> items = cartItemRepository.findByCart(cart.id);
                        for (CartItem item : items) {
                            itemCount++;
                            BigDecimal itemTotal = item.unitPrice.multiply(new BigDecimal(item.quantity));
                            totalAmount = totalAmount.add(itemTotal);
                        }
                    }
                }
            }

            aggregate.totalAmount = totalAmount;
            aggregate.itemCount = itemCount;
            aggregate.orderCount = orderCount;
            aggregate.dataFreshnessTimestamp = OffsetDateTime.now();
            aggregate.jobName = jobName;
            aggregate.updatedAt = OffsetDateTime.now();

            salesAggregateRepo.persist(aggregate);

            LOG.infof(
                    "Sales aggregate refreshed - tenantId=%s, period=%s to %s, totalAmount=%s, itemCount=%d, orderCount=%d",
                    tenantId, periodStart, periodEnd, totalAmount, itemCount, orderCount);

            meterRegistry.counter("reporting.aggregate.refresh.completed", "tenant_id", tenantId.toString(),
                    "aggregate_type", "sales_by_period").increment();

            sample.stop(
                    meterRegistry.timer("reporting.aggregate.refresh.duration", "aggregate_type", "sales_by_period"));

            // Update freshness lag metric
            long lagSeconds = ChronoUnit.SECONDS.between(aggregate.dataFreshnessTimestamp, OffsetDateTime.now());
            meterRegistry
                    .gauge("reporting.aggregate.freshness.lag.seconds",
                            List.of(io.micrometer.core.instrument.Tag.of("aggregate_type", "sales_by_period"),
                                    io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString())),
                            lagSeconds);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh sales aggregates - tenantId=%s, period=%s to %s", tenantId, periodStart,
                    periodEnd);
            meterRegistry.counter("reporting.aggregate.refresh.failed", "tenant_id", tenantId.toString(),
                    "aggregate_type", "sales_by_period").increment();
            throw e;
        }
    }

    /**
     * Refresh consignment payout aggregates for the current tenant and specified period.
     *
     * <p>
     * Computes amounts owed to each consignor based on sold consignment items.
     *
     * @param periodStart
     *            start date (inclusive)
     * @param periodEnd
     *            end date (inclusive)
     */
    @Transactional
    public void refreshConsignmentPayoutAggregates(LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String jobName = JOB_NAME_PREFIX + "refreshConsignmentPayoutAggregates";

        LOG.infof("Refreshing consignment payout aggregates - tenantId=%s, period=%s to %s", tenantId, periodStart,
                periodEnd);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<Consignor> consignors = consignorRepository.findByCurrentTenant();

            for (Consignor consignor : consignors) {
                Optional<ConsignmentPayoutAggregate> existingOpt = payoutAggregateRepo.findExact(consignor.id,
                        periodStart, periodEnd);
                ConsignmentPayoutAggregate aggregate;

                if (existingOpt.isPresent()) {
                    aggregate = existingOpt.get();
                } else {
                    aggregate = new ConsignmentPayoutAggregate();
                    aggregate.tenant = Tenant.findById(tenantId);
                    aggregate.consignor = consignor;
                    aggregate.periodStart = periodStart;
                    aggregate.periodEnd = periodEnd;
                    aggregate.createdAt = OffsetDateTime.now();
                }

                // Compute payout metrics from consignment items (simplified for MVP)
                List<ConsignmentItem> items = consignmentItemRepository.findByConsignor(consignor.id, 0, 10000);
                BigDecimal totalOwed = BigDecimal.ZERO;
                int itemCount = 0;
                int itemsSold = 0;

                for (ConsignmentItem item : items) {
                    OffsetDateTime activityTime = item.soldAt != null ? item.soldAt : item.createdAt;
                    if (activityTime != null) {
                        LocalDate itemDate = activityTime.toLocalDate();
                        if (!itemDate.isBefore(periodStart) && !itemDate.isAfter(periodEnd)) {
                            itemCount++;
                            if ("sold".equals(item.status) && item.soldAt != null) {
                                itemsSold++;
                                // Simplified payout calculation using commission rate
                                // In production, this would use actual sale price from order
                                BigDecimal estimatedSale = new BigDecimal("100.00"); // Placeholder
                                BigDecimal commission = estimatedSale
                                        .multiply(item.commissionRate.divide(new BigDecimal("100")));
                                BigDecimal payout = estimatedSale.subtract(commission);
                                totalOwed = totalOwed.add(payout);
                            }
                        }
                    }
                }

                aggregate.totalOwed = totalOwed;
                aggregate.itemCount = itemCount;
                aggregate.itemsSold = itemsSold;
                aggregate.dataFreshnessTimestamp = OffsetDateTime.now();
                aggregate.jobName = jobName;
                aggregate.updatedAt = OffsetDateTime.now();

                payoutAggregateRepo.persist(aggregate);

                LOG.debugf(
                        "Consignment payout aggregate refreshed - tenantId=%s, consignor=%s, period=%s to %s, totalOwed=%s, itemsSold=%d",
                        tenantId, consignor.id, periodStart, periodEnd, totalOwed, itemsSold);
            }

            meterRegistry.counter("reporting.aggregate.refresh.completed", "tenant_id", tenantId.toString(),
                    "aggregate_type", "consignment_payout").increment();

            sample.stop(meterRegistry.timer("reporting.aggregate.refresh.duration", "aggregate_type",
                    "consignment_payout"));

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh consignment payout aggregates - tenantId=%s, period=%s to %s", tenantId,
                    periodStart, periodEnd);
            meterRegistry.counter("reporting.aggregate.refresh.failed", "tenant_id", tenantId.toString(),
                    "aggregate_type", "consignment_payout").increment();
            throw e;
        }
    }

    /**
     * Refresh inventory aging aggregates for the current tenant.
     *
     * <p>
     * Computes days in stock for each variant at each location based on inventory level history.
     */
    @Transactional
    public void refreshInventoryAgingAggregates() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String jobName = JOB_NAME_PREFIX + "refreshInventoryAgingAggregates";

        LOG.infof("Refreshing inventory aging aggregates - tenantId=%s", tenantId);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            List<InventoryLocation> locations = inventoryLocationRepository.findAllForTenant();

            for (InventoryLocation location : locations) {
                // Query inventory levels by location code (String)
                List<InventoryLevel> levels = inventoryLevelRepository.findByLocation(location.code);

                for (InventoryLevel level : levels) {
                    if (level.variant != null && level.quantity > 0) {
                        Optional<InventoryAgingAggregate> existingOpt = agingAggregateRepo.findExact(level.variant.id,
                                location.id);
                        InventoryAgingAggregate aggregate;

                        if (existingOpt.isPresent()) {
                            aggregate = existingOpt.get();
                        } else {
                            aggregate = new InventoryAgingAggregate();
                            aggregate.tenant = Tenant.findById(tenantId);
                            aggregate.variant = level.variant;
                            aggregate.location = location;
                            aggregate.firstReceivedAt = level.createdAt;
                            aggregate.createdAt = OffsetDateTime.now();
                        }

                        // Calculate days in stock
                        if (aggregate.firstReceivedAt != null) {
                            aggregate.daysInStock = (int) ChronoUnit.DAYS.between(aggregate.firstReceivedAt,
                                    OffsetDateTime.now());
                        } else {
                            aggregate.daysInStock = 0;
                        }

                        aggregate.quantity = level.quantity;
                        aggregate.dataFreshnessTimestamp = OffsetDateTime.now();
                        aggregate.jobName = jobName;
                        aggregate.updatedAt = OffsetDateTime.now();

                        agingAggregateRepo.persist(aggregate);
                    }
                }
            }

            LOG.infof("Inventory aging aggregates refreshed - tenantId=%s", tenantId);

            meterRegistry.counter("reporting.aggregate.refresh.completed", "tenant_id", tenantId.toString(),
                    "aggregate_type", "inventory_aging").increment();

            sample.stop(
                    meterRegistry.timer("reporting.aggregate.refresh.duration", "aggregate_type", "inventory_aging"));

        } catch (Exception e) {
            LOG.errorf(e, "Failed to refresh inventory aging aggregates - tenantId=%s", tenantId);
            meterRegistry.counter("reporting.aggregate.refresh.failed", "tenant_id", tenantId.toString(),
                    "aggregate_type", "inventory_aging").increment();
            throw e;
        }
    }
}
