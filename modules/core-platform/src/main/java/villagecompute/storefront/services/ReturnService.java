package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.models.ReturnAuthorization;
import villagecompute.storefront.services.returns.ReturnWorkflowCoordinator;
import villagecompute.storefront.services.returns.ReturnWorkflowItem;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Return authorization service. Manages product return requests, restock decisions, and refund coordination.
 *
 * <p>
 * Provides return authorization workflow, inventory restocking integration, and domain event emission for audit trails.
 * All operations are tenant-scoped with optimistic locking support.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T9: Refund and return implementation</li>
 * <li>ADR-003: Compensating transactions and inventory management</li>
 * <li>Architecture: Order lifecycle state machine</li>
 * </ul>
 */
@ApplicationScoped
public class ReturnService {

    private static final Logger LOG = Logger.getLogger(ReturnService.class);
    private static final String DEFAULT_LOCATION = "default";
    private static final String DEFAULT_REASON_CODE = "customer_request";

    @Inject
    InventoryService inventoryService;

    @Inject
    RefundService refundService;

    @Inject
    OrderService orderService;

    @Inject
    DomainEventPublisher eventPublisher;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ReturnWorkflowCoordinator returnWorkflowCoordinator;

    /**
     * Initiate a return authorization for an order.
     *
     * @param orderId
     *            Order UUID
     * @param returnItems
     *            List of items to return with reasons
     * @param requestedBy
     *            User ID requesting return
     * @return Created return authorization
     */
    @Transactional
    public ReturnAuthorization initiateReturn(UUID orderId, List<ReturnWorkflowItem> returnItems, UUID requestedBy) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Initiating return - tenantId=%s, orderId=%s, requestedBy=%s", tenantId, orderId, requestedBy);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 1. Validate order ownership
            Order order = Order.findById(orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                throw new EntityNotFoundException("Order not found: " + orderId);
            }

            // 2. Validate order is eligible for return
            if (order.status == Order.OrderStatus.CANCELLED || order.status == Order.OrderStatus.REFUNDED) {
                throw new IllegalStateException("Cannot return cancelled or already refunded order: " + orderId);
            }

            if (returnItems == null || returnItems.isEmpty()) {
                throw new IllegalArgumentException("At least one return item is required");
            }

            // 3. Load order line items
            List<OrderLineItem> lineItems = orderService.getOrderLineItems(orderId);
            Map<UUID, OrderLineItem> lineItemMap = new HashMap<>();
            for (OrderLineItem item : lineItems) {
                lineItemMap.put(item.id, item);
            }

            // 4. Validate return items against order
            for (ReturnWorkflowItem returnItem : returnItems) {
                if (!lineItemMap.containsKey(returnItem.lineItemId())) {
                    throw new IllegalArgumentException("Line item not found in order: " + returnItem.lineItemId());
                }
                OrderLineItem orderItem = lineItemMap.get(returnItem.lineItemId());
                if (returnItem.quantity() > orderItem.quantity) {
                    throw new IllegalArgumentException(
                            "Return quantity exceeds ordered quantity for line item " + returnItem.lineItemId());
                }
            }

            List<ReturnWorkflowItem> normalizedItems = returnItems.stream()
                    .map(item -> item.withDefaults(DEFAULT_REASON_CODE)).toList();

            // 5. Create return authorization
            ReturnAuthorization authorization = new ReturnAuthorization();
            authorization.order = order;
            authorization.status = ReturnAuthorization.ReturnStatus.REQUESTED;
            authorization.restockDecision = ReturnAuthorization.RestockDecision.PENDING_INSPECTION;

            // Serialize return items to JSON
            authorization.items = serializeReturnItems(normalizedItems);

            // Extract unique reason codes
            List<String> reasonCodes = normalizedItems.stream().map(ReturnWorkflowItem::reasonCode).distinct().toList();
            authorization.reasonCodes = serializeReasonCodes(reasonCodes);

            authorization.persist();

            LOG.infof("Return authorization created - tenantId=%s, orderId=%s, returnId=%d", tenantId, orderId,
                    authorization.id);

            // 6. Queue inventory + notification tasks
            returnWorkflowCoordinator.enqueueTasks(order, lineItems, normalizedItems, authorization.id,
                    "return_initiated");

            // 7. Emit RETURN_INITIATED event
            emitReturnEvent(tenantId, order.id, authorization.id, "RETURN_INITIATED", returnItems.size(),
                    buildReturnEventItems(normalizedItems, lineItemMap));

            meterRegistry.counter("return.service.return.initiated", "tenant", tenantId.toString()).increment();

