package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.cursor.NotificationCursorCodec;
import com.dwp.services.notification.domain.NotificationQueryRepository.CounterSnapshot;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxFilters;
import com.dwp.services.notification.domain.NotificationQueryRepository.ViewCounts;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceContextFilterTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(7L, 11L, Set.of(), Set.of(), false, null);

    private NotificationQueryRepository queries;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        queries = mock(NotificationQueryRepository.class);
        service = new NotificationService(
                mock(NotificationDatabaseScope.class),
                queries,
                mock(NotificationCommandRepository.class),
                mock(NotificationPreferenceRepository.class),
                mock(NotificationEffectivePolicyRepository.class),
                mock(NotificationIdempotencyRepository.class),
                mock(NotificationBulkUndoRepository.class),
                mock(NotificationCursorCodec.class),
                mock(NotificationChangePublisher.class),
                Duration.ofMinutes(10));
    }

    @Test
    void opaqueContextKeyIsBoundWithoutNormalization() {
        when(queries.inbox(eq(ACTOR), any(), any(), eq(51), eq(null)))
                .thenReturn(List.of());
        when(queries.counter(ACTOR)).thenReturn(
                new CounterSnapshot(0, 0, 0, 0, 0, Instant.EPOCH));
        when(queries.viewCounts(ACTOR)).thenReturn(new ViewCounts(0, 0, 0, 0, 0, 0));

        service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, null,
                List.of(),
                List.of("THREAD"), List.of("conversation:42"), null, null);

        ArgumentCaptor<InboxFilters> filters = ArgumentCaptor.forClass(InboxFilters.class);
        verify(queries).inbox(eq(ACTOR), any(), filters.capture(), eq(51), eq(null));
        assertThat(filters.getValue().contexts()).containsExactly(
                new NotificationQueryRepository.InboxContextFilter(
                        "THREAD", "conversation:42"));
    }

    @Test
    void nonCanonicalOpaqueContextKeyFailsClosedBeforeQuery() {
        assertThatThrownBy(() -> service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, null,
                List.of(),
                List.of("THREAD"), List.of(" conversation:42"), null, null))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.INVALID_INPUT));

        verify(queries, never()).inbox(any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void repeatedContextPairsRemainAlignedAndCanonical() {
        when(queries.inbox(eq(ACTOR), any(), any(), eq(51), eq(null)))
                .thenReturn(List.of());
        when(queries.counter(ACTOR)).thenReturn(
                new CounterSnapshot(0, 0, 0, 0, 0, Instant.EPOCH));
        when(queries.viewCounts(ACTOR)).thenReturn(new ViewCounts(0, 0, 0, 0, 0, 0));

        service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, null,
                List.of(),
                List.of("THREAD", "ACTOR", "ACTOR"),
                List.of("conversation:42", "user:84", "user:42"), null, null);

        ArgumentCaptor<InboxFilters> filters = ArgumentCaptor.forClass(InboxFilters.class);
        verify(queries).inbox(eq(ACTOR), any(), filters.capture(), eq(51), eq(null));
        assertThat(filters.getValue().contexts()).containsExactly(
                new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:42"),
                new NotificationQueryRepository.InboxContextFilter("ACTOR", "user:84"),
                new NotificationQueryRepository.InboxContextFilter("THREAD", "conversation:42"));
    }

    @Test
    void mismatchedRepeatedContextPairsFailClosedBeforeQuery() {
        assertThatThrownBy(() -> service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, null,
                List.of(),
                List.of("ACTOR", "THREAD"), List.of("user:42"), null, null))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.INVALID_INPUT));

        verify(queries, never()).inbox(any(), any(), any(), any(Integer.class), any());
    }

    @Test
    void includedTypesAreCanonicalizedAndInvalidValuesFailClosed() {
        when(queries.inbox(eq(ACTOR), any(), any(), eq(51), eq(null))).thenReturn(List.of());
        when(queries.counter(ACTOR)).thenReturn(new CounterSnapshot(0, 0, 0, 0, 0, Instant.EPOCH));
        when(queries.viewCounts(ACTOR)).thenReturn(new ViewCounts(0, 0, 0, 0, 0, 0));

        service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, null,
                List.of("ASSIGNED", "DIRECT"), List.of(), List.of(), null, null);

        ArgumentCaptor<InboxFilters> filters = ArgumentCaptor.forClass(InboxFilters.class);
        verify(queries).inbox(eq(ACTOR), any(), filters.capture(), eq(51), eq(null));
        assertThat(filters.getValue().includedTypes()).containsExactly("DIRECT", "ASSIGNED");

        assertThatThrownBy(() -> service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, null,
                List.of("direct"), List.of(), List.of(), null, null))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void materializedAttentionAndExactTotalAreReturnedFromTheSameFilter() {
        when(queries.inbox(eq(ACTOR), any(), any(), eq(51), eq(null))).thenReturn(List.of());
        when(queries.inboxTotal(eq(ACTOR), any(), any())).thenReturn(12L);
        when(queries.counter(ACTOR)).thenReturn(new CounterSnapshot(0, 0, 0, 0, 0, Instant.EPOCH));
        when(queries.viewCounts(ACTOR)).thenReturn(new ViewCounts(0, 0, 0, 0, 0, 0));

        var page = service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, "PRIORITIZE",
                List.of(), List.of(), List.of(), null, null);

        ArgumentCaptor<InboxFilters> filters = ArgumentCaptor.forClass(InboxFilters.class);
        verify(queries).inbox(eq(ACTOR), any(), filters.capture(), eq(51), eq(null));
        verify(queries).inboxTotal(eq(ACTOR), any(), eq(filters.getValue()));
        assertThat(filters.getValue().attentionEffect()).isEqualTo("PRIORITIZE");
        assertThat(page.approximateTotal()).isEqualTo(12L);
    }

    @Test
    void arbitraryAttentionInputFailsClosedBeforeQuery() {
        assertThatThrownBy(() -> service.inbox(
                ACTOR, "ALL", 50, null,
                null, null, null, null, null, "user:vip-42",
                List.of(), List.of(), List.of(), null, null))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.INVALID_INPUT));
        verify(queries, never()).inbox(any(), any(), any(), any(Integer.class), any());
    }
}
