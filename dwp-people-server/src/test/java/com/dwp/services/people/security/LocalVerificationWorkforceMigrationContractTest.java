package com.dwp.services.people.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LocalVerificationWorkforceMigrationContractTest {

    @Test
    void localVerificationIdentityOwnsACompleteFailClosedSelfBinding() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V45__bind_local_verification_identity_to_workforce.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b")
                    .contains("tenant_key = 'default'")
                    .contains("INSERT INTO ppl_persons")
                    .contains("INSERT INTO ppl_workers")
                    .contains("INSERT INTO ppl_work_relationships")
                    .contains("INSERT INTO ppl_positions")
                    .contains("INSERT INTO ppl_assignments")
                    .contains("person.public_id = fixture.person_public_id")
                    .contains("worker.tenant_id = person.tenant_id")
                    .contains("worker.person_id = person.person_id")
                    .contains("ON CONFLICT")
                    .contains("The local verification SELF workforce binding is incomplete");
        }
    }
}
