package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationProofIssuerV1;
import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Per004WorkloadEndpointsTest {
    private static final String PATH = Endpoint.PREPARE.path(null);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test void noRegisteredWorkloadProvidersRejectBeforeOwnerCallWithoutEcho() throws Exception {
        Per004OwnerService owner = mock(Per004OwnerService.class);
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per004WorkloadEndpoints endpoint = new Per004WorkloadEndpoints(owner, mapper,
                empty.getBeanProvider(SigningKeyResolver.class),
                empty.getBeanProvider(TenantAdmissionVerifier.class),
                empty.getBeanProvider(ReplayStore.class));
        MockHttpServletRequest request = request("{\"ownerPayload\":{\"secret\":\"never-echo\"}}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        endpoint.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).doesNotContain("never-echo");
        verifyNoInteractions(owner);
    }

    @Test void exactSignedProofDispatchesOnceButBrowserHeaderDuplicateAndReplayFailClosed() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var resolver = (SigningKeyResolver) keyId -> new VerifiedSigningKey(pair.getPublic(),
                WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, "dwp-platform-server");
        var tenant = (TenantAdmissionVerifier) (endpoint, path, caller, id) -> id == 42;
        Set<UUID> seen = new HashSet<>();
        var replay = (ReplayStore) (id, nonce, expiresAt) -> seen.add(id) && seen.add(nonce);
        StaticListableBeanFactory providers = new StaticListableBeanFactory();
        providers.addBean("keys", resolver);
        providers.addBean("tenant", tenant);
        providers.addBean("replay", replay);
        Per004OwnerService owner = mock(Per004OwnerService.class);
        UUID configuration = UUID.randomUUID();
        String body = """
                {"tenantId":42,"configurationId":"%s","subjectPrincipalPublicId":"%s",
                 "schemaKey":"dwp.hris.performance.policy.v1","schemaVersion":1,
                 "scopeDigest":"%s","createIdempotencyKey":"per004-exact-key-1234",
                 "createRequestDigest":"%s","correlationId":"%s",
                 "ownerPayload":{"schemaVersion":1}}
                """.formatted(configuration, UUID.randomUUID(), "a".repeat(64),
                "b".repeat(64), UUID.randomUUID());
        UUID artifact = UUID.randomUUID();
        OwnerTuple tuple = new OwnerTuple(42, configuration, null, "PER",
                "dwp.hris.performance.policy.v1", 1, "ref_per_artifact_" + artifact,
                "c".repeat(64), "a".repeat(64));
        when(owner.prepare(any())).thenReturn(new OwnerReceipt(ReceiptKind.PREPARATION, tuple,
                "dwp-people-server", UUID.randomUUID(), null, "d".repeat(64),
                "SUCCEEDED", java.time.OffsetDateTime.now(), java.time.OffsetDateTime.now().plusSeconds(30), null));
        Per004WorkloadEndpoints filter = new Per004WorkloadEndpoints(owner, mapper,
                providers.getBeanProvider(SigningKeyResolver.class),
                providers.getBeanProvider(TenantAdmissionVerifier.class),
                providers.getBeanProvider(ReplayStore.class));
        String proof = HrisReferenceConfigurationProofIssuerV1.issue(mapper, Clock.systemUTC(),
                Endpoint.PREPARE, PATH, 42, body.getBytes(StandardCharsets.UTF_8),
                new HrisReferenceConfigurationProofIssuerV1.SigningKey("per004-test", pair.getPrivate(),
                        "dwp-platform-server", WorkloadAuthentication.CLIENT_CREDENTIAL_JWT));
        MockHttpServletRequest request = request(body);
        request.addHeader(SERVICE_IDENTITY_HEADER, "dwp-platform-server");
        request.addHeader(WORKLOAD_PROOF_HEADER, proof);
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(request, accepted, new MockFilterChain());
        assertThat(accepted.getStatus()).isEqualTo(200);
        verify(owner, times(1)).prepare(any());
        MockHttpServletRequest replayRequest = request(body);
        replayRequest.addHeader(SERVICE_IDENTITY_HEADER, "dwp-platform-server");
        replayRequest.addHeader(WORKLOAD_PROOF_HEADER, proof);
        MockHttpServletResponse rejectedReplay = new MockHttpServletResponse();
        filter.doFilter(replayRequest, rejectedReplay, new MockFilterChain());
        assertThat(rejectedReplay.getStatus()).isEqualTo(404);
        MockHttpServletRequest browser = request(body);
        browser.addHeader(SERVICE_IDENTITY_HEADER, "dwp-platform-server");
        browser.addHeader(WORKLOAD_PROOF_HEADER, proof);
        browser.addHeader("Cookie", "sid=browser");
        MockHttpServletResponse rejectedBrowser = new MockHttpServletResponse();
        filter.doFilter(browser, rejectedBrowser, new MockFilterChain());
        assertThat(rejectedBrowser.getStatus()).isEqualTo(404);
        verify(owner, times(1)).prepare(any());
    }

    @Test void exactFinalizeValidateAndSimulatePathsDispatchButWrongTargetFailsClosed() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        Set<UUID> seen = new HashSet<>();
        StaticListableBeanFactory providers = new StaticListableBeanFactory();
        providers.addBean("keys", (SigningKeyResolver) keyId -> new VerifiedSigningKey(
                pair.getPublic(), WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, "dwp-platform-server"));
        providers.addBean("tenant", (TenantAdmissionVerifier) (endpoint, path, caller, id) -> id == 42);
        providers.addBean("replay", (ReplayStore) (id, nonce, expiresAt) -> seen.add(id) && seen.add(nonce));
        Per004OwnerService owner = mock(Per004OwnerService.class);
        Per004WorkloadEndpoints filter = new Per004WorkloadEndpoints(owner, mapper,
                providers.getBeanProvider(SigningKeyResolver.class),
                providers.getBeanProvider(TenantAdmissionVerifier.class),
                providers.getBeanProvider(ReplayStore.class));
        UUID artifact = UUID.randomUUID();
        OwnerTuple tuple = new OwnerTuple(42, UUID.randomUUID(), 7L, "PER",
                "dwp.hris.performance.policy.v1", 1, "ref_per_artifact_" + artifact,
                "c".repeat(64), "a".repeat(64));
        UUID prior = UUID.randomUUID();
        Object[] bodies = {
                new FinalizeRequest(tuple, prior, UUID.randomUUID(), 3),
                new ValidateRequest(tuple, prior),
                new SimulateRequest(tuple, prior, "fixture-scope", "e".repeat(64)),
        };
        Endpoint[] endpoints = { Endpoint.FINALIZE, Endpoint.VALIDATE, Endpoint.SIMULATE };
        for (int i = 0; i < endpoints.length; i++) {
            Endpoint endpoint = endpoints[i];
            String path = endpoint.path(artifact);
            String body = mapper.writeValueAsString(bodies[i]);
            String proof = HrisReferenceConfigurationProofIssuerV1.issue(mapper, Clock.systemUTC(),
                    endpoint, path, 42, body.getBytes(StandardCharsets.UTF_8),
                    new HrisReferenceConfigurationProofIssuerV1.SigningKey("per004-test", pair.getPrivate(),
                            "dwp-platform-server", WorkloadAuthentication.CLIENT_CREDENTIAL_JWT));
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setContentType("application/json");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            request.addHeader(SERVICE_IDENTITY_HEADER, "dwp-platform-server");
            request.addHeader(WORKLOAD_PROOF_HEADER, proof);
            MockHttpServletResponse accepted = new MockHttpServletResponse();
            filter.doFilter(request, accepted, new MockFilterChain());
            assertThat(accepted.getStatus()).isEqualTo(200);
        }
        verify(owner).finalizeBinding(any());
        verify(owner).validate(any());
        verify(owner).simulate(any());
        String wrongPath = Endpoint.VALIDATE.path(UUID.randomUUID());
        String body = mapper.writeValueAsString(new ValidateRequest(tuple, prior));
        String proof = HrisReferenceConfigurationProofIssuerV1.issue(mapper, Clock.systemUTC(),
                Endpoint.VALIDATE, wrongPath, 42, body.getBytes(StandardCharsets.UTF_8),
                new HrisReferenceConfigurationProofIssuerV1.SigningKey("per004-test", pair.getPrivate(),
                        "dwp-platform-server", WorkloadAuthentication.CLIENT_CREDENTIAL_JWT));
        MockHttpServletRequest mismatched = new MockHttpServletRequest("POST", wrongPath);
        mismatched.setContentType("application/json");
        mismatched.setContent(body.getBytes(StandardCharsets.UTF_8));
        mismatched.addHeader(SERVICE_IDENTITY_HEADER, "dwp-platform-server");
        mismatched.addHeader(WORKLOAD_PROOF_HEADER, proof);
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(mismatched, rejected, new MockFilterChain());
        assertThat(rejected.getStatus()).isEqualTo(404);
        verify(owner, times(1)).validate(any());
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
