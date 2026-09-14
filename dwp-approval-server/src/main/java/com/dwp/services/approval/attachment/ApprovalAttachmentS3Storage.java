package com.dwp.services.approval.attachment;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

public final class ApprovalAttachmentS3Storage implements ApprovalAttachmentStorage {
    private final S3Client client;
    private final String bucket;
    private final String prefix;
    public ApprovalAttachmentS3Storage(S3Client client, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank() || prefix == null || !prefix.matches("[a-zA-Z0-9/_-]{1,120}"))
            throw new IllegalArgumentException("An isolated approval attachment bucket/prefix is required.");
        this.client=client; this.bucket=bucket; this.prefix=prefix;
    }
    @Override public String readiness() {
        try {
            return client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status()==BucketVersioningStatus.ENABLED
                    ? "VERSIONING_VERIFIED" : "VERSIONING_REQUIRED";
        } catch (RuntimeException failure) { return "STORAGE_UNAVAILABLE"; }
    }
    @Override public Stored put(String objectKey, byte[] bytes, String sha) {
        ApprovalAttachmentIntegrity.require(bytes,bytes.length,sha);
        if (!"VERSIONING_VERIFIED".equals(readiness())) throw ApprovalAttachmentIntegrity.unavailable("S3 versioning is not verified.");
        try {
            var response=client.putObject(PutObjectRequest.builder().bucket(bucket).key(key(objectKey))
                    .ifNoneMatch("*").contentLength((long)bytes.length).contentType("application/octet-stream")
                    .checksumSHA256(base64(sha)).serverSideEncryption(ServerSideEncryption.AES256)
                    .metadata(Map.of("dwp-sha256",sha,"dwp-state","quarantine")).build(),RequestBody.fromBytes(bytes));
            var stored=receipt(objectKey,response.versionId(),bytes.length,sha);
            load(stored); return stored;
        } catch (RuntimeException unknown) {
            throw ApprovalAttachmentIntegrity.unavailable("STORAGE_RECONCILIATION_REQUIRED");
        }
    }
    @Override public Stored reconcile(String objectKey, long size, String sha) {
        try {
            var head=client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(objectKey)).checksumMode(ChecksumMode.ENABLED).build());
            if (head.contentLength()!=size || !sha.equals(head.metadata().get("dwp-sha256")))
                throw ApprovalAttachmentIntegrity.unavailable("Stored quarantine receipt does not match.");
            var stored=receipt(objectKey,head.versionId(),size,sha); load(stored); return stored;
        } catch (RuntimeException unknown) { throw ApprovalAttachmentIntegrity.unavailable("STORAGE_RECONCILIATION_REQUIRED"); }
    }
    @Override public byte[] load(Stored stored) {
        if (stored.sizeBytes()<1 || stored.sizeBytes()>25L*1024*1024) throw ApprovalAttachmentIntegrity.unavailable("Stored content exceeds the hard cap.");
        try (var input=client.getObject(GetObjectRequest.builder().bucket(bucket).key(key(stored.objectKey()))
                .versionId(stored.versionId()).checksumMode(ChecksumMode.ENABLED).build())) {
            if (!stored.versionId().equals(input.response().versionId()) || input.response().contentLength()!=stored.sizeBytes())
                throw ApprovalAttachmentIntegrity.unavailable("Stored object version changed.");
            byte[] bytes=input.readNBytes(Math.toIntExact(stored.sizeBytes()+1));
            ApprovalAttachmentIntegrity.require(bytes,stored.sizeBytes(),stored.sha256()); return bytes;
        } catch (java.io.IOException failure) { throw ApprovalAttachmentIntegrity.unavailable("Stored content is unavailable."); }
    }
    @Override public void deleteUnbound(Stored stored) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(stored.objectKey())).versionId(stored.versionId()).build());
    }
    private Stored receipt(String key, String version, long size, String sha) {
        if (version==null || version.isBlank() || "null".equals(version)) throw ApprovalAttachmentIntegrity.unavailable("An actual object version is required.");
        return new Stored(key,version,size,sha);
    }
    private String key(String value) {
        if (value==null || !value.matches("[a-z0-9/_-]{1,200}") || value.contains("..") || value.startsWith("/")) throw new IllegalArgumentException("Invalid opaque object identity.");
        return prefix+"/"+value;
    }
    private String base64(String sha) { return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha)); }
}
