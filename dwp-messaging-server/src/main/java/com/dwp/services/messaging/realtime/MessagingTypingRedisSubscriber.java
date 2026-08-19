package com.dwp.services.messaging.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public final class MessagingTypingRedisSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(MessagingTypingRedisSubscriber.class);

    private final MessagingTypingRedisCodec codec;
    private final MessagingTypingService typing;

    public MessagingTypingRedisSubscriber(
            MessagingTypingRedisCodec codec,
            MessagingTypingService typing) {
        this.codec = codec;
        this.typing = typing;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            typing.acceptRemote(codec.decode(message.getBody()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Rejected malformed messaging typing signal errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
