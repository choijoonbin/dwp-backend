package com.dwp.services.messaging.presence;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class MessagingPresenceHealthIndicatorTest {

    @Test
    void reportsLiveAuthorityWithoutChangingOverallReadinessSemantics() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class))).thenReturn("PONG");

        var health = new MessagingPresenceHealthIndicator(redis, true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("authority", "LIVE");
    }

    @Test
    void reportsUnknownForPartialRedisDegradation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisCallback.class)))
                .thenThrow(new IllegalStateException("unavailable"));

        var health = new MessagingPresenceHealthIndicator(redis, true).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails())
                .containsEntry("authority", "UNAVAILABLE")
                .containsEntry("errorType", "IllegalStateException");
    }

    @Test
    void reportsDisabledAuthorityWithoutTouchingRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        var health = new MessagingPresenceHealthIndicator(redis, false).health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("authority", "DISABLED");
    }
}
