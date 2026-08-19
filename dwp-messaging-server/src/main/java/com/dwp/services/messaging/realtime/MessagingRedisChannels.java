package com.dwp.services.messaging.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Component
public final class MessagingRedisChannels {

    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9._:-]{3,120}");

    private final String prefix;
    private final String typingPrefix;
    private final int shardCount;

    public MessagingRedisChannels(
            @Value("${dwp.messaging.realtime.channel-prefix:dwp.messaging.signal.v1}") String prefix,
            @Value("${dwp.messaging.realtime.typing-channel-prefix:dwp.messaging.typing.v1}")
            String typingPrefix,
            @Value("${dwp.messaging.realtime.shard-count:32}") int shardCount) {
        String normalized = prefix == null ? "" : prefix.trim();
        String normalizedTyping = typingPrefix == null ? "" : typingPrefix.trim();
        if (!SAFE_PREFIX.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Messaging Redis channel prefix is invalid.");
        }
        if (!SAFE_PREFIX.matcher(normalizedTyping).matches()) {
            throw new IllegalArgumentException("Messaging typing Redis channel prefix is invalid.");
        }
        if (shardCount < 1 || shardCount > 128) {
            throw new IllegalArgumentException("Messaging Redis shard count must be 1 to 128.");
        }
        this.prefix = normalized;
        this.typingPrefix = normalizedTyping;
        this.shardCount = shardCount;
    }

    public String channel(long tenantId, UUID conversationId) {
        return prefix + "." + shard(tenantId, conversationId);
    }

    public List<ChannelTopic> topics() {
        return IntStream.range(0, shardCount)
                .mapToObj(shard -> new ChannelTopic(prefix + "." + shard))
                .toList();
    }

    public String typingChannel(long tenantId, UUID conversationId) {
        return typingPrefix + "." + shard(tenantId, conversationId);
    }

    public List<ChannelTopic> typingTopics() {
        return IntStream.range(0, shardCount)
                .mapToObj(shard -> new ChannelTopic(typingPrefix + "." + shard))
                .toList();
    }

    private int shard(long tenantId, UUID conversationId) {
        long hash = 31L * tenantId + (conversationId == null ? 0 : conversationId.hashCode());
        return Math.floorMod(hash, shardCount);
    }

    int shardCount() {
        return shardCount;
    }
}
