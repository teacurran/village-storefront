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
 * Custom domain mapping for tenant stores. Allows tenants to use custom domains (e.g., shop.example.com) instead of
 * subdomain.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 1: Tenant Data Model)</li>
 * <li>Migration: V20260102__baseline_schema.sql (custom_domains table)</li>
 * <li>ERD: datamodel_erd.puml</li>
 * </ul>
 */
@Entity
@Table(
        name = "custom_domains")
public class CustomDomain extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @Column(
            unique = true,
            nullable = false,
            length = 253)
    public String domain;

    @Column(
            nullable = false)
    public Boolean verified = false;

    @Column(
            name = "verification_token",
            length = 64)
    public String verificationToken;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;
}
