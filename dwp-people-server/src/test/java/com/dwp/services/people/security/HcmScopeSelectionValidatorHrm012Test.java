package com.dwp.services.people.security;

import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class HcmScopeSelectionValidatorHrm012Test {

    @Test
    void sealsTheReceiptFirstProfileWithoutResolvingPopulationBeforeReceipt() {
        HcmPopulationScopeService populations = mock(HcmPopulationScopeService.class);
        HcmScopeSelectionValidator validator = new HcmScopeSelectionValidator(populations);
        HcmV3PepRegistry.RouteAuthority exact = authority(
                Set.of("predicate.hcm-workforce-ingestion-scope.v1",
                        "predicate.people.object-version.v1"));

        validator.validate(exact);

        verifyNoInteractions(populations);
        assertThatThrownBy(() -> validator.validate(authority(Set.of(
                "predicate.people.object-version.v1"))))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(populations);
    }

    private HcmV3PepRegistry.RouteAuthority authority(Set<String> predicates) {
        return new HcmV3PepRegistry.RouteAuthority(
                "route.hcm.operations.workforce-ingestion.action", "ACTION",
                "workforce-ingestion-executor", false, predicates,
                Set.of("OBJECT", "TARGET_POPULATION"),
                "route.hcm.operations.workforce-ingestion.action.binding.01",
                "hcm.integration.workforce-ingestion.execute",
                "STEPUP-MGMT-HIGH-V1", "POST",
                "/v1/hris/integrations/workforce-ingestions",
                new HcmV3PepRegistry.StepUpBinding(
                        "WORKFORCE_INGESTION_OBJECT", null,
                        java.util.List.of("objectRef"), "people", "dwp-people-server"));
    }
}
