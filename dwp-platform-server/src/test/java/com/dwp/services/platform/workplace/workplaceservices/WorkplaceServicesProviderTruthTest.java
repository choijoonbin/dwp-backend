package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkplaceServicesProviderTruthTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T00:00:00Z");

    @Test
    void providerReadinessRequiresMatchingEvidenceAndExternalFreshness() {
        assertThat(WorkplaceServicesService.providerState(
                catalog("external", false, 4, null, null, null, null), NOW))
                .isEqualTo(ProviderState.NOT_CONFIGURED);
        assertThat(WorkplaceServicesService.providerState(
                catalog("external", true, 4, null, null, null, null), NOW))
                .isEqualTo(ProviderState.CONFIGURED_UNVERIFIED);
        assertThat(WorkplaceServicesService.providerState(
                catalog("external", true, 4, 3L, "HEALTHY", "evidence-1",
                        NOW.minusMinutes(1)), NOW))
                .isEqualTo(ProviderState.CONFIGURED_UNVERIFIED);
        assertThat(WorkplaceServicesService.providerState(
                catalog("external", true, 4, 4L, "HEALTHY", "evidence-1",
                        NOW.minusMinutes(16)), NOW))
                .isEqualTo(ProviderState.STALE);
        assertThat(WorkplaceServicesService.providerState(
                catalog("external", true, 4, 4L, "HEALTHY", "evidence-1",
                        NOW.minusMinutes(1)), NOW))
                .isEqualTo(ProviderState.READY);
        assertThat(WorkplaceServicesService.providerState(
                catalog("external", true, 4, 4L, "DEGRADED", "evidence-1",
                        NOW.minusMinutes(1)), NOW))
                .isEqualTo(ProviderState.DEGRADED);
        assertThat(WorkplaceServicesService.providerState(
                catalog("DWP_NATIVE_FULFILLMENT", true, 1, 1L, "HEALTHY", "native-v1",
                        NOW.minusDays(30)), NOW))
                .isEqualTo(ProviderState.READY);
    }

    @Test
    void catalogValidationRejectsUnknownTenantProviderAndDuplicateOptionKeys() {
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        WorkplaceServicesService service = new WorkplaceServicesService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        when(repository.providerExists(42, "external")).thenReturn(false);

        var validOptions = new ObjectMapper().createArrayNode();
        validOptions.addObject().put("key", "layout").put("type", "TEXT");
        CatalogCreateRequest unknownProvider = createRequest(validOptions);
        assertThatThrownBy(() -> service.validateCatalog(42, unknownProvider))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        when(repository.providerExists(42, "external")).thenReturn(true);
        when(repository.sitesExist(42, List.of())).thenReturn(true);
        var duplicateOptions = new ObjectMapper().createArrayNode();
        duplicateOptions.addObject().put("key", "layout").put("type", "TEXT");
        duplicateOptions.addObject().put("key", "layout").put("type", "TEXT");
        assertThatThrownBy(() -> service.validateCatalog(42, createRequest(duplicateOptions)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void commandReplayRejectsIdempotencyKeyWithAnotherFingerprint() {
        WorkplaceServicesService service = new WorkplaceServicesService(
                mock(WorkplaceServicesRepository.class),
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        CommandRow prior = new CommandRow(UUID.randomUUID(), 42, 99, "SERVICE_ORDER_SUBMIT",
                "stable-key", "original-fingerprint", UUID.randomUUID(), CommandState.ACCEPTED,
                "/v1/workplace/service-orders/order", "correlation", NOW, NOW);

        assertThatThrownBy(() -> service.requireFingerprint(prior, "different-fingerprint"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void previewOptionsAreValidatedAgainstRequiredTypesChoicesAndUnknownKeys() {
        ObjectMapper mapper = new ObjectMapper();
        var schema = mapper.createArrayNode();
        schema.addObject().put("key", "layout").put("type", "SINGLE_SELECT")
                .put("required", true).putArray("values").add("BOARDROOM").add("CLASSROOM");
        schema.addObject().put("key", "microphoneCount").put("type", "NUMBER");
        CatalogRow row = catalog("external", true, 4, 4L, "HEALTHY", "evidence-1",
                NOW.minusMinutes(1), schema);

        assertThat(WorkplaceServicesService.optionLimitations(row, mapper.createObjectNode()))
                .containsExactly("AV_ASSIST:OPTION_REQUIRED:layout");
        assertThat(WorkplaceServicesService.optionLimitations(row,
                mapper.createObjectNode().put("layout", "UNKNOWN")
                        .put("microphoneCount", "two").put("rawSecret", "forbidden")))
                .containsExactlyInAnyOrder("AV_ASSIST:OPTION_INVALID:layout",
                        "AV_ASSIST:OPTION_INVALID:microphoneCount",
                        "AV_ASSIST:OPTION_UNKNOWN:rawSecret");
        assertThat(WorkplaceServicesService.optionLimitations(row,
                mapper.createObjectNode().put("layout", "BOARDROOM")
                        .put("microphoneCount", 2))).isEmpty();
    }

    @Test
    void numberOptionsEnforceGovernedMinimumAndMaximum() {
        ObjectMapper mapper = new ObjectMapper();
        var schema = mapper.createArrayNode();
        schema.addObject().put("key", "microphoneCount").put("type", "NUMBER")
                .put("minimum", 1).put("maximum", 4);
        CatalogRow row = catalog("external", true, 4, 4L, "HEALTHY", "evidence-1",
                NOW.minusMinutes(1), schema);

        assertThat(WorkplaceServicesService.optionLimitations(row,
                mapper.createObjectNode().put("microphoneCount", 0)))
                .containsExactly("AV_ASSIST:OPTION_INVALID:microphoneCount");
        assertThat(WorkplaceServicesService.optionLimitations(row,
                mapper.createObjectNode().put("microphoneCount", 5)))
                .containsExactly("AV_ASSIST:OPTION_INVALID:microphoneCount");
        assertThat(WorkplaceServicesService.optionLimitations(row,
                mapper.createObjectNode().put("microphoneCount", 1))).isEmpty();
        assertThat(WorkplaceServicesService.optionLimitations(row,
                mapper.createObjectNode().put("microphoneCount", 4))).isEmpty();

        WorkplaceServicesService service = new WorkplaceServicesService(
                mock(WorkplaceServicesRepository.class), mapper,
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        var invalidSchema = mapper.createArrayNode();
        invalidSchema.addObject().put("key", "microphoneCount").put("type", "NUMBER")
                .put("minimum", 5).put("maximum", 2);
        assertThatThrownBy(() -> service.validateCatalogValues("AV_ASSIST", "external",
                List.of(), invalidSchema, List.of("ROOM"), BigDecimal.ZERO, "KRW",
                1, 10, 60, 60, 30, 30,
                WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                WorkplaceServiceOperationsDtos.InspectionMode.NONE,
                mapper.createArrayNode()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void catalogMoneyCurrencyQuantityAndPolicyTimeBoundsAreFailClosed() {
        WorkplaceServicesService service = new WorkplaceServicesService(
                mock(WorkplaceServicesRepository.class), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));
        var options = new ObjectMapper().createArrayNode();

        assertThatCode(() -> service.validateCatalogValues("AV_ASSIST", "external",
                List.of(), options, List.of("ROOM"),
                new BigDecimal("499999999999.99"), "KRW",
                1, 1_000, 525_600, 525_600, 525_600, 525_600,
                WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                WorkplaceServiceOperationsDtos.InspectionMode.NONE, options))
                .doesNotThrowAnyException();
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> invalid = List.of(
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), new BigDecimal("1.001"), "KRW",
                        1, 1, 0, 0, 1, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), new BigDecimal("500000000000.00"), "KRW",
                        1, 1, 0, 0, 1, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), BigDecimal.ZERO, "krw",
                        1, 1, 0, 0, 1, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), BigDecimal.ZERO, "KRW",
                        1, 1_001, 0, 0, 1, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), BigDecimal.ZERO, "KRW",
                        1, 1, 525_601, 0, 1, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), BigDecimal.ZERO, "KRW",
                        1, 1, 0, 525_601, 1, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), BigDecimal.ZERO, "KRW",
                        1, 1, 0, 0, 525_601, 0,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options),
                () -> service.validateCatalogValues("AV_ASSIST", "external", List.of(), options,
                        List.of("ROOM"), BigDecimal.ZERO, "KRW",
                        1, 1, 0, 0, 1, 525_601,
                        WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 300,
                        WorkplaceServiceOperationsDtos.InspectionMode.NONE, options));
        invalid.forEach(WorkplaceServicesProviderTruthTest::assertInvalid);
    }

    @Test
    void fulfillmentStateMachineRejectsSkippedAndTerminalTransitions() {
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.SUBMITTED, WorkState.ACCEPTED)).isTrue();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.ACCEPTED, WorkState.IN_PREPARATION)).isTrue();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.IN_PREPARATION, WorkState.PARTIALLY_FULFILLED)).isTrue();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.PARTIALLY_FULFILLED, WorkState.FULFILLED)).isTrue();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.SUBMITTED, WorkState.FULFILLED)).isFalse();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.FULFILLED, WorkState.ACCEPTED)).isFalse();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.CANCELLED, WorkState.ACCEPTED)).isFalse();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.ACCEPTED, WorkState.NOT_CONFIGURED)).isFalse();
        assertThat(WorkplaceServicesService.fulfillmentTransitionAllowed(
                WorkState.ACCEPTED, WorkState.CANCELLED)).isFalse();
    }

    @Test
    void modifiedServiceCommandsValidateLengthsBeforeRepositoryAccess() {
        WorkplaceServicesRepository repository = mock(WorkplaceServicesRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Clock clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
        WorkplaceServicesService services = new WorkplaceServicesService(repository, mapper, clock);
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper,
                        mock(WorkplaceServiceLineAdjustmentProvider.class), clock);
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID adjustmentId = UUID.randomUUID();

        assertInvalid(() -> adjustments.preview(42, 99, orderId, lineId,
                new LineCancellationImpactRequest(1, 1, 1, "r".repeat(501))));
        assertInvalid(() -> adjustments.cancel(42, 99, orderId, lineId, "cancel-key",
                new LineCancellationRequest(UUID.randomUUID(), 1, 1, true,
                        "r".repeat(501)), "corr"));
        assertInvalid(() -> adjustments.reconcile(42, 99, orderId, adjustmentId,
                "reconcile-key", new LineAdjustmentReconcileRequest(
                        1, true, "r".repeat(501)), "corr"));

        List<FulfillmentUpdateRequest> invalidFulfillment = List.of(
                new FulfillmentUpdateRequest(1, WorkState.ACCEPTED, null,
                        "x".repeat(321), null, null, null, 0, "Reason", true),
                new FulfillmentUpdateRequest(1, WorkState.ACCEPTED, null,
                        null, "x".repeat(121), null, null, 0, "Reason", true),
                new FulfillmentUpdateRequest(1, WorkState.ACCEPTED, null,
                        null, null, "x".repeat(1_001), null, 0, "Reason", true),
                new FulfillmentUpdateRequest(1, WorkState.ACCEPTED, null,
                        null, null, null, "x".repeat(1_001), 0, "Reason", true),
                new FulfillmentUpdateRequest(1, WorkState.ACCEPTED, null,
                        null, null, null, null, -1, "Reason", true),
                new FulfillmentUpdateRequest(1, WorkState.ACCEPTED, null,
                        null, null, null, null, 0, "r".repeat(501), true));
        invalidFulfillment.forEach(request -> assertInvalid(() -> services.updateFulfillment(
                42, 99, orderId, UUID.randomUUID(), "fulfillment-key", request, "corr")));

        verify(repository, never()).order(anyLong(), any());
        verify(repository, never()).orderForUpdate(anyLong(), any());
        verify(repository, never()).userOrder(anyLong(), anyLong(), any());
        verify(repository, never()).command(anyLong(), anyLong(), anyString(), anyString());
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private static CatalogCreateRequest createRequest(com.fasterxml.jackson.databind.JsonNode options) {
        return new CatalogCreateRequest("AV_ASSIST", ServiceCategory.AV, "AV 지원", "AV support",
                null, null, "external", List.of(), options, List.of("ROOM"), BigDecimal.ZERO,
                "KRW", 1, 10, 90, 60, 30, 30,
                "1시간 전까지 취소", "Cancel one hour before",
                WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 900,
                WorkplaceServiceOperationsDtos.InspectionMode.NONE,
                new ObjectMapper().createArrayNode(),
                false, false, true, "Publish tested service");
    }

    private static CatalogRow catalog(
            String providerCode,
            boolean configured,
            long configurationVersion,
            Long observedVersion,
            String reportedState,
            String evidence,
            OffsetDateTime receivedAt) {
        return catalog(providerCode, configured, configurationVersion, observedVersion,
                reportedState, evidence, receivedAt, new ObjectMapper().createArrayNode());
    }

    private static CatalogRow catalog(
            String providerCode,
            boolean configured,
            long configurationVersion,
            Long observedVersion,
            String reportedState,
            String evidence,
            OffsetDateTime receivedAt,
            com.fasterxml.jackson.databind.JsonNode optionSchema) {
        return new CatalogRow(UUID.randomUUID(), "AV_ASSIST", ServiceCategory.AV,
                "AV 지원", "AV support", null, null, providerCode,
                new ObjectMapper().createArrayNode(), optionSchema,
                List.of("ROOM"), BigDecimal.ZERO, "KRW", 1, 10, 90, 60, 30, 30,
                "1시간 전까지 취소", "Cancel one hour before",
                WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 900,
                WorkplaceServiceOperationsDtos.InspectionMode.NONE,
                new ObjectMapper().createArrayNode(), false, false, 1,
                configured, configurationVersion, observedVersion, reportedState, evidence,
                receivedAt, receivedAt, null, "ACTIVE", NOW, "ACTIVE",
                "vault://external", configurationVersion);
    }
}
