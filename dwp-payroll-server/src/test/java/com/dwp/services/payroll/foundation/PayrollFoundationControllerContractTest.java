package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.AccessProjection;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceiptView;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationView;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.DependencyPin;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Freshness;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FreshnessState;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.MutationResult;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.PartialFailure;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.RoundingPolicy;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.SimulationReport;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.WorkspaceView;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.DEPENDENCY_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.GROUP_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.LEGAL_ENTITY_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.currency;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.definition;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PayrollFoundationControllerContractTest {

    private static final UUID CONFIGURATION_ID =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND_ID =
            UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");

    private PayrollFoundationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PayrollFoundationService.class);
        PayrollFoundationController controller = new PayrollFoundationController(
                service, tenantId -> PayrollFoundationAccess.compatibilityPolicy());
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(new PayrollFoundationSecurityFilter(
                        "verified-test-token", JsonMapper.builder().findAndAddModules().build()))
                .build();
    }

    @Test
    void emptyWorkspaceStillProjectsServerAuthorizedCreate() throws Exception {
        when(service.list(any())).thenReturn(new WorkspaceView(
                List.of(), new AccessProjection(
                true, false, false, false, false, false,
                "NO_CONFIGURATION_SELECTED"),
                List.of(new PartialFailure("DEPENDENCIES", "UNAVAILABLE"))));

        mvc.perform(headers(get("/v1/hris/payroll/foundation/configurations"), 101))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configurations", hasSize(0)))
                .andExpect(jsonPath("$.data.access.canCreate").value(true))
                .andExpect(jsonPath("$.data.access.canEdit").value(false))
                .andExpect(jsonPath("$.data.access.canSimulate").value(false))
                .andExpect(jsonPath("$.data.access.canPublish").value(false))
                .andExpect(jsonPath("$.data.access.canReverse").value(false))
                .andExpect(jsonPath("$.data.access.canReconcile").value(false))
                .andExpect(jsonPath("$.data.access.publishDenialCode")
                        .value("NO_CONFIGURATION_SELECTED"))
                .andExpect(jsonPath("$.data.partialFailures", hasSize(1)))
                .andExpect(jsonPath("$.data.partialFailures[0].source")
                        .value("DEPENDENCIES"))
                .andExpect(jsonPath("$.data.partialFailures[0].code")
                        .value("UNAVAILABLE"));

        ArgumentCaptor<PayrollFoundationAccess.Actor> actor =
                ArgumentCaptor.forClass(PayrollFoundationAccess.Actor.class);
        verify(service).list(actor.capture());
        assertThat(actor.getValue().purpose()).isEqualTo("PAYROLL_CONFIGURATION");
        assertThat(actor.getValue().legalEntityScopeDigest())
                .isEqualTo(PayrollFoundationCanonical.textDigest("*"));
        assertThat(actor.getValue().policyRevision()).isEqualTo("pay-policy-42-v7");
        assertThat(actor.getValue().authorizationRevision()).isEqualTo("authz-42-v19");
    }

    @Test
    void requiresAppEntitlementAndModuleAction() throws Exception {
        mvc.perform(verifiedHeaders(
                        get("/v1/hris/payroll/foundation/configurations"),
                        101, "PAYROLL_FOUNDATION:VIEW"))
                .andExpect(status().isForbidden());
        mvc.perform(verifiedHeaders(
                        get("/v1/hris/payroll/foundation/configurations"),
                        101, "APP.HRIS:VIEW"))
                .andExpect(status().isForbidden());
    }

    @Test
    void spoofedBusinessHeadersWithoutVerifiedGatewayAreRejected() throws Exception {
        mvc.perform(get("/v1/hris/payroll/foundation/configurations")
                        .header("X-DWP-Tenant-ID", 1)
                        .header("X-DWP-User-ID", 101)
                        .header("X-DWP-Roles", "CONFIGURATION_AUTHOR")
                        .header("X-DWP-Permissions",
                                "APP.HRIS:VIEW PAYROLL_FOUNDATION:EDIT")
                        .header("X-DWP-Purpose", "PAYROLL_CONFIGURATION")
                        .header("X-DWP-Legal-Entity-Scope", "*"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("E2000"));
    }

    @Test
    void enabledBoundaryRejectsMissingTrustedPurposeAndScopeEvidence() throws Exception {
        mvc.perform(get("/v1/hris/payroll/foundation/configurations")
                        .header("X-DWP-Service-Token", "verified-test-token")
                        .header("X-DWP-Tenant-ID", 1)
                        .header("X-DWP-User-ID", 101)
                        .header("X-DWP-Roles", "TENANT_DEFINED_PAYROLL_STEWARD")
                        .header("X-DWP-Permissions",
                                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("E2000"));
    }

    @Test
    void enabledBoundaryRejectsMissingTrustedAuthorityRevisionEvidence() throws Exception {
        mvc.perform(get("/v1/hris/payroll/foundation/configurations")
                        .header("X-DWP-Service-Token", "verified-test-token")
                        .header("X-DWP-Tenant-ID", 1)
                        .header("X-DWP-User-ID", 101)
                        .header("X-DWP-Roles", "TENANT_DEFINED_PAYROLL_STEWARD")
                        .header("X-DWP-Permissions",
                                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW")
                        .header("X-DWP-Purpose", "PAYROLL_CONFIGURATION")
                        .header("X-DWP-Legal-Entity-Scope", "*")
                        .header("X-DWP-Policy-Revision", "pay-policy-42-v7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("E2000"));
    }

    @Test
    void missingConfiguredGatewayIdentityFailsClosed() throws Exception {
        PayrollFoundationController controller = new PayrollFoundationController(
                service, tenantId -> PayrollFoundationAccess.compatibilityPolicy());
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new PayrollFoundationSecurityFilter(
                        "", JsonMapper.builder().findAndAddModules().build()))
                .build();

        unconfigured.perform(headers(
                        get("/v1/hris/payroll/foundation/configurations"), 101))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTHORITY_RESOLUTION_UNAVAILABLE"));
    }

    @Test
    void enabledHttpFailsClosedWithoutTenantPepAdapter() throws Exception {
        PayrollFoundationController unavailableController = new PayrollFoundationController(
                service, new UnavailablePayrollFoundationAccessPolicyProvider());
        MockMvc unavailable = MockMvcBuilders.standaloneSetup(unavailableController)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(new PayrollFoundationSecurityFilter(
                        "verified-test-token", JsonMapper.builder().findAndAddModules().build()))
                .build();

        unavailable.perform(headers(
                        get("/v1/hris/payroll/foundation/configurations"), 101))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode")
                        .value("AUTHORITY_RESOLUTION_UNAVAILABLE"));
    }

    @Test
    void serializesCanonicalFlatConfigurationAndExactNumericContracts() throws Exception {
        ConfigurationView view = view();
        when(service.get(any(), eq(CONFIGURATION_ID))).thenReturn(view);

        mvc.perform(headers(get(
                        "/v1/hris/payroll/foundation/configurations/{id}", CONFIGURATION_ID),
                202))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configurationId")
                        .value(CONFIGURATION_ID.toString()))
                .andExpect(jsonPath("$.data.configuration").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.status").value("SIMULATED"))
                .andExpect(jsonPath("$.data.definition.effectivePeriod.endsOn")
                        .value("2026-12-31"))
                .andExpect(jsonPath("$.data.definition.legalEntity.countryPack.version")
                        .value(7))
                .andExpect(jsonPath("$.data.definition.dependencies[0].version")
                        .value(4))
                .andExpect(jsonPath("$.data.definition.roundingPolicies.USD.increment")
                        .value("0.01"))
                .andExpect(jsonPath("$.data.simulation.configurationVersion").value(3))
                .andExpect(jsonPath("$.data.simulation.successful").value(true))
                .andExpect(jsonPath("$.data.access.canPublish").value(false))
                .andExpect(jsonPath("$.data.access.publishDenialCode")
                        .value("DEPENDENCY_STALE"))
                .andExpect(jsonPath("$.data.freshness.state").value("STALE"));
    }

    @Test
    void serializesOpenEndedEffectivePeriodAsExplicitNull() throws Exception {
        ConfigurationView base = view();
        FoundationDefinition openEndedDefinition = definition(
                LEGAL_ENTITY_ID, GROUP_ID,
                LocalDate.of(2026, 1, 1), null,
                Set.of(currency("USD")), List.of());
        ConfigurationView openEnded = new ConfigurationView(
                base.configurationId(), base.version(), base.status(), openEndedDefinition,
                base.authorId(), base.publisherId(), base.createdAt(), base.updatedAt(),
                base.simulation(), base.lastCommandId(), base.access(), base.freshness());
        when(service.get(any(), eq(CONFIGURATION_ID))).thenReturn(openEnded);

        mvc.perform(headers(get(
                        "/v1/hris/payroll/foundation/configurations/{id}", CONFIGURATION_ID),
                202))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definition.effectivePeriod.endsOn")
                        .value(nullValue()));
    }

    @Test
    void serializesLargeRoundingIncrementAsPlainDecimalString() throws Exception {
        ConfigurationView base = view();
        FoundationDefinition original = base.definition();
        FoundationDefinition largeIncrementDefinition = new FoundationDefinition(
                original.legalEntity(), original.payrollGroup(), original.payCalendar(),
                original.effectivePeriod(),
                Map.of(currency("USD"), new RoundingPolicy(
                        2, RoundingMode.HALF_EVEN, new BigDecimal("1000"))),
                original.dependencies());
        ConfigurationView largeIncrement = new ConfigurationView(
                base.configurationId(), base.version(), base.status(),
                largeIncrementDefinition, base.authorId(), base.publisherId(),
                base.createdAt(), base.updatedAt(), base.simulation(), base.lastCommandId(),
                base.access(), base.freshness());
        when(service.get(any(), eq(CONFIGURATION_ID))).thenReturn(largeIncrement);

        mvc.perform(headers(get(
                        "/v1/hris/payroll/foundation/configurations/{id}", CONFIGURATION_ID),
                202))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definition.roundingPolicies.USD.increment")
                        .value("1000"));
    }

    @Test
    void serializesTerminalReversalFailureWithPublishedConfiguration() throws Exception {
        UUID publishCommandId = UUID.fromString(
                "60000000-0000-0000-0000-000000000002");
        CommandReceiptView failed = new CommandReceiptView(
                COMMAND_ID, CommandType.REVERSE, ReceiptStatus.REVERSAL_FAILED,
                CONFIGURATION_ID, 3L, publishCommandId,
                "REVERSAL_PRECONDITION_FAILED", "corr-1", NOW.minusSeconds(5), NOW);
        ConfigurationView base = view();
        ConfigurationView published = new ConfigurationView(
                base.configurationId(), base.version(), Lifecycle.PUBLISHED,
                base.definition(), base.authorId(), 202L, base.createdAt(), base.updatedAt(),
                base.simulation(), base.lastCommandId(), base.access(), base.freshness());
        when(service.reverse(
                any(), eq(CONFIGURATION_ID), eq(COMMAND_ID), nullable(String.class), any()))
                .thenReturn(new MutationResult(failed, published));

        mvc.perform(headers(post(
                        "/v1/hris/payroll/foundation/configurations/{id}/reversals",
                        CONFIGURATION_ID), 202)
                        .header("Idempotency-Key", COMMAND_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"publishCommandId\":\""
                                + publishCommandId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.status").value("REVERSAL_FAILED"))
                .andExpect(jsonPath("$.data.receipt.failureCode")
                        .value("REVERSAL_PRECONDITION_FAILED"))
                .andExpect(jsonPath("$.data.configuration.status").value("PUBLISHED"));
    }

    @Test
    void receiptLookupAndReconciliationUseMutationResultEnvelope() throws Exception {
        MutationResult result = new MutationResult(receipt(), view());
        when(service.receipt(any(), eq(COMMAND_ID))).thenReturn(result);
        when(service.reconcile(any(), eq(COMMAND_ID))).thenReturn(result);

        mvc.perform(headers(get(
                        "/v1/hris/payroll/foundation/receipts/{id}", COMMAND_ID),
                202))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.commandId").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$.data.receipt.commandType").value("PUBLISH"))
                .andExpect(jsonPath("$.data.receipt.status").value("RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.data.receipt.requestDigest").doesNotExist())
                .andExpect(jsonPath("$.data.configuration.configurationId")
                        .value(CONFIGURATION_ID.toString()));

        mvc.perform(headers(post(
                        "/v1/hris/payroll/foundation/receipts/{id}/reconcile", COMMAND_ID),
                202))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.status").value("RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.data.configuration.access.canReconcile").value(true));
    }

    @Test
    void mapsOptimisticAndLifecycleConflictsToHttp409() throws Exception {
        when(service.publish(
                any(), eq(CONFIGURATION_ID), eq(COMMAND_ID), nullable(String.class), any()))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "Synthetic optimistic conflict."));

        mvc.perform(headers(post(
                        "/v1/hris/payroll/foundation/configurations/{id}/publish",
                        CONFIGURATION_ID), 202)
                        .header("Idempotency-Key", COMMAND_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("E1009"));
    }

    private ConfigurationView view() {
        DependencyPin pin = new DependencyPin("PEOPLE", DEPENDENCY_ID, 4);
        FoundationDefinition definition = definition(
                LEGAL_ENTITY_ID, GROUP_ID,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Set.of(currency("USD")), List.of(pin));
        SimulationReport simulation = new SimulationReport(
                UUID.fromString("70000000-0000-0000-0000-000000000001"),
                3, "b".repeat(64), "c".repeat(64), true, List.of(), NOW, 101);
        return new ConfigurationView(
                CONFIGURATION_ID, 3, Lifecycle.SIMULATED, definition,
                101, null, NOW.minusSeconds(60), NOW, simulation, COMMAND_ID,
                new AccessProjection(true, true, true, false, false, true,
                        "DEPENDENCY_STALE"),
                new Freshness(FreshnessState.STALE, NOW.minusSeconds(30)));
    }

    private CommandReceiptView receipt() {
        return new CommandReceiptView(
                COMMAND_ID, CommandType.PUBLISH, ReceiptStatus.RESULT_UNKNOWN,
                CONFIGURATION_ID, 3L, null, "RESULT_UNKNOWN", "corr-1",
                NOW.minusSeconds(5), NOW);
    }

    private MockHttpServletRequestBuilder headers(
            MockHttpServletRequestBuilder request, long actorId) {
        return verifiedHeaders(
                request,
                actorId,
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:PUBLISH "
                        + "PAYROLL_FOUNDATION:REVERSE PAYROLL_FOUNDATION:RECONCILE");
    }

    private MockHttpServletRequestBuilder verifiedHeaders(
            MockHttpServletRequestBuilder request, long actorId, String permissions) {
        return request
                .header("X-DWP-Service-Token", "verified-test-token")
                .header("X-DWP-Tenant-ID", 1)
                .header("X-DWP-User-ID", actorId)
                .header("X-DWP-Roles", "CONFIGURATION_PUBLISHER")
                .header("X-DWP-Permissions", permissions)
                .header("X-DWP-Purpose", "PAYROLL_CONFIGURATION")
                .header("X-DWP-Legal-Entity-Scope", "*")
                .header("X-DWP-Policy-Revision", "pay-policy-42-v7")
                .header("X-DWP-Authorization-Revision", "authz-42-v19");
    }
}
