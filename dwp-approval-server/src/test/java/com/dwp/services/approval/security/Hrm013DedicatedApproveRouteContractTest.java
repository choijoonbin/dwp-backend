package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.api.ApprovalController;
import com.dwp.services.approval.domain.ApprovalDtos;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.util.ContentCachingRequestWrapper;

class Hrm013DedicatedApproveRouteContractTest {
    @Test
    void approveIsADedicatedFiveEvidenceHeaderRouteNotAnInternalBypass()
            throws Exception {
        var method = ApprovalController.class.getDeclaredMethod(
                "approveHrisSeparationWaiver", UUID.class,
                ApprovalDtos.DecisionRequest.class, String.class, String.class,
                String.class, String.class, Long.class);
        assertThat(method.getAnnotation(PostMapping.class).value())
                .containsExactly("/hris/separation-waiver-tasks/{taskId}/approve");
        assertThat(method.getParameterCount()).isEqualTo(7);

        var filter = new ApprovalSecurityFilter("",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules());
        var request = new MockHttpServletRequest("POST",
                "/v1/hris/separation-waiver-tasks/"
                        + UUID.randomUUID() + "/approve");
        AtomicInteger admitted = new AtomicInteger();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response,
                (ignoredRequest, ignoredResponse) -> admitted.incrementAndGet());
        assertThat(admitted).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(502);
    }

    @Test
    void approveRequiresTenantPlaneAndRejectsProviderOrSupportAuthority()
            throws Exception {
        UUID taskId = UUID.randomUUID();
        String path = "/v1/hris/separation-waiver-tasks/" + taskId + "/approve";
        var filter = new ApprovalSecurityFilter("gateway-secret",
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules());

        var missingPlane = trustedRequest(path);
        var missingPlaneResponse = new MockHttpServletResponse();
        AtomicInteger admitted = new AtomicInteger();
        filter.doFilter(missingPlane, missingPlaneResponse,
                (ignoredRequest, ignoredResponse) -> admitted.incrementAndGet());
        assertThat(ApprovalSecurityFilter.requiresTenantWorkPlane(missingPlane))
                .isTrue();
        assertThat(admitted).hasValue(0);
        assertThat(missingPlaneResponse.getStatus()).isEqualTo(503);

        var provider = trustedRequest(path);
        provider.addHeader("X-DWP-Identity-Plane", "TENANT");
        provider.addHeader(ApprovalSecurityFilter.ROLES_HEADER, "PROVIDER_ADMIN");
        var providerResponse = new MockHttpServletResponse();
        filter.doFilter(provider, providerResponse,
                (ignoredRequest, ignoredResponse) -> admitted.incrementAndGet());
        assertThat(providerResponse.getStatus()).isEqualTo(403);

        var support = trustedRequest(path);
        support.addHeader("X-DWP-Identity-Plane", "TENANT");
        support.addHeader("X-DWP-Support-Session-ID", UUID.randomUUID());
        var supportResponse = new MockHttpServletResponse();
        filter.doFilter(support, supportResponse,
                (ignoredRequest, ignoredResponse) -> admitted.incrementAndGet());
        assertThat(supportResponse.getStatus()).isEqualTo(403);
        assertThat(admitted).hasValue(0);
    }

    @Test
    void semanticallyEqualDtoBodiesCannotShareOneStepUpCommandIdentity()
            throws Exception {
        UUID taskId = UUID.randomUUID();
        String compact = "{\"decision\":\"APPROVE\",\"comment\":null,"
                + "\"expectedVersion\":3,\"quorum\":{}}";
        String reordered = "{ \"quorum\": {}, \"expectedVersion\": 3,"
                + " \"comment\": null, \"decision\": \"APPROVE\" }";

        assertThat(exactDigest(taskId, compact))
                .isNotEqualTo(exactDigest(taskId, reordered));
    }

    private String exactDigest(UUID taskId, String body) throws Exception {
        var raw = new MockHttpServletRequest("POST",
                "/v1/hris/separation-waiver-tasks/" + taskId + "/approve");
        raw.setContent(body.getBytes(StandardCharsets.UTF_8));
        var cached = new ContentCachingRequestWrapper(
                raw, ApprovalWorkflowQuorumBodyFilter.MAX_BODY);
        cached.getInputStream().readAllBytes();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("request", cached);
        var metadata = new ApprovalWorkflowQuorumCommandMetadata(
                beans.getBeanProvider(jakarta.servlet.http.HttpServletRequest.class),
                beans.getBeanProvider(ApprovalWorkflowQuorumCommandProof.class));
        return metadata.hrisSeparationApproveBodySha256(taskId);
    }

    private static MockHttpServletRequest trustedRequest(String path) {
        var request = new MockHttpServletRequest("POST", path);
        request.addHeader(ApprovalSecurityFilter.SERVICE_TOKEN_HEADER,
                "gateway-secret");
        request.addHeader(ApprovalSecurityFilter.USER_HEADER, "7");
        request.addHeader(ApprovalSecurityFilter.TENANT_HEADER, "42");
        request.addHeader(ApprovalSecurityFilter.PERMISSIONS_HEADER,
                "APP.APPROVALS:VIEW,ACTION.APPROVAL_TASK:APPROVE");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_STATE_HEADER, "000");
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "a".repeat(64));
        request.addHeader(ApprovalSecurityFilter.ROLLOUT_COHORT_HEADER,
                "baseline");
        return request;
    }
}
