package villagecompute.storefront.pos.offline;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.data.models.Tenant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Encrypted POS device keys stored per version.
 *
 * <p>
 * Each pairing cycle generates a new symmetric key that is encrypted with the server-side master key and stored in this
 * table. Offline sync jobs retrieve the appropriate version to decrypt queued payloads without exposing raw keys.
 */
@Entity
@Table(
        name = "pos_device_keys",
        uniqueConstraints = {@UniqueConstraint(
                name = "uq_pos_device_keys",
                columnNames = {"device_id", "key_version"})})
public class POSDeviceKey extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "device_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public POSDevice device;

    @Column(
            name = "key_version",
            nullable = false)
    public Integer keyVersion;

    @Column(
            name = "key_ciphertext",
            nullable = false)
    public byte[] keyCiphertext;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Lookup helper for retrieving an encrypted key blob.
     */
    public static POSDeviceKey findByDeviceAndVersion(Long deviceId, Integer keyVersion) {
        return find("device.id = ?1 AND keyVersion = ?2", deviceId, keyVersion).firstResult();
    }
}
