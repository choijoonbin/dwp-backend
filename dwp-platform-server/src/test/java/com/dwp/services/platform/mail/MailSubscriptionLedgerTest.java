package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MailSubscriptionLedgerTest {

    @Test
    void rejectsTokenLikeVerificationEvidenceBeforeDatabasePersistence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MailSubscriptionLedger ledger = new MailSubscriptionLedger(
                jdbc, new ObjectMapper());
        OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);

        assertThatThrownBy(() -> ledger.recordVerified(
                7, UUID.randomUUID(), null, "a".repeat(64), "b".repeat(64),
                expires, expires.minusMinutes(10),
                Map.of("validationToken", "must-never-persist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SUBSCRIPTION_EVIDENCE_INVALID");
        verifyNoInteractions(jdbc);
    }
}
