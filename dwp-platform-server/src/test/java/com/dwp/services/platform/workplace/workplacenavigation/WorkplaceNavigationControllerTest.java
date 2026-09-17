package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceController.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationAdminController.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationController.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkplaceNavigationControllerTest {
    private final WorkplaceNavigationService navigation = mock(WorkplaceNavigationService.class);
    private final WorkplaceDeviceService devices = mock(WorkplaceDeviceService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new WorkplaceNavigationController(navigation),
            new WorkplaceDeviceController(devices),
            new WorkplaceNavigationAdminController(navigation, devices)).build();

    @Test
    void routeReturnsExplicitFallbackWithoutInventedSteps() throws Exception {
        UUID siteId = UUID.randomUUID();
        UUID origin = UUID.randomUUID();
        UUID destination = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        when(navigation.route(eq(42L), eq(siteId), eq(origin), eq(destination),
                eq(true), eq(true), anySet())).thenReturn(new RouteProjection(
                RouteOutcome.GRAPH_MISSING, null, 0, null, null, List.of(), 0,
                new LocationFallback(siteId, null, null, "Campus", null, null,
                        null, List.of()), List.of("No published graph"), null, now));

        mvc.perform(get("/v1/workplace/navigation/routes")
                        .header(TENANT, 42).header(PERMISSIONS, VIEW)
                        .param("siteId", siteId.toString())
                        .param("originPoiId", origin.toString())
                        .param("destinationPoiId", destination.toString())
                        .param("accessible", "true").param("avoidStairs", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.outcome").value("GRAPH_MISSING"))
                .andExpect(jsonPath("$.data.steps").isEmpty());
    }

    @Test
    void deviceProjectionUsesDeviceIdentityAndDoesNotExposeSecretsOrPeople() throws Exception {
        UUID deviceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        DeviceView device = new DeviceView(deviceId, "12A", DeviceType.ROOM_PANEL,
                RegistrationState.BOUND, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Panel", "OS", "app", "policy", now, ConnectivityState.ONLINE,
                now, now, FreshnessState.FRESH, null, true, 3, now);
        ScheduleItem current = new ScheduleItem(UUID.randomUUID(), now.minusMinutes(10),
                now.plusMinutes(30), "Reserved", null, true);
        DeviceProjection projection = new DeviceProjection(DeviceType.ROOM_PANEL,
                new RoomPanelProjection(device, current, null, AvailabilityState.OCCUPIED,
                        false, true, true, null, now), null);
        when(devices.projection(42, deviceId, "opaque-device-identity-material-123456789"))
                .thenReturn(projection);

        mvc.perform(get("/v1/device/workplace/devices/{deviceId}/projection", deviceId)
                        .header(TENANT, 42)
                        .header(DEVICE_IDENTITY, "opaque-device-identity-material-123456789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomPanel.current.title").value("Reserved"))
                .andExpect(jsonPath("$.data.roomPanel.current.organizer").isEmpty())
                .andExpect(jsonPath("$.data.secret").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andExpect(jsonPath("$.data.pin").doesNotExist());
    }

    @Test
    void deviceRegistrationHeartbeatAndProjectionUseCanonicalIdentityPlanePaths()
            throws Exception {
        UUID deviceId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        String identity = "opaque-device-identity-material-123456789";
        DeviceView registered = new DeviceView(deviceId, "12A", DeviceType.ROOM_PANEL,
                RegistrationState.PENDING, null, null, null, "Panel", "OS", null, null,
                null, ConnectivityState.UNREGISTERED, null, null, FreshnessState.UNKNOWN,
                null, true, 1, now);
        DeviceView heartbeat = new DeviceView(deviceId, "12A", DeviceType.ROOM_PANEL,
                RegistrationState.PENDING, null, null, null, "Panel", "OS", "1.2.3", "7",
                now, ConnectivityState.ONLINE, now, now, FreshnessState.FRESH,
                null, true, 2, now);
        DeviceProjection projection = new DeviceProjection(
                DeviceType.ROOM_PANEL, null, null);
        when(devices.register(eq(42L), eq(identity), any())).thenReturn(registered);
        when(devices.heartbeat(eq(42L), eq(deviceId), eq(identity), any()))
                .thenReturn(heartbeat);
        when(devices.projection(42L, deviceId, identity)).thenReturn(projection);

        mvc.perform(post("/v1/device/workplace/devices:register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(DEVICE_IDENTITY, identity)
                        .content(mapper.writeValueAsBytes(new DeviceRegistrationRequest(
                                "12A", DeviceType.ROOM_PANEL, "Panel", "OS"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deviceId").value(deviceId.toString()));
        mvc.perform(post("/v1/device/workplace/devices/{deviceId}/heartbeat", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(DEVICE_IDENTITY, identity)
                        .content(mapper.writeValueAsBytes(new DeviceHeartbeatRequest(
                                1, "1.2.3", "7", now, now, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connectivity").value("ONLINE"));
        mvc.perform(get("/v1/device/workplace/devices/{deviceId}/projection", deviceId)
                        .header(TENANT, 42).header(DEVICE_IDENTITY, identity))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/device/workplace/devices/:register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(DEVICE_IDENTITY, identity)
                        .content(mapper.writeValueAsBytes(new DeviceRegistrationRequest(
                                "12A", DeviceType.ROOM_PANEL, "Panel", "OS"))))
                .andExpect(status().isNotFound());

        verify(devices).register(eq(42L), eq(identity), any());
        verify(devices).heartbeat(eq(42L), eq(deviceId), eq(identity), any());
        verify(devices).projection(42L, deviceId, identity);
    }

    @Test
    void riskyDeviceCommandRequiresManageStepUpAndReturnsRecoverableReceipt() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        assertThatThrownBy(() -> authorizeMutation(ADMIN_VIEW, "ELEVATED"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> authorizeMutation(ADMIN_MANAGE, "NORMAL"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.STEP_UP_REQUIRED));
        DeviceCommandReceipt receipt = new DeviceCommandReceipt(commandId, deviceId, 7,
                DeviceCommandType.FORCE_SYNC, DeviceCommandState.ACCEPTED,
                "Force a verified schedule sync", null, null, 1,
                false, "/v1/admin/workplace/devices/commands/" + commandId,
                "corr-19", now, null, now);
        when(devices.execute(eq(42L), eq(7L), eq(deviceId), eq("command-key"),
                eq("corr-19"), any())).thenReturn(receipt);

        mvc.perform(post("/v1/admin/workplace/devices/{deviceId}/commands", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "command-key").header(CORRELATION, "corr-19")
                        .content(mapper.writeValueAsBytes(new ExecuteDeviceCommandRequest(
                                previewId, 4, "Force a verified schedule sync", true))))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/v1/admin/workplace/devices/commands/" + commandId))
                .andExpect(jsonPath("$.data.state").value("ACCEPTED"));
    }

    @Test
    void administratorWritesRequireAndForwardIdempotencyKeys() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-16T12:00:00Z");
        DeviceView device = new DeviceView(deviceId, "12A", DeviceType.ROOM_PANEL,
                RegistrationState.APPROVED, null, null, null, "Panel", "OS", null, null,
                null, ConnectivityState.UNREGISTERED, null, null, FreshnessState.UNKNOWN,
                null, true, 2, now);
        ProviderTruth provider = new ProviderTruth(ProviderCapability.MDM, "managed-mdm",
                ProviderTruthState.CONFIGURED_UNVERIFIED, 3, null, null, null, null,
                null, null, 1, now);
        DeviceCommandPreview preview = new DeviceCommandPreview(previewId, deviceId,
                DeviceCommandType.FORCE_SYNC, 2, Map.of(), List.of("Refresh"), true,
                List.of(), now.plusMinutes(5), now);
        when(devices.approve(eq(42L), eq(7L), eq(deviceId), eq("approve-key"),
                any(), eq("corr-approve"))).thenReturn(device);
        when(devices.bind(eq(42L), eq(7L), eq(deviceId), eq("bind-key"),
                any(), eq("corr-bind"))).thenReturn(device);
        when(devices.configureProvider(eq(42L), eq(7L), eq(ProviderCapability.MDM),
                eq("provider-key"), any(), eq("corr-provider"))).thenReturn(provider);
        when(devices.preview(eq(42L), eq(7L), eq(deviceId), eq("preview-key"),
                eq("corr-preview"), any())).thenReturn(preview);

        mvc.perform(post("/v1/admin/workplace/devices/{deviceId}:approve", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .content(mapper.writeValueAsBytes(new VersionedAdminCommand(
                                1, "Approve device", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/admin/workplace/devices/{deviceId}:approve", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "approve-key").header(CORRELATION, "corr-approve")
                        .content(mapper.writeValueAsBytes(new VersionedAdminCommand(
                                1, "Approve device", true))))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/workplace/devices/{deviceId}:bind", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "bind-key").header(CORRELATION, "corr-bind")
                        .content(mapper.writeValueAsBytes(new BindDeviceRequest(
                                2, siteId, floorId, resourceId, true, "Bind device", true))))
                .andExpect(status().isOk());
        mvc.perform(put("/v1/admin/workplace/device-providers/{capability}", "MDM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE).header(ACCESS_MODE, "ELEVATED")
                        .header(IDEMPOTENCY, "provider-key")
                        .header(CORRELATION, "corr-provider")
                        .content(mapper.writeValueAsBytes(new ProviderConfigurationRequest(
                                "managed-mdm", 3, true, "Configure provider", true))))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/admin/workplace/devices/{deviceId}/commands:preview", deviceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TENANT, 42).header(USER, 7)
                        .header(PERMISSIONS, ADMIN_MANAGE)
                        .header(IDEMPOTENCY, "preview-key").header(CORRELATION, "corr-preview")
                        .content(mapper.writeValueAsBytes(new DeviceCommandPreviewRequest(
                                DeviceCommandType.FORCE_SYNC, 2, Map.of()))))
                .andExpect(status().isOk());

        verify(devices).approve(eq(42L), eq(7L), eq(deviceId), eq("approve-key"),
                any(), eq("corr-approve"));
        verify(devices).bind(eq(42L), eq(7L), eq(deviceId), eq("bind-key"),
                any(), eq("corr-bind"));
        verify(devices).configureProvider(eq(42L), eq(7L), eq(ProviderCapability.MDM),
                eq("provider-key"), any(), eq("corr-provider"));
        verify(devices).preview(eq(42L), eq(7L), eq(deviceId), eq("preview-key"),
                eq("corr-preview"), any());
    }
}
