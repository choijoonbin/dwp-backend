package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutomationApiContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void controllerMountsExactlySixteenClosedOperations() {
        long operations = Arrays.stream(AutomationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)
                        || method.isAnnotationPresent(PostMapping.class))
                .count();

        assertThat(operations).isEqualTo(16);
        assertThat(Arrays.stream(AutomationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .count()).isEqualTo(2);
        assertThat(Arrays.stream(AutomationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .count()).isEqualTo(14);
    }

    @Test
    void allOwnerBeansAreDefaultDisabledAndRuntimeHasNoScheduler() {
        for (Class<?> type : List.of(
                AutomationController.class, AutomationService.class,
                AutomationRuntimeService.class, AutomationOwnerAuthorityFilter.class,
                AutomationCursorCodec.class, JdbcAutomationRepository.class)) {
            ConditionalOnProperty gate = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).as(type.getSimpleName()).isNotNull();
            assertThat(gate.name()).containsExactly(
                    "dwp.hris.automation-control-plane.enabled");
            assertThat(gate.havingValue()).isEqualTo("true");
            assertThat(gate.matchIfMissing()).isFalse();
        }
        assertThat(Arrays.stream(AutomationRuntimeService.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class))).isTrue();
    }

    @Test
    void commandBodiesRejectUnknownPropertiesAndQueueScopeIdIsNullable()
            throws Exception {
        assertThatThrownBy(() -> json.readValue("""
                {"automationKey":"payroll-close","displayName":"Payroll close",
                 "description":null,"ownerDomain":"PAY","executableClass":"Bad"}
                """, AutomationDtos.CreateDefinitionCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown create-definition property");

        assertThatThrownBy(() -> json.readValue("""
                {"scopeType":"TENANT","scopeId":null,"inputReference":"input:006",
                 "inputDigest":"%s","expectedItemCount":0,"tenantId":42}
                """.formatted("a".repeat(64)), AutomationDtos.QueueRunCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown queue-run property");

        var queue = new AutomationDtos.QueueRunCommand(
                "TENANT", null, "input:006", "a".repeat(64), 0L);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(queue)).isEmpty();
        }
    }

    @Test
    void createVersionWireHasTheExactTwentyTwoSealedFieldsAndSixScopeVocabulary() {
        assertThat(Arrays.stream(
                        AutomationDtos.CreateVersionCommand.class.getRecordComponents())
                .map(RecordComponent::getName).toList()).containsExactly(
                "definitionPayload", "definitionDigest", "executionPolicy",
                "executionPolicyDigest", "manualConcurrencyKeyTemplate",
                "compensationPolicyRef", "compensationPolicyDigest",
                "handlerOwnerService", "handlerKey", "handlerContractVersion",
                "handlerManifestRevision", "handlerManifestDigest",
                "signatureKeyId", "signature", "signatureDigest",
                "inputSchemaRef", "inputSchemaDigest", "receiptSchemaRef",
                "receiptSchemaDigest", "allowedScopeTypes", "retryMode",
                "maxAttempts");

        assertThatThrownBy(() -> new AutomationDtos.CreateVersionCommand(
                json.createObjectNode(), "a".repeat(64), json.createObjectNode(),
                "b".repeat(64), "{tenantId}", null, "c".repeat(64),
                "dwp-pay-server", "hris.payroll", "AutomationInvocation.v1",
                1, "d".repeat(64), "key-1", "signature",
                "e".repeat(64), "schema:input", "f".repeat(64),
                "schema:receipt", "0".repeat(64), List.of("PAY_GROUP"),
                AutomationDtos.RetryMode.NONE, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical PDX-004 scope types");
    }

    @Test
    void typedArtifactWireUsesCanonicalKeySchemaVersionAndReferenceBounds() {
        var invalidVersion = new AutomationDtos.CreateVersionCommand(
                json.createObjectNode(), "a".repeat(64), json.createObjectNode(),
                "b".repeat(64), "{tenantId}", null, "c".repeat(64),
                "DWP-PAY-SERVER", "hris.payroll", "AutomationInvocation",
                1, "d".repeat(64), "key-1", "signature",
                "e".repeat(64), "schema:" + "i".repeat(506), "f".repeat(64),
                "schema:receipt", "0".repeat(64), List.of("TENANT"),
                AutomationDtos.RetryMode.NONE, 1);
        var validBoundaryQueue = new AutomationDtos.QueueRunCommand(
                "TENANT", null, "x".repeat(512), "a".repeat(64), 0L);
        var invalidQueue = new AutomationDtos.QueueRunCommand(
                "PAY_GROUP", null, "x".repeat(513), "a".repeat(64), 0L);

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(invalidVersion))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("handlerOwnerService", "handlerContractVersion",
                            "inputSchemaRef");
            assertThat(validator.validate(validBoundaryQueue)).isEmpty();
            assertThat(validator.validate(invalidQueue))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("scopeType", "inputReference");
        }
    }

    @Test
    void committedReconciliationEvidenceRequiresAReceiptDigest() {
        assertThatThrownBy(() -> new AutomationRuntimePorts.ReconciliationEvidence(
                "COMMITTED", null, "a".repeat(64),
                OffsetDateTime.parse("2026-10-21T00:00:00Z"),
                OffsetDateTime.parse("2026-10-21T00:01:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Closed trusted reconciliation evidence");
    }
}
