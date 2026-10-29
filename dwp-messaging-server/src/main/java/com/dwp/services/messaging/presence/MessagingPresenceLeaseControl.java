package com.dwp.services.messaging.presence;

/** Controls the verified user's ephemeral lease without changing durable privacy consent. */
@FunctionalInterface
public interface MessagingPresenceLeaseControl {
    boolean withdraw(long tenantId, long userId);
}
