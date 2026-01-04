package villagecompute.storefront.api.types;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Paginated response containing media assets.
 */
public class MediaAssetListResponse {

    @JsonProperty("items")
    private List<MediaAssetResponse> items = new ArrayList<>();

    @JsonProperty("total")
    private long total;

    @JsonProperty("page")
    private int page;

    @JsonProperty("size")
    private int size;

    public MediaAssetListResponse() {
    }

    public MediaAssetListResponse(List<MediaAssetResponse> items, long total, int page, int size) {
        this.items = items != null ? items : new ArrayList<>();
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<MediaAssetResponse> getItems() {
        return items;
    }

    public void setItems(List<MediaAssetResponse> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
