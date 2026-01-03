package villagecompute.storefront.api.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PaginationMetadata DTO for paginated API responses.
 *
 * <p>
 * Provides pagination information to clients for constructing next/previous page requests.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: PaginationMetadata component schema</li>
 * </ul>
 */
public class PaginationMetadata {

    @JsonProperty("page")
    private Integer page;

    @JsonProperty("pageSize")
    private Integer pageSize;

    @JsonProperty("totalItems")
    private Long totalItems;

    @JsonProperty("totalPages")
    private Integer totalPages;

    public PaginationMetadata() {
    }

    public PaginationMetadata(Integer page, Integer pageSize, Long totalItems) {
        this.page = page;
        this.pageSize = pageSize;
        this.totalItems = totalItems;
        this.totalPages = (int) Math.ceil((double) totalItems / pageSize);
    }

    // Getters and setters

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Long getTotalItems() {
        return totalItems;
    }

    public void setTotalItems(Long totalItems) {
        this.totalItems = totalItems;
    }

    public Integer getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(Integer totalPages) {
        this.totalPages = totalPages;
    }
}
