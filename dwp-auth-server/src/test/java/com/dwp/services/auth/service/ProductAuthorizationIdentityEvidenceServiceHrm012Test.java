package com.dwp.services.auth.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductAuthorizationIdentityEvidenceServiceHrm012Test {

    @Test
    void promotesOnlyTheExactWorkforceIngestionDutyToResponsibilityEvidence() {
        AuthService auth = mock(AuthService.class);
        AppGovernanceService governance = mock(AppGovernanceService.class);
        ScopedAdminDutyEvidenceService duties = mock(ScopedAdminDutyEvidenceService.class);
        when(auth.getPermissions(20L, 10L)).thenReturn(List.of());
        when(auth.getRoleCodes(20L, 10L)).thenReturn(List.of());
        when(governance.resourceRoles(10L, 20L)).thenReturn(List.of());
        var exact = ScopedAdminDutyEvidenceServiceHrm012Test.duty(Map.of(
                "hcm.integration.workforce-ingestion.execute",
                "ACTION.HRIS_INTEGRATION_EXECUTE:EXECUTE"));
        when(duties.effectiveDuties(10L, 20L)).thenReturn(List.of(exact));
        var service = new ProductAuthorizationIdentityEvidenceService(auth, governance, duties);

        assertThat(service.load(10L, 20L).responsibilities()).singleElement()
                .satisfies(role -> {
                    assertThat(role.responsibilityCode())
                            .isEqualTo("HRIS_DUTY_INTEGRATION_EXECUTOR");
                    assertThat(role.resourceKey()).isEqualTo("APP.HCM");
                    assertThat(role.resourceSetKey()).isEqualTo("RS_HRIS_CONNECTORS_LE_100");
                });

        Map<String, String> drifted = new LinkedHashMap<>(exact.capabilityAuthorities());
        drifted.put("hcm.integration.workforce-ingestion.execute.drift",
                "ACTION.HRIS_INTEGRATION_EXECUTE:VIEW");
        when(duties.effectiveDuties(10L, 20L)).thenReturn(
                List.of(ScopedAdminDutyEvidenceServiceHrm012Test.duty(drifted)));
        assertThat(service.load(10L, 20L).responsibilities()).isEmpty();
    }
}
