package com.dwp.services.approval.documentretention;

import com.dwp.services.approval.attachment.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.IOException;

public final class ApprovalRetentionS3Storage implements ApprovalRetentionStorage {
    private final S3Client client;
    private final String bucket, prefix, locator;
    private final ApprovalAttachmentS3Storage storage;
    private static final AwsRequestOverrideConfiguration BOUNDS=AwsRequestOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5)).build();

    public ApprovalRetentionS3Storage(S3Client client,String bucket,String prefix) {
        this.client=client;this.bucket=bucket;this.prefix=prefix;
        this.storage=new ApprovalAttachmentS3Storage(client,bucket,prefix);
        var configuration=client.serviceClientConfiguration();
        String endpoint=configuration.endpointOverride().map(Object::toString).orElse("aws:"+configuration.region().id());
        this.locator=ApprovalAttachmentIntegrity.sha((endpoint+"\n"+configuration.region().id()+"\n"+bucket+"\n"+prefix).getBytes(StandardCharsets.UTF_8));
    }
    @Override public String locatorSha256(){return locator;}
    @Override public ApprovalAttachmentStorage.Stored reconcile(String key,long size,String sha){return storage.reconcile(key,size,sha);}
    @Override public void verifyPresence(ApprovalAttachmentStorage.Stored stored) {
        if (!"VERSIONING_VERIFIED".equals(storage.readiness())) throw ApprovalAttachmentIntegrity.unavailable("Versioning not verified");
        storage.load(stored);
    }
    @Override public boolean deleteAndConfirmAbsent(ApprovalAttachmentStorage.Stored stored) {
        String key=key(stored.objectKey());
        try {
            try {client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).versionId(stored.versionId()).overrideConfiguration(BOUNDS).build());}
            catch(RuntimeException unknown) { /* An unknown response still requires independent exact-version reconciliation. */ }
            try(var response=client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).versionId(stored.versionId()).overrideConfiguration(BOUNDS).build())) {response.abort();return false;}
            catch(S3Exception absent) {
                String code=absent.awsErrorDetails()==null?null:absent.awsErrorDetails().errorCode();
                if(absent.statusCode()!=404 || !("NoSuchVersion".equals(code)||"NoSuchKey".equals(code))) return false;
            }
            var versions=client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix(key).maxKeys(1000).overrideConfiguration(BOUNDS).build());
            return !Boolean.TRUE.equals(versions.isTruncated())
                    && versions.versions().stream().noneMatch(v->key.equals(v.key()) && stored.versionId().equals(v.versionId()))
                    && versions.deleteMarkers().stream().noneMatch(v->key.equals(v.key()) && stored.versionId().equals(v.versionId()));
        } catch(RuntimeException | IOException unknown) {return false;}
    }
    private String key(String key) {
        if(key==null || !key.matches("[a-z0-9/_-]{1,200}") || key.contains("..") || key.startsWith("/")) throw new IllegalArgumentException("Opaque owned key required");
        return prefix+"/"+key;
    }
}
