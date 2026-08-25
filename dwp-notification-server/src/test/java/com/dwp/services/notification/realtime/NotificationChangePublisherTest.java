package com.dwp.services.notification.realtime;

import com.dwp.services.notification.domain.NotificationModels.ChangeSignal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationChangePublisherTest {

    @Test
    void coalescesSignalsPerUserAtHighestVersion() {
        UUID first = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("40000000-0000-0000-0000-000000000002");

        List<NotificationRealtimeEnvelope> result = NotificationChangePublisher.coalesce(List.of(
                new ChangeSignal(1, 9, 4, first),
                new ChangeSignal(1, 9, 6, second),
                new ChangeSignal(1, 9, 5, first)));

        assertThat(result).containsExactly(new NotificationRealtimeEnvelope(
                1, 9, "6", "6", List.of(first, second), List.of(first, second)));
    }

    @Test
    void triageSignalsRefreshChangedIdsWithoutCreatingArrivalCandidates() {
        UUID notificationId = UUID.fromString("40000000-0000-0000-0000-000000000001");

        List<NotificationRealtimeEnvelope> result = NotificationChangePublisher.coalesce(
                List.of(new ChangeSignal(1, 9, 7, notificationId)),
                NotificationChangeCause.USER_TRIAGE);

        assertThat(result).containsExactly(new NotificationRealtimeEnvelope(
                1, 9, "7", "7", List.of(notificationId), List.of()));
    }

    @Test
    void redisFailureNeverEscapesAfterDurableDatabaseCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        NotificationChangePublisher publisher = new NotificationChangePublisher(
                redis,
                new NotificationRedisChannels("dwp.notification.test", 8),
                new NotificationRedisSignalCodec(new ObjectMapper().findAndRegisterModules()),
                true);
        ChangeSignal signal = new ChangeSignal(
                1, 9, 3, UUID.fromString("40000000-0000-0000-0000-000000000001"));

        assertThatCode(() -> publisher.publishAfterCommit(List.of(signal)))
                .doesNotThrowAnyException();
        verify(redis).convertAndSend(anyString(), anyString());
    }

    @Test
    void shardSetIsFixedAndBounded() {
        NotificationRedisChannels channels = new NotificationRedisChannels(
                "dwp.notification.test", 32);

        assertThat(channels.topics()).hasSize(32);
        assertThat(channels.channel(1, 900018)).startsWith("dwp.notification.test.");
        assertThat(channels.channel(1, 900018)).isEqualTo(channels.channel(1, 900018));
    }
}
