package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HrisServiceGrantDrainAuthorizationExpiryRaceTest {
    @Test
    void contractRejectsAnyCallerChosenDrainWindow() {
        Instant databaseRecordedAt = Instant.parse("2026-10-27T00:00:00Z");
        assertThatThrownBy(() -> new HrisServiceGrantAuthorityV1.DrainAdmission(
                UUID.randomUUID(), 1, databaseRecordedAt.minusSeconds(1),
                HrisServiceGrantAuthorityV1.ServiceRole.CALLER, 1, databaseRecordedAt,
                databaseRecordedAt.plusSeconds(31), databaseRecordedAt.plusSeconds(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
