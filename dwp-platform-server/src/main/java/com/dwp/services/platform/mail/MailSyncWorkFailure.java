package com.dwp.services.platform.mail;

import java.time.OffsetDateTime;
import java.util.Map;

/** Worker-facing synchronization failure with retry and reset disposition evidence. */
final class MailSyncWorkFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String code;
    private final boolean terminal;
    private final String classification;
    private final OffsetDateTime retryAt;
    private final transient Map<String, Object> resetEvidence;

    MailSyncWorkFailure(String code, boolean terminal) {
        this(code, terminal, terminal ? "PERMANENT" : "TRANSIENT", null, Map.of());
    }

    MailSyncWorkFailure(
            String code,
            boolean terminal,
            String classification,
            OffsetDateTime retryAt,
            Map<String, Object> resetEvidence) {
        super(code);
        this.code = code;
        this.terminal = terminal;
        this.classification = classification;
        this.retryAt = retryAt;
        this.resetEvidence = resetEvidence == null ? Map.of() : Map.copyOf(resetEvidence);
    }

    String code() {
        return code;
    }

    boolean terminal() {
        return terminal;
    }

    String classification() {
        return classification;
    }

    OffsetDateTime retryAt() {
        return retryAt;
    }

    Map<String, Object> resetEvidence() {
        return resetEvidence;
    }
}
