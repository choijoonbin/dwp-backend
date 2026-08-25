package com.dwp.services.notification.domain;

import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationBulkUndoRepositoryTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(7, 91L, Set.of(), Set.of(), false, null);

    @Test
    void storesAnOpaqueScopedReceiptAndEveryExactBeforeState() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        when(jdbc.batchUpdate(anyString(), any(MapSqlParameterSource[].class)))
                .thenReturn(new int[]{1, 1});
        NotificationBulkUndoRepository repository = new NotificationBulkUndoRepository(jdbc);
        Instant now = Instant.parse("2026-08-24T03:00:00Z");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        List<NotificationUndoSnapshot> snapshots = List.of(
                new NotificationUndoSnapshot(
                        firstId, "ACTIVE", null, now.minusSeconds(20), null,
                        now.plusSeconds(600), 4),
                new NotificationUndoSnapshot(
                        secondId, "DONE", now.minusSeconds(40), null,
                        now.minusSeconds(30), null, 8));

        NotificationBulkUndoRepository.UndoReceipt receipt = repository.create(
                ACTOR, "COMPLETE", snapshots, now, Duration.ofMinutes(10));

        assertThat(receipt.undoToken()).isNotNull();
        assertThat(receipt.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
        assertThat(receipt.snapshots()).containsExactlyElementsOf(snapshots);

        ArgumentCaptor<MapSqlParameterSource> receiptParams =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(anyString(), receiptParams.capture());
        assertThat(receiptParams.getValue().getValue("tenantId")).isEqualTo(7L);
        assertThat(receiptParams.getValue().getValue("userId")).isEqualTo(91L);
        assertThat(receiptParams.getValue().getValue("undoToken")).isEqualTo(receipt.undoToken());
        assertThat(receiptParams.getValue().getValue("action")).isEqualTo("COMPLETE");

        ArgumentCaptor<MapSqlParameterSource[]> itemParams =
                ArgumentCaptor.forClass(MapSqlParameterSource[].class);
        verify(jdbc).batchUpdate(anyString(), itemParams.capture());
        assertThat(itemParams.getValue()).hasSize(2);
        assertThat(itemParams.getValue()[0].getValue("notificationId")).isEqualTo(firstId);
        assertThat(itemParams.getValue()[0].getValue("inboxState")).isEqualTo("ACTIVE");
        assertThat(itemParams.getValue()[0].getValue("expectedVersion")).isEqualTo(4L);
        assertThat(itemParams.getValue()[1].getValue("notificationId")).isEqualTo(secondId);
        assertThat(itemParams.getValue()[1].getValue("inboxState")).isEqualTo("DONE");
        assertThat(itemParams.getValue()[1].getValue("expectedVersion")).isEqualTo(8L);
    }
}
