package com.dwp.services.platform.productivity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductivityCryptoTest {

    @Test
    void encryptsWithBoundContextAndStableFingerprints() {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        ProductivityCrypto crypto = new ProductivityCrypto(key);

        String encrypted = crypto.encrypt("refresh-token", "tenant:1:user:2");

        assertThat(encrypted).doesNotContain("refresh-token");
        assertThat(crypto.decrypt(encrypted, "tenant:1:user:2")).isEqualTo("refresh-token");
        assertThat(crypto.fingerprint("source-id"))
                .isEqualTo(crypto.fingerprint("source-id"))
                .hasSize(64);
        assertThatThrownBy(() -> crypto.decrypt(encrypted, "tenant:2:user:2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesMissingOrWeakKeys() {
        assertThat(new ProductivityCrypto(new byte[0]).available()).isFalse();
        assertThatThrownBy(() -> new ProductivityCrypto(new byte[16]))
                .isInstanceOf(IllegalStateException.class);
    }
}
