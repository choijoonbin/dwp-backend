package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedAdminDutyEvidenceServicePay003Test {

    @Test
    void projectsTheCanonicalStatutoryOperatorOnlyForTheExactCapabilityMap() {
        var exact = duty(Map.of(
                "hcm.operations.statutory.execute",
                "ACTION.HRIS_STATUTORY_FILING:EXECUTE"));
        assertThat(ScopedAdminDutyEvidenceService.resourceRoles(java.util.List.of(exact)))
                .singleElement()
                .satisfies(role -> {
                    assertThat(role.responsibilityCode())
                            .isEqualTo("HRIS_DUTY_STATUTORY_OPERATOR");
                    assertThat(role.resourceSetKey())
                            .isEqualTo("RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7");
                });

        Map<String, String> drifted = new LinkedHashMap<>(exact.capabilityAuthorities());
        drifted.put(
                "hcm.operations.statutory.execute.drift",
                "ACTION.HRIS_STATUTORY_FILING:VIEW");
        assertThat(ScopedAdminDutyEvidenceService.resourceRoles(
                java.util.List.of(duty(drifted))))
                .noneMatch(role -> "HRIS_DUTY_STATUTORY_OPERATOR".equals(
                        role.responsibilityCode()));
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            Map<String, String> capabilities) {
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), "HRIS_DUTY_STATUTORY_OPERATOR",
                "hcm", "PAYROLL_ADMIN", "APP.HCM",
                "ACTION.HRIS_STATUTORY_FILING", false,
                UUID.randomUUID(), "RS_HRIS_STATUTORY_OPERATOR_LE_100_PG_7",
                capabilities, Set.of(),
                Set.of(new ScopedAdminDutyEvidenceService.ResourceMember(
                        "APP", "APP.HCM")),
                null, "MANUAL", "USER", "20", "test-revision");
    }
}
