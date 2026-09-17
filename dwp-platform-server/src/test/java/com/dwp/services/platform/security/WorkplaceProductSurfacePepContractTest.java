package com.dwp.services.platform.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.platform.workplace.WorkplaceController;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceOperationsController;
import com.dwp.services.platform.workplace.WorkplaceOperationsService;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsController;
import com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkplaceProductSurfacePepContractTest {

    private static final long TENANT = 7L;
    private static final long ACTOR = 101L;
    private static final UUID RESOURCE =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FLOOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CURRENT_REVISION =
            "psr-" + "0123456789abcdef".repeat(4);
    private static final String ROLLOUT_REVISION =
            "rollout-" + "0123456789abcdef".repeat(4);
    private static final String CONTEXT = "psc-" + "a".repeat(64);
    private static final Set<String> DEVICE_BINDINGS = Set.of(
            "POST /v1/device/workplace/devices/{deviceId}/heartbeat",
            "GET /v1/device/workplace/devices/{deviceId}/projection",
            "POST /v1/device/workplace/devices:register",
            "POST /v1/device/workplace/devices/{deviceId}/access-pass:pair",
            "POST /v1/workplace/kiosk/devices/{deviceId}:heartbeat",
            "POST /v1/workplace/kiosk/devices/{deviceId}:help",
            "GET /v1/workplace/kiosk/session",
            "GET /v1/workplace/kiosk/visits/{visitId}",
            "POST /v1/workplace/kiosk/visits/{visitId}:arrive",
            "POST /v1/workplace/kiosk/visits/{visitId}:checkout");
    private static final Set<String> ROOM_BINDINGS = Set.of(
            "GET /v1/rooms/policy",
            "GET /v1/rooms/availability",
            "GET /v1/rooms/bookings",
            "POST /v1/rooms/bookings",
            "PUT /v1/rooms/bookings/{eventId}",
            "POST /v1/rooms/bookings/{eventId}/response",
            "POST /v1/rooms/bookings/{eventId}/cancel",
            "GET /v1/admin/rooms/overview",
            "GET /v1/admin/rooms/policy",
            "PUT /v1/admin/rooms/policy",
            "GET /v1/admin/rooms/bookings/pending",
            "POST /v1/admin/rooms/bookings/{bookingId}/decision",
            "POST /v1/admin/rooms/resources",
            "PUT /v1/admin/rooms/resources/{resourceId}");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final WorkplaceService service = mock(WorkplaceService.class);
    private final WorkplaceOperationsService operations =
            mock(WorkplaceOperationsService.class);
    private final WorkplaceConnectorOpsService connectorOps =
            mock(WorkplaceConnectorOpsService.class);
    private final PlatformWorkplaceProductPepRegistry registry =
            new PlatformWorkplaceProductPepRegistry(objectMapper);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reset(service, operations, connectorOps);
        PlatformSecurityFilter platformSecurity = new PlatformSecurityFilter(
                "trusted", "runtime", objectMapper);
        PlatformWorkplaceProductPepFilter workplacePep =
                new PlatformWorkplaceProductPepFilter(true, registry, objectMapper);
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkplaceController(service),
                        new WorkplaceOperationsController(operations),
                        new WorkplaceConnectorOpsController(connectorOps))
                .addFilters(platformSecurity, workplacePep)
                .build();
    }

    @Test
    void crossTenantOpaqueScopeFailsClosedAtWorkplaceOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = exactExplore();
        replaceHeader(request, PlatformSecurityFilter.SCOPE_HEADER,
                ProductSurfaceScopeKey.key(
                        TENANT + 1, ACTOR, "workplace", "workplace.work",
                        "SELF", "SELF"));

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service, operations);
    }

    @Test
    void canonicalOpaqueScopeEscapeFailsClosedAtWorkplaceOwnerPep() throws Exception {
        MockHttpServletRequestBuilder request = exactExplore();
        replaceHeader(request, PlatformSecurityFilter.SCOPE_HEADER,
                ProductSurfaceScopeKey.key(
                        TENANT, ACTOR, "workplace", "workplace.work",
                        "APP_WORKPLACE", "RESOURCE_SET"));

        mvc.perform(request).andExpect(status().isForbidden());

        verifyNoInteractions(service, operations);
    }

    @Test
    void staleAuthorityRevisionFailsClosedBeforeWorkplaceAction() throws Exception {
        MockHttpServletRequestBuilder request = exactCreateBooking();
        replaceHeader(request, PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                "psr-" + "f".repeat(64));

        mvc.perform(request).andExpect(status().isConflict());

        verify(operations, never()).createBooking(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void normalAndSupportModesCannotConfuseWorkplaceOwnerPep() throws Exception {
        MockHttpServletRequestBuilder providerAsNormal = exactExplore();
        replaceHeader(providerAsNormal, PlatformSecurityFilter.ROLES_HEADER,
                "PROVIDER_SUPPORT");
        mvc.perform(providerAsNormal).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder supportAsNormal = exactExplore()
                .header(PlatformSecurityFilter.SUPPORT_SESSION_HEADER, "support-1")
                .header(PlatformSecurityFilter.SUPPORT_SCOPES_HEADER,
                        "TENANT_CONFIGURATION_READ")
                .header(PlatformSecurityFilter.ACTOR_TENANT_HEADER, "3");
        mvc.perform(supportAsNormal).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder normalAsSupport = exactExplore();
        replaceHeader(normalAsSupport,
                PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER,
                "PROVIDER_SUPPORT");
        mvc.perform(normalAsSupport).andExpect(status().isForbidden());

        verifyNoInteractions(service, operations);
    }

    @Test
    void spoofedInternalAuthorityHeadersCannotBypassPlatformServiceIdentity()
            throws Exception {
        MockHttpServletRequestBuilder request = exactExplore();
        replaceHeader(request, PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "spoofed");

        mvc.perform(request).andExpect(status().isUnauthorized());

        verifyNoInteractions(service, operations);
    }

    @Test
    void v21PageDataAndActionBindingsReachActualWorkplaceRoutes()
            throws Exception {
        mvc.perform(exactExplore())
                .andExpect(status().isOk())
                .andExpect(header().string(
                        PlatformSecurityFilter.RESPONSE_DECISION_REVISION_HEADER,
                        CURRENT_REVISION));
        verify(service).explore(
                eq(TENANT), eq(ACTOR), eq(null), eq(null),
                any(), any(), eq(null), eq(null));

        when(service.floorBackground(TENANT, ACTOR, null, FLOOR)).thenReturn(
                new WorkplaceService.FloorBackground(
                        new ByteArrayResource(new byte[] {1}),
                        "image/png", 1L, "floor-v1"));
        MockHttpServletRequestBuilder elevatedData = exact(
                get("/v1/workplace/floors/{floorId}/background", FLOOR),
                "route.workplace.work.floor-background.data",
                "APP.WORKPLACE:VIEW");
        replaceHeader(elevatedData,
                PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER,
                "ELEVATED");
        mvc.perform(elevatedData)
                .andExpect(status().isOk());
        verify(service).floorBackground(TENANT, ACTOR, null, FLOOR);

        mvc.perform(exactCreateBooking()).andExpect(status().isOk());
        verify(operations).createBooking(
                eq(TENANT), eq(ACTOR), eq(null), eq(null), eq(null), eq(null),
                eq("workplace-contract-test"), eq(null),
                any(WorkplaceDtos.BookingRequest.class));

        assertThat(registry.bindingContracts())
                .allSatisfy(binding -> {
                    assertThat(binding.policyId()).isEqualTo("P-WORKPLACE");
                    assertThat(binding.productId()).isEqualTo("workplace");
                    assertThat(binding.ownerService()).isEqualTo("dwp-platform-server");
                    assertThat(binding.serviceKey()).isEqualTo("platform");
                    assertThat(binding.publicPath()).startsWith("/api/platform/v1/");
                    assertThat(binding.servicePath()).startsWith("/v1/");
                    assertThat(binding.resolvedAuthorities()).isNotEmpty();
                });
        assertThat(registry.bindingContracts()).hasSize(306);
        assertThat(registry.bindingContracts())
                .extracting(
                        PlatformWorkplaceProductPepRegistry.BindingContract::routeContractKey,
                        PlatformWorkplaceProductPepRegistry.BindingContract::routeKind,
                        PlatformWorkplaceProductPepRegistry.BindingContract::method,
                        PlatformWorkplaceProductPepRegistry.BindingContract::publicPath,
                        PlatformWorkplaceProductPepRegistry.BindingContract::servicePath)
                .contains(
                        tuple(
                                "route.workplace.work.explore.page", "PAGE", "GET",
                                "/api/platform/v1/workplace/explore",
                                "/v1/workplace/explore"),
                        tuple(
                                "route.workplace.work.find.page", "PAGE", "GET",
                                "/api/platform/v1/workplace/explore",
                                "/v1/workplace/explore"),
                        tuple(
                                "route.workplace.work.home.page", "PAGE", "GET",
                                "/api/platform/v1/workplace/explore",
                                "/v1/workplace/explore"),
                        tuple(
                                "route.workplace.work.planner.page", "PAGE", "GET",
                                "/api/platform/v1/workplace/explore",
                                "/v1/workplace/explore"),
                        tuple(
                                "route.workplace.work.reservations.page", "PAGE", "GET",
                                "/api/platform/v1/workplace/bookings",
                                "/v1/workplace/bookings"),
                        tuple(
                                "route.workplace.work.reservations.page", "PAGE", "GET",
                                "/api/platform/v1/rooms/bookings",
                                "/v1/rooms/bookings"),
                        tuple(
                                "route.workplace.work.floor-background.data", "DATA", "GET",
                                "/api/platform/v1/workplace/floors/{floorId}/background",
                                "/v1/workplace/floors/{floorId}/background"),
                        tuple(
                                "route.workplace.work.booking-create.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/bookings",
                                "/v1/workplace/bookings"),
                        tuple(
                                "route.workplace.work.booking-beneficiaries.data", "DATA", "GET",
                                "/api/platform/v1/workplace/booking-intents/beneficiaries",
                                "/v1/workplace/booking-intents/beneficiaries"),
                        tuple(
                                "route.workplace.work.booking-intent-preview.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/booking-intents/preview",
                                "/v1/workplace/booking-intents/preview"),
                        tuple(
                                "route.workplace.work.booking-intent-status.data", "DATA", "GET",
                                "/api/platform/v1/workplace/booking-intents/{intentId}",
                                "/v1/workplace/booking-intents/{intentId}"),
                        tuple(
                                "route.workplace.work.booking-intent-hold.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/booking-intents/{intentId}/holds",
                                "/v1/workplace/booking-intents/{intentId}/holds"),
                        tuple(
                                "route.workplace.work.booking-batch-start.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/booking-batches",
                                "/v1/workplace/booking-batches"),
                        tuple(
                                "route.workplace.work.booking-batch-status.data", "DATA", "GET",
                                "/api/platform/v1/workplace/booking-batches/{batchId}",
                                "/v1/workplace/booking-batches/{batchId}"),
                        tuple(
                                "route.workplace.work.booking-batch-compensation.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/booking-batches/{batchId}/compensations",
                                "/v1/workplace/booking-batches/{batchId}/compensations"),
                        tuple(
                                "route.workplace.work.booking-batch-replan.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/booking-batches/{batchId}/replans",
                                "/v1/workplace/booking-batches/{batchId}/replans"),
                        tuple(
                                "route.workplace.work.waitlist-create.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/waitlist-entries",
                                "/v1/workplace/waitlist-entries"),
                        tuple(
                                "route.workplace.work.waitlist-catalog.data", "DATA", "GET",
                                "/api/platform/v1/workplace/waitlist-entries",
                                "/v1/workplace/waitlist-entries"),
                        tuple(
                                "route.workplace.work.waitlist-detail.data", "DATA", "GET",
                                "/api/platform/v1/workplace/waitlist-entries/{entryId}",
                                "/v1/workplace/waitlist-entries/{entryId}"),
                        tuple(
                                "route.workplace.work.waitlist-update.action", "ACTION", "PATCH",
                                "/api/platform/v1/workplace/waitlist-entries/{entryId}",
                                "/v1/workplace/waitlist-entries/{entryId}"),
                        tuple(
                                "route.workplace.work.waitlist-cancel.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/waitlist-entries/{entryId}:cancel",
                                "/v1/workplace/waitlist-entries/{entryId}:cancel"),
                        tuple(
                                "route.workplace.work.alternative-offer-accept.action", "ACTION", "POST",
                                "/api/platform/v1/workplace/alternative-offers/{offerId}:accept",
                                "/v1/workplace/alternative-offers/{offerId}:accept"),
                        tuple(
                                "route.workplace.management.connector-operations.data",
                                "DATA", "GET",
                                "/api/platform/v1/admin/workplace/connectors/operations",
                                "/v1/admin/workplace/connectors/operations"),
                        tuple(
                                "route.workplace.management.connector-operation.data",
                                "DATA", "GET",
                                "/api/platform/v1/admin/workplace/connectors/{kind}/operations",
                                "/v1/admin/workplace/connectors/{kind}/operations"),
                        tuple(
                                "route.workplace.management.connector-replay-preview.action",
                                "ACTION", "POST",
                                "/api/platform/v1/admin/workplace/connectors/{kind}/replays:preview",
                                "/v1/admin/workplace/connectors/{kind}/replays:preview"),
                        tuple(
                                "route.workplace.management.connector-replay-start.action",
                                "ACTION", "POST",
                                "/api/platform/v1/admin/workplace/connectors/{kind}/replays",
                                "/v1/admin/workplace/connectors/{kind}/replays"),
                        tuple(
                                "route.workplace.management.connector-replay-status.data",
                                "DATA", "GET",
                                "/api/platform/v1/admin/workplace/connectors/{kind}/replays/{jobId}",
                                "/v1/admin/workplace/connectors/{kind}/replays/{jobId}"));
        assertThat(registry.bindingContracts().stream()
                .map(PlatformWorkplaceProductPepRegistry.BindingContract::routeContractKey)
                .filter(route -> route.contains("service-catalog")
                        || route.contains("service-order")
                        || route.contains("service-fulfillment"))
                .collect(java.util.stream.Collectors.toSet()))
                .contains(
                        "route.workplace.management.service-catalog-create.action",
                        "route.workplace.management.service-catalog-detail.data",
                        "route.workplace.management.service-catalog-state.action",
                        "route.workplace.management.service-catalog-update.action",
                        "route.workplace.management.service-catalog.page",
                        "route.workplace.management.service-fulfillment-attachment-download.data",
                        "route.workplace.management.service-fulfillment-attachment-scan-result.action",
                        "route.workplace.management.service-fulfillment-attachment-scan-status.data",
                        "route.workplace.management.service-fulfillment-attachment-upload.action",
                        "route.workplace.management.service-fulfillment-attachments.data",
                        "route.workplace.management.service-fulfillment-detail.data",
                        "route.workplace.management.service-fulfillment-events.data",
                        "route.workplace.management.service-fulfillment-line-adjustment-reconcile.action",
                        "route.workplace.management.service-fulfillment-line-adjustment.data",
                        "route.workplace.management.service-fulfillment-message.action",
                        "route.workplace.management.service-fulfillment-messages.data",
                        "route.workplace.management.service-fulfillment-task-update.action",
                        "route.workplace.management.service-fulfillment.page",
                        "route.workplace.work.service-catalog.data",
                        "route.workplace.work.service-order-attachment-download.data",
                        "route.workplace.work.service-order-attachment-upload.action",
                        "route.workplace.work.service-order-attachments.data",
                        "route.workplace.work.service-order-cancel.action",
                        "route.workplace.work.service-order-detail.data",
                        "route.workplace.work.service-order-events.data",
                        "route.workplace.work.service-order-line-adjustment.data",
                        "route.workplace.work.service-order-line-cancel.action",
                        "route.workplace.work.service-order-line-cancellation-impact.action",
                        "route.workplace.work.service-order-message.action",
                        "route.workplace.work.service-order-messages.data",
                        "route.workplace.work.service-order-preview.action",
                        "route.workplace.work.service-order-reconfirm.action",
                        "route.workplace.work.service-order-submit.action",
                        "route.workplace.work.service-orders.page");
        assertThat(registry.bindingContracts())
                .extracting(
                        PlatformWorkplaceProductPepRegistry.BindingContract::routeContractKey,
                        PlatformWorkplaceProductPepRegistry.BindingContract::authorityType,
                        PlatformWorkplaceProductPepRegistry.BindingContract::authorityKey)
                .contains(
                        tuple("route.workplace.work.explore.page",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.find.page",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.planner.page",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.reservations.page",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.reservations.page",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.floor-background.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.booking-create.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.work.booking-beneficiaries.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.booking-intent-preview.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.work.booking-intent-status.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.booking-intent-hold.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.work.booking-batch-start.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.work.booking-batch-status.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.booking-batch-compensation.action",
                                "CAPABILITY", "workplace.booking.update"),
                        tuple("route.workplace.work.booking-batch-replan.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.work.waitlist-create.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.work.waitlist-catalog.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.waitlist-detail.data",
                                "POLICY", "workplace.work-access.v1"),
                        tuple("route.workplace.work.waitlist-update.action",
                                "CAPABILITY", "workplace.booking.update"),
                        tuple("route.workplace.work.waitlist-cancel.action",
                                "CAPABILITY", "workplace.booking.update"),
                        tuple("route.workplace.work.alternative-offer-accept.action",
                                "CAPABILITY", "workplace.space.create"),
                        tuple("route.workplace.management.connector-operations.data",
                                "CAPABILITY", "workplace.connector-runtime.read"),
                        tuple("route.workplace.management.connector-operation.data",
                                "CAPABILITY", "workplace.connector-runtime.read"),
                        tuple("route.workplace.management.connector-replay-preview.action",
                                "CAPABILITY", "workplace.connector-replay.manage"),
                        tuple("route.workplace.management.connector-replay-start.action",
                                "CAPABILITY", "workplace.connector-replay.manage"),
                        tuple("route.workplace.management.connector-replay-status.data",
                                "CAPABILITY", "workplace.connector-replay.manage"));
        assertThat(registry.bindingContracts().stream()
                .map(PlatformWorkplaceProductPepRegistry.BindingContract::routeKind)
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(Set.of("PAGE", "DATA", "ACTION"));
    }

    @Test
    void v21ServiceHistoryLineAdjustmentAndScanBindingsFailClosed() {
        String order = "00000000-0000-4000-8000-000000000001";
        String line = "00000000-0000-4000-8000-000000000002";
        String adjustment = "00000000-0000-4000-8000-000000000003";
        String attachment = "00000000-0000-4000-8000-000000000004";

        assertThat(registry.authorize(
                "route.workplace.work.service-order-events.data", "GET",
                "/v1/workplace/service-orders/" + order + "/events",
                Set.of("APP.WORKPLACE:VIEW"), "NORMAL").allowed()).isTrue();
        assertThat(registry.authorize(
                "route.workplace.work.service-order-events.data", "POST",
                "/v1/workplace/service-orders/" + order + "/events",
                Set.of("APP.WORKPLACE:VIEW"), "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(
                "route.workplace.work.service-order-detail.data", "GET",
                "/v1/workplace/service-orders/" + order + "/events",
                Set.of("APP.WORKPLACE:VIEW"), "NORMAL").allowed()).isFalse();

        assertThat(registry.authorize(
                "route.workplace.work.service-order-line-cancel.action", "POST",
                "/v1/workplace/service-orders/" + order + "/lines/" + line + ":cancel",
                Set.of("APP.WORKPLACE:VIEW"), "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(
                "route.workplace.work.service-order-line-cancel.action", "POST",
                "/v1/workplace/service-orders/" + order + "/lines/" + line + ":cancel",
                Set.of("APP.WORKPLACE:UPDATE"), "NORMAL").allowed()).isTrue();

        String scanStatus = "/v1/admin/workplace/service-orders/" + order
                + "/attachments/" + attachment + "/scan-status";
        assertThat(registry.authorize(
                "route.workplace.management.service-fulfillment-attachment-scan-status.data",
                "GET", scanStatus, Set.of("ADMIN.WORKPLACE:VIEW"), "NORMAL").allowed())
                .isFalse();
        assertThat(registry.authorize(
                "route.workplace.management.service-fulfillment-attachment-scan-status.data",
                "GET", scanStatus, Set.of("ADMIN.WORKPLACE:VIEW"), "ELEVATED").allowed())
                .isTrue();

        String reconcile = "/v1/admin/workplace/service-orders/" + order
                + "/line-adjustments/" + adjustment + ":reconcile";
        assertThat(registry.authorize(
                "route.workplace.management.service-fulfillment-line-adjustment-reconcile.action",
                "POST", reconcile, Set.of("ADMIN.WORKPLACE:MANAGE"), "NORMAL").allowed())
                .isFalse();
        assertThat(registry.authorize(
                "route.workplace.management.service-fulfillment-line-adjustment-reconcile.action",
                "POST", reconcile, Set.of("ADMIN.WORKPLACE:VIEW"), "ELEVATED").allowed())
                .isFalse();
        assertThat(registry.authorize(
                "route.workplace.management.service-fulfillment-line-adjustment-reconcile.action",
                "POST", reconcile, Set.of("ADMIN.WORKPLACE:MANAGE"), "ELEVATED").allowed())
                .isTrue();
    }

    @Test
    void v21ClosesEveryCurrentHumanRouteAndExactRoomSupportWithoutDeviceRoutes()
            throws Exception {
        JsonNode versionTwentyOne = contract("product-surfaces-v1.bundle-v21.json");
        Set<String> registryBindings = workplacePlatformBindings(versionTwentyOne);
        Set<String> openApiWorkplace = platformOpenApiWorkplaceBindings();
        assertThat(openApiWorkplace).hasSize(299).containsAll(DEVICE_BINDINGS);
        Set<String> human = new java.util.LinkedHashSet<>(openApiWorkplace);
        human.removeAll(DEVICE_BINDINGS);
        assertThat(human).hasSize(289).contains(
                "POST /v1/workplace/bookings/{bookingId}/check-in",
                "POST /v1/workplace/bookings/{bookingId}/cancel",
                "POST /v1/workplace/bookings/{bookingId}/release",
                "POST /v1/workplace/bookings/{bookingId}/relocate",
                "POST /v1/workplace/assistant/requests",
                "GET /v1/admin/workplace/assistant/audit-events");
        Set<String> strictRegistry = registryBindings.stream()
                .filter(binding -> binding.contains(" /v1/workplace/")
                        || binding.contains(" /v1/admin/workplace/"))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(strictRegistry).isEqualTo(human);
        assertThat(registryBindings.stream()
                .filter(binding -> binding.contains(" /v1/rooms/")
                        || binding.contains(" /v1/admin/rooms/"))
                .collect(java.util.stream.Collectors.toSet()))
                .isEqualTo(ROOM_BINDINGS);
        assertThat(registryBindings).hasSize(303).doesNotContainAnyElementsOf(DEVICE_BINDINGS);
        assertThat(registry.ownsOwner("GET", "/v1/admin/rooms/policy")).isTrue();
        assertThat(registry.ownsOwner("GET", "/v1/admin/rooms/not-owned")).isFalse();
    }

    @Test
    void v21PublishesEveryNewPageAndEnforcesAllAndExportAuthorities() {
        assertThat(registry.bindingContracts().stream()
                .map(PlatformWorkplaceProductPepRegistry.BindingContract::routeContractKey)
                .collect(java.util.stream.Collectors.toSet()))
                .contains(
                        "route.workplace.work.home.page",
                        "route.workplace.work.wayfinding.page",
                        "route.workplace.work.assistant.page",
                        "route.workplace.work.safety.page",
                        "route.workplace.management.devices.page",
                        "route.workplace.management.service-providers.page",
                        "route.workplace.management.space-planning.page",
                        "route.workplace.management.assistant-governance.page",
                        "route.workplace.management.governance.page",
                        "route.workplace.management.safety.page",
                        "route.workplace.management.visits.page",
                        "route.workplace.management.visit-policies.page",
                        "route.workplace.management.access-zones.page",
                        "route.workplace.management.visit-providers.page",
                        "route.workplace.management.kiosk-devices.page",
                        "route.workplace.management.overview.page",
                        "route.workplace.management.operations.page",
                        "route.workplace.management.exceptions.page",
                        "route.workplace.management.locations.page",
                        "route.workplace.management.policy.page",
                        "route.workplace.management.room-operations.page",
                        "route.workplace.management.room-policy.page");

        String planningApprove = "/v1/admin/workplace/space-planning/scenarios/"
                + "33333333-3333-4333-8333-333333333333:approve";
        String planningRoute =
                "route.workplace.management.space-planning-scenarios-by-scenario-id-approve-post.action";
        assertThat(registry.authorize(planningRoute, "POST", planningApprove,
                Set.of("ADMIN.WORKPLACE:MANAGE"), "ELEVATED").allowed()).isFalse();
        assertThat(registry.authorize(planningRoute, "POST", planningApprove,
                Set.of("ADMIN.WORKPLACE:APPROVE"), "ELEVATED").allowed()).isFalse();
        assertThat(registry.authorize(planningRoute, "POST", planningApprove,
                Set.of("ADMIN.WORKPLACE:MANAGE", "ADMIN.WORKPLACE:APPROVE"),
                "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(planningRoute, "POST", planningApprove,
                Set.of("ADMIN.WORKPLACE:MANAGE", "ADMIN.WORKPLACE:APPROVE"),
                "ELEVATED").allowed()).isTrue();

        String safetyExport = "/v1/admin/workplace/safety/incidents/"
                + "44444444-4444-4444-8444-444444444444/exports";
        String exportRoute =
                "route.workplace.management.safety-incidents-by-incident-id-exports-post.action";
        assertThat(registry.authorize(exportRoute, "POST", safetyExport,
                Set.of("ADMIN.WORKPLACE:MANAGE"), "ELEVATED").allowed()).isFalse();
        assertThat(registry.authorize(exportRoute, "POST", safetyExport,
                Set.of("ADMIN.WORKPLACE:EXPORT"), "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(exportRoute, "POST", safetyExport,
                Set.of("ADMIN.WORKPLACE:EXPORT"), "ELEVATED").allowed()).isTrue();

        String passExecute = "/v1/workplace/navigation/access-pass:execute";
        String passRoute = "route.workplace.work.access-pass-execute.action";
        assertThat(registry.authorize(passRoute, "POST", passExecute,
                Set.of("APP.WORKPLACE:UPDATE"), "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(passRoute, "POST", passExecute,
                Set.of("APP.WORKPLACE:UPDATE"), "ELEVATED").allowed()).isTrue();

        String closureExecute = "/v1/admin/workplace/experience/facilities/"
                + "closure-impact-previews/55555555-5555-4555-8555-555555555555/commands";
        String closureRoute =
                "route.workplace.management.facility-closure-impact-execute.action";
        assertThat(registry.authorize(closureRoute, "POST", closureExecute,
                Set.of("ADMIN.WORKPLACE:UPDATE"), "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(closureRoute, "POST", closureExecute,
                Set.of("ADMIN.WORKPLACE:UPDATE"), "ELEVATED").allowed()).isTrue();

        String recoveryPreview = "/v1/admin/workplace/exceptions/CONNECTOR_CALENDAR/"
                + "recovery:preview";
        String recoveryRoute =
                "route.workplace.management.exception-recovery-preview.action";
        assertThat(registry.authorize(recoveryRoute, "POST", recoveryPreview,
                Set.of("ADMIN.WORKPLACE:MANAGE"), "NORMAL").allowed()).isFalse();
        assertThat(registry.authorize(recoveryRoute, "POST", recoveryPreview,
                Set.of("ADMIN.WORKPLACE:MANAGE"), "ELEVATED").allowed()).isTrue();
    }

    @Test
    void connectorReadsUseViewWhileReplayContractsRequireManageAndElevated()
            throws Exception {
        MockHttpServletRequestBuilder operationsRequest = exactManagement(
                get("/v1/admin/workplace/connectors/operations"),
                "route.workplace.management.connector-operations.data",
                "ADMIN.WORKPLACE:VIEW", "NORMAL");
        mvc.perform(operationsRequest).andExpect(status().isOk());
        verify(connectorOps).operations(TENANT);

        String jobId = "33333333-3333-4333-8333-333333333333";
        MockHttpServletRequestBuilder normalStatus = exactManagement(
                get("/v1/admin/workplace/connectors/CALENDAR/replays/{jobId}", jobId),
                "route.workplace.management.connector-replay-status.data",
                "ADMIN.WORKPLACE:MANAGE", "NORMAL");
        mvc.perform(normalStatus).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder elevatedStatus = exactManagement(
                get("/v1/admin/workplace/connectors/CALENDAR/replays/{jobId}", jobId),
                "route.workplace.management.connector-replay-status.data",
                "ADMIN.WORKPLACE:MANAGE", "ELEVATED");
        mvc.perform(elevatedStatus).andExpect(status().isOk());
        verify(connectorOps).replay(TENANT,
                com.dwp.services.platform.workplace.connectorops
                        .WorkplaceConnectorOpsDtos.ConnectorKind.CALENDAR,
                UUID.fromString(jobId));

        MockHttpServletRequestBuilder elevatedPreview = exactManagement(
                post("/v1/admin/workplace/connectors/CALENDAR/replays:preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "connector-preview-pep-test")
                        .header("X-Correlation-ID", "connector-preview-pep-correlation")
                        .content("""
                                {
                                  "from":"2026-09-16T01:00:00Z",
                                  "to":"2026-09-16T02:00:00Z",
                                  "failedOnly":true,
                                  "maximumRecords":500,
                                  "configurationVersion":4,
                                  "runtimeVersion":7
                                }
                                """),
                "route.workplace.management.connector-replay-preview.action",
                "ADMIN.WORKPLACE:MANAGE", "ELEVATED");
        mvc.perform(elevatedPreview).andExpect(status().isOk());
        verify(connectorOps).preview(eq(TENANT), eq(ACTOR),
                eq(com.dwp.services.platform.workplace.connectorops
                        .WorkplaceConnectorOpsDtos.ConnectorKind.CALENDAR),
                eq("connector-preview-pep-test"), any(),
                eq("connector-preview-pep-correlation"));
    }

    @Test
    void reservationsRouteIsGovernedAndCandidateRouteDriftFailsClosed()
            throws Exception {
        mvc.perform(exact(
                        get("/v1/workplace/bookings")
                                .param("from", "2026-08-28T09:00:00+09:00")
                                .param("to", "2026-08-28T18:00:00+09:00"),
                        "route.workplace.work.reservations.page",
                        "APP.WORKPLACE:VIEW"))
                .andExpect(status().isOk());
        verify(service).myBookings(
                eq(TENANT), eq(ACTOR), any(), any(), eq(null), eq(null));

        MockHttpServletRequestBuilder unknown = exactExplore();
        replaceHeader(unknown, PlatformSecurityFilter.ROUTE_CONTRACT_HEADER,
                "route.workplace.work.unknown.page");
        mvc.perform(unknown).andExpect(status().isForbidden());

        MockHttpServletRequestBuilder missing = exactExplore();
        missing.with(raw -> {
            raw.removeHeader(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER);
            return raw;
        });
        mvc.perform(missing).andExpect(status().isForbidden());

        verify(service, never()).explore(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(operations);
    }

    private JsonNode contract(String name) throws Exception {
        Path path = Path.of("contracts/product-authorization", name);
        if (!Files.exists(path)) path = Path.of("../contracts/product-authorization", name);
        return objectMapper.readTree(Files.readAllBytes(path));
    }

    private Set<String> platformOpenApiWorkplaceBindings() throws Exception {
        Path path = Path.of("contracts/openapi/platform.json");
        if (!Files.exists(path)) path = Path.of("../contracts/openapi/platform.json");
        JsonNode document = objectMapper.readTree(Files.readAllBytes(path));
        Set<String> methods = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
        Set<String> bindings = new java.util.LinkedHashSet<>();
        document.path("paths").properties().forEach(pathEntry -> {
            String servicePath = pathEntry.getKey();
            if (!(servicePath.startsWith("/v1/workplace/")
                    || servicePath.startsWith("/v1/admin/workplace/")
                    || servicePath.startsWith("/v1/device/workplace/"))) {
                return;
            }
            pathEntry.getValue().properties().forEach(operation -> {
                String method = operation.getKey().toUpperCase(java.util.Locale.ROOT);
                if (methods.contains(method)) bindings.add(method + " " + servicePath);
            });
        });
        return Set.copyOf(bindings);
    }

    private Set<String> workplacePlatformBindings(JsonNode bundle) {
        Set<String> values = new java.util.LinkedHashSet<>();
        for (JsonNode route : bundle.path("routes")) {
            if (!"workplace".equals(route.path("subject").path("productKey").asText())) {
                continue;
            }
            for (JsonNode binding : route.path("servicePepBindings")) {
                if ("platform".equals(binding.path("serviceKey").asText())) {
                    values.add(binding.path("method").asText()
                            + " " + binding.path("path").asText());
                }
            }
        }
        return Set.copyOf(values);
    }

    private MockHttpServletRequestBuilder exactExplore() {
        return exact(
                get("/v1/workplace/explore")
                        .param("from", "2026-08-28T09:00:00+09:00")
                        .param("to", "2026-08-28T10:00:00+09:00"),
                "route.workplace.work.explore.page",
                "APP.WORKPLACE:VIEW");
    }

    private MockHttpServletRequestBuilder exactCreateBooking() {
        return exact(
                post("/v1/workplace/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "workplace-contract-test")
                        .content("""
                                {
                                  "resourceId": "%s",
                                  "startsAt": "2026-08-29T09:00:00+09:00",
                                  "endsAt": "2026-08-29T10:00:00+09:00",
                                  "purpose": "Contract verification",
                                  "visibleToColleagues": false
                                }
                                """.formatted(RESOURCE)),
                "route.workplace.work.booking-create.action",
                "APP.WORKPLACE:CREATE");
    }

    private MockHttpServletRequestBuilder exact(
            MockHttpServletRequestBuilder request,
            String route,
            String permissions) {
        request.header(PlatformSecurityFilter.SERVICE_TOKEN_HEADER, "trusted")
                .header(PlatformSecurityFilter.USER_HEADER, Long.toString(ACTOR))
                .header(PlatformSecurityFilter.TENANT_HEADER, Long.toString(TENANT))
                .header(PlatformSecurityFilter.ROLES_HEADER, "WORKSPACE_MEMBER")
                .header(PlatformSecurityFilter.PERMISSIONS_HEADER, permissions)
                .header(PlatformSecurityFilter.ROLLOUT_STATE_HEADER, "110")
                .header(PlatformSecurityFilter.ROLLOUT_REVISION_HEADER, ROLLOUT_REVISION)
                .header(PlatformSecurityFilter.ROLLOUT_COHORT_HEADER, "full")
                .header(PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL")
                .header(PlatformSecurityFilter.ROUTE_CONTRACT_HEADER, route)
                .header(PlatformSecurityFilter.CURRENT_DECISION_REVISION_HEADER,
                        CURRENT_REVISION)
                .header(PlatformSecurityFilter.CURRENT_REVALIDATE_AT_HEADER,
                        "2099-01-01T00:00:00Z")
                .header(PlatformSecurityFilter.CONTEXT_HEADER, CONTEXT)
                .header(PlatformSecurityFilter.SCOPE_HEADER,
                        ProductSurfaceScopeKey.key(
                                TENANT, ACTOR, "workplace", "workplace.work",
                                "SELF", "SELF"));
        if (route.endsWith(".action")) {
            request.header(PlatformSecurityFilter.EXPECTED_DECISION_REVISION_HEADER,
                    CURRENT_REVISION);
        }
        return request;
    }

    private MockHttpServletRequestBuilder exactManagement(
            MockHttpServletRequestBuilder request,
            String route,
            String permissions,
            String accessMode) {
        exact(request, route, permissions)
                .header(PlatformSecurityFilter.RESOURCE_ROLES_HEADER,
                        "APP_CONFIG_ADMIN@APP_WORKPLACE");
        replaceHeader(request, PlatformSecurityFilter.SCOPE_HEADER,
                ProductSurfaceScopeKey.resourceSet(
                        TENANT, ACTOR, "workplace", "workplace.management",
                        "APP_WORKPLACE"));
        replaceHeader(request,
                PlatformWorkplaceProductPepFilter.ACTIVE_ACCESS_MODE_HEADER,
                accessMode);
        return request;
    }

    private void replaceHeader(
            MockHttpServletRequestBuilder request,
            String name,
            String value) {
        request.with(raw -> {
            raw.removeHeader(name);
            raw.addHeader(name, value);
            return raw;
        });
    }
}
