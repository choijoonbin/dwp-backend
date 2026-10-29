package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3AttachmentStorageTest {

    @Test
    void requiresBucketAndRejectsUnsafePrefixesAndObjectKeys() {
        S3Client s3 = mock(S3Client.class);

        assertThatThrownBy(() -> new S3AttachmentStorage(s3, " ", "quarantine", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket is required");
        assertThatThrownBy(() -> new S3AttachmentStorage(s3, "attachments", "unsafe//prefix", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid messaging attachment S3 prefix");

        S3AttachmentStorage storage = new S3AttachmentStorage(
                s3, "attachments", "/dwp-messaging/quarantine/", "");
        for (String objectKey : new String[] {null, "", "/tenant/object", "tenant/../object", "bad.key"}) {
            assertThatThrownBy(() -> storage.store(objectKey, new byte[] {1}))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }
    }

    @Test
    void writesQuarantineObjectsWithTheConfiguredEncryptionContract() {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        S3AttachmentStorage aes = new S3AttachmentStorage(
                s3, "attachments", "/dwp-messaging/quarantine/", "");
        S3AttachmentStorage kms = new S3AttachmentStorage(
                s3, "attachments", "dwp-messaging/quarantine", "kms-key-1");

        aes.store("91/object-a", new byte[] {1, 2});
        kms.store("91/object-b", new byte[] {3});

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.times(2))
                .putObject(requests.capture(), any(RequestBody.class));
        PutObjectRequest aesRequest = requests.getAllValues().get(0);
        PutObjectRequest kmsRequest = requests.getAllValues().get(1);
        assertThat(aesRequest.bucket()).isEqualTo("attachments");
        assertThat(aesRequest.key()).isEqualTo("dwp-messaging/quarantine/91/object-a");
        assertThat(aesRequest.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(aesRequest.metadata()).containsEntry("dwp-scan-state", "quarantined");
        assertThat(kmsRequest.serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(kmsRequest.ssekmsKeyId()).isEqualTo("kms-key-1");
    }

    @Test
    void reportsHeadBucketEvidenceAndFailsClosedWhenTheProbeFails() {
        S3Client s3 = mock(S3Client.class);
        S3AttachmentStorage storage = new S3AttachmentStorage(
                s3, "attachments", "quarantine", "");

        assertThat(storage.health()).isEqualTo(AttachmentStorage.ProviderHealth.ready());

        doThrow(s3Failure(403)).when(s3).headBucket(any(HeadBucketRequest.class));
        assertThat(storage.health()).isEqualTo(
                AttachmentStorage.ProviderHealth.unavailable("S3_BUCKET_UNAVAILABLE"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mapsMissingObjectsAndProviderFailuresWithoutLeakingSdkErrors() {
        S3Client missingClient = mock(S3Client.class);
        doThrow(s3Failure(404)).when(missingClient)
                .getObject(any(GetObjectRequest.class), any(software.amazon.awssdk.core.sync.ResponseTransformer.class));
        S3AttachmentStorage missing = new S3AttachmentStorage(
                missingClient, "attachments", "quarantine", "");

        assertThatThrownBy(() -> missing.load("91/missing"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        S3Client unavailableClient = mock(S3Client.class);
        when(unavailableClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Failure(503));
        S3AttachmentStorage unavailable = new S3AttachmentStorage(
                unavailableClient, "attachments", "quarantine", "");

        assertThatThrownBy(() -> unavailable.store("91/object", new byte[] {1}))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.EXTERNAL_SERVICE_ERROR));
    }

    private S3Exception s3Failure(int statusCode) {
        S3Exception.Builder builder = S3Exception.builder();
        builder.statusCode(statusCode);
        builder.message("provider failure");
        return (S3Exception) builder.build();
    }
}
