package com.dwp.services.messaging.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AttachmentConfiguration.class);

    @Test
    void configurationBindingUsesExplicitSafeDevelopmentDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AttachmentProperties.class);
            AttachmentProperties properties = context.getBean(AttachmentProperties.class);
            assertThat(properties.storage()).isEqualTo("local");
            assertThat(properties.scanner()).isEqualTo("local");
            assertThat(properties.clamavPort()).isEqualTo(3310);
        });
    }

    @Test
    void configurationBindingRejectsAnExplicitInvalidValue() {
        contextRunner
                .withPropertyValues("dwp.messaging.attachments.clamav-port=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void preservesSupportedProviderNamesWithoutChangingConfiguredLimits() {
        AttachmentProperties properties = properties(
                "s3", "clamav", Duration.ofMinutes(10),
                Duration.ofMinutes(1), 100, 2, 3310, Duration.ofSeconds(10));

        assertThat(properties.storage()).isEqualTo("s3");
        assertThat(properties.scanner()).isEqualTo("clamav");
        assertThat(properties.maximumTransferMb()).isEqualTo(100);
        assertThat(properties.clamavPort()).isEqualTo(3310);
    }

    @Test
    void rejectsUnsupportedProvidersInsteadOfFallingBackToLocal() {
        assertThatThrownBy(() -> properties(
                "filesystem", "clamav", Duration.ofMinutes(10),
                Duration.ofMinutes(1), 100, 2, 3310, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("storage");
        assertThatThrownBy(() -> properties(
                "s3", "disabled", Duration.ofMinutes(10),
                Duration.ofMinutes(1), 100, 2, 3310, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanner");
    }

    @Test
    void rejectsUnsafeTransferAndClamAvValuesInsteadOfSilentlyReplacingThem() {
        assertThatThrownBy(() -> properties(
                "s3", "clamav", Duration.ZERO,
                Duration.ofMinutes(1), 100, 2, 3310, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upload TTL");
        assertThatThrownBy(() -> properties(
                "s3", "clamav", Duration.ofMinutes(10),
                Duration.ofMinutes(1), 0, 2, 3310, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum transfer MB");
        assertThatThrownBy(() -> properties(
                "s3", "clamav", Duration.ofMinutes(10),
                Duration.ofMinutes(1), 100, 2, 0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ClamAV port");
        assertThatThrownBy(() -> properties(
                "s3", "clamav", Duration.ofMinutes(10),
                Duration.ofMinutes(1), 100, 2, 3310, Duration.ofMillis(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ClamAV timeout");
    }

    private AttachmentProperties properties(
            String storage,
            String scanner,
            Duration uploadTtl,
            Duration downloadTtl,
            int maximumTransferMb,
            int maximumConcurrentTransfers,
            int clamavPort,
            Duration clamavTimeout) {
        return new AttachmentProperties(
                storage, Path.of("build/test-attachments"), scanner,
                uploadTtl, downloadTtl, maximumTransferMb, maximumConcurrentTransfers,
                "clamav.messaging.internal", clamavPort, clamavTimeout);
    }
}
