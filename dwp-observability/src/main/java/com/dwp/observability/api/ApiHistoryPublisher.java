package com.dwp.observability.api;

/** Non-blocking event port. API processing must never wait for the history store. */
@FunctionalInterface
public interface ApiHistoryPublisher extends AutoCloseable {

    ApiHistoryPublisher NOOP = event -> { };

    void publish(ApiHistoryEvent event);

    @Override
    default void close() {
        // Most publishers do not own resources.
    }
}
