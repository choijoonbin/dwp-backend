package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedAdminDutyEvidenceServiceHrm011Test {

    @Test
    void projectsTheCanonicalContractIssuerOnlyForTheExactCapabilityMap() {
        var exact = duty(Map.of(
                "hcm.operations.contract.issue",
                "ACTION.HRIS_EMPLOYMENT_CONTRACT:ISSUE"));
        assertThat(ScopedAdminDutyEvidenceService.resourceRoles(java.util.List.of(exact)))
                .singleElement()
                .satisfies(role -> {
                    assertThat(role.responsibilityCode())
                            .isEqualTo("HRIS_DUTY_CONTRACT_ISSUER");
                    assertThat(role.resourceSetKey())
                            .isEqualTo("RS_HRIS_CONTRACT_ISSUERS_LE_100");
                });

        Map<String, String> drifted = new LinkedHashMap<>(exact.capabilityAuthorities());
        drifted.put(
                "hcm.operations.contract.issue.drift",
                "ACTION.HRIS_EMPLOYMENT_CONTRACT:VIEW");
        assertThat(ScopedAdminDutyEvidenceService.resourceRoles(
                java.util.List.of(duty(drifted))))
                .noneMatch(role -> "HRIS_DUTY_CONTRACT_ISSUER".equals(
                        role.responsibilityCode()));
    }

    private ScopedAdminDutyEvidenceService.EffectiveDuty duty(
            Map<String, String> capabilities) {
        return new ScopedAdminDutyEvidenceService.EffectiveDuty(
                10L, 20L, UUID.randomUUID(), "HRIS_DUTY_CONTRACT_ISSUER",
                "hcm", "HR_ADMIN", "APP.HCM",
                "ACTION.HRIS_EMPLOYMENT_CONTRACT", false,
                UUID.randomUUID(), "RS_HRIS_CONTRACT_ISSUERS_LE_100",
                capabilities, Set.of(),
                Set.of(new ScopedAdminDutyEvidenceService.ResourceMember(
                        "APP", "APP.HCM")),
                null, "MANUAL", "USER", "20", "test-revision");
    }
}
