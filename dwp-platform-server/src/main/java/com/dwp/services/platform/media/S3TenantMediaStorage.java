package com.dwp.services.platform.media;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(
        prefix = "dwp.platform.assets",
        name = "storage",
        havingValue = "s3")
public class S3TenantMediaStorage implements TenantMediaStorage {

    private static final Pattern CATEGORY_PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9/-]{0,79}");
    private static final Pattern EXTENSION_PATTERN = Pattern.compile("[a-z0-9]{1,8}");

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final String kmsKeyId;

    public S3TenantMediaStorage(
            S3Client s3,
            @Value("${dwp.platform.assets.s3.bucket}") String bucket,
            @Value("${dwp.platform.assets.s3.prefix:dwp-platform}") String prefix,
            @Value("${dwp.platform.assets.s3.kms-key-id:}") String kmsKeyId) {
        this.s3 = s3;
        this.bucket = requireSegment(bucket, "S3 bucket");
        this.prefix = normalizePrefix(prefix);
        this.kmsKeyId = kmsKeyId == null ? "" : kmsKeyId.trim();
    }

    @Override
    public String store(Long tenantId, String category, String extension, byte[] content) {
        requireTenant(tenantId);
        if (category == null
                || !CATEGORY_PATTERN.matcher(category).matches()
                || category.contains("//")
                || category.contains("..")
                || category.endsWith("/")) {
            throw invalid("Invalid tenant media category.");
        }
        if (extension == null || !EXTENSION_PATTERN.matcher(extension).matches()) {
            throw invalid("Invalid tenant media extension.");
        }
        if (content == null || content.length == 0) {
            throw invalid("Tenant media content is required.");
        }
        String storageKey =
                tenantId + "/" + category + "/" + UUID.randomUUID() + "." + extension;
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(tenantId, storageKey))
                .contentLength((long) content.length);
        if (kmsKeyId.isBlank()) {
            request.serverSideEncryption(ServerSideEncryption.AES256);
        } else {
            request.serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(kmsKeyId);
        }
        try {
            s3.putObject(request.build(), RequestBody.fromBytes(content));
            return storageKey;
        } catch (S3Exception exception) {
            throw external("Tenant media could not be stored.", exception);
        }
    }

    @Override
    public Resource load(Long tenantId, String storageKey) {
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObject(
                    GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey(tenantId, storageKey))
                            .build(),
                    ResponseTransformer.toBytes());
            return new ByteArrayResource(bytes.asByteArray());
        } catch (NoSuchKeyException exception) {
            throw new BaseException(ErrorCode.NOT_FOUND, "Tenant media was not found.");
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new BaseException(ErrorCode.NOT_FOUND, "Tenant media was not found.");
            }
            throw external("Tenant media could not be loaded.", exception);
        }
    }

    @Override
    public void delete(Long tenantId, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey(tenantId, storageKey))
                    .build());
        } catch (S3Exception exception) {
            throw external("Tenant media could not be deleted.", exception);
        }
    }

    String objectKey(Long tenantId, String storageKey) {
        requireTenant(tenantId);
        if (storageKey == null
                || !storageKey.startsWith(tenantId + "/")
                || storageKey.contains("..")
                || storageKey.contains("//")) {
            throw invalid("Invalid tenant media key.");
        }
        return prefix + "/" + storageKey;
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw invalid("Invalid tenant media owner.");
        }
    }

    private String normalizePrefix(String value) {
        String normalized = requireSegment(value, "S3 prefix")
                .replaceAll("^/+|/+$", "");
        if (normalized.contains("..") || normalized.contains("//")) {
            throw invalid("Invalid S3 tenant-media prefix.");
        }
        return normalized;
    }

    private String requireSegment(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required.");
        }
        return value.trim();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException external(String message, Exception exception) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, exception);
    }
}
