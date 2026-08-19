package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceOperationsRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    void idempotencyMetadataIsAttachedWithinTheOwnedBookingScope() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        WorkplaceOperationsRepository repository = repository();
        UUID bookingId = UUID.randomUUID();

        assertThat(repository.attachIdempotency(
                3L, 17L, bookingId, "request-17", "a".repeat(64))).isEqualTo(1);

        SqlCall call = updateCall();
        assertThat(call.sql())
                .contains("idempotency_key = :idempotencyKey")
                .contains("request_fingerprint = :requestFingerprint")
                .contains("tenant_id = :tenantId AND user_id = :userId")
                .contains("idempotency_key IS NULL");
        assertThat(call.parameters().getValue("bookingId")).isEqualTo(bookingId);
        assertThat(call.parameters().getValue("idempotencyKey")).isEqualTo("request-17");
    }

    @Test
    void relocationUsesVersionOwnerAndFutureReservedGuardsInOneUpdate() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        WorkplaceOperationsRepository repository = repository();
        UUID bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-08-19T10:00:00+09:00");

        assertThat(repository.relocate(
                3L, 17L, bookingId, 4L, resourceId,
                now.plusDays(1), now.plusDays(1).plusHours(1), now)).isEqualTo(1);

        SqlCall call = updateCall();
        assertThat(call.sql())
                .contains("resource_id = :resourceId")
                .contains("booking_id = :bookingId AND version = :version")
                .contains("booking_status = 'RESERVED' AND starts_at > :now")
                .contains("tenant_id = :tenantId AND user_id = :userId");
        assertThat(call.parameters().getValue("resourceId")).isEqualTo(resourceId);
        assertThat(call.parameters().getValue("version")).isEqualTo(4L);
    }

    @Test
    void forceCancellationOnlyTransitionsActiveVersionedBookings() {
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        WorkplaceOperationsRepository repository = repository();

        assertThat(repository.forceCancel(
                3L, 99L, UUID.randomUUID(), 2L, OffsetDateTime.now())).isEqualTo(1);

        SqlCall call = updateCall();
        assertThat(call.sql())
                .contains("booking_status = 'CANCELLED'")
                .contains("booking_status IN ('RESERVED', 'CHECKED_IN')")
                .contains("released_at = CASE WHEN booking_status = 'CHECKED_IN'")
                .contains("version = :version");
        assertThat(call.parameters().getValue("actorId")).isEqualTo(99L);
    }

    private WorkplaceOperationsRepository repository() {
        return new WorkplaceOperationsRepository(jdbc, new ObjectMapper());
    }

    private SqlCall updateCall() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> parameters =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(sql.capture(), parameters.capture());
        return new SqlCall(sql.getValue(), (MapSqlParameterSource) parameters.getValue());
    }

    private record SqlCall(String sql, MapSqlParameterSource parameters) {
    }
}
