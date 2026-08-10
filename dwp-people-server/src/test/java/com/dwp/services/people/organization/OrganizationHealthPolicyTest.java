package com.dwp.services.people.organization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationHealthPolicyTest {

    @Test
    void keepsLowSeveritySignalsActionableWithoutEscalatingThemToCritical() {
        List<String> signals = List.of("NARROW_SPAN", "HIGH_VACANCY");

        assertThat(OrganizationHealthPolicy.score(signals)).isEqualTo(70);
        assertThat(OrganizationHealthPolicy.status(signals)).isEqualTo("ATTENTION");
    }

    @Test
    void escalatesCombinedMaterialRisks() {
        List<String> signals = List.of("HIGH_CONTINGENT", "HIGH_VACANCY", "NARROW_SPAN");

        assertThat(OrganizationHealthPolicy.score(signals)).isEqualTo(50);
        assertThat(OrganizationHealthPolicy.status(signals)).isEqualTo("CRITICAL");
    }

    @Test
    void treatsStructuralOverloadAsMaterialRisk() {
        List<String> signals = List.of("WIDE_SPAN", "EXCESS_LAYERS");

        assertThat(OrganizationHealthPolicy.score(signals)).isEqualTo(30);
        assertThat(OrganizationHealthPolicy.status(signals)).isEqualTo("CRITICAL");
    }
}
