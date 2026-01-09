package villagecompute.storefront.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Paginated list response for carts.
 *
 * <p>
 * Used by {@code GET /admin/carts} endpoint to return paginated cart listings with metadata.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Cart admin API DTOs</li>
 * <li>OpenAPI: CartListResponse schema</li>
 * </ul>
 */
public class CartListResponse {

    @JsonProperty("items")
    private List<CartDto> items;

    @JsonProperty("pagination")
    private PaginationMetadata pagination;

    public CartListResponse() {
    }

    public CartListResponse(List<CartDto> items, PaginationMetadata pagination) {
        this.items = items;
        this.pagination = pagination;
    }

    public List<CartDto> getItems() {
        return items;
    }

    public void setItems(List<CartDto> items) {
        this.items = items;
    }

    public PaginationMetadata getPagination() {
        return pagination;
    }

    public void setPagination(PaginationMetadata pagination) {
        this.pagination = pagination;
    }
}
