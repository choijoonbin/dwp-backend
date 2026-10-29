package com.dwp.services.messaging.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Proves the dedicated Redis pub/sub wake-up path without emitting a user-visible event. */
@Component
public final class MessagingRealtimeFanoutProbe implements MessageListener {

    private final StringRedisTemplate redisTemplate;
    private final MessagingRedisChannels channels;
    private final boolean enabled;
    private final Duration timeout;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending =
            new ConcurrentHashMap<>();

    public MessagingRealtimeFanoutProbe(
            StringRedisTemplate redisTemplate,
            MessagingRedisChannels channels,
            @Value("${dwp.messaging.realtime.redis-enabled:true}") boolean enabled,
            @Value("${dwp.messaging.realtime.readiness-probe-timeout:PT1S}") Duration timeout) {
        if (timeout == null
                || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException(
                    "Messaging realtime readiness probe timeout must be 100ms to 5s.");
        }
        this.redisTemplate = redisTemplate;
        this.channels = channels;
        this.enabled = enabled;
        this.timeout = timeout;
    }

    public ProbeResult probe(OffsetDateTime verifiedAt) {
        if (!enabled) {
            return new ProbeResult(
                    false, false, "DISABLED", "REALTIME_FANOUT_DISABLED", verifiedAt);
        }
        String nonce = UUID.randomUUID().toString();
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        pending.put(nonce, acknowledgement);
        try {
            Long subscribers = redisTemplate.convertAndSend(channels.readinessChannel(), nonce);
            if (subscribers == null || subscribers < 1) {
                return new ProbeResult(
                        true, false, "DEGRADED", "REALTIME_FANOUT_NO_SUBSCRIBERS", verifiedAt);
            }
            acknowledgement.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new ProbeResult(true, true, "READY", null, verifiedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ProbeResult(
                    true, false, "DEGRADED", "REALTIME_FANOUT_PROBE_INTERRUPTED", verifiedAt);
        } catch (RuntimeException exception) {
            return new ProbeResult(
                    true, false, "DEGRADED", "REALTIME_FANOUT_PUBLISH_FAILED", verifiedAt);
        } catch (Exception exception) {
            return new ProbeResult(
                    true, false, "DEGRADED", "REALTIME_FANOUT_ACK_TIMEOUT", verifiedAt);
        } finally {
            pending.remove(nonce);
        }
    }

    public boolean configured() {
        return enabled;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String nonce = new String(message.getBody(), StandardCharsets.UTF_8);
        CompletableFuture<Void> acknowledgement = pending.get(nonce);
        if (acknowledgement != null) acknowledgement.complete(null);
    }

    public record ProbeResult(
            boolean configured,
            boolean effective,
            String state,
            String reasonCode,
            OffsetDateTime verifiedAt) {
    }
}
