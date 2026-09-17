package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceRepository.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplaceDeviceServiceTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final String IDENTITY = "device-identity-material-at-least-32-characters";

    private final WorkplaceDeviceRepository repository = mock(WorkplaceDeviceRepository.class);
    private final WorkplaceDeviceCommandProvider provider = mock(WorkplaceDeviceCommandProvider.class);
    private final WorkplaceDeviceService service = new WorkplaceDeviceService(
            repository, Optional.of(provider), Clock.fixed(FIXED, ZoneOffset.UTC));

    @Test
    void registrationPersistsOnlyTheOneWayIdentityHash() throws Exception {
        DeviceRegistrationRequest request = new DeviceRegistrationRequest(
                "Room panel 12A", DeviceType.ROOM_PANEL, "Panel X", "DeviceOS 3");
        DeviceRow pending = device(DeviceType.ROOM_PANEL, RegistrationState.PENDING);
        when(repository.deviceByIdentity(42, hash(IDENTITY))).thenReturn(Optional.empty());
        when(repository.register(eq(42L), eq(hash(IDENTITY)), same(request), eq(NOW)))
                .thenReturn(pending);

        service.register(42, IDENTITY, request);

        verify(repository).deviceByIdentity(42, hash(IDENTITY));
        verify(repository).register(42, hash(IDENTITY), request, NOW);
        verify(repository, never()).deviceByIdentity(42, IDENTITY);
    }

    @Test
    void pendingRegistrationHasNoHeartbeatOrProjectionPrivilege() throws Exception {
        DeviceRow pending = device(DeviceType.ROOM_PANEL, RegistrationState.PENDING);
        when(repository.device(42, pending.deviceId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.heartbeat(42, pending.deviceId(), IDENTITY,
                new DeviceHeartbeatRequest(pending.version(), "19.4", "policy-8", NOW,
                        NOW.minusMinutes(1), null)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> service.projection(42, pending.deviceId(), IDENTITY))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository).heartbeat(eq(42L), eq(pending.deviceId()), any(), eq(NOW));
    }

    @Test
    void deviceAuthenticationChecksTenantBindingAndOneWayIdentity() throws Exception {
        DeviceRow row = device(DeviceType.ROOM_PANEL, RegistrationState.BOUND);
        when(repository.device(42, row.deviceId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.projection(42, row.deviceId(), "x".repeat(40)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.projection(43, row.deviceId(), IDENTITY))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository).device(42, row.deviceId());
        verify(repository).device(43, row.deviceId());
    }

    @Test
    void deviceAuthenticationFailsClosedForMalformedAndRevokedIdentity() throws Exception {
        DeviceRow suspended = device(DeviceType.ROOM_PANEL, RegistrationState.SUSPENDED);
        DeviceRow retired = device(DeviceType.STATUS_BOARD, RegistrationState.RETIRED);
        when(repository.device(42, suspended.deviceId())).thenReturn(Optional.of(suspended));
        when(repository.device(42, retired.deviceId())).thenReturn(Optional.of(retired));

        assertThatThrownBy(() -> service.projection(42, suspended.deviceId(), "short"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> service.projection(42, suspended.deviceId(), IDENTITY))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.projection(42, retired.deviceId(), IDENTITY))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void roomPanelProjectionAlwaysMasksPurposeAndOrganizer() throws Exception {
        DeviceRow row = device(DeviceType.ROOM_PANEL, RegistrationState.BOUND);
        when(repository.device(42, row.deviceId())).thenReturn(Optional.of(row));
        when(repository.schedules(eq(42L), eq(row.resourceId()), any(), any())).thenReturn(List.of(
                new ScheduleRow(UUID.randomUUID(), NOW.minusMinutes(10), NOW.plusMinutes(50),
                        "Confidential acquisition", "Actual Executive Name", true),
                new ScheduleRow(UUID.randomUUID(), NOW.plusHours(1), NOW.plusHours(2),
                        "Private investigation", "Sensitive Organizer", true)));
        when(repository.activeSafetyFrame(42, row.deviceId())).thenReturn(Optional.empty());

        DeviceProjection projection = service.projection(42, row.deviceId(), IDENTITY);

        assertThat(projection.roomPanel().current().title()).isEqualTo("Reserved");
        assertThat(projection.roomPanel().current().organizer()).isNull();
        assertThat(projection.roomPanel().current().privacyMasked()).isTrue();
        assertThat(projection.roomPanel().next().title()).isEqualTo("Reserved");
    }

    @Test
    void providerTruthIsFailClosedForMissingUnverifiedDegradedAndStaleEvidence() {
        ProviderTruthRow unverified = truthRow(ProviderCapability.MDM, null, null, NOW);
        ProviderTruthRow degraded = truthRow(
                ProviderCapability.GRAPH, ProviderReportedState.DEGRADED, 1L, NOW);
        ProviderTruthRow stale = truthRow(
                ProviderCapability.BLE, ProviderReportedState.HEALTHY, 1L, NOW.minusMinutes(6));
        when(repository.providerTruth(42)).thenReturn(List.of(unverified, degraded, stale));

        List<ProviderTruth> truth = service.providerTruth(42);

        assertThat(find(truth, ProviderCapability.MDM).state())
                .isEqualTo(ProviderTruthState.CONFIGURED_UNVERIFIED);
        assertThat(find(truth, ProviderCapability.GRAPH).state())
                .isEqualTo(ProviderTruthState.DEGRADED);
        assertThat(find(truth, ProviderCapability.BLE).state())
                .isEqualTo(ProviderTruthState.STALE);
        assertThat(find(truth, ProviderCapability.TPM).state())
                .isEqualTo(ProviderTruthState.NOT_CONFIGURED);
    }

    @Test
    void resultUnknownReceiptIsReadOnlyAndNeverRepeatsProviderMutation() throws Exception {
        DeviceRow device = device(DeviceType.ROOM_PANEL, RegistrationState.BOUND);
        UUID commandId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        CommandRow unknown = command(commandId, device.deviceId(), previewId, DeviceCommandState.RESULT_UNKNOWN,
                "provider-operation", 2, null);
        when(repository.command(42, commandId)).thenReturn(Optional.of(unknown));

        DeviceCommandReceipt receipt = service.receipt(42, commandId);

        assertThat(receipt.state()).isEqualTo(DeviceCommandState.RESULT_UNKNOWN);
        assertThat(receipt.recoveryByGetOnly()).isTrue();
        verify(provider, never()).execute(any());
        verify(provider, never()).status(any());
    }

    @Test
    void staleScheduleFailsClosedForRoomPanelAndStatusBoard() throws Exception {
        DeviceRow room = device(DeviceType.ROOM_PANEL, RegistrationState.BOUND,
                NOW.minusMinutes(20), NOW);
        DeviceRow board = device(DeviceType.STATUS_BOARD, RegistrationState.BOUND,
                NOW.minusMinutes(20), NOW);
        when(repository.device(42, room.deviceId())).thenReturn(Optional.of(room));
        when(repository.device(42, board.deviceId())).thenReturn(Optional.of(board));
        when(repository.schedules(eq(42L), eq(room.resourceId()), any(), any()))
                .thenReturn(List.of());
        when(repository.floorResources(42, board.floorId(), NOW)).thenReturn(List.of(
                new FloorResourceRow(UUID.randomUUID(), UUID.randomUUID(), "업무 구역", "Work zone",
                        "회의실", "Room", false, "복도 끝", "End of corridor")));
        when(repository.activeSafetyFrame(anyLong(), any())).thenReturn(Optional.empty());

        DeviceProjection roomProjection = service.projection(42, room.deviceId(), IDENTITY);
        DeviceProjection boardProjection = service.projection(42, board.deviceId(), IDENTITY);

        assertThat(roomProjection.roomPanel().availability())
                .isEqualTo(AvailabilityState.UNAVAILABLE);
        assertThat(roomProjection.roomPanel().walkUpBookingAllowed()).isFalse();
        assertThat(roomProjection.roomPanel().checkInAllowed()).isFalse();
        assertThat(roomProjection.roomPanel().earlyEndAllowed()).isFalse();
        assertThat(boardProjection.statusBoard().freshness()).isEqualTo(FreshnessState.STALE);
        assertThat(boardProjection.statusBoard().availableCount()).isZero();
        assertThat(boardProjection.statusBoard().occupiedCount()).isZero();
        assertThat(boardProjection.statusBoard().unavailableCount()).isEqualTo(1);
        assertThat(boardProjection.statusBoard().resources().getFirst().availability())
                .isEqualTo(AvailabilityState.UNAVAILABLE);
    }

    @Test
    void commandPayloadRejectsUnexpectedOrUnboundedFieldsBeforePersistence() throws Exception {
        DeviceRow row = device(DeviceType.ROOM_PANEL, RegistrationState.BOUND);
        when(repository.device(42, row.deviceId())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.preview(42, 7, row.deviceId(),
                "preview-unexpected", "correlation",
                new DeviceCommandPreviewRequest(DeviceCommandType.FORCE_SYNC,
                        row.version(), Map.of("secret", "must-not-be-persisted"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> service.preview(42, 7, row.deviceId(),
                "preview-unbounded", "correlation",
                new DeviceCommandPreviewRequest(DeviceCommandType.SAFETY_TAKEOVER,
                        row.version(), Map.of("message", " ", "direction", "Exit"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).savePreview(any(), anyLong(), anyLong());
    }

    @Test
    void executionRechecksProviderTruthAfterEligiblePreview() throws Exception {
        DeviceRow row = device(DeviceType.ROOM_PANEL, RegistrationState.BOUND);
        UUID previewId = UUID.randomUUID();
        DeviceCommandPreview preview = new DeviceCommandPreview(previewId, row.deviceId(),
                DeviceCommandType.FORCE_SYNC, row.version(), Map.of(), List.of(), true,
                List.of(), NOW.plusMinutes(5), NOW.minusMinutes(1));
        when(repository.preview(42, 7, previewId)).thenReturn(Optional.of(preview));
        when(repository.device(42, row.deviceId())).thenReturn(Optional.of(row));
        when(repository.providerTruth(42, ProviderCapability.MDM)).thenReturn(Optional.of(
                truthRow(ProviderCapability.MDM, ProviderReportedState.DEGRADED, 1L, NOW)));

        assertThatThrownBy(() -> service.execute(42, 7, row.deviceId(), "command-key",
                "correlation", new ExecuteDeviceCommandRequest(
                        previewId, row.version(), "Force schedule sync", true)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(repository, never()).createCommand(any());
    }

    @Test
    void administratorApprovalUsesDurableExactReplayAndRejectsKeyReuse() throws Exception {
        DeviceRow pending = device(DeviceType.ROOM_PANEL, RegistrationState.PENDING);
        UUID auditEventId = UUID.randomUUID();
        VersionedAdminCommand request = new VersionedAdminCommand(
                pending.version(), "Approve verified hardware", true);
        when(repository.approve(42, 7, pending.deviceId(), pending.version(), NOW))
                .thenReturn(true);
        when(repository.device(42, pending.deviceId())).thenReturn(Optional.of(pending));
        when(repository.audit(42, 7, "navigation.device.approved", "DEVICE",
                pending.deviceId(), "correlation", NOW)).thenReturn(auditEventId);

        DeviceView first = service.approve(
                42, 7, pending.deviceId(), "admin-approve-key", request, "correlation");

        org.mockito.ArgumentCaptor<String> fingerprint =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).saveAdminCommand(eq(42L), eq(7L), eq("DEVICE_APPROVE"),
                eq("DEVICE"), eq(pending.deviceId()), eq("admin-approve-key"),
                fingerprint.capture(), eq("DEVICE"), same(first), eq(auditEventId),
                eq("correlation"), eq(NOW));
        AdminCommandRow receipt = new AdminCommandRow("DEVICE_APPROVE", "DEVICE",
                pending.deviceId(), fingerprint.getValue(), "DEVICE", "{}", auditEventId,
                "correlation", NOW);
        when(repository.adminCommand(42, 7, "admin-approve-key"))
                .thenReturn(Optional.of(receipt));
        when(repository.adminCommandResult(receipt, DeviceView.class)).thenReturn(first);

        assertThat(service.approve(42, 7, pending.deviceId(), "admin-approve-key",
                request, "retry-correlation")).isEqualTo(first);
        assertThatThrownBy(() -> service.approve(42, 7, pending.deviceId(),
                "admin-approve-key", new VersionedAdminCommand(
                        pending.version(), "A different reason", true), "correlation"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, times(1)).approve(anyLong(), anyLong(), any(), anyLong(), any());
        verify(repository, times(1)).audit(anyLong(), anyLong(), anyString(), anyString(),
                any(), any(), any());
    }

    @Test
    void administratorIdempotencyKeyRequiresVisibleAsciiWithoutNormalization() throws Exception {
        DeviceRow pending = device(DeviceType.ROOM_PANEL, RegistrationState.PENDING);
        VersionedAdminCommand request = new VersionedAdminCommand(
                pending.version(), "Approve verified hardware", true);

        assertThatThrownBy(() -> service.approve(
                42, 7, pending.deviceId(), " key-with-space", request, "correlation"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> service.approve(
                42, 7, pending.deviceId(), "관리자-key", request, "correlation"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).lockAdminCommand(anyLong(), anyLong(), anyString());
    }

    private DeviceRow device(DeviceType type, RegistrationState state) throws Exception {
        return device(type, state, NOW.minusMinutes(1), NOW.minusSeconds(50));
    }

    private DeviceRow device(
            DeviceType type,
            RegistrationState state,
            OffsetDateTime scheduleSourceAt,
            OffsetDateTime scheduleReceivedAt) throws Exception {
        return new DeviceRow(UUID.randomUUID(), 42, hash(IDENTITY), "Room panel 12A", type,
                state, UUID.randomUUID(), UUID.randomUUID(),
                type == DeviceType.ROOM_PANEL ? UUID.randomUUID() : null,
                "Panel X", "DeviceOS 3", "19.4", "policy-8", NOW.minusSeconds(30),
                scheduleSourceAt, scheduleReceivedAt, null, true, 8, NOW.minusSeconds(20));
    }

    private ProviderTruthRow truthRow(
            ProviderCapability capability,
            ProviderReportedState reported,
            Long observedVersion,
            OffsetDateTime receivedAt) {
        return new ProviderTruthRow(42, capability, "provider", 1, observedVersion,
                reported, reported == null ? null : "evidence", receivedAt, receivedAt,
                receivedAt, reported == ProviderReportedState.DEGRADED ? "DEGRADED" : null,
                true, 2);
    }

    private CommandRow command(
            UUID commandId,
            UUID deviceId,
            UUID previewId,
            DeviceCommandState state,
            String providerReference,
            long version,
            OffsetDateTime completedAt) throws Exception {
        return new CommandRow(commandId, 42, 7, deviceId, previewId, DeviceCommandType.FORCE_SYNC,
                "key", "a".repeat(64), state, "Synchronize", "corr",
                providerReference, state == DeviceCommandState.SUCCEEDED ? "SYNCED" : null,
                "provider", 1L, "secret-manager://workplace/device-provider-v1",
                version, NOW.minusMinutes(1), completedAt, NOW);
    }

    private static ProviderTruth find(List<ProviderTruth> truth, ProviderCapability capability) {
        return truth.stream().filter(item -> item.capability() == capability).findFirst().orElseThrow();
    }

    private static String hash(String identity) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(identity.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        return HexFormat.of().formatHex(digest.digest());
    }
}
