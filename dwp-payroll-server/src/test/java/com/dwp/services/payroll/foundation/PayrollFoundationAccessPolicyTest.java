package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationAction;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.LEGAL_ENTITY_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollFoundationAccessPolicyTest {

    @Test
    void compatibilityPolicyUsesVerifiedPermissionPurposeAndScopeNotRoleName() {
        PayrollFoundationAccess.Actor actor = PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:EDIT",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString());

        assertThat(actor.allows(FoundationAction.EDIT, LEGAL_ENTITY_ID)).isTrue();
        assertThatThrownBy(() -> PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "APP.HRIS:VIEW OTHER_RESOURCE:EDIT",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void requiresBothHrisEntitlementAndModuleAction() {
        assertThatThrownBy(() -> PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "PAYROLL_FOUNDATION:EDIT",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "APP.HRIS:VIEW",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString()))
                .isInstanceOf(BaseException.class);

        assertThatThrownBy(() -> PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "APP.HCM:VIEW PAYROLL_FOUNDATION:EDIT",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString()))
                .isInstanceOf(BaseException.class);

        PayrollFoundationAccess.Actor actor = PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:EDIT",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString());

        assertThat(actor.allows(FoundationAction.EDIT, LEGAL_ENTITY_ID)).isTrue();
    }

    @Test
    void resolvesCustomTenantResourcePurposeAndRoleMapping() {
        PayrollFoundationAccess.AccessPolicy custom = new PayrollFoundationAccess.AccessPolicy(
                "TENANT-42-PAYROLL-POLICY",
                9,
                "TENANT_PAY",
                Set.of("TENANT_APP:VIEW"),
                Set.of("CUSTOM_CONFIG"),
                Set.of("CUSTOM_AUDIT"),
                Map.of(
                        "TENANT_STEWARD", Set.of(
                                FoundationAction.VIEW, FoundationAction.EDIT),
                        "TENANT_REVIEWER", Set.of(FoundationAction.PUBLISH)),
                Map.of());
        PayrollFoundationAccessPolicyProvider provider = tenantId -> custom;

        PayrollFoundationAccess.Actor actor = PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_STEWARD",
                "TENANT_APP:VIEW TENANT_PAY:VIEW TENANT_PAY:EDIT",
                "CUSTOM_CONFIG", LEGAL_ENTITY_ID.toString(),
                provider.policyFor(42));

        assertThat(actor.allows(FoundationAction.VIEW, LEGAL_ENTITY_ID)).isTrue();
        assertThat(actor.allows(FoundationAction.EDIT, LEGAL_ENTITY_ID)).isTrue();
        assertThat(actor.allows(FoundationAction.PUBLISH, LEGAL_ENTITY_ID)).isFalse();
    }

    @Test
    void customPolicyRejectsCompatibilityPurposeAndResource() {
        PayrollFoundationAccess.AccessPolicy custom = new PayrollFoundationAccess.AccessPolicy(
                "TENANT-42-PAYROLL-POLICY",
                9,
                "TENANT_PAY",
                Set.of("TENANT_APP:VIEW"),
                Set.of("CUSTOM_CONFIG"),
                Set.of("CUSTOM_AUDIT"),
                Map.of("TENANT_STEWARD", Set.of(FoundationAction.EDIT)),
                Map.of());

        assertThatThrownBy(() -> PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_STEWARD",
                "TENANT_APP:VIEW PAYROLL_FOUNDATION:EDIT",
                "PAYROLL_CONFIGURATION", "*", custom))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void manageOnlyDoesNotImplyAnExactPayrollAction() {
        assertThatThrownBy(() -> PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_DEFINED_PAYROLL_STEWARD",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:MANAGE",
                "PAYROLL_CONFIGURATION", LEGAL_ENTITY_ID.toString()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void explicitTenantPolicyDenyOverridesAllow() {
        PayrollFoundationAccess.AccessPolicy custom = new PayrollFoundationAccess.AccessPolicy(
                "TENANT-42-PAYROLL-POLICY",
                10,
                "TENANT_PAY",
                Set.of("TENANT_APP:VIEW"),
                Set.of("CUSTOM_CONFIG"),
                Set.of("CUSTOM_AUDIT"),
                Map.of("TENANT_STEWARD", Set.of(FoundationAction.EDIT)),
                Map.of("TENANT_RESTRICTED", Set.of(FoundationAction.EDIT)));

        PayrollFoundationAccess.Actor actor = PayrollFoundationAccess.actor(
                42L, 700L, "TENANT_STEWARD,TENANT_RESTRICTED",
                "TENANT_APP:VIEW TENANT_PAY:EDIT",
                "CUSTOM_CONFIG", LEGAL_ENTITY_ID.toString(), custom);

        assertThat(actor.allows(FoundationAction.EDIT, LEGAL_ENTITY_ID)).isFalse();
    }

    @Test
    void gatewayVerifiedRuntimeProviderHasNoRoleNameGrantMapping() {
        PayrollFoundationAccessPolicyProvider provider =
                new GatewayVerifiedPayrollFoundationAccessPolicyProvider();

        PayrollFoundationAccess.AccessPolicy policy = provider.policyFor(42);

        assertThat(policy.policyId()).isEqualTo("PAYROLL-GATEWAY-OWNER-V1");
        assertThat(policy.roleActions()).isEmpty();
        assertThat(policy.roleDenials()).isEmpty();
        assertThat(policy.acceptedEntitlements()).containsExactly("APP.HCM:VIEW");
    }

    @Test
    void fallbackProviderFailsClosedIfProductionProviderIsMissing() {
        PayrollFoundationAccessPolicyProvider provider =
                new UnavailablePayrollFoundationAccessPolicyProvider();

        assertThatThrownBy(() -> provider.policyFor(42))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
}
