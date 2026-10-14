package com.dwp.services.platform.security;

import com.dwp.core.common.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.OffsetDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class HcmAuditEvidenceSecurityPolicyTest {

    private final HcmAuditEvidenceSecurityPolicy policy =
            new HcmAuditEvidenceSecurityPolicy();

    @Test
    void exactElevatedTenantAuditorIntersectionIsAllowed() {
        MockHttpServletRequest request = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);

        HcmAuditEvidenceSecurityPolicy.Evaluation result = policy.evaluate(
                request, authority(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE));

        assertThat(result.allowed()).isTrue();
        assertThat(result.routeContractKey())
                .isEqualTo(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
    }

    @Test
    void productContextAloneNeverGrantsAuthority() {
        MockHttpServletRequest request = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        request.removeHeader(PlatformSecurityHeaders.PERMISSIONS);
        request.addHeader(PlatformSecurityHeaders.PERMISSIONS, "APP.HCM:VIEW");

        HcmAuditEvidenceSecurityPolicy.Evaluation result = policy.evaluate(
                request, authority(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE));

        assertThat(result.allowed()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void normalModePassesTheFullIntersectionForTheFilterToChallenge() {
        MockHttpServletRequest normal = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        normal.removeHeader(PlatformSecurityHeaders.ACTIVE_ACCESS_MODE);
        normal.addHeader(PlatformSecurityHeaders.ACTIVE_ACCESS_MODE, "NORMAL");
        assertThat(policy.evaluate(
                normal, authority(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE)).allowed())
                .isTrue();
    }

    @ParameterizedTest
    @MethodSource("conflictingPackages")
    void everySealedSodConflictFailsClosed(String conflictingPackage) {
        MockHttpServletRequest conflict = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        conflict.removeHeader(PlatformSecurityHeaders.ROLES);
        conflict.addHeader(PlatformSecurityHeaders.ROLES,
                "HRIS_AUDITOR," + conflictingPackage);
        HcmAuditEvidenceSecurityPolicy.Evaluation result = policy.evaluate(
                conflict, authority(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE));
        assertThat(result.allowed()).as(conflictingPackage).isFalse();
        assertThat(result.errorCode()).as(conflictingPackage)
                .isEqualTo(ErrorCode.SOD_CONFLICT);
    }

    @Test
    void sealedSodInventoryContainsExactlyTwentyEightConflicts() {
        assertThat(HcmAuditEvidenceSecurityPolicy.CONFLICTING_PACKAGES).hasSize(28);
    }

    @Test
    void wrongRouteDuplicateProductAndUnknownFilterAreRejected() {
        MockHttpServletRequest wrongRoute = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE);
        assertThat(policy.evaluate(
                wrongRoute, authority(HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE))
                .allowed()).isFalse();

        MockHttpServletRequest duplicate = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        duplicate.setParameter("product", "HCM", "HCM");
        assertThat(policy.exactHcmRouteCandidate(duplicate)).isFalse();

        MockHttpServletRequest hiddenOracle = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        hiddenOracle.setParameter("actor", "someone");
        HcmAuditEvidenceSecurityPolicy.Evaluation result = policy.evaluate(
                hiddenOracle, authority(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE));
        assertThat(result.errorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);

        MockHttpServletRequest encoded = validRequest(
                "/v1/admin/audit-control/events",
                HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
        encoded.setQueryString("product=%48CM");
        assertThat(policy.exactHcmRouteCandidate(encoded)).isFalse();
    }

    @Test
    void providerSupportAndDelegatedEvidenceFailClosedEvenWhenBlank() {
        for (String header : new String[]{
                PlatformSecurityHeaders.SUPPORT_SESSION,
                PlatformSecurityHeaders.ACTOR_TENANT,
                "X-DWP-Provider-Tenant-ID"}) {
            MockHttpServletRequest request = validRequest(
                    "/v1/admin/audit-control/events",
                    HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE);
            request.addHeader(header, "");

            HcmAuditEvidenceSecurityPolicy.Evaluation result = policy.evaluate(
                    request, authority(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE));

            assertThat(result.allowed()).as(header).isFalse();
            assertThat(result.errorCode()).as(header).isEqualTo(ErrorCode.FORBIDDEN);
        }
    }

    @Test
    void auditorEvidenceIsRecognizedForGenericBypassFence() {
        MockHttpServletRequest role = new MockHttpServletRequest();
        role.addHeader(PlatformSecurityHeaders.ROLES, "HRIS_AUDITOR");
        assertThat(policy.carriesHrisAuditorEvidence(role)).isTrue();

        MockHttpServletRequest duty = new MockHttpServletRequest();
        duty.addHeader(PlatformSecurityHeaders.RESOURCE_ROLES,
                "HRIS_DUTY_AUDITOR@RS_OTHER_VALID_RESOURCE");
        assertThat(policy.carriesHrisAuditorEvidence(duty)).isTrue();

        MockHttpServletRequest malformedDuty = new MockHttpServletRequest();
        malformedDuty.addHeader(PlatformSecurityHeaders.RESOURCE_ROLES,
                "HRIS_DUTY_AUDITOR@invalid/resource");
        assertThat(policy.carriesHrisAuditorEvidence(malformedDuty)).isFalse();
    }

    @Test
    void versionSevenDetailAndSubjectIdentifiersAreAccepted() {
        String eventId = "018f47a2-7b3c-7def-8abc-0123456789ab";
        MockHttpServletRequest request = validRequest(
                "/v1/admin/audit-control/events/" + eventId,
                HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE);
        request.removeHeader(PlatformSecurityHeaders.SUBJECT_PRINCIPAL_PUBLIC_ID);
        request.addHeader(PlatformSecurityHeaders.SUBJECT_PRINCIPAL_PUBLIC_ID,
                "018f47a2-7b3c-7def-8abc-0123456789ac");

        assertThat(policy.exactHcmRouteCandidate(request)).isTrue();
        assertThat(policy.evaluate(
                request, authority(HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE)).allowed())
                .isTrue();
    }

    private MockHttpServletRequest validRequest(String path, String route) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setParameter("product", "HCM");
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader(PlatformSecurityHeaders.PERMISSIONS,
                "APP.HCM:VIEW,ADMIN.AUDIT_VIEW:VIEW");
        request.addHeader(PlatformSecurityHeaders.ROLES, "HRIS_AUDITOR");
        request.addHeader(PlatformSecurityHeaders.RESOURCE_ROLES,
                "HRIS_DUTY_AUDITOR@RS_HCM_CONFIG");
        request.addHeader(PlatformSecurityHeaders.PURPOSE,
                HcmAuditEvidenceSecurityPolicy.PURPOSE);
        request.addHeader(PlatformSecurityHeaders.ACTIVE_ACCESS_MODE, "ELEVATED");
        request.addHeader(PlatformSecurityHeaders.SUBJECT_PRINCIPAL_PUBLIC_ID,
                "10000000-0000-4000-8000-000000000001");
        request.addHeader(PlatformSecurityHeaders.FIELD_POLICY_REVISION, "17");
        request.addHeader(PlatformSecurityHeaders.AUTHORIZATION_REVISION, "29");
        request.addHeader(PlatformSecurityHeaders.ROUTE_CONTRACT, route);
        return request;
    }

    private PlatformProductAuthorizationSupport.TrustedAuthorityEvidence authority(
            String route) {
        return new PlatformProductAuthorizationSupport.TrustedAuthorityEvidence(
                route,
                "psr-" + "a".repeat(64),
                OffsetDateTime.now().plusMinutes(5),
                HcmAuditEvidenceSecurityPolicy.CONTEXT,
                "hcm-scope-" + "b".repeat(40));
    }

    private static Stream<String> conflictingPackages() {
        return HcmAuditEvidenceSecurityPolicy.CONFLICTING_PACKAGES.stream();
    }
}
