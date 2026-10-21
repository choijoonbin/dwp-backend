package com.dwp.services.people.hris.contracts.tim003;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TimeAbsenceWorkforceSnapshotTenantPurposeFieldNegativeTest {

    private static final UUID RELATIONSHIP =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID JTI =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID NONCE =
            UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void defaultOffReturnsEmpty503BeforeProofAndAttemptsSanitizedAudit() throws Exception {
        AtomicInteger proofCalls = new AtomicInteger();
        List<TimeAbsenceOwnerContract.IngressDenialEvidence> denials = new ArrayList<>();
        TimeAbsenceOwnerIngressFilter filter = new TimeAbsenceOwnerIngressFilter(
                false, (request, ownerRequest, path, fingerprint) -> {
                    proofCalls.incrementAndGet();
                    throw new AssertionError("proof verifier must not run while default OFF");
                }, evidence -> {
                    denials.add(evidence);
                    return false;
                });
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(proofCalls).hasValue(0);
        assertThat(denials).singleElement().satisfies(evidence -> {
            assertThat(evidence.reason()).isEqualTo(
                    TimeAbsenceOwnerContract.IngressDenialReason.DEFAULT_OFF);
            assertThat(evidence.status()).isEqualTo(503);
            assertThat(evidence.bindingId()).isEqualTo(TimeAbsenceOwnerContract.BINDING_ID);
            assertThat(evidence.routeTemplate())
                    .isEqualTo(TimeAbsenceOwnerContract.ROUTE_TEMPLATE);
            assertThat(evidence.presentedCorrelationId()).isEqualTo(JTI);
        });
        assertThat(Arrays.stream(TimeAbsenceOwnerContract.IngressDenialEvidence.class
                        .getRecordComponents()).map(component -> component.getName()))
                .containsExactly("bindingId", "routeTemplate", "reason", "status",
                        "presentedCorrelationId");
    }

    @Test
    void exactVerifiedContextIsDelegatedAndThenCleared() throws Exception {
        AtomicBoolean delegated = new AtomicBoolean();
        TimeAbsenceOwnerIngressFilter filter = new TimeAbsenceOwnerIngressFilter(
                true, (servletRequest, ownerRequest, path, fingerprint) -> verified(
                        ownerRequest, path, fingerprint));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            assertThat(TimeAbsenceOwnerRequestContext.isVerifiedRequest(
                    (MockHttpServletRequest) servletRequest)).isTrue();
            delegated.set(true);
        });

        assertThat(delegated).isTrue();
        assertThat(TimeAbsenceOwnerRequestContext.isVerifiedRequest(request)).isFalse();
    }

    @Test
    void rejectsUnsignedTenantOverrideAndNoncanonicalQueryBeforeProof() throws Exception {
        AtomicInteger proofCalls = new AtomicInteger();
        List<TimeAbsenceOwnerContract.IngressDenialEvidence> denials = new ArrayList<>();
        TimeAbsenceOwnerIngressFilter filter = new TimeAbsenceOwnerIngressFilter(
                true, (request, ownerRequest, path, fingerprint) -> {
                    proofCalls.incrementAndGet();
                    return verified(ownerRequest, path, fingerprint);
                }, evidence -> {
                    denials.add(evidence);
                    return true;
                });
        MockHttpServletRequest tenantOverride = request();
        tenantOverride.addHeader("X-Tenant-ID", "71");
        MockHttpServletResponse tenantResponse = new MockHttpServletResponse();

        filter.doFilter(tenantOverride, tenantResponse, new MockFilterChain());

        MockHttpServletRequest encoded = request();
        encoded.setQueryString(
                "minimumRelationshipRevision=07&effectiveOn=2026-10-21");
        encoded.setParameter("minimumRelationshipRevision", "07");
        MockHttpServletResponse encodedResponse = new MockHttpServletResponse();
        filter.doFilter(encoded, encodedResponse, new MockFilterChain());

        assertThat(tenantResponse.getStatus()).isEqualTo(400);
        assertThat(encodedResponse.getStatus()).isEqualTo(400);
        assertThat(proofCalls).hasValue(0);
        assertThat(denials).extracting(TimeAbsenceOwnerContract.IngressDenialEvidence::reason)
                .containsExactly(
                        TimeAbsenceOwnerContract.IngressDenialReason
                                .HEADER_OR_BODY_REJECTED,
                        TimeAbsenceOwnerContract.IngressDenialReason.QUERY_INVALID);
        assertThat(denials).extracting(
                        TimeAbsenceOwnerContract.IngressDenialEvidence::presentedCorrelationId)
                .containsOnly(JTI);
    }

    @Test
    void methodPathAndProofDenialsAttemptSanitizedAuditAndNeverDelegate() throws Exception {
        AtomicInteger delegated = new AtomicInteger();
        List<TimeAbsenceOwnerContract.IngressDenialEvidence> denials = new ArrayList<>();
        TimeAbsenceOwnerIngressFilter filter = new TimeAbsenceOwnerIngressFilter(
                true, (request, ownerRequest, path, fingerprint) -> {
                    throw TimeAbsenceOwnerContract.Failure.invalidProof();
                }, evidence -> {
                    denials.add(evidence);
                    return true;
                });

        MockHttpServletRequest method = request();
        method.setMethod("POST");
        filter.doFilter(method, new MockHttpServletResponse(),
                (request, response) -> delegated.incrementAndGet());

        MockHttpServletRequest path = new MockHttpServletRequest(
                "GET", TimeAbsenceOwnerContract.PATH_PREFIX + "NOT-A-UUID");
        filter.doFilter(path, new MockHttpServletResponse(),
                (request, response) -> delegated.incrementAndGet());

        filter.doFilter(request(), new MockHttpServletResponse(),
                (request, response) -> delegated.incrementAndGet());

        MockHttpServletRequest invalidCorrelation = request();
        invalidCorrelation.removeHeader("X-Correlation-ID");
        invalidCorrelation.addHeader("X-Correlation-ID", "not-a-uuid");
        filter.doFilter(invalidCorrelation, new MockHttpServletResponse(),
                (request, response) -> delegated.incrementAndGet());

        MockHttpServletRequest repeatedCorrelation = request();
        repeatedCorrelation.addHeader("X-Correlation-ID",
                "60000000-0000-4000-8000-000000000001");
        filter.doFilter(repeatedCorrelation, new MockHttpServletResponse(),
                (request, response) -> delegated.incrementAndGet());

        assertThat(delegated).hasValue(0);
        assertThat(denials).extracting(TimeAbsenceOwnerContract.IngressDenialEvidence::reason)
                .containsExactly(
                        TimeAbsenceOwnerContract.IngressDenialReason.METHOD_NOT_ALLOWED,
                        TimeAbsenceOwnerContract.IngressDenialReason.PATH_INVALID,
                        TimeAbsenceOwnerContract.IngressDenialReason.PROOF_REJECTED,
                        TimeAbsenceOwnerContract.IngressDenialReason.PROOF_REJECTED,
                        TimeAbsenceOwnerContract.IngressDenialReason.PROOF_REJECTED);
        assertThat(denials).extracting(
                        TimeAbsenceOwnerContract.IngressDenialEvidence::presentedCorrelationId)
                .containsExactly(JTI, null, JTI, null, null);
    }

    private static MockHttpServletRequest request() {
        String path = TimeAbsenceOwnerContract.PATH_PREFIX + RELATIONSHIP;
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setSecure(true);
        request.setQueryString(
                "minimumRelationshipRevision=7&effectiveOn=2026-10-21");
        request.setParameter("minimumRelationshipRevision", "7");
        request.setParameter("effectiveOn", "2026-10-21");
        request.addHeader("Authorization", "Bearer proof");
        request.addHeader("X-Correlation-ID", JTI.toString());
        return request;
    }

    private static TimeAbsenceOwnerContract.VerifiedRequest verified(
            TimeAbsenceOwnerContract.Request request,
            String path,
            String fingerprint) {
        Instant issuedAt = Instant.parse("2026-10-20T15:00:00Z");
        return new TimeAbsenceOwnerContract.VerifiedRequest(
                "GET", path, fingerprint, TimeAbsenceOwnerContract.BINDING_ID,
                JTI, NONCE, JTI, issuedAt, issuedAt.plusSeconds(30), 71L,
                TimeAbsenceOwnerContract.CALLER_SPIFFE,
                TimeAbsenceOwnerContract.AUDIENCE, "time-key-2026-10",
                3L, "Asia/Seoul", TimeAbsenceOwnerContract.PURPOSE,
                TimeAbsenceOwnerContract.PROJECTION, 4L, 5L, request);
    }
}
