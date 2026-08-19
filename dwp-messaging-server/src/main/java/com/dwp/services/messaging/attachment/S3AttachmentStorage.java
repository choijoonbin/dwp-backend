package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

final class S3AttachmentStorage implements AttachmentStorage {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;
    private final String kmsKeyId;

    S3AttachmentStorage(S3Client s3, String bucket, String prefix, String kmsKeyId) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("The messaging attachment S3 bucket is required.");
        }
        this.s3 = s3;
        this.bucket = bucket.strip();
        this.prefix = normalizePrefix(prefix);
        this.kmsKeyId = kmsKeyId == null ? "" : kmsKeyId.strip();
    }

    @Override
    public void store(String objectKey, byte[] content) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket).key(key(objectKey)).contentLength((long) content.length)
                .contentType("application/octet-stream")
                .metadata(java.util.Map.of("dwp-scan-state", "quarantined"));
        if (kmsKeyId.isBlank()) {
            request.serverSideEncryption(ServerSideEncryption.AES256);
        } else {
            request.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
        }
        try {
            s3.putObject(request.build(), RequestBody.fromBytes(content));
        } catch (S3Exception exception) {
            throw external("The attachment could not be stored in quarantine.", exception);
        }
    }

    @Override
    public byte[] load(String objectKey) {
        try {
            ResponseBytes<GetObjectResponse> response = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key(objectKey)).build(),
                    ResponseTransformer.toBytes());
            return response.asByteArray();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new BaseException(ErrorCode.NOT_FOUND, "The attachment content was not found.");
            }
            throw external("The attachment could not be loaded.", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(objectKey)).build());
        } catch (S3Exception exception) {
            throw external("The rejected attachment could not be removed.", exception);
        }
    }

    private String key(String objectKey) {
        if (objectKey == null || !objectKey.matches("[a-zA-Z0-9/_-]{1,500}")
                || objectKey.contains("..") || objectKey.startsWith("/")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid attachment object key.");
        }
        return prefix + "/" + objectKey;
    }

    private String normalizePrefix(String value) {
        String normalized = value == null ? "dwp-messaging/quarantine"
                : value.strip().replaceAll("^/+|/+$", "");
        if (normalized.isBlank() || normalized.contains("..") || normalized.contains("//")) {
            throw new IllegalStateException("Invalid messaging attachment S3 prefix.");
        }
        return normalized;
    }

    private BaseException external(String message, Exception cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
