package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessagingRedisChannelsTest {

    @Test
    void assignsStableDurableAndTypingChannelsAcrossAllConfiguredShards() {
        MessagingRedisChannels channels =
                new MessagingRedisChannels("dwp.messaging.signal.v1", "dwp.messaging.typing.v1", 8);
        UUID conversationId = UUID.randomUUID();

        assertThat(channels.channel(1, conversationId))
                .isEqualTo(channels.channel(1, conversationId))
                .startsWith("dwp.messaging.signal.v1.");
        assertThat(channels.typingChannel(1, conversationId))
                .startsWith("dwp.messaging.typing.v1.");
        assertThat(channels.topics()).hasSize(8);
        assertThat(channels.typingTopics()).hasSize(8);
    }

    @Test
    void rejectsUnsafePrefixesAndUnboundedShardCounts() {
        assertThatThrownBy(() -> new MessagingRedisChannels("bad prefix", "typing.ok", 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessagingRedisChannels("signal.ok", "bad prefix", 8))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessagingRedisChannels("signal.ok", "typing.ok", 129))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
