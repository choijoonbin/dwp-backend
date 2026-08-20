package com.dwp.services.messaging.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentTransferGuardFilterTest {

    private final AttachmentTransferGuardFilter filter = new AttachmentTransferGuardFilter(
            new AttachmentProperties(
                    "local", Path.of("build/test-attachments"), "local",
                    Duration.ofMinutes(10), Duration.ofMinutes(1),
                    1, 1,
                    "localhost", 3310, Duration.ofSeconds(1)),
            new ObjectMapper().findAndRegisterModules());

    @Test
    void rejectsAnUploadWithoutContentLength() throws Exception {
        MockHttpServletRequest request = uploadRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(411);
        assertThat(response.getContentAsString()).contains("Content-Length");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsAnUploadOverTheConfiguredAbsoluteLimit() throws Exception {
        MockHttpServletRequest request = uploadRequest();
        request.setContent(new byte[1024 * 1024 + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("transfer limit");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void allowsABoundedUploadToReachTheController() throws Exception {
        MockHttpServletRequest request = uploadRequest();
        request.setContent(new byte[512]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private MockHttpServletRequest uploadRequest() {
        UUID conversationId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        return new MockHttpServletRequest(
                "PUT",
                "/v1/conversations/" + conversationId
                        + "/attachments/" + attachmentId + "/content");
    }
}
