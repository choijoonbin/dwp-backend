package com.dwp.services.messaging.realtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public final class MessagingTypingService {

    private static final Logger log = LoggerFactory.getLogger(MessagingTypingService.class);
    private static final Pattern SAFE_KEY_PREFIX = Pattern.compile("[A-Za-z0-9._:-]{3,120}");

    private final MessagingRealtimeRepository repository;
    private final MessagingStreamService streams;
    private final StringRedisTemplate redisTemplate;
    private final MessagingRedisChannels channels;
    private final MessagingTypingRedisCodec codec;
    private final boolean redisEnabled;
    private final Duration ttl;
    private final String keyPrefix;

    public MessagingTypingService(
            MessagingRealtimeRepository repository,
            MessagingStreamService streams,
            StringRedisTemplate redisTemplate,
            MessagingRedisChannels channels,
            MessagingTypingRedisCodec codec,
            @Value("${dwp.messaging.realtime.redis-enabled:true}") boolean redisEnabled,
            @Value("${dwp.messaging.realtime.typing-ttl:8s}") Duration ttl,
            @Value("${dwp.messaging.realtime.typing-key-prefix:dwp:messaging:typing:v1}")
            String keyPrefix) {
        if (ttl.compareTo(Duration.ofSeconds(2)) < 0 || ttl.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Messaging typing TTL must be 2 to 30 seconds.");
        }
        String normalizedPrefix = keyPrefix == null ? "" : keyPrefix.trim();
        if (!SAFE_KEY_PREFIX.matcher(normalizedPrefix).matches()) {
            throw new IllegalArgumentException("Messaging typing Redis key prefix is invalid.");
        }
        this.repository = repository;
        this.streams = streams;
        this.redisTemplate = redisTemplate;
        this.channels = channels;
        this.codec = codec;
        this.redisEnabled = redisEnabled;
        this.ttl = ttl;
        this.keyPrefix = normalizedPrefix;
    }

    public void change(UUID conversationId, boolean started) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        if (!repository.isActiveConversationMember(
                subject.tenantId(), conversationId, subject.userId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Only active conversation members can publish typing state.");
        }
        OffsetDateTime changedAt = OffsetDateTime.now(ZoneOffset.UTC);
        MessagingTypingSignal signal = new MessagingTypingSignal(
                UUID.randomUUID(),
                subject.tenantId(),
                conversationId,
                subject.userId(),
                started,
                changedAt,
                started ? changedAt.plus(ttl) : changedAt);
        streams.dispatchTyping(signal);
        publishBestEffort(signal);
    }

    void acceptRemote(MessagingTypingSignal signal) {
        if (signal.started() && !signal.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) return;
        if (!repository.isActiveConversationMember(
                signal.tenantId(), signal.conversationId(), signal.userId())) {
            log.warn(
                    "Rejected messaging typing signal from a non-member"
                            + " tenantId={} userId={} conversationId={}",
                    signal.tenantId(), signal.userId(), signal.conversationId());
            return;
        }
        streams.dispatchTyping(signal);
    }

    private void publishBestEffort(MessagingTypingSignal signal) {
        if (!redisEnabled) return;
        String key = key(signal);
        try {
            if (signal.started()) {
                redisTemplate.opsForValue().set(key, signal.signalId().toString(), ttl);
            } else {
                redisTemplate.delete(key);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "Messaging typing TTL state update failed; durable messaging is unaffected"
                            + " tenantId={} conversationId={} userId={} errorType={}",
                    signal.tenantId(), signal.conversationId(), signal.userId(),
                    exception.getClass().getSimpleName());
        }
        try {
            redisTemplate.convertAndSend(
                    channels.typingChannel(signal.tenantId(), signal.conversationId()),
                    codec.encode(signal));
        } catch (RuntimeException exception) {
            log.warn(
                    "Messaging typing fanout failed; durable messaging is unaffected"
                            + " tenantId={} conversationId={} userId={} errorType={}",
                    signal.tenantId(), signal.conversationId(), signal.userId(),
                    exception.getClass().getSimpleName());
        }
    }

    private String key(MessagingTypingSignal signal) {
        return keyPrefix + ":" + signal.tenantId() + ":" + signal.conversationId()
                + ":" + signal.userId();
    }
}
