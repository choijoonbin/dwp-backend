package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationCounter;
import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationSummary;
import com.dwp.services.notification.domain.NotificationAppSummaryRepository.AppSummaryMetadata;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAppSummaryServiceTest {

    private static final NotificationRequestContext.Actor ACTOR =
            new NotificationRequestContext.Actor(
                    42, 900018L, Set.of(), Set.of(), false, "dwp-gateway");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-21T00:30:00Z");
    private static final Instant QUERY_SNAPSHOT = Instant.parse("2026-08-25T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(QUERY_SNAPSHOT, ZoneOffset.UTC);

    @Test
    void returnsContentFreeBulkCountersWithFreshnessVersions() {
        NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
        NotificationAppSummaryRepository repository =
                mock(NotificationAppSummaryRepository.class);
        when(repository.metadata(ACTOR))
                .thenReturn(Optional.of(new AppSummaryMetadata(54, UPDATED_AT)));
        when(repository.unreadByApp(ACTOR, 101)).thenReturn(List.of(
                new AppNotificationCounter("approvals", 3, 3, 1, UPDATED_AT),
                new AppNotificationCounter("messaging", 6, 2, 0, UPDATED_AT)));
        NotificationAppSummaryService service =
                new NotificationAppSummaryService(databaseScope, repository, CLOCK);

        AppNotificationSummary result = service.summary(ACTOR);

        assertThat(result.partial()).isFalse();
        assertThat(result.unavailableSources()).isEmpty();
        assertThat(result.apps()).extracting(AppNotificationCounter::appKey)
                .containsExactly("approvals", "messaging");
        assertThat(result.changeVersion()).isEqualTo("54");
        assertThat(result.counterVersion()).isEqualTo("54");
        assertThat(result.generatedAt()).isEqualTo(QUERY_SNAPSHOT);
        assertThat(result.apps()).allSatisfy(counter ->
                assertThat(counter.lastActivityAt()).isEqualTo(UPDATED_AT));
        verify(databaseScope).applyUser(ACTOR);
    }

    @Test
    void representsAUserWithoutAProjectionAsAnEmptyCurrentSummary() {
        NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
        NotificationAppSummaryRepository repository =
                mock(NotificationAppSummaryRepository.class);
        when(repository.metadata(ACTOR)).thenReturn(Optional.empty());
        when(repository.unreadByApp(ACTOR, 101)).thenReturn(List.of());
        NotificationAppSummaryService service =
                new NotificationAppSummaryService(databaseScope, repository);

        Instant before = Instant.now();
        AppNotificationSummary result = service.summary(ACTOR);
        Instant after = Instant.now();

        assertThat(result.partial()).isFalse();
        assertThat(result.apps()).isEmpty();
        assertThat(result.changeVersion()).isEqualTo("0");
        assertThat(result.counterVersion()).isEqualTo("0");
        assertThat(result.generatedAt()).isBetween(before, after);
    }

    @Test
    void isolatesProjectionFailureAsAnExplicitPartialSummary() {
        NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
        NotificationAppSummaryRepository repository =
                mock(NotificationAppSummaryRepository.class);
        when(repository.metadata(ACTOR))
                .thenReturn(Optional.of(new AppSummaryMetadata(54, UPDATED_AT)));
        when(repository.unreadByApp(ACTOR, 101))
                .thenThrow(new DataAccessResourceFailureException("projection unavailable"));
        NotificationAppSummaryService service =
                new NotificationAppSummaryService(databaseScope, repository);

        AppNotificationSummary result = service.summary(ACTOR);

        assertThat(result.partial()).isTrue();
        assertThat(result.unavailableSources())
                .containsExactly(NotificationAppSummaryService.PROJECTION_UNAVAILABLE);
        assertThat(result.apps()).isEmpty();
        assertThat(result.changeVersion()).isEqualTo("54");
    }

    @Test
    void boundsTheSingleBulkResponseAndMarksTruncation() {
        NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
        NotificationAppSummaryRepository repository =
                mock(NotificationAppSummaryRepository.class);
        when(repository.metadata(ACTOR))
                .thenReturn(Optional.of(new AppSummaryMetadata(54, UPDATED_AT)));
        List<AppNotificationCounter> rows = IntStream.rangeClosed(0, 100)
                .mapToObj(index -> new AppNotificationCounter(
                        "app-" + index, 1, 0, 0, UPDATED_AT))
                .toList();
        when(repository.unreadByApp(ACTOR, 101)).thenReturn(rows);
        NotificationAppSummaryService service =
                new NotificationAppSummaryService(databaseScope, repository);

        AppNotificationSummary result = service.summary(ACTOR);

        assertThat(result.partial()).isTrue();
        assertThat(result.apps()).hasSize(100);
        assertThat(result.unavailableSources())
                .containsExactly(NotificationAppSummaryService.SUMMARY_LIMIT_REACHED);
    }
}
