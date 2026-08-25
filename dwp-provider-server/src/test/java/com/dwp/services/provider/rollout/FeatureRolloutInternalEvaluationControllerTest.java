package com.dwp.services.provider.rollout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureRolloutInternalEvaluationControllerTest {

    @Test
    void evaluatesWithoutAnOperatorPermissionThroughThePurposeSpecificService() {
        FeatureRolloutInternalEvaluationService service =
                mock(FeatureRolloutInternalEvaluationService.class);
        FeatureRolloutInternalEvaluationController controller =
                new FeatureRolloutInternalEvaluationController(service);
        var request = new FeatureRolloutDtos.InternalEvaluationRequest(
                41L,
                "ux.product-surfaces.approvals.v1");

        controller.evaluate(request);

        verify(service).evaluate(request);
    }

    @Test
    void exactInternalPathRequiresBothTokenAndGatewayIdentity() throws Exception {
        FeatureRolloutInternalEvaluationSecurityFilter filter =
                new FeatureRolloutInternalEvaluationSecurityFilter(
                        "rollout-secret", new ObjectMapper().findAndRegisterModules());
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", FeatureRolloutInternalEvaluationSecurityFilter.PATH);
        request.addHeader("X-DWP-Service-Token", "rollout-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        MockHttpServletRequest trusted = new MockHttpServletRequest(
                "POST", FeatureRolloutInternalEvaluationSecurityFilter.PATH);
        trusted.addHeader("X-DWP-Service-Token", "rollout-secret");
        trusted.addHeader("X-DWP-Service-Identity", "dwp-gateway");
        MockHttpServletResponse trustedResponse = new MockHttpServletResponse();
        MockFilterChain trustedChain = new MockFilterChain();

        filter.doFilter(trusted, trustedResponse, trustedChain);

        assertThat(trustedChain.getRequest()).isSameAs(trusted);
    }

    @Test
    void mapsExposureToAStableServerCohortAndOpaqueRevision() {
        FeatureRolloutService rolloutService = mock(FeatureRolloutService.class);
        FeatureRolloutDecisionOutboxRepository outbox =
                mock(FeatureRolloutDecisionOutboxRepository.class);
        FeatureRolloutInternalEvaluationService service =
                new FeatureRolloutInternalEvaluationService(rolloutService, outbox);
        Long authTenantId = 41L;
        String flag = "ux.product-surfaces.services.v1";
        when(rolloutService.evaluateProductSurfaceFlag(flag, authTenantId)).thenReturn(
                new FeatureRolloutDtos.Evaluation(
                        flag, UUID.randomUUID(), "opaque-tenant",
                        new ObjectMapper().valueToTree(true),
                        "ROLLOUT_MATCH", UUID.randomUUID(), 4, BigDecimal.valueOf(25),
                        1024, false, Instant.parse("2026-08-24T00:00:00Z")));
        when(outbox.revision(flag)).thenReturn(13L);

        FeatureRolloutDtos.InternalEvaluation result = service.evaluate(
                new FeatureRolloutDtos.InternalEvaluationRequest(authTenantId, flag));

        assertThat(result.enabled()).isTrue();
        assertThat(result.cohort()).isEqualTo("eligible-25");
        assertThat(result.opaqueRevision()).isEqualTo("rev-00000000000000000013");
    }
}
