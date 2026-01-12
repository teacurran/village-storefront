package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

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
 * <li>Migration: V20260132__custom_domain_ssl_automation.sql (SSL automation fields)</li>
 * <li>Task I5.T4: Custom Domain SSL Automation</li>
 * <li>ERD: datamodel_erd.puml</li>
 * </ul>
 */
@Entity
@Table(
        name = "custom_domains")
public class CustomDomain extends PanacheEntityBase {

    /** Domain verification lifecycle states */
    public enum DomainStatus {
        /** Awaiting DNS verification */
        PENDING,
        /** Verified and certificate issued */
        ACTIVE,
        /** Verification failed - requires user action */
        FAILED
    }

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

    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false)
    public DomainStatus status = DomainStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "dns_challenge",
            columnDefinition = "jsonb")
    public JsonNode dnsChallenge;

    @Column(
            name = "last_verification_attempt")
    public OffsetDateTime lastVerificationAttempt;

    @Column(
            name = "verification_retry_count",
            nullable = false)
    public Integer verificationRetryCount = 0;

    @Column(
            name = "verification_error_message")
    public String verificationErrorMessage;

    @Column(
            name = "certificate_expiry")
    public OffsetDateTime certificateExpiry;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;
}
