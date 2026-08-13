package com.dwp.core.event;

/** Pure ordering decision shared by JDBC delivery and deterministic tests. */
public final class DomainEventOrderingPolicy {

    private DomainEventOrderingPolicy() {
    }

    public static Decision decide(long lastAppliedSequence, long incomingSequence) {
        if (lastAppliedSequence < 0 || incomingSequence < 1) {
            throw new IllegalArgumentException("Domain-event sequences are invalid.");
        }
        if (incomingSequence <= lastAppliedSequence) return Decision.DUPLICATE;
        if (incomingSequence == lastAppliedSequence + 1) return Decision.ACCEPT;
        return Decision.OUT_OF_ORDER;
    }

    public enum Decision {
        ACCEPT,
        DUPLICATE,
        OUT_OF_ORDER
    }
}
