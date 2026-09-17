package com.dwp.services.approval.incidents;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentRedactorTest {
    @Test
    void recursivelyRemovesCredentialsRawPayloadsAndTokenLikeValues() {
        Map<String, Object> redacted = IncidentRedactor.redact(Map.of(
                "status", 503,
                "authorization", "Bearer sensitive",
                "request", Map.of(
                        "payload", Map.of("employee", "A"),
                        "message", "Bearer abcdefghijklmnopqrstuvwxyz0123456789"),
                "events", List.of(Map.of("code", "TIMEOUT"))));

        assertThat(redacted).containsEntry("authorization", "[REDACTED]");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) redacted.get("request");
        assertThat(request).containsEntry("payload", "[REDACTED]")
                .containsEntry("message", "[REDACTED]");
    }

    @Test
    void rejectsUnsupportedObjectsAndUnboundedNesting() {
        assertThatThrownBy(() -> IncidentRedactor.redact(Map.of("socket", new Object())))
                .isInstanceOf(IncidentRejected.class);

        Map<String, Object> nested = Map.of("leaf", "ok");
        for (int depth = 0; depth < 10; depth++) nested = Map.of("next", nested);
        Map<String, Object> tooDeep = nested;
        assertThatThrownBy(() -> IncidentRedactor.redact(tooDeep))
                .isInstanceOf(IncidentRejected.class);
    }
}
