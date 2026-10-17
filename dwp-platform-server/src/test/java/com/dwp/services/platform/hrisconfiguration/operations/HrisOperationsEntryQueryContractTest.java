package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HrisOperationsEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-17T07:30:00Z");
    private static final OffsetDateTime NOW_OFFSET = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID SUBJECT =
            UUID.fromString("10000000-0000-4000-8000-000000000010");
    private static final UUID EXCEPTION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000020");
    private static final UUID CORRELATION_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000030");
    private static final String SECRET = "s".repeat(32);
    private static final long TENANT_ID = 42;
    private static final long ACTOR_ID = 101;
    private static final String OPERATION_RESOURCE_SET =
            "RS_HCM_HRM_OPERATIONS";
    private static final String SETTINGS_SCOPE = ProductSurfaceScopeKey.key(
            TENANT_ID, ACTOR_ID, "hcm", "hcm.management",
            "APP_RESOURCE_SET:RS_HCM_CONFIG", "RESOURCE_SET");
    private static final String OPERATION_SCOPE = operationScope(
            TENANT_ID, ACTOR_ID, OPERATION_RESOURCE_SET);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ApplicationContextRunner featureContext =
            new ApplicationContextRunner().withUserConfiguration(
                    HrisOperationsRuntimeControls.class,
                    HrisOperationalExceptionEventContract.class,
                    HrisOperationalExceptionSummaryKeyConfiguration.class,
                    HrisOperationsService.class,
                    JdbcHrisOperationalExceptionRepository.class,
                    HrisOperationsController.class,
                    HrisOperationalExceptionEventConsumer.class);

    @Test
    void listUsesStableOpaqueCursorAndProjectsInternalStatesAndDeepLinks() {
        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        Projection recovering = projection(
                OwnerModule.HRM, InternalState.RECOVERING,
                "route.hcm.operations.employment-assignments.page");
        when(repository.list(
                eq(42L), eq(Set.of(OwnerModule.HRM)), eq(OwnerModule.HRM),
                eq(Severity.HIGH), eq(PublicState.OPEN), any(), eq(null), eq(1)))
                .thenReturn(new ProjectionPage(
                        List.of(recovering), new ProjectionPosition(
                        recovering.lastObservedAt(), recovering.exceptionPublicId())));
        HrisOperationsService service = service(repository, true);

        ExceptionListResponse response = service.list(
                access(HrisOperationsService.LIST_ROUTE, recovery(FieldDecision.OMIT)),
                null, 1, OwnerModule.HRM, Severity.HIGH, PublicState.OPEN);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(PublicState.OPEN);
            assertThat(item.ownerDeepLink()).isEqualTo(
                    "/hr/operations/people/employment-assignments?focus="
                            + EXCEPTION_ID);
            assertThat(item.ownerDeepLink()).doesNotContain("#", "token=", "http");
        });
        assertThat(response.nextCursor())
                .matches("^[A-Za-z0-9_-]{16,4096}$")
                .doesNotContain(".", "=");

        assertThatThrownBy(() -> service.list(
                access(HrisOperationsService.LIST_ROUTE,
                        recovery(FieldDecision.OMIT)),
                response.nextCursor(), 1, OwnerModule.HRM,
                Severity.WARNING, PublicState.OPEN))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("cursor");
        verify(repository, never()).list(
                eq(42L), eq(Set.of(OwnerModule.HRM)), eq(OwnerModule.HRM),
                eq(Severity.WARNING), eq(PublicState.OPEN),
                any(), any(), anyInt());
    }

    @Test
    void detailHasClosedFieldDecisionsAndNeverReturnsRedactedPayload() {
        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        Projection suppressed = projection(
                OwnerModule.HRM, InternalState.SUPPRESSED,
                "route.hcm.operations.employee-change-requests.page");
        when(repository.find(42, Set.of(OwnerModule.HRM), EXCEPTION_ID))
                .thenReturn(Optional.of(suppressed));
        HrisOperationsService service = service(repository, true);

        ExceptionDetailResponse omitted = service.detail(
                access(HrisOperationsService.DETAIL_ROUTE,
                        recovery(FieldDecision.OMIT)), EXCEPTION_ID);
        assertThat(omitted.state()).isEqualTo(PublicState.OPEN);
        assertThat(omitted.correlationId()).isNull();
        assertThat(omitted.evidenceRefs()).isEmpty();
        assertThat(omitted.fieldDecisions()).containsExactlyInAnyOrderEntriesOf(
                Map.of(
                        "OPERATION_EXCEPTION", FieldDecision.VIEW,
                        "OWNER_DEEP_LINK", FieldDecision.VIEW,
                        "RECOVERY_METADATA", FieldDecision.OMIT,
                        "REDACTED_PAYLOAD", FieldDecision.OMIT));

        ExceptionDetailResponse visible = service.detail(
                access(HrisOperationsService.DETAIL_ROUTE,
                        recovery(FieldDecision.VIEW)), EXCEPTION_ID);
        assertThat(visible.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(visible.evidenceRefs()).containsExactly(
                "urn:dwp:hris:evidence:domain-event:"
                        + "40000000-0000-4000-8000-000000000040");
        assertThat(json.valueToTree(visible).toString())
                .doesNotContain("eventDigest", "sourceRef", "ownerService",
                        "internalState", "rawPayload", "stackTrace");
    }

    @Test
    void unknownOrCrossModuleOwnerRouteFailsClosed() {
        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        when(repository.find(42, Set.of(OwnerModule.HRM), EXCEPTION_ID))
                .thenReturn(Optional.of(projection(
                        OwnerModule.HRM, InternalState.OPEN,
                        "route.hcm.operations.pay.page")));

        assertThatThrownBy(() -> service(repository, true).detail(
                access(HrisOperationsService.DETAIL_ROUTE,
                        recovery(FieldDecision.VIEW)), EXCEPTION_ID))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("owner route");
    }

    @Test
    void iaqSummaryDistinguishesUnavailablePartialStaleAndComplete() {
        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        when(repository.summary(eq(42L), any())).thenReturn(
                summary(0, Map.of()),
                summary(2, Map.of(
                        OwnerModule.HRM, NOW_OFFSET.minusMinutes(1),
                        OwnerModule.PAY, NOW_OFFSET.minusMinutes(2))),
                summary(2, Map.of(
                        OwnerModule.HRM, NOW_OFFSET.minusMinutes(16),
                        OwnerModule.PAY, NOW_OFFSET.minusMinutes(1))),
                summary(5, Map.of(
                        OwnerModule.HRM, NOW_OFFSET.minusMinutes(1),
                        OwnerModule.PER, NOW_OFFSET.minusMinutes(1),
                        OwnerModule.PAY, NOW_OFFSET.minusMinutes(1),
                        OwnerModule.SYS, NOW_OFFSET.minusMinutes(1),
                        OwnerModule.TIM, NOW_OFFSET.minusMinutes(1))));
        HrisOperationsService service = service(repository, true);
        AccessSeal settings = settingsAccess();

        assertThat(service.settings(settings).projectionState())
                .isEqualTo(ProjectionState.UNAVAILABLE);
        assertThat(service.settings(settings).projectionState())
                .isEqualTo(ProjectionState.PARTIAL);
        assertThat(service.settings(settings).projectionState())
                .isEqualTo(ProjectionState.STALE);
        assertThat(service.settings(settings).projectionState())
                .isEqualTo(ProjectionState.COMPLETE);
    }

    @Test
    void settingsControllerDerivesOpaqueConfigScopeWithoutPopulationHeader() {
        HrisOperationsService service = mock(HrisOperationsService.class);
        SettingsOperationsResponse expected = new SettingsOperationsResponse(
                NOW_OFFSET, ProjectionState.UNAVAILABLE, null, List.of(),
                new ExceptionCounts(0, 0, 0, 0, 0));
        when(service.settings(any())).thenReturn(expected);
        HrisOperationsController controller = new HrisOperationsController(service);

        MockHttpServletRequest allowed = settingsRequest(
                SETTINGS_SCOPE, "HRIS_CONFIGURATION");
        assertThat(allowed.getHeader("X-DWP-Population-Kind")).isNull();
        assertThat(controller.settings(allowed).getBody()).isEqualTo(expected);

        assertThatThrownBy(() -> controller.settings(settingsRequest(
                "APP_RESOURCE_SET:RS_HCM_CONFIG", "HRIS_CONFIGURATION")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.settings(settingsRequest(
                ProductSurfaceScopeKey.key(
                        TENANT_ID, ACTOR_ID + 1, "hcm", "hcm.management",
                        "APP_RESOURCE_SET:RS_HCM_CONFIG", "RESOURCE_SET"),
                "HRIS_CONFIGURATION")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.settings(settingsRequest(
                ProductSurfaceScopeKey.key(
                        TENANT_ID + 1, ACTOR_ID, "hcm", "hcm.management",
                        "APP_RESOURCE_SET:RS_HCM_CONFIG", "RESOURCE_SET"),
                "HRIS_CONFIGURATION")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.settings(settingsRequest(
                ProductSurfaceScopeKey.key(
                        TENANT_ID, ACTOR_ID, "hcm", "hcm.management",
                        "APP_RESOURCE_SET:RS_OTHER", "RESOURCE_SET"),
                "HRIS_CONFIGURATION")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> controller.settings(settingsRequest(
                SETTINGS_SCOPE, "HRIS_OPERATIONS")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void exceptionsControllerBindsOpaqueScopeToExactCanonicalResourceDuty() {
        HrisOperationsService service = mock(HrisOperationsService.class);
        ExceptionListResponse expected = new ExceptionListResponse(
                NOW_OFFSET, List.of(), null);
        when(service.list(any(), eq(null), eq(50), eq(null), eq(null), eq(null)))
                .thenReturn(expected);
        HrisOperationsController controller = new HrisOperationsController(service);

        assertThat(controller.exceptions(exceptionsRequest(
                OPERATION_SCOPE,
                "HRIS_DUTY_OPERATION_EXCEPTION_READER@"
                        + OPERATION_RESOURCE_SET),
                null, 50, null, null, null).getBody()).isEqualTo(expected);

        for (MockHttpServletRequest denied : List.of(
                exceptionsRequest(operationScope(
                                TENANT_ID, ACTOR_ID + 1, OPERATION_RESOURCE_SET),
                        "HRIS_DUTY_OPERATION_EXCEPTION_READER@"
                                + OPERATION_RESOURCE_SET),
                exceptionsRequest(operationScope(
                                TENANT_ID + 1, ACTOR_ID, OPERATION_RESOURCE_SET),
                        "HRIS_DUTY_OPERATION_EXCEPTION_READER@"
                                + OPERATION_RESOURCE_SET),
                exceptionsRequest(OPERATION_SCOPE,
                        "HRIS_DUTY_OPERATION_EXCEPTION_READER@RS_OTHER"),
                exceptionsRequest(OPERATION_SCOPE,
                        "HRIS_DUTY_OTHER@" + OPERATION_RESOURCE_SET),
                exceptionsRequest(OPERATION_SCOPE,
                        "HRIS_DUTY_OPERATION_EXCEPTION_READER@not-canonical"))) {
            assertThatThrownBy(() -> controller.exceptions(
                    denied, null, 50, null, null, null))
                    .isInstanceOf(BaseException.class);
        }
    }

    @Test
    void requiredNullableWireFieldsArePresentAsJsonNull() throws Exception {
        ExceptionListItem item = new ExceptionListItem(
                EXCEPTION_ID, OwnerModule.HRM, Severity.HIGH, PublicState.OPEN,
                "CODE", "hris.operations.exception.hrm.code",
                "route.hcm.operations.employment-assignments.page", null,
                1, NOW_OFFSET, NOW_OFFSET, 1);
        JsonNode list = json.valueToTree(new ExceptionListResponse(
                NOW_OFFSET, List.of(item), null));
        assertThat(list.path("items").get(0).has("ownerDeepLink")).isTrue();
        assertThat(list.path("items").get(0).get("ownerDeepLink").isNull())
                .isTrue();
        assertThat(list.has("nextCursor")).isTrue();
        assertThat(list.get("nextCursor").isNull()).isTrue();

        JsonNode detail = json.valueToTree(new ExceptionDetailResponse(
                EXCEPTION_ID, OwnerModule.HRM, Severity.HIGH, PublicState.OPEN,
                "CODE", "hris.operations.exception.hrm.code",
                "route.hcm.operations.employment-assignments.page", null,
                1, NOW_OFFSET, NOW_OFFSET, 1, "TYPE", null,
                List.of(), recovery(FieldDecision.OMIT)));
        assertThat(detail.has("ownerDeepLink")).isTrue();
        assertThat(detail.get("ownerDeepLink").isNull()).isTrue();
        assertThat(detail.has("correlationId")).isTrue();
        assertThat(detail.get("correlationId").isNull()).isTrue();

        JsonNode settings = json.valueToTree(new SettingsOperationsResponse(
                NOW_OFFSET, ProjectionState.UNAVAILABLE, null, List.of(),
                new ExceptionCounts(0, 0, 0, 0, 0)));
        assertThat(settings.has("lastSuccessfulRefreshAt")).isTrue();
        assertThat(settings.get("lastSuccessfulRefreshAt").isNull()).isTrue();
    }

    @Test
    void defaultOffMakesZeroProjectionQueryCallsAndControllerHasNoMutation() {
        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        HrisOperationsService service = service(repository, false);

        assertThatThrownBy(() -> service.list(
                access(HrisOperationsService.LIST_ROUTE,
                        recovery(FieldDecision.OMIT)),
                null, 50, null, null, null))
                .isInstanceOf(BaseException.class);
        verifyNoInteractions(repository);

        for (Method method : HrisOperationsController.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(PostMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
            assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
        }
    }

    @Test
    void featureAbsentOrFalseRegistersNoOperationsSliceBeans() {
        featureContext.run(context -> {
            assertThat(context).hasNotFailed();
            assertNoOperationsSliceBeans(context);
        });
        featureContext.withPropertyValues(
                        "dwp.hris.operations-exception-center.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoOperationsSliceBeans(context);
                });
    }

    @Test
    void enabledSliceTransactionAndValidationTargetsRemainProxyable() {
        assertThat(Modifier.isFinal(
                JdbcHrisOperationalExceptionRepository.class.getModifiers()))
                .isFalse();
        assertThat(Modifier.isFinal(
                HrisOperationsController.class.getModifiers())).isFalse();
    }

    private void assertNoOperationsSliceBeans(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext
                    context) {
        assertThat(context).doesNotHaveBean(HrisOperationsRuntimeControls.class);
        assertThat(context)
                .doesNotHaveBean(HrisOperationalExceptionEventContract.class);
        assertThat(context).doesNotHaveBean(
                HrisOperationalExceptionSummaryKeyConfiguration.class);
        assertThat(context)
                .doesNotHaveBean(HrisOperationalExceptionSummaryKeyRegistry.class);
        assertThat(context).doesNotHaveBean(HrisOperationsService.class);
        assertThat(context)
                .doesNotHaveBean(HrisOperationalExceptionRepository.class);
        assertThat(context)
                .doesNotHaveBean(JdbcHrisOperationalExceptionRepository.class);
        assertThat(context).doesNotHaveBean(HrisOperationsController.class);
        assertThat(context)
                .doesNotHaveBean(HrisOperationalExceptionEventConsumer.class);
    }

    private HrisOperationsService service(
            HrisOperationalExceptionRepository repository, boolean enabled) {
        return new HrisOperationsService(
                repository, new HrisOperationsRuntimeControls(
                enabled, enabled ? "42" : ""), json,
                Clock.fixed(NOW, ZoneOffset.UTC), SECRET,
                Duration.ofMinutes(5), Duration.ofMinutes(15));
    }

    private AccessSeal access(
            String route, Map<String, FieldDecision> decisions) {
        return new AccessSeal(
                42, SUBJECT, route, "ctx-hrm", "package-hrm", "decision-1",
                7, 9, NOW_OFFSET.plusMinutes(10), Set.of(OwnerModule.HRM),
                decisions);
    }

    private AccessSeal settingsAccess() {
        Map<String, FieldDecision> decisions = Map.of(
                "OPERATION_POLICY", FieldDecision.VIEW,
                "EXCEPTION_POLICY", FieldDecision.VIEW,
                "EFFECTIVE_STATUS", FieldDecision.VIEW);
        return new AccessSeal(
                42, SUBJECT, HrisOperationsService.SETTINGS_ROUTE,
                "ctx-config", SETTINGS_SCOPE, "decision-1",
                7, 9, NOW_OFFSET.plusMinutes(10), Set.of(), decisions);
    }

    private MockHttpServletRequest settingsRequest(String scope, String purpose) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/hris/workbench/settings-operations");
        request.setAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority",
                new HcmPlatformPepRegistry.RouteAuthority(
                        HrisOperationsService.SETTINGS_ROUTE, "PAGE", "GET",
                        "/v1/hris/workbench/settings-operations",
                        "configuration-reader", false,
                        "hcm.configuration.workbench.view", null, null));
        request.addHeader("X-DWP-Tenant-ID", "42");
        request.addHeader("X-DWP-User-ID", Long.toString(ACTOR_ID));
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                SUBJECT.toString());
        request.addHeader("X-DWP-Context-Key", "ctx-config");
        request.addHeader("X-DWP-Context-Scope-Key", scope);
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-1");
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "9");
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW_OFFSET.plusMinutes(10).toString());
        request.addHeader("X-Purpose-Code", purpose);
        return request;
    }

    private MockHttpServletRequest exceptionsRequest(
            String scope, String resourceRole) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/hris/operations/exceptions");
        request.setAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority",
                new HcmPlatformPepRegistry.RouteAuthority(
                        HrisOperationsService.LIST_ROUTE, "DATA", "GET",
                        "/v1/admin/hris/operations/exceptions",
                        "operation-exception-reader", false,
                        "hcm.operations.exception.view", null, null));
        request.addHeader("X-DWP-Tenant-ID", Long.toString(TENANT_ID));
        request.addHeader("X-DWP-User-ID", Long.toString(ACTOR_ID));
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                SUBJECT.toString());
        request.addHeader("X-DWP-Context-Key", "ctx-operations");
        request.addHeader("X-DWP-Context-Scope-Key", scope);
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-1");
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "9");
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW_OFFSET.plusMinutes(10).toString());
        request.addHeader("X-Purpose-Code", "HRIS_OPERATIONS");
        request.addHeader("X-DWP-Population-Kind", "TARGET_POPULATION");
        request.addHeader("X-DWP-Roles", "HRIS_HR_BUSINESS_PARTNER");
        request.addHeader("X-DWP-Resource-Roles", resourceRole);
        return request;
    }

    private static String operationScope(
            long tenantId, long actorId, String resourceSet) {
        return ProductSurfaceScopeKey.key(
                tenantId, actorId, "hcm", "hcm.operations", resourceSet,
                "RESOURCE_SET");
    }

    private Map<String, FieldDecision> recovery(FieldDecision recovery) {
        return Map.of(
                "OPERATION_EXCEPTION", FieldDecision.VIEW,
                "OWNER_DEEP_LINK", FieldDecision.VIEW,
                "RECOVERY_METADATA", recovery,
                "REDACTED_PAYLOAD", FieldDecision.OMIT);
    }

    private Projection projection(
            OwnerModule module, InternalState state, String ownerRouteKey) {
        return new Projection(
                42, EXCEPTION_ID,
                "urn:dwp:people:hrm:operational-exception",
                "dwp-people-server", module,
                "urn:dwp:hris:hrm:assignment:" + EXCEPTION_ID,
                1, "ASSIGNMENT_PROCESSING", Severity.HIGH, state,
                "HRM_ASSIGNMENT_STALLED",
                "hris.operations.exception.hrm.assignment-stalled",
                ownerRouteKey,
                List.of("urn:dwp:hris:evidence:domain-event:"
                        + "40000000-0000-4000-8000-000000000040"),
                CORRELATION_ID, "a".repeat(64),
                NOW_OFFSET.minusMinutes(5), NOW_OFFSET.minusMinutes(1), 1, 1);
    }

    private ProjectionSummary summary(
            long total, Map<OwnerModule, OffsetDateTime> latest) {
        OffsetDateTime refresh = latest.values().stream().max(
                OffsetDateTime::compareTo).orElse(null);
        return new ProjectionSummary(
                NOW_OFFSET, refresh, latest, total, total, 0, 0, total);
    }
}
