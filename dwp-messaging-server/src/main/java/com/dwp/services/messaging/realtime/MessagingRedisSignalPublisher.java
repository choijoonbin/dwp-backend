package com.dwp.services.messaging.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public final class MessagingRedisSignalPublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagingRedisSignalPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final MessagingRedisChannels channels;
    private final MessagingRedisSignalCodec codec;
    private final boolean enabled;

    public MessagingRedisSignalPublisher(
            StringRedisTemplate redisTemplate,
            MessagingRedisChannels channels,
            MessagingRedisSignalCodec codec,
            @Value("${dwp.messaging.realtime.redis-enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.channels = channels;
        this.codec = codec;
        this.enabled = enabled;
    }

    public void publish(MessagingRealtimeSignal signal) {
        if (!enabled) {
            log.debug("Messaging Redis wake-up publishing is disabled");
            return;
        }
        try {
            redisTemplate.convertAndSend(
                    channels.channel(signal.tenantId(), signal.conversationId()),
                    codec.encode(signal));
        } catch (RuntimeException exception) {
            // The signal is expendable: active local clients were already notified and remote
            // clients recover from msg_realtime_events on heartbeat or SSE reconnect.
            log.warn(
                    "Messaging Redis wake-up publish failed; durable replay remains authoritative"
                            + " tenantId={} conversationId={} eventSequence={} errorType={}",
                    signal.tenantId(),
                    signal.conversationId(),
                    signal.eventSequence(),
                    exception.getClass().getSimpleName());
        }
    }
}
