package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomePersonalizationBodySizeFilterTest {
    private final HomePersonalizationBodySizeFilter filter = new HomePersonalizationBodySizeFilter(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void rejectsOversizedChunkedBodyWhenContentLengthIsUnknown() throws ServletException, IOException {
        MockHttpServletRequest request = unknownLengthRequest("POST", "/v1/home-views");
        request.setContent(new byte[(int) HomePersonalizationBodySizeFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("E1001", "exceeds 512 KiB");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void buffersAllowedChunkedBodyForDownstreamJsonParsing() throws ServletException, IOException {
        byte[] body = "{\"name\":\"Focus\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = unknownLengthRequest("POST", "/v1/home-views");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(chain.getRequest().getContentLengthLong()).isEqualTo(body.length);
    }

    private MockHttpServletRequest unknownLengthRequest(String method, String uri) {
        return new MockHttpServletRequest(method, uri) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }
}
