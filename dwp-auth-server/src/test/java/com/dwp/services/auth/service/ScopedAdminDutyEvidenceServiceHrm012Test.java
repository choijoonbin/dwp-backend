package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedAdminDutyEvidenceServiceHrm012Test {

    @Test
    void projectsOnlyTheExactWorkforceIngestionExecutorDuty() {
        var exact = duty(Map.of(
                "hcm.integration.workforce-ingestion.execute",
                "ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE"));
        assertThat(ScopedAdminDutyEvidenceService.resourceRoles(java.util.List.of(exact)))
                .singleElement().satisfies(role -> {
                    assertThat(role.responsibilityCode())
                            .isEqualTo("HRIS_DUTY_INTEGRATION_EXECUTOR");
                    assertThat(role.resourceSetKey()).isEqualTo("RS_HRIS_CONNECTORS_LE_100");
                });

        Map<String, String> drifted = new LinkedHashMap<>(exact.capabilityAuthorities());
        drifted.put("hcm.integration.workforce-ingestion.execute.drift",
                "ACTION.HRIS_INTEGRATION_EXECUTE:VIEW");
        assertThat(ScopedAdminDutyEvidenceService.resourceRoles(
                java.util.List.of(duty(drifted))))
                .noneMatch(role -> "HRIS_DUTY_INTEGRATION_EXECUTOR".equals(
                        role.responsibilityCode()));
    }

    static ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            Map<String, String> capabilities) {
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), "HRIS_DUTY_INTEGRATION_EXECUTOR",
                "hcm", "INTEGRATION_ADMIN", "APP.HCM",
                "ACTION.HRIS_INTEGRATION_EXECUTE", false,
                UUID.randomUUID(), "RS_HRIS_CONNECTORS_LE_100",
                capabilities, Set.of(),
                Set.of(new ScopedAdminDutyEvidenceService.ResourceMember("APP", "APP.HCM")),
                null, "MANUAL", "USER", "20", "test-revision");
    }
}
