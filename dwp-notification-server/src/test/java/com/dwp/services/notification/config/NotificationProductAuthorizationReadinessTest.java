package com.dwp.services.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationProductAuthorizationReadinessTest {

    @Test
    void draftArtifactIsAnExplicitExternalProductionBlocker() throws Exception {
        var health = new NotificationProductAuthorizationReadiness().health();
        Path artifact = Files.isRegularFile(Path.of(
                "contracts/product-authorization/product-surfaces-v1.bundle-v4.json"))
                ? Path.of("contracts/product-authorization/product-surfaces-v1.bundle-v4.json")
                : Path.of(
                        "../contracts/product-authorization/product-surfaces-v1.bundle-v4.json");
        String actualStatus = new ObjectMapper().readTree(Files.readString(artifact))
                .path("bundleStatus").asText();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(actualStatus).isEqualTo(
                NotificationProductionConfigurationGuard
                        .EMBEDDED_PRODUCT_AUTHORIZATION_V4_STATUS);
        assertThat(health.getDetails())
                .containsEntry("state", "BLOCKED_EXTERNAL")
                .containsEntry("bundleStatus", "DRAFT");
    }
}
