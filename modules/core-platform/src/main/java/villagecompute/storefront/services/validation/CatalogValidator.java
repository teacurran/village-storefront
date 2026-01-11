package villagecompute.storefront.services.validation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.repositories.CategoryRepository;
import villagecompute.storefront.data.repositories.CollectionRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.services.ValidationException;

/**
 * Validator for catalog entities (Product, Category, Collection).
 *
 * <p>
 * Enforces business rules including:
 * <ul>
 * <li>Status transition rules (draft → active → archived, etc.)</li>
 * <li>Slug uniqueness within tenant scope</li>
 * <li>Required field validation</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T1: Validation requirements</li>
 * <li>ADR-001: Tenant-scoped validation</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogValidator {

    private static final Set<String> VALID_STATUSES = Set.of("draft", "scheduled", "active", "archived", "deleted");
    private static final Set<String> VALID_PRODUCT_TYPES = Set.of("physical", "digital", "service");
    private static final Set<String> VALID_COLLECTION_TYPES = Set.of("manual", "automatic");
    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of("draft",
            Set.of("draft", "scheduled", "active", "archived"), "scheduled",
            Set.of("scheduled", "draft", "active", "archived"), "active", Set.of("active", "archived"), "archived",
            Set.of("archived", "active", "deleted"), "deleted", Set.of("deleted"));

    @Inject
    ProductRepository productRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    CollectionRepository collectionRepository;

    /**
     * Validate status transition for any catalog entity.
     *
     * @param currentStatus
     *            current entity status
     * @param newStatus
     *            requested new status
     * @throws ValidationException
     *             if transition is invalid
     */
    public void validateStatusTransition(String currentStatus, String newStatus) {
        if (!VALID_STATUSES.contains(newStatus)) {
            throw new ValidationException("Invalid status: " + newStatus + ". Must be one of: " + VALID_STATUSES);
        }

        // Business rules for status transitions
        if ("deleted".equals(currentStatus)) {
            throw new ValidationException("Cannot change status of deleted entity");
        }

        if ("deleted".equals(newStatus) && !"archived".equals(currentStatus)) {
            throw new ValidationException("Can only delete archived entities. Archive first, then delete.");
        }

        if ("active".equals(currentStatus) && "draft".equals(newStatus)) {
            throw new ValidationException("Cannot revert active entity to draft. Use archived status instead.");
        }

        Set<String> allowed = VALID_TRANSITIONS.getOrDefault(currentStatus, VALID_STATUSES);
        if (!allowed.contains(newStatus)) {
            throw new ValidationException(
                    "Cannot transition from " + currentStatus + " to " + newStatus + " for catalog entities.");
        }
    }

    /**
     * Validate product slug uniqueness within current tenant.
     *
     * @param slug
     *            product slug
     * @param excludeId
     *            product ID to exclude from uniqueness check (for updates)
     * @throws ValidationException
     *             if slug is not unique
     */
    public void validateProductSlugUniqueness(String slug, UUID excludeId) {
        if (slug == null || slug.isBlank()) {
            return; // Slug is optional
        }

        Optional<Product> existing = productRepository.findBySlug(slug);
        if (existing.isPresent() && !existing.get().id.equals(excludeId)) {
            throw new ValidationException("Product slug '" + slug + "' is already in use");
        }
    }

    /**
     * Validate category slug uniqueness within current tenant.
     *
     * @param slug
     *            category slug
     * @param excludeId
     *            category ID to exclude from uniqueness check (for updates)
     * @throws ValidationException
     *             if slug is not unique
     */
    public void validateCategorySlugUniqueness(String slug, UUID excludeId) {
        if (slug == null || slug.isBlank()) {
            return; // Slug is optional
        }

        Optional<Category> existing = categoryRepository.findBySlug(slug);
        if (existing.isPresent() && !existing.get().id.equals(excludeId)) {
            throw new ValidationException("Category slug '" + slug + "' is already in use");
        }
    }

    /**
     * Validate collection slug uniqueness within current tenant.
     *
     * @param slug
     *            collection slug
     * @param excludeId
     *            collection ID to exclude from uniqueness check (for updates)
     * @throws ValidationException
     *             if slug is not unique
     */
    public void validateCollectionSlugUniqueness(String slug, UUID excludeId) {
        if (slug == null || slug.isBlank()) {
            return; // Slug is optional
        }

        Optional<Collection> existing = collectionRepository.findBySlug(slug);
        if (existing.isPresent() && !existing.get().id.equals(excludeId)) {
            throw new ValidationException("Collection slug '" + slug + "' is already in use");
        }
    }

    /**
     * Validate product type.
     *
     * @param type
     *            product type
     * @throws ValidationException
     *             if type is invalid
     */
    public void validateProductType(String type) {
        if (!VALID_PRODUCT_TYPES.contains(type)) {
            throw new ValidationException("Invalid product type: " + type + ". Must be one of: " + VALID_PRODUCT_TYPES);
        }
    }

    /**
     * Validate collection type.
     *
     * @param collectionType
     *            collection type
     * @throws ValidationException
     *             if type is invalid
     */
    public void validateCollectionType(String collectionType) {
        if (!VALID_COLLECTION_TYPES.contains(collectionType)) {
            throw new ValidationException(
                    "Invalid collection type: " + collectionType + ". Must be one of: " + VALID_COLLECTION_TYPES);
        }
    }

    /**
     * Validate slug format (lowercase, alphanumeric, hyphens only).
     *
     * @param slug
     *            slug to validate
     * @throws ValidationException
     *             if slug format is invalid
     */
    public void validateSlugFormat(String slug) {
        if (slug == null || slug.isBlank()) {
            return; // Slug is optional
        }

        if (!slug.matches("^[a-z0-9-]+$")) {
            throw new ValidationException("Slug must contain only lowercase letters, numbers, and hyphens");
        }

        if (slug.startsWith("-") || slug.endsWith("-")) {
            throw new ValidationException("Slug cannot start or end with a hyphen");
        }

        if (slug.contains("--")) {
            throw new ValidationException("Slug cannot contain consecutive hyphens");
        }
    }
}
