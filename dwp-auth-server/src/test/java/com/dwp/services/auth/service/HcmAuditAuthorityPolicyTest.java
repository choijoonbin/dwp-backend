package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HcmAuditAuthorityPolicyTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void exactPackagePermissionDutyAndProductRootScopeAreAnIntersection() {
        assertThat(HcmAuditAuthorityPolicy.evaluate(identity(
                Set.of("APP.HCM:VIEW", "ADMIN.AUDIT_VIEW:VIEW"),
                Set.of("HRIS_AUDITOR"), exactDuty())).outcome())
                .isEqualTo(HcmAuditAuthorityPolicy.Outcome.ALLOWED);
        assertThat(HcmAuditAuthorityPolicy.evaluate(identity(
                Set.of("ADMIN.AUDIT_VIEW:VIEW"),
                Set.of("HRIS_AUDITOR"), exactDuty())).outcome())
                .isEqualTo(HcmAuditAuthorityPolicy.Outcome.APP_ENTITLEMENT_REQUIRED);
        assertThat(HcmAuditAuthorityPolicy.evaluate(identity(
                Set.of("APP.HCM:VIEW", "ADMIN.AUDIT_VIEW:VIEW"),
                Set.of(), exactDuty())).outcome())
                .isEqualTo(HcmAuditAuthorityPolicy.Outcome.CAPABILITY_REQUIRED);
        assertThat(HcmAuditAuthorityPolicy.evaluate(identity(
                Set.of("APP.HCM:VIEW", "ADMIN.AUDIT_VIEW:VIEW"),
                Set.of("HRIS_AUDITOR"), role(
                        "HRIS_DUTY_AUDITOR", "APP.HCM", "RS_HCM_OTHER"))).outcome())
                .isEqualTo(HcmAuditAuthorityPolicy.Outcome.RESPONSIBILITY_REQUIRED);
    }

    @Test
    void everySealedConflictingPackageBlocksTheAuditor() {
        assertThat(HcmAuditAuthorityPolicy.CONFLICTING_PACKAGES).hasSize(28);
        for (String conflicting : HcmAuditAuthorityPolicy.CONFLICTING_PACKAGES) {
            var result = HcmAuditAuthorityPolicy.evaluate(identity(
                    Set.of("APP.HCM:VIEW", "ADMIN.AUDIT_VIEW:VIEW"),
                    Set.of("HRIS_AUDITOR", conflicting), exactDuty()));
            assertThat(result.outcome()).as(conflicting)
                    .isEqualTo(HcmAuditAuthorityPolicy.Outcome.SOD_CONFLICT);
        }
    }

    @Test
    void onlyTheTwoExactV42RoutesCanUseTheReadChallengeException() throws Exception {
        ProductAuthorizationContractDtos.BundleContract contract = json.readValue(
                getClass().getResourceAsStream(
                        "/product-authorization/product-surfaces-v1.bundle-v42.generated.json"),
                ProductAuthorizationContractDtos.BundleContract.class);
        var capability = contract.capabilities().stream()
                .filter(value -> HcmAuditAuthorityPolicy.CAPABILITY.equals(value.contractKey()))
                .findFirst().orElseThrow();
        assertThat(contract.routes().stream()
                .filter(route -> HcmAuditAuthorityPolicy.isExactReadRoute(route, capability))
                .map(ProductAuthorizationContractDtos.GovernedRoute::routeContractKey))
                .containsExactlyInAnyOrderElementsOf(HcmAuditAuthorityPolicy.ROUTES);
        assertThat(contract.routes().stream()
                .filter(route -> !HcmAuditAuthorityPolicy.ROUTES.contains(route.routeContractKey()))
                .noneMatch(route -> HcmAuditAuthorityPolicy.isExactReadRoute(route, capability)))
                .isTrue();
        for (var route : contract.routes()) {
            ProductAuthorizationGateTopologyValidator.validateRoute(route,
                    contract.capabilities().stream().collect(java.util.stream.Collectors.toMap(
                            ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                            value -> value)), contract.version());
        }
    }

    private ProductAuthorizationIdentityEvidenceService.IdentityEvidence identity(
            Set<String> permissions,
            Set<String> roles,
            AppGovernanceDtos.ResourceRole responsibility) {
        return new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                permissions, roles, List.of(responsibility), List.of(), "auth-v42-test");
    }

    private AppGovernanceDtos.ResourceRole exactDuty() {
        return role("HRIS_DUTY_AUDITOR", "APP.HCM", "RS_HCM_CONFIG");
    }

    private AppGovernanceDtos.ResourceRole role(
            String responsibility,
            String resource,
            String resourceSet) {
        return new AppGovernanceDtos.ResourceRole(
                responsibility, "APP", resource,
                UUID.nameUUIDFromBytes(resourceSet.getBytes(StandardCharsets.UTF_8)),
                resourceSet, null);
    }
}
