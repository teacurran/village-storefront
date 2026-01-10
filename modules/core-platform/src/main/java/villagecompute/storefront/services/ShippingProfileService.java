package villagecompute.storefront.services;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.ShippingProfile;
import villagecompute.storefront.data.models.Tenant;

/**
 * Service for managing {@link ShippingProfile} entities per tenant.
 *
 * <p>
 * Provides helper methods for CRUD operations, enforcing tenant scoping and ensuring only one default profile exists
 * per tenant.
 * </p>
 */
@ApplicationScoped
public class ShippingProfileService {

    private static final Logger LOG = Logger.getLogger(ShippingProfileService.class);

    /**
     * List shipping profiles for a tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @return profiles ordered by name
     */
    public List<ShippingProfile> listProfiles(UUID tenantId) {
        return ShippingProfile.list("tenant.id = ?1 ORDER BY name", tenantId);
    }

    /**
     * Find shipping profile for tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param profileId
     *            profile id
     * @return optional profile
     */
    public Optional<ShippingProfile> findProfile(UUID tenantId, UUID profileId) {
        return ShippingProfile.find("tenant.id = ?1 AND id = ?2", tenantId, profileId).firstResultOptional();
    }

    /**
     * Find default profile for tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @return optional default profile
     */
    public Optional<ShippingProfile> findDefaultProfile(UUID tenantId) {
        return ShippingProfile.find("tenant.id = ?1 AND isDefault = true", tenantId).firstResultOptional();
    }

    /**
     * Create profile for tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param profile
     *            profile payload
     * @param makeDefault
     *            flag to mark as default after creation
     * @return persisted profile
     */
    @Transactional
    public ShippingProfile createProfile(UUID tenantId, ShippingProfile profile, boolean makeDefault) {
        Tenant tenant = Tenant.findById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant not found for shipping profile creation");
        }
        profile.tenant = tenant;
        profile.persist();

        if (makeDefault) {
            markDefaultInternal(profile);
        }

        LOG.infof("Created shipping profile %s for tenant %s (default=%s)", profile.id, tenantId, makeDefault);
        return profile;
    }

    /**
     * Update profile for tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param profileId
     *            profile identifier
     * @param updates
     *            updated payload
     * @param makeDefault
     *            mark profile as default
     * @return updated profile
     */
    @Transactional
    public ShippingProfile updateProfile(UUID tenantId, UUID profileId, ShippingProfile updates, boolean makeDefault) {
        ShippingProfile existing = findProfile(tenantId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("Shipping profile not found: " + profileId));

        existing.name = updates.name;
        existing.enabledCarriers = updates.enabledCarriers;
        existing.originAddress = updates.originAddress;
        existing.carrierCredentials = updates.carrierCredentials;
        existing.metadata = updates.metadata;
        existing.active = updates.active;
        existing.persist();

        if (makeDefault) {
            markDefaultInternal(existing);
        } else {
            existing.isDefault = updates.isDefault;
        }

        LOG.infof("Updated shipping profile %s for tenant %s", profileId, tenantId);
        return existing;
    }

    /**
     * Delete profile for tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param profileId
     *            profile identifier
     */
    @Transactional
    public void deleteProfile(UUID tenantId, UUID profileId) {
        ShippingProfile profile = findProfile(tenantId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("Shipping profile not found: " + profileId));
        profile.delete();
        LOG.infof("Deleted shipping profile %s for tenant %s", profileId, tenantId);
    }

    /**
     * Mark specific profile as default for tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param profileId
     *            profile identifier
     * @return updated profile
     */
    @Transactional
    public ShippingProfile markDefault(UUID tenantId, UUID profileId) {
        ShippingProfile profile = findProfile(tenantId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("Shipping profile not found: " + profileId));
        markDefaultInternal(profile);
        return profile;
    }

    private void markDefaultInternal(ShippingProfile profile) {
        ShippingProfile.update("isDefault = false WHERE tenant.id = ?1 AND id <> ?2", profile.tenant.id, profile.id);
        profile.isDefault = true;
        profile.persist();
    }
}
