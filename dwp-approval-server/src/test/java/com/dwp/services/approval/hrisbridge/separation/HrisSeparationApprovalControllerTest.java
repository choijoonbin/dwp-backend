package com.dwp.services.approval.hrisbridge.separation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class HrisSeparationApprovalControllerTest {
    @Test
    void preservesExactBytesAndReturnsOnlyTheStoredNoStoreReceipt() {
        var service = mock(HrisSeparationApprovalBridgeService.class);
        var trust = mock(HrisSeparationTrustProfile.class);
        byte[] requestBody = "{\"exact\":\"요청\"}".getBytes(StandardCharsets.UTF_8);
        byte[] responseBody = "{\"state\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST",
                HrisSeparationApprovalController.PATH);
        var identity = new Hrm013WorkloadProofV1.TransportIdentity(
                "dwp-people-server", java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[32]));
        when(trust.applicationHeaders(request)).thenReturn(List.of());
        when(trust.authenticate(request)).thenReturn(identity);
        when(service.dispatch("POST", HrisSeparationApprovalController.PATH,
                List.of(), requestBody, identity)).thenReturn(
                new HrisSeparationApprovalBridgeService.PersistedResponse(
                        responseBody, false));

        var response = new HrisSeparationApprovalController(service, trust)
                .create(request, requestBody);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsExactly(responseBody);
        var order = inOrder(trust, service);
        order.verify(trust).authenticate(request);
        order.verify(trust).applicationHeaders(request);
        order.verify(service).dispatch("POST", HrisSeparationApprovalController.PATH,
                List.of(), requestBody, identity);
        verify(service).dispatch("POST", HrisSeparationApprovalController.PATH,
                List.of(), requestBody, identity);
    }

    @Test
    void rejectsAQueryAliasBeforeAuthenticationOrDispatch() {
        var service = mock(HrisSeparationApprovalBridgeService.class);
        var trust = mock(HrisSeparationTrustProfile.class);
        var request = new MockHttpServletRequest("POST",
                HrisSeparationApprovalController.PATH);
        request.setQueryString("alias=true");

        assertThatThrownBy(() -> new HrisSeparationApprovalController(service, trust)
                .create(request, new byte[] {1}))
                .isInstanceOf(HrisSeparationProtocolException.class);
    }
}
