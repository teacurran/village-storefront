package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Tenant entity representing a merchant store. Each tenant is a separate merchant with isolated data.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 1: Tenant Data Model)</li>
 * <li>Migration: V20260102__baseline_schema.sql (tenants table)</li>
 * <li>ERD: datamodel_erd.puml</li>
 * </ul>
 */
@Entity
@Table(
        name = "tenants")
public class Tenant extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            unique = true,
            nullable = false,
            length = 63)
    public String subdomain;

    @Column(
            nullable = false)
    public String name;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active";

    @Column(
            length = 10000)
    public String settings = "{}";

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;
}
