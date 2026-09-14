package com.dwp.services.approval.attachment;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentS3InteropTest {
    @Container static final GenericContainer<?> S3=new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e")
            .withEnv("MINIO_ROOT_USER","approval-test").withEnv("MINIO_ROOT_PASSWORD","approval-test-private")
            .withEnv("MINIO_KMS_SECRET_KEY","approval-test-key:"+java.util.Base64.getEncoder().encodeToString(new byte[32]))
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofSeconds(120)));
    S3Client client;String bucket;ApprovalAttachmentS3Storage storage;
    @BeforeEach void before() {
        client=S3Client.builder().endpointOverride(URI.create("http://"+S3.getHost()+":"+S3.getMappedPort(9000))).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("approval-test","approval-test-private")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        bucket="approval-"+UUID.randomUUID();client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        client.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket).versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build()).build());
        storage=new ApprovalAttachmentS3Storage(client,bucket,"quarantine");
    }
    @AfterEach void after(){client.close();}
    @Test void actualVersionAndContentShaAreVerifiedAndLatestOverwriteCannotReplacePinnedBytes() {
        byte[] original={1,2,3};String sha=ApprovalAttachmentIntegrity.sha(original);
        var stored=storage.put("tenant42/opaque",original,sha);assertThat(stored.versionId()).isNotBlank().isNotEqualTo("null");
        assertThat(storage.load(stored)).isEqualTo(original);
        client.putObject(PutObjectRequest.builder().bucket(bucket).key("quarantine/tenant42/opaque").build(),RequestBody.fromBytes(new byte[]{9}));
        assertThat(storage.load(stored)).isEqualTo(original);
        assertThatThrownBy(()->storage.reconcile("tenant42/opaque",original.length,sha)).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void actualUnknownPutResponseReconcilesWithoutASecondObjectVersion() {
        var uncertain=mock(S3Client.class,withSettings().defaultAnswer(invocation->invocation.getMethod().invoke(client,invocation.getArguments())));
        doAnswer(invocation->{client.putObject(invocation.getArgument(0,PutObjectRequest.class),invocation.getArgument(1,RequestBody.class));throw SdkClientException.create("Response lost after durable put");})
                .when(uncertain).putObject(any(PutObjectRequest.class),any(RequestBody.class));
        var adapter=new ApprovalAttachmentS3Storage(uncertain,bucket,"quarantine");byte[] bytes={4,5};String sha=ApprovalAttachmentIntegrity.sha(bytes);
        assertThatThrownBy(()->adapter.put("tenant42/unknown",bytes,sha)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        var recovered=adapter.reconcile("tenant42/unknown",bytes.length,sha);assertThat(adapter.load(recovered)).isEqualTo(bytes);
        assertThat(client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix("quarantine/tenant42/unknown").build()).versions()).hasSize(1);
    }
    @Test void disabledVersioningAndTamperedReceiptFailClosed() {
        byte[] bytes={7};String sha=ApprovalAttachmentIntegrity.sha(bytes);var stored=storage.put("tenant42/version",bytes,sha);
        assertThatThrownBy(()->storage.load(new ApprovalAttachmentStorage.Stored(stored.objectKey(),stored.versionId(),stored.sizeBytes(),"a".repeat(64)))).isInstanceOf(com.dwp.core.exception.BaseException.class);
        client.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket).versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.SUSPENDED).build()).build());
        assertThat(storage.readiness()).isEqualTo("VERSIONING_REQUIRED");
        assertThatThrownBy(()->storage.put("tenant42/disabled",bytes,sha)).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
}
