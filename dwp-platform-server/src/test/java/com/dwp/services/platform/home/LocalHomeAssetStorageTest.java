package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalHomeAssetStorageTest {

    @TempDir
    Path root;

    @Test
    void storesAssetsUnderTheTenantBoundaryAndDeletesThem() throws Exception {
        LocalHomeAssetStorage storage = new LocalHomeAssetStorage(root.toString());

        String key = storage.store(7L, "png", new byte[]{1, 2, 3, 4});

        assertThat(key).startsWith("7/").endsWith(".png");
        assertThat(storage.load(7L, key).getContentAsByteArray())
                .containsExactly(1, 2, 3, 4);

        storage.delete(7L, key);
        assertThatThrownBy(() -> storage.load(7L, key))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void rejectsCrossTenantAndTraversalKeys() {
        LocalHomeAssetStorage storage = new LocalHomeAssetStorage(root.toString());

        assertThatThrownBy(() -> storage.load(8L, "7/asset.png"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> storage.load(7L, "7/../../asset.png"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }
}
