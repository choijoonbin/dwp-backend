package com.dwp.services.platform.home;

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

@Component
public class LocalHomeAssetStorage implements HomeAssetStorage {

    private final Path root;

    public LocalHomeAssetStorage(
            @Value("${dwp.platform.home-assets.root:${user.home}/.dwp/home-assets}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String store(Long tenantId, String extension, byte[] content) {
        String key = tenantId + "/" + UUID.randomUUID() + "." + extension;
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
                    "Home background could not be stored.",
                    exception);
        }
    }

    @Override
    public Resource load(Long tenantId, String storageKey) {
        Path path = resolve(tenantId, storageKey);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Home background was not found.");
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(Long tenantId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        try {
            Files.deleteIfExists(resolve(tenantId, storageKey));
        } catch (IOException exception) {
            throw new IllegalStateException("Home background cleanup failed.", exception);
        }
    }

    Path resolve(Long tenantId, String storageKey) {
        String tenantPrefix = tenantId + "/";
        if (storageKey == null || !storageKey.startsWith(tenantPrefix)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid home asset key.");
        }
        Path resolved = root.resolve(storageKey).normalize();
        Path tenantRoot = root.resolve(String.valueOf(tenantId)).normalize();
        if (!resolved.startsWith(tenantRoot)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid home asset path.");
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A failed temporary-file cleanup is handled by operational retention.
        }
    }
}
