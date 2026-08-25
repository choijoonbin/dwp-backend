package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.service.AppAdminPresetService;
import com.dwp.services.auth.service.AppGovernanceService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AppAdminPresetControllerTest {

    @Test
    void selfServiceEndpointsForceTheAuthenticatedActorAndExactAppResourceContract() {
        AppAdminPresetService presets = mock(AppAdminPresetService.class);
        AppGovernanceController controller = new AppGovernanceController(
                mock(AppGovernanceService.class), presets);
        Authentication member = authentication(71L);
        UUID resourceSetId = UUID.randomUUID();
        var request = new AppGovernanceDtos.CreateSelfServicePresetRequest(
                "APPROVAL_OPERATOR", resourceSetId,
                OffsetDateTime.parse("2030-12-01T00:00:00Z"),
                OffsetDateTime.parse("2030-06-01T00:00:00Z"),
                "Request the exact time-bound operations specialist package.");

        controller.selfServiceOptions(member, "9", "APP.APPROVALS");
        controller.selfServiceRequest(
                member, "9", "corr-member-1", "member-request-0001", request);

        verify(presets).selfServiceOptions(9L, 71L, "APP.APPROVALS");
        verify(presets).requestSelfService(
                9L, 71L, "corr-member-1", "member-request-0001", request);
    }

    @Test
    void activationEndpointDelegatesAuthenticatedActorAndVersionedReason() {
        AppAdminPresetService presets = mock(AppAdminPresetService.class);
        AppGovernanceController controller = new AppGovernanceController(
                mock(AppGovernanceService.class), presets);
        UUID assignmentId = UUID.randomUUID();
        var request = new AppGovernanceDtos.ActivateAppAdminPresetRequest(
                "Independent access manager activates the approved exact package.", 2L);

        controller.activatePresetAssignment(
                authentication(71L), "9", "activate-correlation-1", assignmentId, request);

        verify(presets).activate(
                9L, 71L, "activate-correlation-1", assignmentId, request);
    }

    @Test
    void selfServiceHttpSignatureHasNoSurfaceIdAndRequiresConstrainedIdempotencyKey()
            throws Exception {
        Method options = AppGovernanceController.class.getMethod(
                "selfServiceOptions", Authentication.class, String.class, String.class);
        RequestParam appResource = options.getParameters()[2].getAnnotation(RequestParam.class);
        assertThat(appResource).isNotNull();
        assertThat(appResource.required()).isTrue();
        assertThat(options.getParameters())
                .noneMatch(parameter -> parameter.getName().equals("surfaceId"));

        Method request = AppGovernanceController.class.getMethod(
                "selfServiceRequest", Authentication.class, String.class, String.class,
                String.class, AppGovernanceDtos.CreateSelfServicePresetRequest.class);
        var key = request.getParameters()[3];
        RequestHeader header = key.getAnnotation(RequestHeader.class);
        assertThat(header).isNotNull();
        assertThat(header.value()).isEqualTo("Idempotency-Key");
        assertThat(header.required()).isTrue();
        assertThat(key.getAnnotation(Size.class).min()).isEqualTo(8);
        assertThat(key.getAnnotation(Size.class).max()).isEqualTo(160);
        assertThat(key.getAnnotation(Pattern.class).regexp())
                .isEqualTo("[A-Za-z0-9][A-Za-z0-9._:-]{7,159}");
    }

    private Authentication authentication(Long userId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .issuedAt(Instant.parse("2026-08-25T03:00:00Z"))
                .expiresAt(Instant.parse("2026-08-25T04:00:00Z"))
                .claim("tenant_id", 9L)
                .claim("roles", List.of("EMPLOYEE"))
                .build();
        return new TestingAuthenticationToken(jwt, null);
    }
}
