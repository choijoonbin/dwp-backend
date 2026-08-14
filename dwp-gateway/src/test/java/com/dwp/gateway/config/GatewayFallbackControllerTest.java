package com.dwp.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayFallbackControllerTest {

    @Test
    void returnsAStableServiceUnavailableContract() {
        var response = new GatewayFallbackController().unavailable();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("code", "UPSTREAM_UNAVAILABLE");
        assertThat(response.getBody()).containsKey("message");
    }
}
