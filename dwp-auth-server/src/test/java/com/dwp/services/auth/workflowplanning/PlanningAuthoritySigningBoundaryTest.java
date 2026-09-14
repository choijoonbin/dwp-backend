package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class PlanningAuthoritySigningBoundaryTest {
    @Test
    void serviceDependsOnlyOnTypedSigningPort() {
        assertThat(PlanningAuthorityService.class.getDeclaredFields())
                .noneMatch(field -> field.getType().equals(PlanningAuthorityIssuer.class));
        assertThat(PlanningAuthorityService.class.getConstructors()).allSatisfy(constructor ->
                assertThat(constructor.getParameterTypes()).doesNotContain(PlanningAuthorityIssuer.class));
        assertThat(PlanningAuthorityService.SigningPort.class.isAssignableFrom(PlanningAuthorityIssuer.class))
                .isTrue();
    }

    @Test
    void onlyServiceCanConstructCurrentSigningEvidence() throws Exception {
        assertThat(PlanningAuthorityService.Current.class.getDeclaredConstructors()).allSatisfy(constructor ->
                assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        assertThat(PlanningAuthorityService.Current.class.getDeclaredMethods()).noneMatch(method ->
                Modifier.isStatic(method.getModifiers()));
        assertThat(PlanningAuthorityService.SigningPort.class.getDeclaredMethod("issue",
                PlanningProofVerifier.Verified.class, PlanningAuthorityService.Current.class).getReturnType())
                .isEqualTo(String.class);
    }
}
