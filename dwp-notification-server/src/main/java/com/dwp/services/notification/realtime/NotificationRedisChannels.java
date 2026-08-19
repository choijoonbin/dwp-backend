package com.dwp.services.notification.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Component
public final class NotificationRedisChannels {

    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9._:-]{3,120}");

    private final String prefix;
    private final int shardCount;

    public NotificationRedisChannels(
            @Value("${dwp.notification.realtime.channel-prefix:dwp.notification.signal.v1}")
            String prefix,
            @Value("${dwp.notification.realtime.shard-count:32}") int shardCount) {
        String normalized = prefix == null ? "" : prefix.trim();
        if (!SAFE_PREFIX.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Notification Redis channel prefix is invalid.");
        }
        if (shardCount < 1 || shardCount > 128) {
            throw new IllegalArgumentException("Notification Redis shard count must be 1 to 128.");
        }
        this.prefix = normalized;
        this.shardCount = shardCount;
    }

    public String channel(long tenantId, long userId) {
        long hash = 31L * tenantId + userId;
        return prefix + "." + Math.floorMod(hash, shardCount);
    }

    public List<ChannelTopic> topics() {
        return IntStream.range(0, shardCount)
                .mapToObj(shard -> new ChannelTopic(prefix + "." + shard))
                .toList();
    }

    int shardCount() {
        return shardCount;
    }
}
