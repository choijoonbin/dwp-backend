package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationApiContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void controllerAndOwnerPepExposeExactlyTwentyClosedOperations() {
        long get = Arrays.stream(IntegrationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class)).count();
        long post = Arrays.stream(IntegrationController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class)).count();

        assertThat(get).isEqualTo(4);
        assertThat(post).isEqualTo(16);
        assertThat(IntegrationOwnerAuthorityFilter.bindingContracts()).hasSize(20);
        assertThat(Sys008ProjectionContract.ACTION_KEYS).hasSize(16)
                .containsExactlyInAnyOrder(
                        IntegrationService.CREATE_CONNECTOR,
                        IntegrationService.TEST_CONNECTOR,
                        IntegrationService.DRY_RUN_MAPPING,
                        IntegrationService.CREATE_EXECUTION,
                        IntegrationService.VALIDATE_DEFINITION,
                        IntegrationService.SUBMIT_DEFINITION,
                        IntegrationService.APPROVE_DEFINITION,
                        IntegrationService.ACTIVATE_DEFINITION,
                        IntegrationService.CREATE_MAPPING,
                        IntegrationService.VALIDATE_MAPPING,
                        IntegrationService.SUBMIT_MAPPING,
                        IntegrationService.APPROVE_MAPPING,
                        IntegrationService.ACTIVATE_MAPPING,
                        IntegrationService.RECONCILE_EXECUTION,
                        IntegrationService.RETRY_EXECUTION,
                        IntegrationService.CANCEL_EXECUTION);
        assertThat(IntegrationOwnerAuthorityFilter.bindingContracts()
                .stream().filter(IntegrationOwnerAuthorityFilter.Binding::cas).count())
                .isEqualTo(12);
    }

    @Test
    void allOwnerBeansAreDefaultOffAndNoRuntimeSchedulerExists() {
        for (Class<?> type : List.of(
                IntegrationController.class, IntegrationService.class,
                IntegrationRuntimeService.class, IntegrationOwnerQueryService.class,
                IntegrationOwnerAuthorityFilter.class, IntegrationCursorCodec.class,
                JdbcIntegrationRepository.class)) {
            ConditionalOnProperty gate = type.getAnnotation(ConditionalOnProperty.class);
            assertThat(gate).as(type.getSimpleName()).isNotNull();
            assertThat(gate.name()).containsExactly(
                    "dwp.hris.integration-control-plane.enabled");
            assertThat(gate.havingValue()).isEqualTo("true");
            assertThat(gate.matchIfMissing()).isFalse();
        }
        assertThat(Arrays.stream(IntegrationRuntimeService.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class))).isTrue();
        assertThat(IntegrationRuntimePorts.PRODUCTION_PROVIDER_BINDING_COUNT).isZero();
        assertThat(Sys008OpaqueReferenceOwnerPort.PRODUCTION_PROVIDER_BINDING_COUNT)
                .isZero();
    }

    @Test
    void commandBodiesRejectUnknownPropertiesAndLiteralSecrets() throws Exception {
        assertThatThrownBy(() -> json.readValue("""
                {"connectorKey":"payroll.bank","type":"HTTPS",
                 "endpoint":"https://provider.example/hris",
                 "secretRef":"secret_abcdefghijklmnop",
                 "egressPolicyKey":"bank-egress","tenantId":42}
                """, IntegrationDtos.ConnectorCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown connector command property");

        var literal = new IntegrationDtos.ConnectorCommand(
                "payroll.bank", IntegrationDtos.ConnectorType.HTTPS,
                java.net.URI.create("https://provider.example/hris"),
                "literal-password-value", "bank-egress");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(literal))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("secretRef");
        }
    }

    @Test
    void wireResponsesMatchTheFrontendClosedShapes() {
        assertThat(Arrays.stream(
                IntegrationDtos.ExecutionResponse.class.getRecordComponents())
                .map(component -> component.getName()).toList()).containsExactly(
                "executionId", "mappingVersion", "state", "sourceCount",
                "acceptedCount", "rejectedCount", "selectedItemCount",
                "selectedItemDigest", "reconciliationDigest", "aggregateVersion");
        assertThat(Arrays.stream(
                Sys008ProjectionContract.ProjectionResponse.class.getRecordComponents())
                .map(component -> component.getName()).toList()).containsExactly(
                "projection", "state", "items");
        assertThat(Sys008ProjectionContract.CURSOR_BINDINGS).hasSize(11);
        assertThat(Sys008ProjectionContract.PROJECTIONS.values())
                .allSatisfy(spec -> assertThat(spec.sorts()).hasSize(8));
    }
}
