package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WidgetRegistryTransitionMatrixTest {
    private final WidgetRegistryResponseMapper mapper =
            new WidgetRegistryResponseMapper(mock(WidgetRendererBindingRepository.class));

    @Test
    void workflowAndReleaseTransitionsAreClosedAndStateDependent() {
        assertTransitions(version("DRAFT", "UNPUBLISHED", "CLEAR"), "VALIDATE");
        assertTransitions(version("VALIDATED", "UNPUBLISHED", "CLEAR"), "SUBMIT");
        assertTransitions(version("SUBMITTED", "UNPUBLISHED", "CLEAR"), "APPROVE", "REJECT");
        assertTransitions(version("REJECTED", "UNPUBLISHED", "CLEAR"), "REWORK");
        assertTransitions(version("APPROVED", "UNPUBLISHED", "CLEAR"), "PUBLISH");
        assertTransitions(version("APPROVED", "PUBLISHED", "CLEAR"),
                "BLOCK", "DEPRECATE", "QUARANTINE", "REVOKE");
        assertTransitions(version("APPROVED", "BLOCKED", "CLEAR"),
                "QUARANTINE", "REVOKE");
        assertTransitions(version("APPROVED", "DEPRECATED", "CLEAR"),
                "BLOCK", "QUARANTINE", "REVOKE");
        assertTransitions(version("APPROVED", "BLOCKED", "QUARANTINED"), "REVOKE");
        assertTransitions(version("APPROVED", "BLOCKED", "REVOKED"));
    }

    private void assertTransitions(WidgetDefinitionVersion value, String... transitions) {
        assertThat(mapper.version(value).allowedTransitions()).containsExactly(transitions);
    }

    private WidgetDefinitionVersion version(String workflow, String release, String safety) {
        return WidgetDefinitionVersion.builder()
                .versionId(UUID.randomUUID())
                .definitionId(UUID.randomUUID())
                .semanticVersion("1.0.0")
                .manifest(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                .manifestHash("a".repeat(64))
                .rendererKey("home.focus")
                .workflowState(workflow)
                .releaseState(release)
                .safetyState(safety)
                .attestation(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode())
                .certificationStatus("NOT_RUN")
                .build();
    }
}
