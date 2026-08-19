package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class LocalAttachmentStorage implements AttachmentStorage {

    private final Path root;

    LocalAttachmentStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(String objectKey, byte[] content) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "The attachment could not be stored.", exception);
        }
    }

    @Override
    public byte[] load(String objectKey) {
        try {
            return Files.readAllBytes(resolve(objectKey));
        } catch (IOException exception) {
            throw new BaseException(ErrorCode.NOT_FOUND, "The attachment content was not found.");
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "The attachment could not be removed.", exception);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !objectKey.matches("[a-zA-Z0-9/_-]{1,500}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid attachment object key.");
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid attachment object key.");
        }
        return resolved;
    }
}
