package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class Hrm013PayrollTaskDispatchControllerTest {
    @Test
    void rejectsRequestsThatDidNotPassTheCryptographicIngress() {
        var controller = new Hrm013PayrollTaskDispatchController(
                mock(Hrm013PayrollTaskDispatchService.class));
        var request = new MockHttpServletRequest("POST",
                "/internal/hris-payroll/v1/separation-task-dispatches");
        request.addHeader("X-Correlation-ID",
                "d6990436-76cf-4eb7-b97d-a98da96979fa");

        var response = controller.dispatch(request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void returnsExactlyOneOwnerSignedAdmissionReceiptProof() {
        var service = mock(Hrm013PayrollTaskDispatchService.class);
        byte[] receipt = "{\"receipt\":\"exact\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.admit(any())).thenReturn(
                new Hrm013PayrollTaskDispatchService.AdmissionResponse(
                        receipt, "protected.claims.signature"));
        var controller = new Hrm013PayrollTaskDispatchController(service);
        var request = new MockHttpServletRequest("POST",
                "/internal/hris-payroll/v1/separation-task-dispatches");
        request.setAttribute(Hrm013PayrollTaskDispatchIngressFilter.VERIFIED_ATTRIBUTE,
                mock(Hrm013PayrollTaskDispatchIngressFilter.VerifiedDispatch.class));

        var response = controller.dispatch(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().get(
                Hrm013PayrollTaskDispatchController.RECEIPT_PROOF_HEADER))
                .containsExactly("protected.claims.signature");
        assertThat(response.getBody()).isEqualTo(receipt);
    }
}
