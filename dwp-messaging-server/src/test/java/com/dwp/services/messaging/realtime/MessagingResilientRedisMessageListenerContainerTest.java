package com.dwp.services.messaging.realtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MessagingResilientRedisMessageListenerContainerTest {

    @Test
    void initialRedisFailureDoesNotEscapeAndSubscriptionRetriesInTheBackground() throws Exception {
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        MessagingResilientRedisMessageListenerContainer container =
                new MessagingResilientRedisMessageListenerContainer(100) {
                    @Override
                    void startContainer() {
                        if (starts.incrementAndGet() == 1) {
                            throw new IllegalStateException("Redis is temporarily unavailable");
                        }
                        recovered.countDown();
                    }

                    @Override
                    void stopContainer() {
                        // The probe has no native Redis resources.
                    }
                };

        assertThatCode(container::start).doesNotThrowAnyException();
        assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(starts).hasValue(2);

        container.destroy();
    }
}
