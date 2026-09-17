package com.dwp.services.approval.security;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalHomeProviderFilterBoundaryTest {

    private final ApprovalSecurityFilter filter =
            new ApprovalSecurityFilter("gateway-purpose-token", new ObjectMapper());

    @Test
    void delegatesOnlyExactProviderPostPathsToTheDedicatedControllerSecurity() {
        assertThat(filter.shouldNotFilter(request("POST", HomeWidgetProviderContract.BATCH_PATH)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("POST", HomeWidgetProviderContract.COMMAND_PATH)))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("GET", HomeWidgetProviderContract.BATCH_PATH)))
                .isFalse();
        assertThat(filter.shouldNotFilter(request(
                "POST", HomeWidgetProviderContract.BATCH_PATH + "/suffix")))
                .isFalse();
        assertThat(filter.shouldNotFilter(request(
                "POST", "/internal/home/v1/widget-data")))
                .isFalse();
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
