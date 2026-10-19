package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductAuthorizationIdentityEvidenceServicePay003Test {

    @Test
    void promotesOnlyTheExactStatutoryDutyToResponsibilityEvidence() {
        AuthService auth = mock(AuthService.class);
        AppGovernanceService governance = mock(AppGovernanceService.class);
        ScopedAdminDutyEvidenceService duties = mock(ScopedAdminDutyEvidenceService.class);
        when(auth.getPermissions(20L, 10L)).thenReturn(List.of());
        when(auth.getRoleCodes(20L, 10L)).thenReturn(List.of());
        when(governance.resourceRoles(10L, 20L)).thenReturn(List.of());
        var exact = duty(Map.of(
                "hcm.operations.statutory.execute",
                "ACTION.HRIS_STATUTORY_FILING:EXECUTE"));
        when(duties.effectiveDuties(10L, 20L)).thenReturn(List.of(exact));
        var service = new ProductAuthorizationIdentityEvidenceService(auth, governance, duties);

        var identity = service.load(10L, 20L);

        assertThat(identity.responsibilities()).singleElement().satisfies(role -> {
            assertThat(role.responsibilityCode()).isEqualTo("HRIS_DUTY_STATUTORY_OPERATOR");
            assertThat(role.resourceKey()).isEqualTo("APP.HCM");
            assertThat(role.resourceSetKey())
                    .isEqualTo("RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7");
        });

        Map<String, String> drifted = new LinkedHashMap<>(exact.capabilityAuthorities());
        drifted.put("hcm.operations.statutory.execute.drift",
                "ACTION.HRIS_STATUTORY_FILING:VIEW");
        when(duties.effectiveDuties(10L, 20L)).thenReturn(List.of(duty(drifted)));

        assertThat(service.load(10L, 20L).responsibilities()).isEmpty();
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            Map<String, String> capabilities) {
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), "HRIS_DUTY_STATUTORY_OPERATOR",
                "hcm", "PAYROLL_ADMIN", "APP.HCM",
                "ACTION.HRIS_STATUTORY_FILING", false,
                UUID.randomUUID(), "RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7",
                capabilities, Set.of(),
                Set.of(new ScopedAdminDutyEvidenceService.ResourceMember("APP", "APP.HCM")),
                null, "MANUAL", "USER", "20", "test-revision");
    }
}
