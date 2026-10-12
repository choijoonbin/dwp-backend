package com.dwp.services.approval.hrisbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class HrisApprovalBridgeControllerTest {
    @Mock
    private HrisApprovalBridgeService service;

    @Test
    void preservesExactRequestAndResponseBytesWithoutApiEnvelope() {
        byte[] requestBody = "{\"exact\":\"요청\"}".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "{\"exact\":\"receipt\"}".getBytes(StandardCharsets.UTF_8);
        var receipt = new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                UUID.fromString("00000000-0000-4000-8000-000000000071"),
                1, "PENDING", "a".repeat(64));
        when(service.dispatch(anyList(), same(requestBody))).thenReturn(
                new HrisApprovalBridgeV2.PersistedResponse<>(receipt, responseBody));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", HrisApprovalBridgeController.PATH);
        request.addHeader("Content-Type", "application/json");
        request.addHeader("X-DWP-Service-Identity", "dwp-people-server");
        request.addHeader("X-DWP-Workload-Proof", "one.two.three");

        var response = new HrisApprovalBridgeController(service)
                .create(request, requestBody);

        assertThat(response.getBody()).containsExactly(responseBody);
        assertThat(response.getHeaders().getContentType()).hasToString("application/json");
        verify(service).dispatch(anyList(), same(requestBody));
    }

    @Test
    void forwardsEveryDuplicateHeaderValueAndRejectsQueryAliasBeforeDispatch() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", HrisApprovalBridgeController.PATH);
        request.addHeader("X-DWP-Workload-Proof", "first");
        request.addHeader("X-DWP-Workload-Proof", "second");
        assertThat(HrisApprovalBridgeController.headers(request).stream()
                .filter(header -> header.name().equalsIgnoreCase("X-DWP-Workload-Proof")))
                .hasSize(2);
        request.setQueryString("alias=true");
        assertThatThrownBy(() -> new HrisApprovalBridgeController(service)
                .create(request, new byte[] { 1 }))
                .isInstanceOf(HrisApprovalProtocolException.class)
                .hasMessage("EXACT_INTERNAL_PATH_REQUIRED");
    }
}
