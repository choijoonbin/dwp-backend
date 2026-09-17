package com.dwp.services.meeting.security;

import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingHomeProviderExactPathSecurityTest {

    private final MeetingSecurityFilter filter =
            new MeetingSecurityFilter("gateway-purpose-token", new ObjectMapper());

    @Test
    void delegatesOnlyExactPostProviderEndpoints() {
        assertDelegationBoundary(filter::shouldNotFilter);
    }

    private void assertDelegationBoundary(RequestDecision decision) {
        assertThat(decision.test(request("POST", HomeWidgetProviderContract.BATCH_PATH))).isTrue();
        assertThat(decision.test(request("POST", HomeWidgetProviderContract.COMMAND_PATH))).isTrue();
        assertThat(decision.test(request("GET", HomeWidgetProviderContract.BATCH_PATH))).isFalse();
        assertThat(decision.test(request("PUT", HomeWidgetProviderContract.COMMAND_PATH))).isFalse();
        assertThat(decision.test(request("POST", HomeWidgetProviderContract.BATCH_PATH + "/suffix")))
                .isFalse();
        assertThat(decision.test(request("POST", "/internal/home/v1/widget-data"))).isFalse();
    }

    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @FunctionalInterface
    private interface RequestDecision {
        boolean test(MockHttpServletRequest request);
    }
}
