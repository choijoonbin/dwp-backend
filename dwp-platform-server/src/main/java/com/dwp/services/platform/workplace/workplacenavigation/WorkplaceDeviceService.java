package com.dwp.services.platform.workplace.workplacenavigation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceRepository.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceSupport.*;

@Service
public class WorkplaceDeviceService {
    static final Duration HEARTBEAT_FRESHNESS = Duration.ofMinutes(2);
    static final Duration SCHEDULE_FRESHNESS = Duration.ofMinutes(10);
    static final Duration PROVIDER_FRESHNESS = Duration.ofMinutes(5);
    static final Duration PREVIEW_TTL = Duration.ofMinutes(5);

    private final WorkplaceDeviceRepository repository;
    private final WorkplaceNavigationAdminCommandSupport adminCommands;
    private final Optional<WorkplaceDeviceCommandProvider> commandProvider;
    private final Clock clock;

    @Autowired
    public WorkplaceDeviceService(
            WorkplaceDeviceRepository repository,
            Optional<WorkplaceDeviceCommandProvider> commandProvider) {
        this(repository, commandProvider, Clock.systemUTC());
    }

    WorkplaceDeviceService(
            WorkplaceDeviceRepository repository,
            Optional<WorkplaceDeviceCommandProvider> commandProvider,
            Clock clock) {
        this.repository = repository;
        this.adminCommands = new WorkplaceNavigationAdminCommandSupport(repository);
        this.commandProvider = commandProvider;
        this.clock = clock;
    }

    @Transactional
    public DeviceView register(
            long tenantId,
            String deviceIdentity,
            DeviceRegistrationRequest request) {
        requireTenant(tenantId);
        String identityHash = identityHash(deviceIdentity);
        DeviceRow existing = repository.deviceByIdentity(tenantId, identityHash).orElse(null);
        if (existing != null) {
            if (existing.deviceType() != request.deviceType()
                    || !existing.hardwareModel().equals(request.hardwareModel().trim())) {
                throw conflict("This device identity is already registered with different hardware metadata.");
            }
            return view(existing);
        }
        return view(repository.register(tenantId, identityHash, request, now()));
    }

    @Transactional
    public DeviceView heartbeat(
            long tenantId,
            UUID deviceId,
            String deviceIdentity,
            DeviceHeartbeatRequest request) {
        DeviceRow device = authenticate(tenantId, deviceId, deviceIdentity);
        OffsetDateTime now = now();
        if (request.observedAt().isAfter(now.plusMinutes(1))
                || (device.heartbeatAt() != null && request.observedAt().isBefore(device.heartbeatAt()))) {
            throw invalid("Device heartbeat clocks must be monotonic and cannot be in the future.");
        }
        if (request.scheduleSourceAt() != null
                && request.scheduleSourceAt().isAfter(request.observedAt())) {
            throw invalid("Schedule source time cannot be newer than the observed heartbeat.");
        }
        if (!repository.heartbeat(tenantId, deviceId, request, now)) {
            throw conflict("The device version changed; refresh registration before retrying heartbeat.");
        }
        return view(repository.device(tenantId, deviceId)
                .orElseThrow(() -> notFound("The registered device was not found.")));
    }

    @Transactional(readOnly = true)
    public DeviceProjection projection(
            long tenantId,
            UUID deviceId,
            String deviceIdentity) {
        DeviceRow device = authenticate(tenantId, deviceId, deviceIdentity);
        if (device.registrationState() != RegistrationState.BOUND) {
            throw forbidden("The device is not approved and bound to a Workplace surface.");
        }
        return device.deviceType() == DeviceType.ROOM_PANEL
                ? new DeviceProjection(DeviceType.ROOM_PANEL, roomPanel(device), null)
                : new DeviceProjection(DeviceType.STATUS_BOARD, null, statusBoard(device));
    }

