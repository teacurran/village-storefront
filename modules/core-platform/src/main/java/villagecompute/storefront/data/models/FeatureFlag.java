package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Feature flag entity for configuration-driven feature toggles. Supports both global (tenant_id = null) and
 * tenant-specific flags.
 *
 * <p>
 * References:
 * <ul>
 * <li>Architecture Overview Section 1: Feature Flag Strategy</li>
 * <li>Migration: V20260102__baseline_schema.sql (feature_flags table)</li>
 * <li>ERD: datamodel_erd.puml</li>
 * </ul>
 */
@Entity
@Table(
        name = "feature_flags")
public class FeatureFlag extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne
    @JoinColumn(
            name = "tenant_id")
    public Tenant tenant; // Nullable for global flags

    @Column(
            name = "flag_key",
            nullable = false,
            length = 100)
    public String flagKey;

    @Column(
            nullable = false)
    public Boolean enabled = false;

    @Column(
            length = 10000)
    public String config = "{}";

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @Column(
            nullable = false,
            length = 255)
    public String owner;

    @Column(
            name = "risk_level",
            nullable = false,
            length = 20)
    public String riskLevel = "LOW";

    @Column(
            name = "review_cadence_days")
    public Integer reviewCadenceDays = 90;

    @Column(
            name = "expiry_date")
    public OffsetDateTime expiryDate;

    @Column(
            name = "last_reviewed_at")
    public OffsetDateTime lastReviewedAt;

    @Column(
            columnDefinition = "TEXT")
    public String description;

    @Column(
            name = "rollback_instructions",
            columnDefinition = "TEXT")
    public String rollbackInstructions;
}
