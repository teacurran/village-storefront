package villagecompute.storefront.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Paginated list response for orders.
 *
 * <p>
 * Used by {@code GET /orders} and {@code GET /admin/orders} endpoints to return paginated order summaries.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order API DTOs</li>
 * <li>OpenAPI: OrderListResponse schema</li>
 * </ul>
 */
public class OrderListResponse {

    @JsonProperty("items")
    private List<OrderSummary> items;

    @JsonProperty("pagination")
    private PaginationMetadata pagination;

    public OrderListResponse() {
    }

    public OrderListResponse(List<OrderSummary> items, PaginationMetadata pagination) {
        this.items = items;
        this.pagination = pagination;
    }

    public List<OrderSummary> getItems() {
        return items;
    }

    public void setItems(List<OrderSummary> items) {
        this.items = items;
    }

    public PaginationMetadata getPagination() {
        return pagination;
    }

    public void setPagination(PaginationMetadata pagination) {
        this.pagination = pagination;
    }
}
