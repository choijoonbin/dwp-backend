package com.dwp.services.space.domain;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SpaceMembershipInputValidationTest {

    @Test
    void membershipExpiryMustBeInTheFutureButMayBeUnlimited() {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        try (var factory = Validation.byDefaultProvider().configure()
                .clockProvider(() -> Clock.fixed(now, ZoneOffset.UTC)).buildValidatorFactory()) {
            var validator = factory.getValidator();
            for (Instant invalid : new Instant[] {now.minusSeconds(1), now}) {
                assertThat(validator.validate(new SpaceDtos.SaveMemberRequest(
                        "USER", "42", "VIEWER", invalid)))
                        .anyMatch(violation -> violation.getPropertyPath().toString().equals("validUntil"));
                assertThat(validator.validate(new SpaceDtos.UpdateMemberRequest("VIEWER", invalid, 0L)))
                        .anyMatch(violation -> violation.getPropertyPath().toString().equals("validUntil"));
            }
            assertThat(validator.validate(new SpaceDtos.SaveMemberRequest(
                    "USER", "42", "VIEWER", now.plusSeconds(1)))).isEmpty();
            assertThat(validator.validate(new SpaceDtos.UpdateMemberRequest(
                    "VIEWER", now.plusSeconds(1), 0L))).isEmpty();
            assertThat(validator.validate(new SpaceDtos.SaveMemberRequest(
                    "USER", "42", "VIEWER", null))).isEmpty();
            assertThat(validator.validate(new SpaceDtos.UpdateMemberRequest("VIEWER", null, 0L))).isEmpty();
        }
    }
}
