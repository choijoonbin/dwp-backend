package com.dwp.services.people.hris.performance.home;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.people.security.PeopleSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PerformanceHomeControllerTest {

    private static final String PATH =
            "/internal/hris-performance/v1/home-contributions/href_0123456789abcdef";
    private final PerformanceHomeController filter = new PerformanceHomeController(
            null, null, new ObjectMapper().findAndRegisterModules());

    @Test
    void executesBeforeTheBrowserSecurityFilterAndClaimsOnlyItsRouteFamily() {
        int workloadOrder = PerformanceHomeController.class
                .getAnnotation(Order.class).value();
        int browserOrder = PeopleSecurityFilter.class.getAnnotation(Order.class).value();

        assertThat(workloadOrder).isLessThan(browserOrder);
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", PATH)))
                .isFalse();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest(
                "GET", "/v1/hr/performance"))).isTrue();
    }

    @Test
    void rejectsBodyAndTransferEncodingBeforeAdmission() throws Exception {
        MockHttpServletRequest body = new MockHttpServletRequest("GET", PATH);
        body.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertOpaqueNotFound(body);

        MockHttpServletRequest chunked = new MockHttpServletRequest("GET", PATH);
        chunked.addHeader("Transfer-Encoding", "chunked");
        assertOpaqueNotFound(chunked);
    }

    @Test
    void rejectsQueryMethodAndMissingPayloadReferenceBeforeAdmission() throws Exception {
        MockHttpServletRequest query = new MockHttpServletRequest("GET", PATH);
        query.setQueryString("tenantId=7");
        assertOpaqueNotFound(query);

        assertOpaqueNotFound(new MockHttpServletRequest("POST", PATH));
        assertOpaqueNotFound(new MockHttpServletRequest(
                "GET", "/internal/hris-performance/v1/home-contributions"));
    }

    private void assertOpaqueNotFound(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString())
                .contains("PERFORMANCE_HOME_CONTRIBUTION_NOT_FOUND");
        assertThat(chain.getRequest()).isNull();
    }
}
