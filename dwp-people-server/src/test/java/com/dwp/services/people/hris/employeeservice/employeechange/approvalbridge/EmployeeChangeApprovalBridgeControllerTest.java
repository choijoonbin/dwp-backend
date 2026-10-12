package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EmployeeChangeApprovalBridgeControllerTest {
    private static final UUID REQUEST =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void preservesExactRequestAndResponseBytesWithoutApiEnvelope() {
        EmployeeChangeApprovalBridgeService service =
                mock(EmployeeChangeApprovalBridgeService.class);
        byte[] requestBody = "{\"exact\":\"요청\"}".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "{\"exact\":\"receipt\"}".getBytes(StandardCharsets.UTF_8);
        var receipt = new CommandReceiptV1(
                uuid(1), HrisApprovalBridgeV2.CALLBACK_COMMAND_TYPE,
                HrisApprovalBridgeV2.CALLBACK_ORIGINATING_ACTION, uuid(2),
                "a".repeat(64), 3, HrisApprovalBridgeV2.CALLBACK_PURPOSE,
                5, "SUCCEEDED", uuid(3), "b".repeat(64),
                Instant.parse("2026-10-12T08:00:00Z"));
        when(service.dispatch(eq(REQUEST), anyList(), same(requestBody))).thenReturn(
                new HrisApprovalBridgeV2.PersistedResponse<>(receipt, responseBody));
        String path = HrisApprovalBridgeV2.DECISION_CALLBACK.path(REQUEST);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-approval-server");
        request.addHeader("X-DWP-Workload-Proof", "one.two.three");

        var response = new EmployeeChangeApprovalBridgeController(service)
                .record(request, REQUEST, requestBody);

        assertThat(response.getBody()).containsExactly(responseBody);
        assertThat(response.getHeaders().getContentType()).hasToString("application/json");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(service).dispatch(eq(REQUEST), anyList(), same(requestBody));
    }

    @Test
    void forwardsDuplicateHeadersAndRejectsQueryOrNonCanonicalUuidBeforeDispatch() {
        EmployeeChangeApprovalBridgeService service =
                mock(EmployeeChangeApprovalBridgeService.class);
        String path = HrisApprovalBridgeV2.DECISION_CALLBACK.path(REQUEST);
        MockHttpServletRequest duplicate = new MockHttpServletRequest("POST", path);
        duplicate.addHeader("X-DWP-Workload-Proof", "first");
        duplicate.addHeader("X-DWP-Workload-Proof", "second");
        assertThat(EmployeeChangeApprovalBridgeController.headers(duplicate).stream()
                .filter(header -> header.name().equalsIgnoreCase("X-DWP-Workload-Proof")))
                .hasSize(2);

        duplicate.setQueryString("retry=true");
        assertThatThrownBy(() -> new EmployeeChangeApprovalBridgeController(service)
                .record(duplicate, REQUEST, new byte[] {1}))
                .isInstanceOf(EmployeeChangeApprovalProtocolException.class)
                .hasMessage("EXACT_INTERNAL_PATH_REQUIRED");

        MockHttpServletRequest uppercase = new MockHttpServletRequest(
                "POST", path.toUpperCase());
        assertThatThrownBy(() -> new EmployeeChangeApprovalBridgeController(service)
                .record(uppercase, REQUEST, new byte[] {1}))
                .isInstanceOf(EmployeeChangeApprovalProtocolException.class)
                .hasMessage("EXACT_INTERNAL_PATH_REQUIRED");
    }

    @Test
    void exposesBindingRaceAsRetryableTooEarlyWithoutCachingTheFailure() {
        var response = new EmployeeChangeApprovalBridgeAdvice().protocolFailure(
                EmployeeChangeApprovalProtocolException.tooEarly(
                        "EMPLOYEE_CHANGE_APPROVAL_BINDING_PENDING"));

        assertThat(response.getStatusCode().value()).isEqualTo(425);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsEntry(
                "reason", "EMPLOYEE_CHANGE_APPROVAL_BINDING_PENDING");
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
