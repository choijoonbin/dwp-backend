package com.dwp.services.time.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class JdbcTimeHomeReplayReservationsTest {

    @Test
    void acceptsOnlyAnAtomicTwoIdentifierReservationAndMarksPartialCollisionRollbackOnly() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(status);
        when(jdbc.update(contains("ON CONFLICT (consumer_name,event_id) DO NOTHING"),
                any(MapSqlParameterSource.class))).thenReturn(2, 1);
        var reservations = new JdbcTimeHomeReplayReservations(jdbc, transactionManager);
        UUID proof = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID nonce = UUID.fromString("00000000-0000-0000-0000-000000000202");
        Instant expiry = Instant.parse("2026-10-16T03:00:20Z");

        assertThat(reservations.reserve(proof, nonce, 7, expiry)).isTrue();
        assertThat(reservations.reserve(proof, nonce, 7, expiry)).isFalse();

        verify(status).setRollbackOnly();
    }
}
