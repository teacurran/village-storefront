package villagecompute.storefront.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void getCurrentTenantIdThrowsWhenContextMissing() {
        assertFalse(TenantContext.hasContext());
        assertThrows(IllegalStateException.class, TenantContext::getCurrentTenantId);
        assertThrows(IllegalStateException.class, TenantContext::getCurrentTenant);
    }

    @Test
    void setCurrentTenantExposesValuesAndCanBeCleared() {
        UUID tenantId = UUID.randomUUID();
        TenantInfo info = new TenantInfo(tenantId, "unit-test", "Unit Test Store", "active");

        TenantContext.setCurrentTenant(info);

        assertTrue(TenantContext.hasContext());
        assertEquals(tenantId, TenantContext.getCurrentTenantId());
        assertEquals(info, TenantContext.getCurrentTenant());

        TenantContext.clear();
        assertFalse(TenantContext.hasContext());
    }
}
