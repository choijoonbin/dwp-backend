package com.dwp.services.payroll.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcPayrollHomeReplayLedgerTest {

    @Test
    void reportsOnlyTheFirstAtomicJtiAndNonceReservationAsAccepted() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(contains("ON CONFLICT DO NOTHING"),
                any(MapSqlParameterSource.class))).thenReturn(1, 0);
        var ledger = new JdbcPayrollHomeReplayLedger(
                jdbc, new ObjectMapper().findAndRegisterModules());
        UUID proof = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID nonce = UUID.fromString("00000000-0000-0000-0000-000000000102");
        Instant expiry = Instant.parse("2026-10-16T03:00:20Z");

        assertThat(ledger.reserve(proof, nonce, 7, expiry)).isTrue();
        assertThat(ledger.reserve(proof, nonce, 7, expiry)).isFalse();
    }
}
