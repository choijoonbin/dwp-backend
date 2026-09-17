package com.dwp.services.platform.workplace.workplaceassistant;

import org.junit.jupiter.api.Test;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.RedactionState.APPLIED;
import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.RedactionState.NOT_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceAssistantRedactorTest {
    private final WorkplaceAssistantRedactor redactor = new WorkplaceAssistantRedactor();

    @Test
    void removesContactAndCredentialMaterialBeforePersistence() {
        var result = redactor.redact(
                "Contact jane@example.com or +82 10-1234-5678; api_key=super-secret "
                        + "Authorization: Bearer abc.def.ghi");

        assertThat(result.state()).isEqualTo(APPLIED);
        assertThat(result.value())
                .doesNotContain("jane@example.com", "10-1234-5678", "super-secret",
                        "abc.def.ghi")
                .contains("[email redacted]", "[phone redacted]", "[secret redacted]",
                        "[token redacted]");
    }

    @Test
    void preservesOrdinaryBookingConstraints() {
        var result = redactor.redact("Quiet accessible desk near the elevator");
        assertThat(result.state()).isEqualTo(NOT_REQUIRED);
        assertThat(result.value()).isEqualTo("Quiet accessible desk near the elevator");
    }
}