    @Transactional(readOnly = true)
    public List<DeviceView> devices(
            long tenantId, UUID siteId, RegistrationState state) {
        requireTenant(tenantId);
        return repository.devices(tenantId, siteId, state).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DeviceView device(long tenantId, UUID deviceId) {
        requireTenant(tenantId);
        return view(repository.device(tenantId, deviceId)
                .orElseThrow(() -> notFound("The device was not found in this tenant.")));
    }

    @Transactional(readOnly = true)
    public List<DeviceCommandReceipt> commands(long tenantId, UUID deviceId) {
        device(tenantId, deviceId);
        return repository.commands(tenantId, deviceId).stream().map(this::receipt).toList();
    }

    @Transactional(readOnly = true)
    public List<DeviceAuditEvent> auditEvents(long tenantId, UUID deviceId) {
        device(tenantId, deviceId);
        return repository.auditEvents(tenantId, deviceId);
    }

    @Transactional
    public DeviceView approve(
            long tenantId,
            long actorId,
            UUID deviceId,
            String idempotencyKey,
            VersionedAdminCommand request,
            String correlationId) {
        requireActor(tenantId, actorId);
        WorkplaceNavigationAdminCommandSupport.requireConfirmation(request.explicitConfirmation());
        var command = adminCommands.begin(tenantId, actorId, idempotencyKey,
                "DEVICE_APPROVE", "DEVICE", DeviceView.class, deviceId,
                request.expectedVersion(), request.reason().trim(), request.explicitConfirmation());
        if (command.replay() != null) return command.replay();
        if (!repository.approve(tenantId, actorId, deviceId, request.expectedVersion(), now())) {
            throw conflict("The device state or version changed; refresh before approval.");
        }
        UUID auditEventId = repository.audit(
                tenantId, actorId, "navigation.device.approved", "DEVICE",
                deviceId, correlationId, now());
        DeviceView result = device(tenantId, deviceId);
        adminCommands.save(command, "DEVICE", deviceId, result, auditEventId, correlationId, now());
        return result;
    }

    @Transactional
    public DeviceView bind(
            long tenantId,
            long actorId,
            UUID deviceId,
            String idempotencyKey,
            BindDeviceRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        WorkplaceNavigationAdminCommandSupport.requireConfirmation(request.explicitConfirmation());
        var command = adminCommands.begin(tenantId, actorId, idempotencyKey,
                "DEVICE_BIND", "DEVICE", DeviceView.class, deviceId,
                request.expectedVersion(), request.siteId(), request.floorId(), request.resourceId(),
                request.safetyOfflineFallback(), request.reason().trim(),
                request.explicitConfirmation());
        if (command.replay() != null) return command.replay();
        DeviceRow device = repository.device(tenantId, deviceId)
                .orElseThrow(() -> notFound("The device was not found in this tenant."));
        if (!repository.siteFloorBinding(tenantId, request.siteId(), request.floorId())) {
            throw invalid("The selected floor does not belong to the selected site and tenant.");
        }
        if (device.deviceType() == DeviceType.ROOM_PANEL
                && (request.resourceId() == null
                || !repository.resourceBinding(tenantId, request.floorId(), request.resourceId()))) {
            throw invalid("A room panel must bind to a resource on the selected floor.");
        }
        if (device.deviceType() == DeviceType.STATUS_BOARD && request.resourceId() != null) {
            throw invalid("A status board binds to a floor, not an individual resource.");
        }
        if (!repository.bind(tenantId, deviceId, device.deviceType(), request, now())) {
            throw conflict("The device state or version changed; refresh before binding.");
        }
        UUID auditEventId = repository.audit(
                tenantId, actorId, "navigation.device.bound", "DEVICE",
                deviceId, correlationId, now());
        DeviceView result = device(tenantId, deviceId);
        adminCommands.save(command, "DEVICE", deviceId, result, auditEventId, correlationId, now());
        return result;
    }

    @Transactional(readOnly = true)
    public List<ProviderTruth> providerTruth(long tenantId) {
        requireTenant(tenantId);
        List<ProviderTruth> result = new ArrayList<>();
        Map<ProviderCapability, ProviderTruthRow> rows = repository.providerTruth(tenantId).stream()
                .collect(java.util.stream.Collectors.toMap(ProviderTruthRow::capability, row -> row));
        for (ProviderCapability capability : ProviderCapability.values()) {
            result.add(truth(capability, rows.get(capability)));
        }
        return List.copyOf(result);
    }

    @Transactional
    public ProviderTruth configureProvider(
            long tenantId,
            long actorId,
            ProviderCapability capability,
            String idempotencyKey,
            ProviderConfigurationRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        WorkplaceNavigationAdminCommandSupport.requireConfirmation(request.explicitConfirmation());
        UUID resourceId = WorkplaceNavigationAdminCommandSupport.deterministicId(capability.name());
        var command = adminCommands.begin(tenantId, actorId, idempotencyKey,
                "PROVIDER_CONFIGURE", "PROVIDER_TRUTH", ProviderTruth.class, capability,
                request.providerCode().trim(), request.configurationVersion(), request.configured(),
                request.reason().trim(), request.explicitConfirmation());
        if (command.replay() != null) return command.replay();
        repository.configureProvider(tenantId, capability, request.providerCode().trim(),
                request.configurationVersion(), request.configured(), now());
        UUID auditEventId = repository.audit(tenantId, actorId, "navigation.provider.configured",
                "PROVIDER_TRUTH", resourceId, correlationId, now());
        ProviderTruth result = truth(
                capability, repository.providerTruth(tenantId, capability).orElse(null));
        adminCommands.save(command, "PROVIDER_TRUTH", resourceId, result, auditEventId,
                correlationId, now());
        return result;
    }

    @Transactional
    public ProviderTruth observeProvider(ProviderObservation observation) {
        requireTenant(observation.tenantId());
        if (observation.receivedAt().isBefore(observation.sourceAt())
                || observation.receivedAt().isAfter(now().plusMinutes(1))
                || (observation.lastSuccessAt() != null
                && observation.lastSuccessAt().isAfter(observation.receivedAt()))) {
            throw invalid("Provider source and received clocks are invalid.");
        }
        if (!repository.observeProvider(observation, now())) {
            throw conflict("The provider observation does not match the active configuration.");
        }
        return truth(observation.capability(), repository.providerTruth(
                observation.tenantId(), observation.capability()).orElse(null));
    }

    @Transactional
    public DeviceCommandPreview preview(
            long tenantId,
            long actorId,
            UUID deviceId,
            String idempotencyKey,
            String correlationId,
            DeviceCommandPreviewRequest request) {
        requireActor(tenantId, actorId);
        var command = adminCommands.begin(tenantId, actorId, idempotencyKey,
                "DEVICE_COMMAND_PREVIEW", "DEVICE_COMMAND_PREVIEW",
                DeviceCommandPreview.class, deviceId,
                request.commandType(), request.expectedDeviceVersion(), request.payload());
        if (command.replay() != null) return command.replay();
        DeviceRow device = repository.device(tenantId, deviceId)
                .orElseThrow(() -> notFound("The device was not found in this tenant."));
        if (device.version() != request.expectedDeviceVersion()) {
            throw conflict("The device version changed; refresh before previewing the command.");
        }
        validatePayload(request.commandType(), request.payload());
        List<String> limitations = limitations(tenantId, device, request);
        OffsetDateTime now = now();
        DeviceCommandPreview preview = new DeviceCommandPreview(UUID.randomUUID(), deviceId,
                request.commandType(), request.expectedDeviceVersion(), Map.copyOf(request.payload()),
                impact(request.commandType()), limitations.isEmpty(), limitations,
                now.plus(PREVIEW_TTL), now);
        repository.savePreview(preview, tenantId, actorId);
        UUID auditEventId = repository.audit(tenantId, actorId,
                "navigation.device.command.previewed", "DEVICE", deviceId, correlationId, now);
        adminCommands.save(command, "DEVICE", deviceId, preview, auditEventId,
                correlationId, now);
        return preview;
    }

    @Transactional
    public DeviceCommandReceipt execute(
            long tenantId,
            long actorId,
            UUID deviceId,
            String idempotencyKey,
            String correlationId,
            ExecuteDeviceCommandRequest request) {
        requireActor(tenantId, actorId);
        WorkplaceNavigationAdminCommandSupport.requireConfirmation(request.explicitConfirmation());
        String key = WorkplaceNavigationAdminCommandSupport.requireKey(idempotencyKey);
        DeviceCommandPreview preview = repository.preview(tenantId, actorId, request.previewId())
                .orElseThrow(() -> notFound("The device command preview was not found."));
        if (!preview.deviceId().equals(deviceId) || preview.expiresAt().isBefore(now())) {
            throw conflict("The command preview expired or does not match this device.");
        }
        if (!preview.eligible()) {
            throw forbidden("The command preview is not eligible for execution.");
        }
        if (request.expectedDeviceVersion() != preview.expectedDeviceVersion()) {
            throw conflict("The confirmed device version does not match the preview.");
        }
        String fingerprint = fingerprint(deviceId, preview.previewId(), preview.commandType(),
                request.expectedDeviceVersion(), request.reason().trim());
        CommandRow replay = repository.commandByIdempotency(tenantId, actorId, key).orElse(null);
        if (replay != null) {
            if (!replay.requestFingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key was already used for another device command.");
            }
            return receipt(replay);
        }
        DeviceRow device = repository.device(tenantId, deviceId)
                .orElseThrow(() -> notFound("The device was not found in this tenant."));
        if (device.version() != request.expectedDeviceVersion()) {
            throw conflict("The device version changed after preview; create a new preview.");
        }
        DeviceCommandPreviewRequest currentRequest = new DeviceCommandPreviewRequest(
                preview.commandType(), request.expectedDeviceVersion(), preview.payload());
        validatePayload(currentRequest.commandType(), currentRequest.payload());
        if (!limitations(tenantId, device, currentRequest).isEmpty()) {
            throw forbidden("The device command is no longer eligible; create a new preview.");
        }
        OffsetDateTime now = now();
        ProviderBinding providerBinding = preview.commandType() == DeviceCommandType.UNBIND
                ? null : activeProviderBinding(tenantId)
                .orElseThrow(() -> forbidden(
                        "The MDM provider relay is not configured and verified."));
        CommandRow command = new CommandRow(UUID.randomUUID(), tenantId, actorId, deviceId,
                preview.previewId(), preview.commandType(), key, fingerprint,
                DeviceCommandState.ACCEPTED, request.reason().trim(), correlationId,
                null, null, providerBinding == null ? null : providerBinding.providerCode(),
                providerBinding == null ? null : providerBinding.configurationVersion(),
                providerBinding == null ? null : providerBinding.credentialReference(),
                1, now, null, now);
        repository.createCommand(command);
        if (preview.commandType() == DeviceCommandType.UNBIND) {
            boolean unbound = repository.unbind(tenantId, deviceId,
                    request.expectedDeviceVersion(), now);
            return complete(command, unbound ? DeviceCommandState.SUCCEEDED : DeviceCommandState.FAILED,
                    null, unbound ? "UNBOUND" : "DEVICE_VERSION_CONFLICT", preview, device);
        }
        return receipt(command);
    }

    @Transactional
    public DispatchEnvelope prepareDispatch() {
        CommandRow command = repository.claimNextCommand(now()).orElse(null);
        if (command == null) return null;
        DeviceCommandPreview preview = repository.preview(
                command.tenantId(), command.actorUserId(), command.previewId())
                .orElseThrow(() -> notFound("The command preview was not found."));
        DeviceRow device = repository.device(command.tenantId(), command.deviceId())
                .orElseThrow(() -> notFound("The command device was not found."));
        DeviceCommandPreviewRequest request = new DeviceCommandPreviewRequest(
                preview.commandType(), preview.expectedDeviceVersion(), preview.payload());
        List<String> limitations = limitations(command.tenantId(), device, request, false);
        return new DispatchEnvelope(command, preview, device,
                commandBinding(command).isEmpty() ? "PROVIDER_BINDING_SNAPSHOT_MISSING"
                        : limitations.isEmpty() ? null : "COMMAND_PRECONDITION_CHANGED");
    }

    @Transactional
    public DispatchEnvelope prepareReconciliation() {
        CommandRow command = repository.claimNextReconciliation(now()).orElse(null);
        if (command == null) return null;
        DeviceCommandPreview preview = repository.preview(
                command.tenantId(), command.actorUserId(), command.previewId())
                .orElseThrow(() -> notFound("The command preview was not found."));
        DeviceRow device = repository.device(command.tenantId(), command.deviceId())
                .orElseThrow(() -> notFound("The command device was not found."));
        return new DispatchEnvelope(command, preview, device,
                commandBinding(command).isEmpty() ? "PROVIDER_BINDING_SNAPSHOT_MISSING" : null);
    }

    @Transactional
    public int recoverStaleDispatches(Duration processingTimeout) {
        OffsetDateTime now = now();
        return repository.recoverStaleProcessing(now.minus(processingTimeout), now);
    }

    @Transactional
    public DeviceCommandReceipt finishDispatch(
            DispatchEnvelope envelope, ProviderCommandOutcome outcome) {
        return complete(envelope.command(), state(outcome.state()),
                outcome.providerOperationReference(), outcome.resultCode(),
                envelope.preview(), envelope.device());
    }

    @Transactional
    public DeviceCommandReceipt receipt(long tenantId, UUID commandId) {
        requireTenant(tenantId);
        return receipt(repository.command(tenantId, commandId)
                .orElseThrow(() -> notFound("The device command receipt was not found.")));
    }

    @Transactional(readOnly = true)
    public List<ProviderObservationCandidate> providerObservationCandidates(int limit) {
        if (limit < 1 || limit > 500) throw invalid("Provider observation limit is invalid.");
        return repository.configuredProviderTruth(limit).stream()
                .map(row -> new ProviderObservationCandidate(row,
                        commandProvider.flatMap(provider -> provider.binding(
                                row.providerCode(), row.configurationVersion())).orElse(null)))
                .toList();
    }

    @Transactional
    public void applyProviderObservation(
            ProviderObservationCandidate candidate,
            ProviderRuntimeObservation observation,
            OffsetDateTime receivedAt) {
        observeProvider(new ProviderObservation(candidate.truth().tenantId(),
                candidate.truth().capability(), candidate.truth().providerCode(),
                candidate.truth().configurationVersion(), observation.state(),
                observation.evidenceReference(), observation.sourceAt(), receivedAt,
                observation.lastSuccessAt(), observation.errorCode()));
    }

    private DeviceCommandReceipt complete(
            CommandRow command,
            DeviceCommandState state,
            String providerReference,
            String resultCode,
            DeviceCommandPreview preview,
            DeviceRow device) {
        OffsetDateTime now = now();
        OffsetDateTime completedAt = state == DeviceCommandState.RESULT_UNKNOWN ? null : now;
        if (!repository.updateCommand(command.tenantId(), command.commandId(), command.version(),
                state, providerReference, resultCode, completedAt, now)) {
            return receipt(repository.command(command.tenantId(), command.commandId())
                    .orElseThrow(() -> notFound("The device command receipt was not found.")));
        }
        if (state == DeviceCommandState.SUCCEEDED
                && preview.commandType() == DeviceCommandType.SAFETY_TAKEOVER) {
            repository.activateSafetyFrame(command.tenantId(), command.actorUserId(),
                    command.deviceId(), command.commandId(), preview.payload(),
                    device.safetyOfflineFallback(), now);
            repository.audit(command.tenantId(), command.actorUserId(),
                    "navigation.device.safety.activated", "DEVICE",
                    command.deviceId(), command.correlationId(), now);
        } else if (state == DeviceCommandState.SUCCEEDED
                && preview.commandType() == DeviceCommandType.CLEAR_SAFETY) {
            repository.clearSafetyFrame(command.tenantId(), command.actorUserId(),
                    command.deviceId(), now);
            repository.audit(command.tenantId(), command.actorUserId(),
                    "navigation.device.safety.cleared", "DEVICE",
                    command.deviceId(), command.correlationId(), now);
        }
        if (state != DeviceCommandState.RESULT_UNKNOWN) {
            repository.audit(command.tenantId(), command.actorUserId(),
                    "navigation.device.command." + state.name().toLowerCase(java.util.Locale.ROOT),
                    "DEVICE_COMMAND", command.commandId(), command.correlationId(), now);
        }
        return receipt(repository.command(command.tenantId(), command.commandId())
                .orElseThrow(() -> notFound("The device command receipt was not found.")));
    }

    private RoomPanelProjection roomPanel(DeviceRow device) {
        OffsetDateTime now = now();
        List<ScheduleRow> schedule = repository.schedules(device.tenantId(), device.resourceId(),
                now.minusMinutes(1), now.plusDays(1));
        ScheduleRow current = schedule.stream()
                .filter(item -> !item.startsAt().isAfter(now) && item.endsAt().isAfter(now))
                .findFirst().orElse(null);
        ScheduleRow next = schedule.stream().filter(item -> item.startsAt().isAfter(now))
                .findFirst().orElse(null);
        boolean authoritative = connectivity(device, now) == ConnectivityState.ONLINE
                && scheduleFreshness(device, now) == FreshnessState.FRESH;
        return new RoomPanelProjection(view(device), masked(current), masked(next),
                !authoritative ? AvailabilityState.UNAVAILABLE
                        : current == null ? AvailabilityState.AVAILABLE : AvailabilityState.OCCUPIED,
                authoritative && current == null, authoritative && current != null,
                authoritative && current != null,
                repository.activeSafetyFrame(device.tenantId(), device.deviceId()).orElse(null), now);
    }

    private StatusBoardProjection statusBoard(DeviceRow device) {
        OffsetDateTime now = now();
        FreshnessState freshness = scheduleFreshness(device, now);
        boolean authoritative = connectivity(device, now) == ConnectivityState.ONLINE
                && freshness == FreshnessState.FRESH;
        List<FloorResourceStatus> resources = repository.floorResources(
                device.tenantId(), device.floorId(), now).stream()
                .map(item -> new FloorResourceStatus(item.resourceId(), item.zoneId(),
                        item.zoneNameKo(), item.zoneNameEn(), item.nameKo(), item.nameEn(),
                        !authoritative ? AvailabilityState.UNAVAILABLE
                                : item.occupied()
                                ? AvailabilityState.OCCUPIED : AvailabilityState.AVAILABLE,
                        item.directionKo(), item.directionEn())).toList();
        int occupied = (int) resources.stream()
                .filter(item -> item.availability() == AvailabilityState.OCCUPIED).count();
        int available = (int) resources.stream()
                .filter(item -> item.availability() == AvailabilityState.AVAILABLE).count();
        return new StatusBoardProjection(view(device), resources,
                available, occupied, resources.size() - available - occupied,
                repository.activeSafetyFrame(device.tenantId(), device.deviceId()).orElse(null),
                freshness, now);
    }

    private ScheduleItem masked(ScheduleRow row) {
        if (row == null) return null;
        return new ScheduleItem(row.bookingId(), row.startsAt(), row.endsAt(),
                "Reserved", null, true);
    }

    private List<String> limitations(
            long tenantId,
            DeviceRow device,
            DeviceCommandPreviewRequest request) {
        return limitations(tenantId, device, request, true);
    }

    private List<String> limitations(
            long tenantId,
            DeviceRow device,
            DeviceCommandPreviewRequest request,
            boolean requireCurrentProvider) {
        List<String> result = new ArrayList<>();
        if (device.version() != request.expectedDeviceVersion()) {
            result.add("The device version changed after the command preview.");
        }
        if (device.registrationState() != RegistrationState.BOUND) {
            result.add("The device must be approved and bound before this command.");
        }
        if (request.commandType() == DeviceCommandType.SAFETY_TAKEOVER
                && (!request.payload().containsKey("message")
                || !request.payload().containsKey("direction"))) {
            result.add("Safety takeover requires a message and direction.");
        }
        if (request.commandType() == DeviceCommandType.CLEAR_SAFETY
                && repository.activeSafetyFrame(tenantId, device.deviceId()).isEmpty()) {
            result.add("No active safety takeover exists for this device.");
        }
        if (requireCurrentProvider && request.commandType() != DeviceCommandType.UNBIND) {
            ProviderTruth mdm = truth(ProviderCapability.MDM,
                    repository.providerTruth(tenantId, ProviderCapability.MDM).orElse(null));
            if (mdm.state() != ProviderTruthState.HEALTHY) {
                result.add("The MDM provider is not verified and healthy.");
            }
            ProviderTruthRow mdmRow = repository.providerTruth(
                    tenantId, ProviderCapability.MDM).orElse(null);
            if (mdmRow == null || commandProvider.isEmpty()) {
                result.add("No approved device command adapter is installed.");
            } else {
                Optional<ProviderBinding> binding = commandProvider.get().binding(
                        mdmRow.providerCode(), mdmRow.configurationVersion());
                if (binding.isEmpty() || !commandProvider.get().ready(binding.get())) {
                    result.add("The MDM provider relay credential is unavailable.");
                }
            }
        }
        if (request.commandType() != DeviceCommandType.UNBIND
                && connectivity(device, now()) != ConnectivityState.ONLINE) {
            result.add("The device is offline; remote execution is unavailable.");
        }
        return List.copyOf(result);
    }

    private ProviderTruth truth(ProviderCapability capability, ProviderTruthRow row) {
        OffsetDateTime now = now();
        if (row == null || !row.configured()) {
            return new ProviderTruth(capability, row == null ? null : row.providerCode(),
                    ProviderTruthState.NOT_CONFIGURED, row == null ? 0 : row.configurationVersion(),
                    null, null, null, null, null, null, row == null ? 0 : row.version(), now);
        }
        ProviderTruthState state;
        if (row.reportedState() == null || row.observedConfigurationVersion() == null
                || row.evidenceReference() == null
                || row.observedConfigurationVersion() != row.configurationVersion()) {
            state = ProviderTruthState.CONFIGURED_UNVERIFIED;
        } else if (row.receivedAt() == null || row.receivedAt().isBefore(now.minus(PROVIDER_FRESHNESS))) {
            state = ProviderTruthState.STALE;
        } else if (row.reportedState() == ProviderReportedState.HEALTHY) {
            state = ProviderTruthState.HEALTHY;
        } else {
            state = ProviderTruthState.DEGRADED;
        }
        return new ProviderTruth(capability, row.providerCode(), state,
                row.configurationVersion(), row.observedConfigurationVersion(),
                row.evidenceReference(), row.sourceAt(), row.receivedAt(), row.lastSuccessAt(),
                row.errorCode(), row.version(), now);
    }

    private DeviceView view(DeviceRow row) {
        OffsetDateTime now = now();
        return new DeviceView(row.deviceId(), row.displayName(), row.deviceType(),
                row.registrationState(), row.siteId(), row.floorId(), row.resourceId(),
                row.hardwareModel(), row.osVersion(), row.appVersion(), row.policyVersion(),
                row.heartbeatAt(), connectivity(row, now), row.scheduleSourceAt(),
                row.scheduleReceivedAt(), scheduleFreshness(row, now), row.recentErrorCode(),
                row.safetyOfflineFallback(), row.version(), row.updatedAt());
    }

    private static ConnectivityState connectivity(DeviceRow row, OffsetDateTime now) {
        if (row.registrationState() == RegistrationState.PENDING
                || row.registrationState() == RegistrationState.APPROVED) {
            return ConnectivityState.UNREGISTERED;
        }
        return row.heartbeatAt() != null && !row.heartbeatAt().isBefore(now.minus(HEARTBEAT_FRESHNESS))
                ? ConnectivityState.ONLINE : ConnectivityState.OFFLINE;
    }

    private static FreshnessState scheduleFreshness(DeviceRow row, OffsetDateTime now) {
        if (row.scheduleSourceAt() == null || row.scheduleReceivedAt() == null) {
            return FreshnessState.UNKNOWN;
        }
        return row.scheduleSourceAt().isBefore(now.minus(SCHEDULE_FRESHNESS))
                || row.scheduleReceivedAt().isBefore(now.minus(SCHEDULE_FRESHNESS))
                ? FreshnessState.STALE : FreshnessState.FRESH;
    }

    private DeviceRow authenticate(long tenantId, UUID deviceId, String identity) {
        requireTenant(tenantId);
        DeviceRow row = repository.device(tenantId, deviceId)
                .orElseThrow(() -> forbidden("The device identity or tenant binding is invalid."));
        if (!matchesIdentityHash(row.identitySha256(), identity)
                || row.registrationState() == RegistrationState.SUSPENDED
                || row.registrationState() == RegistrationState.RETIRED) {
            throw forbidden("The device identity or tenant binding is invalid.");
        }
        return row;
    }

    private DeviceCommandReceipt receipt(CommandRow row) {
        return new DeviceCommandReceipt(row.commandId(), row.deviceId(), row.actorUserId(),
                row.type(), row.state(), row.reason(), row.resultCode(),
                row.providerOperationReference(), row.version(),
                row.state() == DeviceCommandState.RESULT_UNKNOWN,
                "/v1/admin/workplace/devices/commands/" + row.commandId(),
                row.correlationId(), row.acceptedAt(), row.completedAt(), row.updatedAt());
    }

    Optional<ProviderBinding> commandBinding(CommandRow command) {
        if (command.providerCode() == null || command.providerConfigurationVersion() == null
                || command.credentialReference() == null) return Optional.empty();
        return Optional.of(new ProviderBinding(command.providerCode(),
                command.providerConfigurationVersion(), command.credentialReference()));
    }

    ProviderCommand providerCommand(DispatchEnvelope envelope) {
        ProviderBinding binding = commandBinding(envelope.command()).orElseThrow();
        return new ProviderCommand(envelope.command().tenantId(), envelope.command().commandId(),
                envelope.command().deviceId(), envelope.command().type(),
                envelope.preview().payload(), envelope.command().correlationId(), binding);
    }

    private Optional<ProviderBinding> activeProviderBinding(long tenantId) {
        if (commandProvider.isEmpty()) return Optional.empty();
        ProviderTruthRow row = repository.providerTruth(
                tenantId, ProviderCapability.MDM).orElse(null);
        if (row == null || truth(ProviderCapability.MDM, row).state() != ProviderTruthState.HEALTHY) {
            return Optional.empty();
        }
        return commandProvider.get().binding(row.providerCode(), row.configurationVersion())
                .filter(commandProvider.get()::ready);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    record DispatchEnvelope(
            CommandRow command,
            DeviceCommandPreview preview,
            DeviceRow device,
            String preflightFailure) { }

    record ProviderObservationCandidate(
            ProviderTruthRow truth,
            ProviderBinding binding) { }
}
