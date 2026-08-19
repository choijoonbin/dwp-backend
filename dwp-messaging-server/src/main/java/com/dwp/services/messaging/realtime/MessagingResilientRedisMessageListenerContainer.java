package com.dwp.services.messaging.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps Redis availability outside the messaging application's startup critical path. */
class MessagingResilientRedisMessageListenerContainer extends RedisMessageListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(
            MessagingResilientRedisMessageListenerContainer.class);

    private final long retryIntervalMillis;
    private final ScheduledExecutorService retryExecutor;
    private final AtomicBoolean lifecycleActive = new AtomicBoolean();
    private final AtomicBoolean retryScheduled = new AtomicBoolean();
    private final AtomicInteger failedAttempts = new AtomicInteger();

    MessagingResilientRedisMessageListenerContainer(long retryIntervalMillis) {
        if (retryIntervalMillis < 100) {
            throw new IllegalArgumentException("Messaging Redis retry interval must be at least 100ms.");
        }
        this.retryIntervalMillis = retryIntervalMillis;
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "messaging-redis-subscription-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start() {
        lifecycleActive.set(true);
        attemptStart();
    }

    @Override
    public void stop() {
        lifecycleActive.set(false);
        stopContainer();
    }

    @Override
    public void destroy() throws Exception {
        lifecycleActive.set(false);
        retryExecutor.shutdownNow();
        super.destroy();
    }

    void startContainer() {
        super.start();
    }

    void stopContainer() {
        super.stop();
    }

    private void attemptStart() {
        retryScheduled.set(false);
        if (!lifecycleActive.get() || super.isRunning()) return;
        try {
            startContainer();
            failedAttempts.set(0);
        } catch (RuntimeException exception) {
            stopContainer();
            int attempt = failedAttempts.incrementAndGet();
            if (attempt == 1 || attempt % 30 == 0) {
                log.warn(
                        "Messaging Redis subscription is unavailable; durable replay remains active"
                                + " attempt={} errorType={}",
                        attempt,
                        exception.getClass().getSimpleName());
            } else {
                log.debug(
                        "Messaging Redis subscription retry deferred attempt={} errorType={}",
                        attempt,
                        exception.getClass().getSimpleName());
            }
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (!lifecycleActive.get() || !retryScheduled.compareAndSet(false, true)) return;
        retryExecutor.schedule(this::attemptStart, retryIntervalMillis, TimeUnit.MILLISECONDS);
    }
}
