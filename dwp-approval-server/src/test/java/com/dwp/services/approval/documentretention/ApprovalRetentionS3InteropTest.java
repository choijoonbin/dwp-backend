package com.dwp.services.approval.documentretention;

import com.dwp.services.approval.attachment.*;
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
class ApprovalRetentionS3InteropTest {
    @Container static final GenericContainer<?> S3=new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e")
            .withEnv("MINIO_ROOT_USER","approval-test").withEnv("MINIO_ROOT_PASSWORD","approval-test-private")
            .withEnv("MINIO_KMS_SECRET_KEY","approval-test-key:"+java.util.Base64.getEncoder().encodeToString(new byte[32]))
            .withCommand("server","/data").withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofSeconds(120)));
    S3Client client;
    String bucket;
    ApprovalAttachmentS3Storage intake;
    ApprovalRetentionS3Storage retention;

    @BeforeEach void before() {
        client=S3Client.builder().endpointOverride(URI.create("http://"+S3.getHost()+":"+S3.getMappedPort(9000)))
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("approval-test","approval-test-private")))
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)))
                .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(15)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
        bucket="retention-"+UUID.randomUUID();client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        client.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket).versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build()).build());
        intake=new ApprovalAttachmentS3Storage(client,bucket,"quarantine");retention=new ApprovalRetentionS3Storage(client,bucket,"quarantine");
    }
    @AfterEach void after(){client.close();}

    @Test void exactOldVersionDeletionPreservesActualNewerVersion() {
        var old=put("old");retention.verifyPresence(old);
        var next=client.putObject(PutObjectRequest.builder().bucket(bucket).key("quarantine/"+old.objectKey()).build(),RequestBody.fromBytes(new byte[]{9}));
        assertThat(retention.deleteAndConfirmAbsent(old)).isTrue();
        assertThat(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key("quarantine/"+old.objectKey()).versionId(next.versionId()).build()).asByteArray()).containsExactly((byte)9);
        assertThat(retention.deleteAndConfirmAbsent(old)).isTrue();
    }
    @Test void actualDeleteWithLostResponseReconcilesIndependentExactGetAndList() {
        var stored=put("lost");retention.verifyPresence(stored);
        var uncertain=delegating();
        doAnswer(call->{client.deleteObject(call.getArgument(0,DeleteObjectRequest.class));throw SdkClientException.create("Actual DELETE response lost");})
                .when(uncertain).deleteObject(any(DeleteObjectRequest.class));
        assertThat(new ApprovalRetentionS3Storage(uncertain,bucket,"quarantine").deleteAndConfirmAbsent(stored)).isTrue();
        assertThat(client.listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix("quarantine/"+stored.objectKey()).build()).versions()).isEmpty();
    }
    @Test void actualDeletionWithUnknownListingIsNotConfirmedUntilRetry() {
        var stored=put("list-unknown");retention.verifyPresence(stored);var uncertain=delegating();
        doThrow(SdkClientException.create("LIST response unavailable")).when(uncertain).listObjectVersions(any(ListObjectVersionsRequest.class));
        assertThat(new ApprovalRetentionS3Storage(uncertain,bucket,"quarantine").deleteAndConfirmAbsent(stored)).isFalse();
        assertThat(retention.deleteAndConfirmAbsent(stored)).isTrue();
    }
    @Test void forbiddenGetAndTruncatedListCannotBecomeAbsenceProof() {
        var stored=put("not-proof");retention.verifyPresence(stored);var uncertain=delegating();
        doThrow(S3Exception.builder().statusCode(403).awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode("AccessDenied").build()).build())
                .when(uncertain).getObject(any(GetObjectRequest.class));
        assertThat(new ApprovalRetentionS3Storage(uncertain,bucket,"quarantine").deleteAndConfirmAbsent(stored)).isFalse();
        var truncated=delegating();doReturn(ListObjectVersionsResponse.builder().isTruncated(true).build()).when(truncated).listObjectVersions(any(ListObjectVersionsRequest.class));
        assertThat(new ApprovalRetentionS3Storage(truncated,bucket,"quarantine").deleteAndConfirmAbsent(stored)).isFalse();
    }
    @Test void wrongLocatorTamperedHashAndDisabledVersioningCannotVerifyPresence() {
        var stored=put("presence");String other="retention-"+UUID.randomUUID();client.createBucket(CreateBucketRequest.builder().bucket(other).build());
        var wrong=new ApprovalRetentionS3Storage(client,other,"quarantine");
        assertThat(wrong.locatorSha256()).isNotEqualTo(retention.locatorSha256());
        assertThatThrownBy(()->wrong.verifyPresence(stored)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        assertThatThrownBy(()->retention.verifyPresence(new ApprovalAttachmentStorage.Stored(stored.objectKey(),stored.versionId(),stored.sizeBytes(),"a".repeat(64))))
                .isInstanceOf(com.dwp.core.exception.BaseException.class);
        client.putBucketVersioning(PutBucketVersioningRequest.builder().bucket(bucket).versioningConfiguration(VersioningConfiguration.builder().status(BucketVersioningStatus.SUSPENDED).build()).build());
        assertThatThrownBy(()->retention.verifyPresence(stored)).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    private ApprovalAttachmentStorage.Stored put(String suffix) {
        byte[] bytes={1,2,3};return intake.put("tenant42/"+suffix,bytes,ApprovalAttachmentIntegrity.sha(bytes));
    }
    private S3Client delegating(){return mock(S3Client.class,withSettings().defaultAnswer(call->{
        try{return call.getMethod().invoke(client,call.getArguments());}
        catch(java.lang.reflect.InvocationTargetException wrapped){throw wrapped.getCause();}
    }));}
}
