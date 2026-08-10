package com.dwp.services.platform.apihistory;

import com.dwp.core.filter.ApiHistoryServletFilter;
import com.dwp.observability.api.HttpApiHistoryPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiHistoryIngestSecurityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rejectsMissingOrInvalidCollectorIdentity() throws Exception {
        ApiHistoryIngestSecurityFilter filter =
                new ApiHistoryIngestSecurityFilter("trusted-ingest-token", objectMapper);
        MockHttpServletRequest request = request();
        request.addHeader(HttpApiHistoryPublisher.INGEST_TOKEN_HEADER, "invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Trusted observability identity");
    }

    @Test
    void acceptsTheConfiguredCollectorIdentity() throws Exception {
        ApiHistoryIngestSecurityFilter filter =
                new ApiHistoryIngestSecurityFilter("trusted-ingest-token", objectMapper);
        MockHttpServletRequest request = request();
        request.addHeader(HttpApiHistoryPublisher.INGEST_TOKEN_HEADER, "trusted-ingest-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", ApiHistoryServletFilter.COLLECTOR_PATH);
    }
}
