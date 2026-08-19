package com.dwp.services.platform.media;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class S3TenantMediaStorageTest {

    private final S3TenantMediaStorage storage = new S3TenantMediaStorage(
            mock(S3Client.class), "dwp-assets", "tenant-media", "");

    @Test
    void objectKeysRemainInsideTheOwningTenantPrefix() {
        assertThat(storage.objectKey(21L, "21/workplace/floors/a.png"))
                .isEqualTo("tenant-media/21/workplace/floors/a.png");
    }

    @Test
    void objectKeysRejectCrossTenantAndTraversalInput() {
        assertThatThrownBy(() -> storage.objectKey(
                21L, "22/workplace/floors/a.png"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> storage.objectKey(
                21L, "21/workplace/../secrets/a.png"))
                .isInstanceOf(BaseException.class);
    }
}
