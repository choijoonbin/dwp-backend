package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingRealtimeFanoutProbeTest {

    private static final OffsetDateTime VERIFIED_AT =
            OffsetDateTime.parse("2026-10-28T10:00:00Z");

    @Test
    void reportsReadyOnlyAfterTheDedicatedSubscriberAcknowledgesTheNonce() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessagingRedisChannels channels =
                new MessagingRedisChannels("signal.valid", "typing.valid", 2);
        MessagingRealtimeFanoutProbe probe = new MessagingRealtimeFanoutProbe(
                redis, channels, true, Duration.ofMillis(100));
        when(redis.convertAndSend(eq(channels.readinessChannel()), anyString()))
                .thenAnswer(invocation -> {
                    Message message = mock(Message.class);
                    when(message.getBody()).thenReturn(
                            invocation.getArgument(1, String.class)
                                    .getBytes(StandardCharsets.UTF_8));
                    probe.onMessage(message, null);
                    return 1L;
                });

        MessagingRealtimeFanoutProbe.ProbeResult result = probe.probe(VERIFIED_AT);

        assertThat(result.effective()).isTrue();
        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void publishWithoutAListenerCannotBeReportedAsReady() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MessagingRedisChannels channels =
                new MessagingRedisChannels("signal.valid", "typing.valid", 2);
        when(redis.convertAndSend(eq(channels.readinessChannel()), anyString())).thenReturn(0L);
        MessagingRealtimeFanoutProbe probe = new MessagingRealtimeFanoutProbe(
                redis, channels, true, Duration.ofMillis(100));

        MessagingRealtimeFanoutProbe.ProbeResult result = probe.probe(VERIFIED_AT);

        assertThat(result.effective()).isFalse();
        assertThat(result.state()).isEqualTo("DEGRADED");
        assertThat(result.reasonCode()).isEqualTo("REALTIME_FANOUT_NO_SUBSCRIBERS");
    }

    @Test
    void disabledRedisFanoutIsExplicit() {
        MessagingRealtimeFanoutProbe probe = new MessagingRealtimeFanoutProbe(
                mock(StringRedisTemplate.class),
                new MessagingRedisChannels("signal.valid", "typing.valid", 2),
                false,
                Duration.ofMillis(100));

        MessagingRealtimeFanoutProbe.ProbeResult result = probe.probe(VERIFIED_AT);

        assertThat(result.configured()).isFalse();
        assertThat(result.state()).isEqualTo("DISABLED");
        assertThat(result.reasonCode()).isEqualTo("REALTIME_FANOUT_DISABLED");
    }
}
