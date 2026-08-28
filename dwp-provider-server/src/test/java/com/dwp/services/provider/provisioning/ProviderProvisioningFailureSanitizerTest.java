package com.dwp.services.provider.provisioning;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderProvisioningFailureSanitizerTest {

    private final ProviderProvisioningFailureSanitizer sanitizer =
            new ProviderProvisioningFailureSanitizer();

    @Test
    void downstreamHttpBodyNeverEntersOperationEvidence() {
        String canary = "provider-secret-canary bearer-token user@example.test";
        HttpServerErrorException failure = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Downstream rejected request",
                HttpHeaders.EMPTY,
                ("{\"detail\":\"" + canary + "\"}").getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        ProviderProvisioningFailureSanitizer.Failure sanitized = sanitizer.sanitize(failure);

        assertThat(sanitized.code()).isEqualTo("HTTP_500");
        assertThat(sanitized.message()).isEqualTo("Downstream provisioning failed (HTTP 500).");
        assertThat(sanitized.message()).doesNotContain(canary, "bearer-token", "user@example.test");
    }
}
