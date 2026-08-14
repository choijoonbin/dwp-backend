package com.dwp.core.http;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.observability.api.ApiHistoryAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundHttpHeadersTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void propagatesCorrelationAndChildTraceParentFromCurrentServletRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/example");
        request.addHeader(HeaderConstants.X_CORRELATION_ID, "corr-123");
        request.addHeader(
                HeaderConstants.TRACE_PARENT,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        request.addHeader(HeaderConstants.TRACE_STATE, "vendor=value");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        HttpHeaders headers = new HttpHeaders();

        OutboundHttpHeaders.propagateObservability(headers);

        assertThat(headers.getFirst(HeaderConstants.X_CORRELATION_ID)).isEqualTo("corr-123");
        assertThat(headers.getFirst(HeaderConstants.TRACE_STATE)).isEqualTo("vendor=value");
        assertThat(headers.getFirst(HeaderConstants.TRACE_PARENT))
                .matches("^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$");
        assertThat(headers.getFirst(HeaderConstants.TRACE_PARENT))
                .doesNotContain("00f067aa0ba902b7");
    }

    @Test
    void canBuildTraceParentFromRequestAttributesWhenHeaderIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/example");
        request.setAttribute(ApiHistoryAttributes.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
        request.setAttribute(ApiHistoryAttributes.SPAN_ID, "00f067aa0ba902b7");
        HttpHeaders headers = new HttpHeaders();

        OutboundHttpHeaders.propagateObservability(request, headers);

        assertThat(headers.getFirst(HeaderConstants.TRACE_PARENT))
                .matches("^00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01$");
    }

    @Test
    void leavesHeadersUntouchedWithoutRequestContext() {
        HttpHeaders headers = new HttpHeaders();

        OutboundHttpHeaders.propagateObservability(headers);

        assertThat(headers).isEmpty();
    }
}
