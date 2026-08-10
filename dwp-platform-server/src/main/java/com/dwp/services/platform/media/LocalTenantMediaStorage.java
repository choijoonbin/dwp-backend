package com.dwp.services.platform.media;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class LocalTenantMediaStorage implements TenantMediaStorage {

    private static final Pattern CATEGORY_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9/-]{0,79}");
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("[a-z0-9]{1,8}");

    private final Path root;

    public LocalTenantMediaStorage(
            @Value("${dwp.platform.assets.root:${user.home}/.dwp/platform-assets}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String store(Long tenantId, String category, String extension, byte[] content) {
        requireCategory(category);
        if (extension == null || !EXTENSION_PATTERN.matcher(extension).matches()) {
            throw invalid("Invalid tenant media extension.");
        }
        if (content == null || content.length == 0) {
            throw invalid("Tenant media content is required.");
        }

        String key = tenantId + "/" + category + "/" + UUID.randomUUID() + "." + extension;
        Path target = resolve(tenantId, key);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temporary, content);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return key;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Tenant media could not be stored.",
                    exception);
        }
    }

    @Override
    public Resource load(Long tenantId, String storageKey) {
        Path path = resolve(tenantId, storageKey);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Tenant media was not found.");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(Long tenantId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(tenantId, storageKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Tenant media cleanup failed.", exception);
        }
    }

    Path resolve(Long tenantId, String storageKey) {
        if (tenantId == null || tenantId <= 0) {
            throw invalid("Invalid tenant media owner.");
        }
        String tenantPrefix = tenantId + "/";
        if (storageKey == null || !storageKey.startsWith(tenantPrefix)) {
            throw invalid("Invalid tenant media key.");
        }
        Path resolved = root.resolve(storageKey).normalize();
        Path tenantRoot = root.resolve(String.valueOf(tenantId)).normalize();
        if (!resolved.startsWith(tenantRoot)) {
            throw invalid("Invalid tenant media path.");
        }
        return resolved;
    }

    private void requireCategory(String category) {
        if (category == null
                || !CATEGORY_PATTERN.matcher(category).matches()
                || category.contains("//")
                || category.contains("..")
                || category.endsWith("/")) {
            throw invalid("Invalid tenant media category.");
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed temporary-file cleanup is handled by operational retention.
        }
    }
}
