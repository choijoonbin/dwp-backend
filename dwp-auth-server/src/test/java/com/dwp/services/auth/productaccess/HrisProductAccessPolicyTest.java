package com.dwp.services.auth.productaccess;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HrisProductAccessPolicyTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T03:00:00Z");
    private final HrisProductAccessPolicy policy = new HrisProductAccessPolicy();

    @Test
    void deniesEveryGrantWhenTheHcmApplicationEntitlementIsMissing() {
        var result = evaluate(Set.of("DATA.WORKFORCE:VIEW_TEAM"), List.of());

        assertThat(result.state()).isEqualTo(HrisProductAccessDtos.AccessState.DENIED);
        assertThat(result.reasonCode()).isEqualTo("APP_ENTITLEMENT_MISSING");
        assertThat(result.grants()).isEmpty();
    }

    @Test
    void appEntitlementAllowsTheShellButDoesNotInventDataGrants() {
        var result = evaluate(Set.of("APP.HCM:VIEW"), List.of());

        assertThat(result.state()).isEqualTo(HrisProductAccessDtos.AccessState.ALLOWED);
        assertThat(result.roleGroups()).containsExactly(HrisProductAccessDtos.RoleGroup.EMPLOYEE);
        assertThat(result.grants())
                .noneMatch(grant -> "DATA".equals(grant.resourceType()));
        assertThat(result.authority().commandAuthorizationReusable()).isFalse();
    }

    @Test
    void acceptsCanonicalHcmReadActionsAndTheHrisAliasForAppEntry() {
        for (String permission : List.of(
                "APP.HCM:VIEW", "APP.HCM:MANAGE",
                "APP.HRIS:VIEW", "APP.HRIS:MANAGE")) {
            assertThat(evaluate(Set.of(permission), List.of()).state())
                    .as(permission)
                    .isEqualTo(HrisProductAccessDtos.AccessState.ALLOWED);
        }
        for (String permission : List.of("APP.HCM:USE", "APP.HCM:LAUNCH")) {
            assertThat(evaluate(Set.of(permission), List.of()).state())
                    .as(permission)
                    .isEqualTo(HrisProductAccessDtos.AccessState.DENIED);
        }
    }

    @Test
    void manageOnlyAppEntryDoesNotRejectASettingsAdministrator() {
        var result = evaluate(Set.of(
                "APP.HRIS:MANAGE", "HCM.CONFIGURATION_WORKBENCH:UPDATE"), List.of());

        assertThat(result.state()).isEqualTo(HrisProductAccessDtos.AccessState.ALLOWED);
        assertThat(result.roleGroups())
                .contains(HrisProductAccessDtos.RoleGroup.CONFIGURATION_ADMIN);
        assertThat(result.authority().configurationAuthority()).isTrue();
    }

    @Test
    void appEntryDenyWinsAcrossAcceptedActionsAndAliases() {
        var actionConflict = evaluateWithDenies(
                Set.of("APP.HCM:MANAGE"), Set.of("APP.HCM:VIEW"));
        var aliasConflict = evaluateWithDenies(
                Set.of("APP.HCM:VIEW"), Set.of("APP.HRIS:MANAGE"));
        var aliasPositive = evaluateWithDenies(
                Set.of("APP.HRIS:VIEW", "APP.HCM:MANAGE"), Set.of("APP.HRIS:USE"));

        assertThat(actionConflict.state()).isEqualTo(HrisProductAccessDtos.AccessState.DENIED);
        assertThat(aliasConflict.state()).isEqualTo(HrisProductAccessDtos.AccessState.DENIED);
        assertThat(aliasPositive.state()).isEqualTo(HrisProductAccessDtos.AccessState.ALLOWED);
    }

    @Test
    void explicitSelfPermissionsProjectOnlyTheirDeclaredDataResources() {
        var result = evaluate(Set.of(
                "APP.HCM:VIEW", "DATA.WORKFORCE:VIEW_SELF", "DATA.HR_TIME:VIEW"), List.of());

        assertThat(result.grants())
                .filteredOn(grant -> "DATA".equals(grant.resourceType()))
                .extracting(HrisProductAccessDtos.Grant::resourceKey)
                .containsExactlyInAnyOrder("DATA.WORKFORCE", "DATA.HR_TIME");
        assertThat(result.grants())
                .filteredOn(grant -> "DATA".equals(grant.resourceType()))
                .allSatisfy(grant -> {
                    assertThat(grant.action()).isEqualTo("VIEW");
                    assertThat(grant.scopeType()).isEqualTo("SELF");
                    assertThat(grant.scopeKey()).isEqualTo("11");
                });
    }

    @Test
    void explicitDataDenyOverridesAnExplicitSelfGrant() {
        var result = policy.evaluate(
                7L, 11L,
                new HrisProductAccessPolicy.Evidence(
                        Set.of("APP.HCM:VIEW", "DATA.HR_PAY:VIEW_SELF"),
                        Set.of("DATA.HR_PAY:VIEW"), List.of()), NOW);

        assertThat(result.grants())
                .filteredOn(grant -> "DATA".equals(grant.resourceType()))
                .extracting(HrisProductAccessDtos.Grant::resourceKey)
                .doesNotContain("DATA.HR_PAY");
    }

    @Test
    void homeDataDenyWinsAcrossViewAndManageCandidates() {
        var manageDeniedByView = evaluateWithDenies(
                Set.of("APP.HCM:VIEW", "DATA.WORKFORCE:MANAGE"),
                Set.of("DATA.WORKFORCE:VIEW"));
        var viewDeniedByManage = evaluateWithDenies(
                Set.of("APP.HCM:VIEW", "DATA.WORKFORCE:VIEW"),
                Set.of("DATA.WORKFORCE:MANAGE"));

        assertThat(manageDeniedByView.state())
                .isEqualTo(HrisProductAccessDtos.AccessState.ALLOWED);
        assertThat(manageDeniedByView.grants()).noneMatch(grant ->
                "DATA.WORKFORCE".equals(grant.resourceKey()));
        assertThat(manageDeniedByView.roleGroups())
                .doesNotContain(HrisProductAccessDtos.RoleGroup.OPERATIONS);
        assertThat(viewDeniedByManage.grants()).noneMatch(grant ->
                "DATA.WORKFORCE".equals(grant.resourceKey()));
    }

    @Test
    void managerDecisionEvidenceCannotInventScopeOrCancelASeparateHomeReadRequirement() {
        var approvalOnly = evaluate(Set.of(
                "APP.HCM:VIEW", "DATA.HR_TIME:APPROVE"), List.of());
        var homeRead = evaluateWithDenies(
                Set.of("APP.HCM:VIEW", "DATA.HR_TIME:VIEW"),
                Set.of("DATA.HR_TIME:APPROVE"));

        assertThat(approvalOnly.grants()).noneMatch(grant ->
                "DATA.HR_TIME".equals(grant.resourceKey()));
        assertThat(approvalOnly.roleGroups())
                .doesNotContain(HrisProductAccessDtos.RoleGroup.MANAGER);
        assertThat(homeRead.grants()).anySatisfy(grant -> {
            assertThat(grant.action()).isEqualTo("VIEW");
            assertThat(grant.resourceKey()).isEqualTo("DATA.HR_TIME");
            assertThat(grant.scopeType()).isEqualTo("SELF");
        });
    }

    @Test
    void customRolesWorkThroughEffectiveGrantVocabularyWithoutReservedRoleNames() {
        var result = evaluate(Set.of(
                "APP.HCM:VIEW",
                "DATA.WORKFORCE:VIEW_TEAM",
                "DATA.HR_TIME:VIEW_TENANT",
                "HCM.CONFIGURATION_WORKBENCH:UPDATE"), List.of());

        assertThat(result.roleGroups()).containsExactly(
                HrisProductAccessDtos.RoleGroup.EMPLOYEE,
                HrisProductAccessDtos.RoleGroup.MANAGER,
                HrisProductAccessDtos.RoleGroup.OPERATIONS,
                HrisProductAccessDtos.RoleGroup.CONFIGURATION_ADMIN);
        assertThat(result.grants()).anySatisfy(grant -> {
            assertThat(grant.resourceKey()).isEqualTo("DATA.WORKFORCE");
            assertThat(grant.scopeType()).isEqualTo("TEAM");
        });
        assertThat(result.grants()).noneMatch(grant ->
                grant.action().equals("PUBLISH"));
    }

    @Test
    void missingConfigurationGrantDoesNotCreateMutationAuthority() {
        var result = evaluate(Set.of(
                "APP.HCM:VIEW", "HCM.CONFIGURATION_WORKBENCH:VIEW"), List.of());

        assertThat(result.roleGroups())
                .doesNotContain(HrisProductAccessDtos.RoleGroup.CONFIGURATION_ADMIN);
        assertThat(result.grants()).noneMatch(HrisProductAccessDtos.Grant::mutable);
    }

    @Test
    void ignoresCrossProductAndExpiredResponsibilities() {
        var result = evaluate(Set.of("APP.HRIS:VIEW"), List.of(
                scope("APP_OWNER", "APP.CALENDAR", null),
                scope("APP_OWNER", "APP.HCM", NOW.minusSeconds(1))));

        assertThat(result.authority().accessGovernanceAuthority()).isFalse();
        assertThat(result.grants()).noneMatch(grant ->
                "APP_RESOURCE_SET".equals(grant.resourceType()));
    }

    @Test
    void failsClosedWhenMutationAndAuditAuthorityCoexist() {
        var result = evaluate(Set.of(
                "APP.HCM:VIEW",
                "HCM.CONFIGURATION_WORKBENCH:UPDATE",
                "HCM.CONFIGURATION_WORKBENCH:AUDIT"), List.of());

        assertThat(result.state()).isEqualTo(HrisProductAccessDtos.AccessState.DENIED);
        assertThat(result.reasonCode()).isEqualTo("SEPARATION_OF_DUTIES_CONFLICT");
        assertThat(result.authority().separationOfDutiesConflict()).isTrue();
        assertThat(result.grants()).isEmpty();
    }

    @Test
    void keepsTheEnterpriseAuditorReadOnly() {
        var result = evaluate(Set.of(
                "APP.HCM:VIEW", "HCM.CONFIGURATION_WORKBENCH:AUDIT"), List.of());

        assertThat(result.authority().readOnly()).isTrue();
        assertThat(result.grants()).extracting(HrisProductAccessDtos.Grant::action)
                .contains("VIEW")
                .doesNotContain("UPDATE", "PUBLISH");
        assertThat(result.grants()).noneMatch(HrisProductAccessDtos.Grant::mutable);
    }

    private HrisProductAccessDtos.AccessSnapshot evaluate(
            Set<String> permissions,
            List<HrisProductAccessPolicy.ScopedResponsibility> responsibilities) {
        return policy.evaluate(
                7L, 11L,
                new HrisProductAccessPolicy.Evidence(permissions, Set.of(), responsibilities), NOW);
    }

    private HrisProductAccessDtos.AccessSnapshot evaluateWithDenies(
            Set<String> permissions,
            Set<String> deniedPermissions) {
        return policy.evaluate(
                7L, 11L,
                new HrisProductAccessPolicy.Evidence(
                        permissions, deniedPermissions, List.of()), NOW);
    }

    private HrisProductAccessPolicy.ScopedResponsibility scope(
            String responsibility,
            String resource,
            OffsetDateTime validTo) {
        return new HrisProductAccessPolicy.ScopedResponsibility(
                responsibility, "APP", resource, "HCM_SCOPE", validTo);
    }
}
