package villagecompute.storefront.services.returns;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.repositories.ConsignorRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Coordinates queuing of inventory adjustments and notifications for return workflows.
 */
@ApplicationScoped
public class ReturnWorkflowCoordinator {

    private static final Logger LOG = Logger.getLogger(ReturnWorkflowCoordinator.class);
    private static final String DEFAULT_REASON_CODE = "customer_request";

    @Inject
    ReturnInventoryTaskQueue inventoryTaskQueue;

    @Inject
    ReturnNotificationTaskQueue notificationTaskQueue;

    @Inject
    ConsignorRepository consignorRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Enqueue workflow tasks for the supplied return items.
     *
     * @param order
     *            owning order
     * @param lineItems
     *            persisted line items for validation/context
     * @param requestedItems
     *            items requested for return/restock
     * @param returnAuthorizationId
     *            optional return authorization ID (null when restocking via refund flow)
     * @param trigger
     *            trigger identifier (e.g., {@code return_initiated}, {@code refund_restock})
     */
    public void enqueueTasks(Order order, List<OrderLineItem> lineItems, List<ReturnWorkflowItem> requestedItems,
            Long returnAuthorizationId, String trigger) {

        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("At least one line item must be specified for return");
        }
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(lineItems, "lineItems");

        Map<UUID, OrderLineItem> lineItemMap = lineItems.stream().collect(Collectors.toMap(oli -> oli.id, oli -> oli));

        UUID tenantId = order.tenant.id;
        List<ReturnWorkflowItem> normalizedItems = requestedItems.stream()
                .map(item -> item.withDefaults(DEFAULT_REASON_CODE)).toList();

        List<ReturnInventoryTask> inventoryTasks = new ArrayList<>();
        List<ReturnNotificationTask> notificationTasks = new ArrayList<>();

        for (ReturnWorkflowItem item : normalizedItems) {
            OrderLineItem lineItem = lineItemMap.get(item.lineItemId());
            if (lineItem == null) {
                throw new IllegalArgumentException("Line item not found in order: " + item.lineItemId());
            }
            if (item.quantity() > lineItem.quantity) {
                throw new IllegalArgumentException(
                        "Return quantity exceeds ordered quantity for line item " + lineItem.id);
            }

            inventoryTasks.add(new ReturnInventoryTask(UUID.randomUUID(), tenantId, returnAuthorizationId, order.id,
                    lineItem.id, lineItem.variantId, item.quantity(), item.reasonCode(), lineItem.vendorId, trigger,
                    OffsetDateTime.now()));

            if (lineItem.vendorId != null) {
                consignorRepository.findByIdAndTenant(lineItem.vendorId).ifPresent(consignor -> notificationTasks
                        .add(buildNotificationTask(order, returnAuthorizationId, consignor, lineItem, item, trigger)));
            }
        }

        inventoryTasks.forEach(task -> {
            inventoryTaskQueue.enqueue(task);
            meterRegistry.counter("returns.inventory.tasks.enqueued", "tenant", tenantId.toString(), "trigger", trigger)
                    .increment();
            LOG.debugf("Queued inventory task - tenantId=%s, orderId=%s, lineItemId=%s", tenantId, task.getOrderId(),
                    task.getLineItemId());
        });

        notificationTasks.forEach(task -> {
            notificationTaskQueue.enqueue(task);
            meterRegistry.counter("returns.notifications.enqueued", "tenant", tenantId.toString(), "trigger", trigger)
                    .increment();
            LOG.debugf("Queued return notification - tenantId=%s, consignorId=%s, product=%s", tenantId,
                    task.getConsignorId(), task.getProductName());
        });
    }

    private ReturnNotificationTask buildNotificationTask(Order order, Long returnAuthorizationId, Consignor consignor,
            OrderLineItem lineItem, ReturnWorkflowItem item, String trigger) {

        String email = extractEmail(consignor);
        return new ReturnNotificationTask(UUID.randomUUID(), order.tenant.id, returnAuthorizationId, consignor.id,
                consignor.name, email, order.id, lineItem.productName, item.quantity(), item.reasonCode(), trigger,
                OffsetDateTime.now());
    }

    private String extractEmail(Consignor consignor) {
        if (consignor.contactInfo == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(consignor.contactInfo);
            if (node != null && node.hasNonNull("email")) {
                return node.get("email").asText();
            }
        } catch (Exception e) {
            LOG.debugf(e, "Failed to parse consignor contact info for consignorId=%s", consignor.id);
        }
        return null;
    }
}
