package com.dwp.services.messaging.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MessagingReceiptEventBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"messaging.read-cursor.updated", "messaging.privacy-preferences.updated"})
    void misaddressedAndPublicSelfEventsFailClosedEvenBeforeMembershipQueries(String type) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var repository = new MessagingRealtimeRepository(jdbc, new ObjectMapper());
        for (Long audience : new Long[] {null, 200L}) {
            var event = event(type, audience);
            assertThat(repository.canReceive(event, 100)).isFalse();
            assertThat(repository.canReceive(event, 200)).isFalse();
        }
        assertThat(repository.canReceive(event(type, 100L), 100)).isTrue();
        assertThat(repository.canReceive(event(type, 100L), 200)).isFalse();
        verifyNoInteractions(jdbc);
    }

    private MessagingRealtimeEvent event(String type, Long audience) {
        return new MessagingRealtimeEvent(1, UUID.randomUUID(), 7, audience, null, null, null,
                100, type, Map.of("version", 1), OffsetDateTime.now());
    }
}
