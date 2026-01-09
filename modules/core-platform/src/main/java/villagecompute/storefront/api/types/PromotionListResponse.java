package villagecompute.storefront.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Paginated list response for promotions.
 *
 * <p>
 * Used by {@code GET /admin/promotions} endpoint to return paginated promotion listings.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Promotion API DTOs</li>
 * <li>OpenAPI: PromotionListResponse schema</li>
 * </ul>
 */
public class PromotionListResponse {

    @JsonProperty("items")
    private List<PromotionDto> items;

    @JsonProperty("pagination")
    private PaginationMetadata pagination;

    public PromotionListResponse() {
    }

    public PromotionListResponse(List<PromotionDto> items, PaginationMetadata pagination) {
        this.items = items;
        this.pagination = pagination;
    }

    public List<PromotionDto> getItems() {
        return items;
    }

    public void setItems(List<PromotionDto> items) {
        this.items = items;
    }

    public PaginationMetadata getPagination() {
        return pagination;
    }

    public void setPagination(PaginationMetadata pagination) {
        this.pagination = pagination;
    }
}
