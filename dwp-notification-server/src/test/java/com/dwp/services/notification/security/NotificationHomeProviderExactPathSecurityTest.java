package com.dwp.services.notification.security;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationHomeProviderExactPathSecurityTest {

    private final NotificationSecurityFilter filter = new NotificationSecurityFilter(
            "gateway-purpose-token-at-least-24",
            "dwp-gateway",
            "",
            "",
            new ObjectMapper());

    @Test
    void delegatesOnlyExactPostProviderEndpoints() {
        assertThat(filter.shouldNotFilter(request("POST", HomeWidgetProviderContract.BATCH_PATH)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("POST", HomeWidgetProviderContract.COMMAND_PATH)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("GET", HomeWidgetProviderContract.BATCH_PATH)))
                .isFalse();
        assertThat(filter.shouldNotFilter(request("PUT", HomeWidgetProviderContract.COMMAND_PATH)))
                .isFalse();
        assertThat(filter.shouldNotFilter(request(
                "POST", HomeWidgetProviderContract.BATCH_PATH + "/suffix"))).isFalse();
        assertThat(filter.shouldNotFilter(request("POST", "/internal/home/v1/widget-data")))
                .isFalse();
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
