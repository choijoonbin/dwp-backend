package com.dwp.services.messaging.presence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Reports partial presence degradation without making the Messaging process itself unavailable. */
@Component("messagingPresenceHealth")
public final class MessagingPresenceHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;

    public MessagingPresenceHealthIndicator(
            StringRedisTemplate redisTemplate,
            @Value("${dwp.messaging.presence.enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.unknown()
                    .withDetail("authority", "DISABLED")
                    .build();
        }
        try {
            String reply = redisTemplate.execute(
                    (RedisCallback<String>) connection -> connection.commands().ping());
            if ("PONG".equalsIgnoreCase(reply)) {
                return Health.up()
                        .withDetail("authority", "LIVE")
                        .build();
            }
            return unavailable("UnexpectedReply");
        } catch (RuntimeException exception) {
            return unavailable(exception.getClass().getSimpleName());
        }
    }

    private Health unavailable(String errorType) {
        return Health.unknown()
                .withDetail("authority", "UNAVAILABLE")
                .withDetail("errorType", errorType)
                .build();
    }
}
