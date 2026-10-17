package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.dwp.services.auth.service.PlanningActualAuthHarness;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/** Proves the exact sealed v39 release remains compatible with the private planning authority. */
class PlanningV39ReadinessPostgresTest {
    @Test
    void activeSealedV39IsAcceptedByPlanningRegistryAndReadiness() throws Exception {
        try (var auth = new PlanningActualAuthHarness(
                new RSAKeyGenerator(2048).keyID("v39-planning-owner").generate(),
                new RSAKeyGenerator(2048).keyID("v39-planning-transport").generate(),
                new RSAKeyGenerator(2048).keyID("v39-planning-attestation").generate(),
                39L)) {
            assertThatCode(auth.bridge()::requireRegistered).doesNotThrowAnyException();
            var health = new PlanningReadiness(auth.service()).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("state", "READY");
        }
    }
}
