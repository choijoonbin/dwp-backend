package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InformationReplayWorkloadTest {
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", InformationReplayProtocol.PATH);
        request.addHeader(InformationReplayProtocol.HEADER, "signed-token-checked-by-service");
        request.addHeader("X-DWP-Service-Identity", "dwp-approval-server");
        request.setContentType("application/json"); request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
    private int denied(MockHttpServletRequest request, boolean enabled) throws Exception {
        var response = new MockHttpServletResponse();
        new InformationReplaySecurityConfig.WorkloadFilter(new ObjectMapper().findAndRegisterModules(), enabled)
                .doFilter(request, response, (input, output) -> fail("Invalid workload must not reach controller/DB."));
        return response.getStatus();
    }
    @Test void defaultFalseReturns503WithoutReadingBody() throws Exception {
        var request = new MockHttpServletRequest("POST", InformationReplayProtocol.PATH) {
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Disabled source must not read body."); }
        };
        request.addHeader(InformationReplayProtocol.HEADER, "token"); request.addHeader("X-DWP-Service-Identity", "dwp-approval-server");
        request.setContentType("application/json"); assertEquals(503, denied(request, false));
    }
    @Test void rejectsAmbientAndOtherPurposeHeaders() throws Exception {
        for (String header : new String[]{"Authorization", "Cookie", "X-DWP-Approval-Workflow-Runtime-Token", "X-DWP-Approval-Policy-Impact-Token", "X-DWP-Actor-Id"}) {
            var request = request(); request.addHeader(header, "borrowed"); assertEquals(401, denied(request, true));
        }
    }
    @Test void rejectsDuplicateAndOversizedTokens() throws Exception {
        var duplicate = request(); duplicate.addHeader(InformationReplayProtocol.HEADER, "second"); assertEquals(401, denied(duplicate, true));
        var oversized = request(); oversized.removeHeader(InformationReplayProtocol.HEADER); oversized.addHeader(InformationReplayProtocol.HEADER, "a".repeat(2049));
        assertEquals(401, denied(oversized, true));
    }
    @Test void rejectsHeadQueryMatrixPercentAndNestedAliases() throws Exception {
        var head = request(); head.setMethod("HEAD"); assertEquals(403, denied(head, true));
        var query = request(); query.setQueryString("operation=RECEIPT_REPLAY"); assertEquals(403, denied(query, true));
        for (String path : new String[]{InformationReplayProtocol.PATH + ";x=1", InformationReplayProtocol.PATH + "/nested", "/internal/approval-workflow/%69nformation-command-replay"}) {
            var alias = request(); alias.setRequestURI(path); assertEquals(403, denied(alias, true));
        }
    }
    @Test void rejectsNonJsonCharsetAndBodyOverflow() throws Exception {
        var content = request(); content.setContentType("application/json;charset=UTF-16"); assertEquals(403, denied(content, true));
        var overflow = request(); overflow.setContent(new byte[InformationReplayProtocol.BODY_LIMIT + 1]); assertEquals(403, denied(overflow, true));
    }
    @Test void allowsOnlyBoundedBodyToReachCryptographicService() throws Exception {
        var request = request(); var response = new MockHttpServletResponse(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        new InformationReplaySecurityConfig.WorkloadFilter(new ObjectMapper(), true).doFilter(request, response, (input, output) -> {
            calls.incrementAndGet(); assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), input.getInputStream().readAllBytes());
        });
        assertEquals(1, calls.get());
    }
}
