package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;

class Hrm013TimeTaskDispatchControllerTest {
    @Test
    void endpointIsExactAndRejectsRequestsThatBypassTheIngressFilter() throws Exception {
        var controller = new Hrm013TimeTaskDispatchController(
                Mockito.mock(Hrm013TimeTaskDispatchService.class));

        assertThatThrownBy(() -> controller.admit(new MockHttpServletRequest()))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);

        PostMapping mapping = Hrm013TimeTaskDispatchController.class
                .getMethod("admit", jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(PostMapping.class);
        assertThat(mapping.value()).containsExactly("/separation-task-dispatches");
        assertThat(Hrm013TimeTaskDispatchController.RECEIPT_PROOF_HEADER)
                .isEqualTo("X-DWP-Workload-Receipt-Proof");
    }

    @Test
    void exactReplayKeepsTheStoredBodyButIssuesAFreshCurrentProof() {
        long tenantId = 41;
        Instant now = Instant.parse("2026-10-27T01:00:00Z");
        UUID taskId = UUID.randomUUID();
        UUID eventId = Hrm013TaskDispatchV1.dispatchEventId(
                tenantId, taskId, 1, Hrm013TaskDispatchV1.TIME.bindingId());
        var body = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION, eventId, UUID.randomUUID(),
                        taskId, "TIME_FINALIZATION", 1, 1, now.minusSeconds(60),
                        "0".repeat(64), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "VOLUNTARY", LocalDate.of(2026, 10, 31),
                        LocalDate.of(2026, 10, 30)));
        byte[] request = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, tenantId, body);
        Instant originalAdmission = now.minusSeconds(120);
        var storedReceipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        eventId, taskId, body.payloadSha256(),
                        Hrm013TaskDispatchV1.requestBodySha256(request),
                        originalAdmission, "0".repeat(64), body.correlationId()));
        byte[] storedBody = Hrm013TaskDispatchV1.encode(storedReceipt);

        var store = mock(Hrm013TimeTaskDispatchStore.class);
        when(store.admit(any())).thenReturn(new Hrm013TimeTaskDispatchStore.Admission(
                Hrm013TimeTaskDispatchStore.AdmissionKind.EXACT_REPLAY,
                storedBody, "expired-proof", originalAdmission));
        var authority = mock(AuthHrm013ServiceAuthorityClient.class);
        var current = mock(AuthHrm013ServiceAuthorityClient.CurrentAuthority.class);
        var lease = mock(Hrm013TimeWorkloadCredentialProvider.Lease.class);
        when(current.lease()).thenReturn(lease);
        when(current.drainAdmission()).thenReturn(null);
        when(lease.expiresAt()).thenReturn(now.plusSeconds(60));
        var claims = mock(Hrm013WorkloadProofV1.ProofClaims.class);
        when(claims.tenantId()).thenReturn(tenantId);
        when(claims.tenantGrantRevision()).thenReturn(3L);
        when(claims.authorizationRevision()).thenReturn(5L);
        when(claims.grantUseMode())
                .thenReturn(Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK);
        when(claims.drainClaims()).thenReturn(null);
        when(claims.expiresAt()).thenReturn(now.plusSeconds(30));
        when(authority.resolveAdmissionOwner(claims)).thenReturn(current);
        var proof = mock(Hrm013WorkloadProofV1.VerifiedProof.class);
        when(proof.claims()).thenReturn(claims);
        when(proof.proofSha256()).thenReturn("a".repeat(64));
        var credentials = mock(Hrm013TimeWorkloadCredentialProvider.class);
        when(credentials.admissionReceiptProof(any(), any(), any(), any()))
                .thenReturn("candidate-proof", "fresh-current-proof");
        var service = new Hrm013TimeTaskDispatchService(
                store, authority, credentials, new ObjectMapper(),
                Clock.fixed(now, ZoneOffset.UTC));

        var response = service.admit(
                new Hrm013TimeTaskDispatchService.VerifiedDispatch(body, request, proof));

        assertThat(response.exactReplay()).isTrue();
        assertThat(response.exactBody()).isEqualTo(storedBody);
        assertThat(response.receiptProof()).isEqualTo("fresh-current-proof");
        assertThat(Hrm013TaskDispatchV1.decodeAdmissionReceipt(response.exactBody())
                .admittedAt()).isEqualTo(originalAdmission);
    }
}
