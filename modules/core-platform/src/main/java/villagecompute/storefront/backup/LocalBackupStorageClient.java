package villagecompute.storefront.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Local filesystem implementation of {@link BackupStorageClient} intended for development and test profiles.
 *
 * <p>
 * This implementation keeps backup artifacts on disk under {@code backup.storage.local-root} (defaults to the system
 * temp directory) so CDI injections are satisfied without requiring Cloudflare R2 credentials. Production deployments
 * should enable {@link R2BackupStorageClient} via {@code quarkus.arc.selected-alternatives}.
 */
@ApplicationScoped
public class LocalBackupStorageClient implements BackupStorageClient {

    private static final Logger LOG = Logger.getLogger(LocalBackupStorageClient.class);

    private static final String DEFAULT_ROOT = System.getProperty("java.io.tmpdir")
            + "/village-storefront/local-backups";

    private final Path storageRoot;

    public LocalBackupStorageClient(@ConfigProperty(
            name = "backup.storage.local-root",
            defaultValue = "") String configuredRoot) {
        String rootToUse = configuredRoot == null || configuredRoot.isBlank() ? DEFAULT_ROOT : configuredRoot;
        this.storageRoot = Path.of(Objects.requireNonNull(rootToUse, "backup.storage.local-root")).normalize();
    }

    @PostConstruct
    void ensureRootExists() {
        try {
            Files.createDirectories(storageRoot);
            LOG.debugf("Local backup storage initialized at %s", storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize local backup storage directory", e);
        }
    }

    @Override
    public String uploadBackup(String key, InputStream data, String contentType, long contentLength,
            String md5Checksum) {
        Path destination = resolveKey(key);
        try {
            Path parent = destination.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream outputStream = Files.newOutputStream(destination)) {
                data.transferTo(outputStream);
            }
            LOG.debugf("Stored backup locally - key=%s size=%d bytes type=%s", key, contentLength, contentType);
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store local backup: " + key, e);
        }
    }

    @Override
    public InputStream downloadBackup(String key) {
        Path destination = resolveKey(key);
        try {
            return Files.newInputStream(destination);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read local backup: " + key, e);
        }
    }

    @Override
    public String getSignedDownloadUrl(String key, Duration expiry) {
        // For local storage, return the file URI. Expiry is informational only.
        return resolveKey(key).toUri().toString();
    }

    @Override
    public void deleteBackup(String key) {
        Path destination = resolveKey(key);
        try {
            Files.deleteIfExists(destination);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete local backup: " + key, e);
        }
    }

    @Override
    public boolean backupExists(String key) {
        return Files.exists(resolveKey(key));
    }

    @Override
    public boolean verifyChecksum(String key, String expectedMd5) {
        if (!backupExists(key)) {
            return false;
        }
        Path destination = resolveKey(key);
        try {
            return computeMd5Base64(destination).equals(expectedMd5);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to verify checksum for %s", key);
            return false;
        }
    }

    @Override
    public List<String> listBackups(String prefix) {
        Path prefixPath = resolveKey(prefix);
        if (!Files.exists(prefixPath)) {
            return List.of();
        }

        List<String> backups = new ArrayList<>();
        try {
            Files.walkFileTree(prefixPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile()) {
                        backups.add(storageRoot.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return backups;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list local backups", e);
        }
    }

    private Path resolveKey(String key) {
        Path resolved = storageRoot.resolve(key).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Backup key resolves outside of storage root: " + key);
        }
        return resolved;
    }

    private String computeMd5Base64(Path file) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            try (InputStream inputStream = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    md5.update(buffer, 0, read);
                }
            }
            return Base64.getEncoder().encodeToString(md5.digest());
        } catch (Exception e) {
            throw new IOException("Unable to compute MD5 for " + file, e);
        }
    }
}
