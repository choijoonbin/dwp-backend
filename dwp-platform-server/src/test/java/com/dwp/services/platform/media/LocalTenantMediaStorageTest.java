package com.dwp.services.platform.media;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTenantMediaStorageTest {

    @TempDir
    Path root;

    @Test
    void storesAssetsUnderTheTenantAndCategoryBoundary() throws Exception {
        LocalTenantMediaStorage storage = new LocalTenantMediaStorage(root.toString());

        String key = storage.store(
                7L, "branding/logos", "svg", new byte[]{1, 2, 3, 4});

        assertThat(key).startsWith("7/branding/logos/").endsWith(".svg");
        assertThat(storage.load(7L, key).getContentAsByteArray())
                .containsExactly(1, 2, 3, 4);

        storage.delete(7L, key);
        assertThatThrownBy(() -> storage.load(7L, key))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void rejectsCrossTenantTraversalAndInvalidCategories() {
        LocalTenantMediaStorage storage = new LocalTenantMediaStorage(root.toString());

        assertThatThrownBy(() -> storage.load(8L, "7/branding/logos/asset.svg"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> storage.load(7L, "7/../../asset.svg"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> storage.store(7L, "../logos", "svg", new byte[]{1}))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }
}