            return authorization;

        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException e) {
            LOG.warnf(e, "Return initiation validation failed - tenantId=%s, orderId=%s", tenantId, orderId);
            meterRegistry.counter("return.service.return.validation_failed", "tenant", tenantId.toString()).increment();
            throw e;

        } catch (Exception e) {
            LOG.errorf(e, "Return initiation failed - tenantId=%s, orderId=%s", tenantId, orderId);
            meterRegistry.counter("return.service.return.failed", "tenant", tenantId.toString()).increment();
            throw new RuntimeException("Return initiation failed: " + e.getMessage(), e);

        } finally {
            sample.stop(meterRegistry.timer("return.service.return.initiate.duration", "tenant", tenantId.toString()));
        }
    }

    /**
     * Approve a return authorization and optionally trigger refund.
     *
     * @param returnAuthorizationId
     *            Return authorization ID
     * @param approvedBy
     *            User ID approving return
     * @param restockDecision
     *            Restock decision
     * @param triggerRefund
     *            Whether to trigger refund
     * @param refundAmount
     *            Refund amount (null = full refund)
     * @param idempotencyKey
     *            Idempotency key for refund
     * @return Updated return authorization
     */
    @Transactional
    public ReturnAuthorization approveReturn(Long returnAuthorizationId, UUID approvedBy,
            ReturnAuthorization.RestockDecision restockDecision, boolean triggerRefund, BigDecimal refundAmount,
            String idempotencyKey) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Approving return - tenantId=%s, returnId=%d, approvedBy=%s, restock=%s, triggerRefund=%b", tenantId,
                returnAuthorizationId, approvedBy, restockDecision, triggerRefund);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 1. Load return authorization
            ReturnAuthorization authorization = ReturnAuthorization.findByIdAndTenant(tenantId, returnAuthorizationId);
            if (authorization == null) {
                throw new IllegalArgumentException("Return authorization not found: " + returnAuthorizationId);
            }

            // 2. Validate state
            if (authorization.status != ReturnAuthorization.ReturnStatus.REQUESTED) {
                throw new IllegalStateException("Return authorization not in REQUESTED state: " + authorization.status);
            }

            // 3. Update authorization
            authorization.status = ReturnAuthorization.ReturnStatus.APPROVED;
            authorization.approvedBy = approvedBy;
            authorization.restockDecision = restockDecision;
            authorization.persist();

            LOG.infof("Return authorization approved - tenantId=%s, returnId=%d", tenantId, returnAuthorizationId);

            // 4. Emit RETURN_APPROVED event
            emitReturnEvent(tenantId, authorization.order.id, authorization.id, "RETURN_APPROVED", 0,
                    Collections.emptyList());

            // 5. Trigger refund if requested
            if (triggerRefund) {
                if (idempotencyKey == null || idempotencyKey.isBlank()) {
                    throw new IllegalArgumentException("Idempotency key required for refund");
                }

                RefundService.RefundResult refundResult = refundService.initiateRefund(authorization.order.id,
                        refundAmount, "Product return", idempotencyKey, false);

                authorization.refundId = refundResult.refundId();
                authorization.persist();

                LOG.infof("Refund triggered for return - tenantId=%s, returnId=%d, refundId=%d", tenantId,
                        returnAuthorizationId, refundResult.refundId());
            }

            meterRegistry.counter("return.service.return.approved", "tenant", tenantId.toString()).increment();

            return authorization;

        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf(e, "Return approval validation failed - tenantId=%s, returnId=%d", tenantId,
                    returnAuthorizationId);
            meterRegistry.counter("return.service.return.approval_validation_failed", "tenant", tenantId.toString())
                    .increment();
            throw e;

        } catch (Exception e) {
            LOG.errorf(e, "Return approval failed - tenantId=%s, returnId=%d", tenantId, returnAuthorizationId);
            meterRegistry.counter("return.service.return.approval_failed", "tenant", tenantId.toString()).increment();
            throw new RuntimeException("Return approval failed: " + e.getMessage(), e);

        } finally {
            sample.stop(meterRegistry.timer("return.service.return.approve.duration", "tenant", tenantId.toString()));
        }
    }

    /**
     * Mark return items as received and restock inventory if applicable.
     *
     * @param returnAuthorizationId
     *            Return authorization ID
     * @return Updated return authorization
     */
    @Transactional
    public ReturnAuthorization receiveReturn(Long returnAuthorizationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Receiving return - tenantId=%s, returnId=%d", tenantId, returnAuthorizationId);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // 1. Load return authorization
            ReturnAuthorization authorization = ReturnAuthorization.findByIdAndTenant(tenantId, returnAuthorizationId);
            if (authorization == null) {
                throw new IllegalArgumentException("Return authorization not found: " + returnAuthorizationId);
            }

            // 2. Validate state
            if (authorization.status != ReturnAuthorization.ReturnStatus.APPROVED) {
                throw new IllegalStateException("Return authorization not approved: " + authorization.status);
            }

            // 3. Mark as received
            authorization.status = ReturnAuthorization.ReturnStatus.RECEIVED;
            authorization.receivedAt = Instant.now();

            // 4. Restock inventory if decision is RESTOCK
            if (authorization.restockDecision == ReturnAuthorization.RestockDecision.RESTOCK) {
                restockReturnItems(authorization);
                authorization.status = ReturnAuthorization.ReturnStatus.RESTOCKED;
            }

            authorization.persist();

            LOG.infof("Return received and processed - tenantId=%s, returnId=%d, status=%s", tenantId,
                    returnAuthorizationId, authorization.status);

            // 5. Emit RETURN_RECEIVED event
            emitReturnEvent(tenantId, authorization.order.id, authorization.id, "RETURN_RECEIVED", 0,
                    Collections.emptyList());

            meterRegistry.counter("return.service.return.received", "tenant", tenantId.toString()).increment();

            return authorization;

        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf(e, "Return receive validation failed - tenantId=%s, returnId=%d", tenantId,
                    returnAuthorizationId);
            meterRegistry.counter("return.service.return.receive_validation_failed", "tenant", tenantId.toString())
                    .increment();
            throw e;

        } catch (Exception e) {
            LOG.errorf(e, "Return receive failed - tenantId=%s, returnId=%d", tenantId, returnAuthorizationId);
            meterRegistry.counter("return.service.return.receive_failed", "tenant", tenantId.toString()).increment();
            throw new RuntimeException("Return receive failed: " + e.getMessage(), e);

        } finally {
            sample.stop(meterRegistry.timer("return.service.return.receive.duration", "tenant", tenantId.toString()));
        }
    }

    private void restockReturnItems(ReturnAuthorization authorization) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Restocking return items - tenantId=%s, returnId=%d", tenantId, authorization.id);

        try {
            List<ReturnWorkflowItem> returnItems = deserializeReturnItems(authorization.items);
            List<OrderLineItem> lineItems = orderService.getOrderLineItems(authorization.order.id);

            Map<UUID, OrderLineItem> lineItemMap = new HashMap<>();
            for (OrderLineItem item : lineItems) {
                lineItemMap.put(item.id, item);
            }

            for (ReturnWorkflowItem returnItem : returnItems) {
                OrderLineItem orderItem = lineItemMap.get(returnItem.lineItemId());
                if (orderItem == null) {
                    LOG.warnf("Line item not found for restock - returnId=%d, lineItemId=%d", authorization.id,
                            returnItem.lineItemId());
                    continue;
                }

                if (orderItem.variantId != null) {
                    try {
                        inventoryService.adjustInventory(orderItem.variantId, DEFAULT_LOCATION, returnItem.quantity());
                        LOG.infof("Restocked inventory - returnId=%d, variantId=%s, quantity=%d", authorization.id,
                                orderItem.variantId, returnItem.quantity());
                    } catch (Exception e) {
                        LOG.errorf(e, "Failed to restock inventory - returnId=%d, variantId=%s, quantity=%d",
                                authorization.id, orderItem.variantId, returnItem.quantity());
                        // Continue with other items even if one fails
                    }
                }
            }

            meterRegistry.counter("return.service.restock.completed", "tenant", tenantId.toString()).increment();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to restock return items - tenantId=%s, returnId=%d", tenantId, authorization.id);
            throw new RuntimeException("Restock failed: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> buildReturnEventItems(List<ReturnWorkflowItem> returnItems,
            Map<UUID, OrderLineItem> lineItemMap) {
        List<Map<String, Object>> payloadItems = new ArrayList<>();
        for (ReturnWorkflowItem item : returnItems) {
            OrderLineItem lineItem = lineItemMap.get(item.lineItemId());
            if (lineItem == null) {
                continue;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("lineItemId", lineItem.id.toString());
            payload.put("variantId", lineItem.variantId.toString());
            payload.put("quantity", item.quantity());
            payload.put("reasonCode", item.reasonCode());
            if (lineItem.vendorId != null) {
                payload.put("consignorId", lineItem.vendorId.toString());
            }
            payloadItems.add(payload);
        }
        return payloadItems;
    }

    private void emitReturnEvent(UUID tenantId, UUID orderId, Long returnId, String eventType, int itemCount,
            List<Map<String, Object>> items) {
        try {
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("orderId", orderId.toString());
            eventPayload.put("returnId", returnId);
            eventPayload.put("itemCount", itemCount);
            eventPayload.put("items", items);
            eventPayload.put("userId", "system"); // TODO: Add user context when auth is implemented

            eventPublisher.publish("ORDER", orderId, eventType, objectMapper.writeValueAsString(eventPayload));

            LOG.infof("Emitted event - tenantId=%s, eventType=%s, orderId=%s, returnId=%d", tenantId, eventType,
                    orderId, returnId);

        } catch (Exception e) {
            LOG.warnf(e, "Failed to emit return event - tenantId=%s, eventType=%s, orderId=%s", tenantId, eventType,
                    orderId);
        }
    }

    private String serializeReturnItems(List<ReturnWorkflowItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialize return items");
            return "[]";
        }
    }

    private String serializeReasonCodes(List<String> reasonCodes) {
        try {
            return objectMapper.writeValueAsString(reasonCodes);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialize reason codes");
            return "[]";
        }
    }

    private List<ReturnWorkflowItem> deserializeReturnItems(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ReturnWorkflowItem.class));
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to deserialize return items");
            throw new IllegalStateException("Failed to deserialize return items", e);
        }
    }
}
