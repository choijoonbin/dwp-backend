package com.dwp.services.auth.approvalpolicyimpact;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PolicyImpactPurposeFilterTest {
    final PolicyImpactProtocolFixture fixture = new PolicyImpactProtocolFixture();
    final PolicyImpactAuthorityPort authority = mock(PolicyImpactAuthorityPort.class);
    final PolicyImpactReplayStore replay = mock(PolicyImpactReplayStore.class);
    final PolicyImpactAuthorityIssuer issuer = mock(PolicyImpactAuthorityIssuer.class);
    MockHttpServletRequest request() {
        var exchange = fixture.exchange(fixture.binding()); var request = new MockHttpServletRequest("POST", PolicyImpactProtocol.PATH);
        request.addHeader("X-DWP-Service-Identity", "dwp-approval-server"); request.addHeader(PolicyImpactProtocol.HEADER, exchange.token());
        request.setContentType("application/json"); request.setContent(exchange.body()); return request;
    }
    int filter(MockHttpServletRequest request, boolean enabled, AtomicInteger calls) throws Exception {
        var service = new PolicyImpactAuthorityService(fixture::verifier, authority, replay, issuer, enabled);
        var filter = new PolicyImpactSecurityConfiguration.PurposeFilter(service, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), enabled);
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, (req, res) -> calls.incrementAndGet()); return response.getStatus();
    }
    @Test void canonicalProofPassesOnlyCryptographicFilterWithoutAuthOrReplayRead() throws Exception {
        var request = request(); var calls = new AtomicInteger(); assertThat(filter(request, true, calls)).isEqualTo(200);
        assertThat(calls).hasValue(1); assertThat(request.getAttribute(PolicyImpactController.PROOF_ATTRIBUTE)).isInstanceOf(PolicyImpactProofVerifier.Verified.class);
        verifyNoInteractions(authority, replay, issuer);
    }
    @Test void defaultClosedBoundaryDoesNotReadBodyOrResolveDirectory() throws Exception {
        var request = request(); var calls = new AtomicInteger(); assertThat(filter(request, false, calls)).isEqualTo(503);
        assertThat(calls).hasValue(0); verifyNoInteractions(authority, replay, issuer);
    }
    @Test void percentMatrixHeadAndOtherMethodsAreCandidateButNeverCanonical() throws Exception {
        for (String uri : new String[]{PolicyImpactProtocol.PATH + ";x=y", PolicyImpactProtocol.PATH.replace("evaluate", "%65valuate"),
                PolicyImpactProtocol.PATH.replace("approval", "%61pproval"), PolicyImpactProtocol.PATH + "/"}) {
            assertThat(PolicyImpactSecurityConfiguration.candidate(uri)).isTrue(); var request = request(); request.setRequestURI(uri);
            var calls = new AtomicInteger(); assertThat(filter(request, true, calls)).isEqualTo(403); assertThat(calls).hasValue(0);
        }
        for (String method : new String[]{"GET", "HEAD", "PUT", "OPTIONS"}) {
            var request = request(); request.setMethod(method); assertThat(filter(request, true, new AtomicInteger())).isEqualTo(403);
        }
        verifyNoInteractions(authority, replay, issuer);
    }
    @Test void ambientGatewayHeadersCredentialsCookiesAndDuplicateDedicatedTokensAreDenied() throws Exception {
        for (String header : new String[]{"Authorization", "Cookie", "X-DWP-Tenant-ID", "X-DWP-Decision-Revision", "X-DWP-User-ID",
                "X-DWP-Approval-Workflow-Runtime-Token", "X-DWP-Identity-Sync-Token", PolicyImpactProtocol.HEADER, "X-DWP-Service-Identity"}) {
            var request = request(); request.addHeader(header, "borrowed"); var calls = new AtomicInteger();
            assertThat(filter(request, true, calls)).isEqualTo(403); assertThat(calls).hasValue(0);
        }
        verifyNoInteractions(authority, replay, issuer);
    }
    @Test void additionalQueryNonJsonAndOversizedBodyCannotReachAuthority() throws Exception {
        var query = request(); query.setQueryString("contextKey=borrowed"); assertThat(filter(query, true, new AtomicInteger())).isEqualTo(403);
        var wrong = request(); wrong.setContentType("text/plain"); assertThat(filter(wrong, true, new AtomicInteger())).isEqualTo(403);
        var large = request(); large.setContent(new byte[PolicyImpactProtocol.BODY_LIMIT + 1]); assertThat(filter(large, true, new AtomicInteger())).isEqualTo(403);
        verifyNoInteractions(authority, replay, issuer);
    }
    @Test void invalidBodyHashAndSignatureCannotReachCurrentAuth() throws Exception {
        var request = request(); request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(filter(request, true, new AtomicInteger())).isEqualTo(403); verifyNoInteractions(authority, replay, issuer);
    }
}
