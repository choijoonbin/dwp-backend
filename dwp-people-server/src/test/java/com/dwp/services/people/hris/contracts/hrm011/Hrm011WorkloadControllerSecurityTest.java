package com.dwp.services.people.hris.contracts.hrm011;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class Hrm011WorkloadControllerSecurityTest {

    @Test
    void rawHeadersWithoutTypedContextNeverReachTheIngress() {
        Hrm011WorkloadIngress ingress = mock(Hrm011WorkloadIngress.class);
        Hrm011WorkloadController controller = new Hrm011WorkloadController(ingress);
        MockHttpServletRequest request = request(
                Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION);
        request.addHeader("Authorization", "Bearer raw-token");
        request.addHeader("X-Correlation-ID",
                "10000000-0000-4000-8000-000000000002");
        request.addHeader("X-DWP-Workload-SPIFFE-ID",
                "spiffe://dwp/service/dwp-payroll-server");
        request.addHeader("X-DWP-TLS-Protocol", "TLSv1.3");
        request.addHeader("X-Tenant-ID", "71");

        assertThatThrownBy(() -> controller.compensation(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Verified HRM-011 workload context is required.");
        verifyNoInteractions(ingress);
    }

    @Test
    void exactTypedContextsArePassedUnchangedToTheirBoundIngressMethods() {
        Hrm011WorkloadIngress ingress = mock(Hrm011WorkloadIngress.class);
        Hrm011WorkloadController controller = new Hrm011WorkloadController(ingress);

        MockHttpServletRequest compensation = request(
                Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION);
        var compensationVerified = verified(
                compensation,
                Hrm011WorkloadIngressFilter.COMPENSATION_BINDING,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION);
        Hrm011WorkloadRequestContext.set(compensation, compensationVerified);

        controller.compensation(compensation);

        verify(ingress).compensationSnapshots(compensationVerified);

        MockHttpServletRequest payroll = request(
                Hrm011WorkloadIngressFilter.PAYROLL_FACTS_PATH,
                Hrm011WorkloadIngress.SVC005_PURPOSE,
                "TAX_IDENTITY_V1");
        var payrollVerified = verified(
                payroll,
                Hrm011WorkloadIngressFilter.PAYROLL_FACTS_BINDING,
                Hrm011WorkloadIngress.SVC005_PURPOSE,
                "TAX_IDENTITY_V1");
        Hrm011WorkloadRequestContext.set(payroll, payrollVerified);

        controller.payrollFacts(payroll);

        verify(ingress).payrollWorkerFacts(payrollVerified);
    }

    @Test
    void queryTamperingAndCrossBindingInvalidateAnExistingTypedContext() {
        Hrm011WorkloadIngress ingress = mock(Hrm011WorkloadIngress.class);
        Hrm011WorkloadController controller = new Hrm011WorkloadController(ingress);
        MockHttpServletRequest request = request(
                Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION);
        var verified = verified(
                request,
                Hrm011WorkloadIngressFilter.COMPENSATION_BINDING,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION);
        Hrm011WorkloadRequestContext.set(request, verified);
        request.addParameter("limit", "101");

        assertThatThrownBy(() -> controller.compensation(request))
                .isInstanceOf(IllegalStateException.class);

        MockHttpServletRequest cross = request(
                Hrm011WorkloadIngressFilter.COMPENSATION_PATH,
                Hrm011WorkloadIngress.SVC003_PURPOSE,
                Hrm011WorkloadIngress.COMPENSATION);
        var payrollContext = new Hrm011WorkloadRequestContext.VerifiedRequest(
                "GET", Hrm011WorkloadIngressFilter.PAYROLL_FACTS_PATH,
                verified.queryFingerprint(),
                Hrm011WorkloadIngressFilter.PAYROLL_FACTS_BINDING,
                verified.attemptId(), verified.correlationId(),
                verified.tenantGrantRevision(), verified.trusted(),
                verified.snapshotRequest());
        Hrm011WorkloadRequestContext.set(cross, payrollContext);

        assertThatThrownBy(() -> controller.compensation(cross))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ingress);
    }

    private MockHttpServletRequest request(
            String path, String purpose, String projection) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("X-Correlation-ID",
                "10000000-0000-4000-8000-000000000002");
        request.addParameter("purposeCode", purpose);
        request.addParameter("asOf", "2026-10-19T02:00:00Z");
        request.addParameter("projection", projection);
        request.addParameter("limit", "100");
        return request;
    }

    private Hrm011WorkloadRequestContext.VerifiedRequest verified(
            MockHttpServletRequest servletRequest,
            String binding,
            String purpose,
            String projection) {
        var snapshotRequest = new Hrm011WorkloadIngress.SnapshotRequest(
                purpose, Instant.parse("2026-10-19T02:00:00Z"),
                projection, 100, null);
        return new Hrm011WorkloadRequestContext.VerifiedRequest(
                "GET", servletRequest.getRequestURI(),
                fingerprint(servletRequest.getRequestURI(), snapshotRequest), binding,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000002"), 3,
                new Hrm011WorkloadIngress.TrustedWorkload(
                        71L, Hrm011WorkloadIngress.PAYROLL_CALLER,
                        Hrm011WorkloadIngress.AUDIENCE, purpose,
                        true, true, true, true, 9),
                snapshotRequest);
    }

    private String fingerprint(
            String path, Hrm011WorkloadIngress.SnapshotRequest request) {
        String canonical = "GET\n" + path
                + "\npurposeCode=" + request.purposeCode()
                + "\nasOf=" + request.asOf()
                + "\nprojection=" + request.projection()
                + "\nlimit=" + request.limit()
                + "\ncursor=<null>";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
