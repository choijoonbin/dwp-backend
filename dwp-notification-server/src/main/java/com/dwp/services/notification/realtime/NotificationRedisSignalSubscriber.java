package com.dwp.services.notification.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public final class NotificationRedisSignalSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationRedisSignalSubscriber.class);

    private final NotificationRedisSignalCodec codec;
    private final NotificationStreamService streamService;

    public NotificationRedisSignalSubscriber(
            NotificationRedisSignalCodec codec,
            NotificationStreamService streamService) {
        this.codec = codec;
        this.streamService = streamService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            streamService.dispatch(codec.decode(message.getBody()));
        } catch (RuntimeException exception) {
            log.warn(
                    "Rejected malformed notification realtime signal errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
