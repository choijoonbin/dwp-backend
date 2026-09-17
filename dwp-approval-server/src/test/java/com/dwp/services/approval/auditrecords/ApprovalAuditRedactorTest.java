package com.dwp.services.approval.auditrecords;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalAuditRedactorTest {
    private final ApprovalAuditRedactor redactor =
            new ApprovalAuditRedactor(new ObjectMapper().findAndRegisterModules());

    @Test
    void metadataAndPrivilegedViewsNeverExposeSecrets() {
        Map<String, Object> source = Map.of(
                "stepKey", "finance-review",
                "payload", Map.of("amount", 1000, "password", "not-visible"),
                "accessToken", "not-visible",
                "items", List.of(Map.of("credential", "not-visible")));

        Map<String, Object> metadata = redactor.redact(
                source, ApprovalAuditModels.AccessLevel.METADATA);
        Map<String, Object> privileged = redactor.redact(
                source, ApprovalAuditModels.AccessLevel.PRIVILEGED);

        assertThat(metadata).containsEntry("stepKey", "finance-review")
                .containsEntry("accessToken", "[REDACTED]")
                .doesNotContainKey("payload");
        assertThat(privileged.toString()).doesNotContain("not-visible")
                .contains("[REDACTED]");
    }

    @Test
    void privilegedActorsArePseudonymousAndAuditorsCanSeeTheIdentifier() {
        assertThat(redactor.actorIdentifier(
                "user-17", ApprovalAuditModels.AccessLevel.METADATA)).isNull();
        assertThat(redactor.actorIdentifier(
                "user-17", ApprovalAuditModels.AccessLevel.PRIVILEGED))
                .startsWith("pseudonym:").doesNotContain("user-17");
        assertThat(redactor.actorIdentifier(
                "user-17", ApprovalAuditModels.AccessLevel.AUDITOR))
                .isEqualTo("user-17");
    }
}
