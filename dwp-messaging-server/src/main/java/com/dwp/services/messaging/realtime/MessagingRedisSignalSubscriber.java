package com.dwp.services.messaging.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public final class MessagingRedisSignalSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(MessagingRedisSignalSubscriber.class);

    private final MessagingRedisSignalCodec codec;
    private final MessagingStreamService streams;

    public MessagingRedisSignalSubscriber(
            MessagingRedisSignalCodec codec,
            MessagingStreamService streams) {
        this.codec = codec;
        this.streams = streams;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            streams.wakeUp(codec.decode(message.getBody()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Rejected malformed messaging realtime signal errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
