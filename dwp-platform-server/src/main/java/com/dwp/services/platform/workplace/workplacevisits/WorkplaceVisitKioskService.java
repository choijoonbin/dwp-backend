package com.dwp.services.platform.workplace.workplacevisits;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.*;

@Service
public class WorkplaceVisitKioskService {
    private final WorkplaceVisitRepository repository;
    private final WorkplaceVisitManagementService management;
    private final Clock clock;

    @Autowired
    public WorkplaceVisitKioskService(
            WorkplaceVisitRepository repository, WorkplaceVisitManagementService management) {
        this(repository, management, Clock.systemUTC());
    }

    WorkplaceVisitKioskService(WorkplaceVisitRepository repository,
                               WorkplaceVisitManagementService management, Clock clock) {
        this.repository = repository;
        this.management = management;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public KioskDevice session(long tenantId, String credential) {
        String governedIdentity = WorkplaceVisitManagementService.identityHash(credential);
        return repository.deviceByIdentity(tenantId, governedIdentity)
                .filter(row -> WorkplaceVisitManagementService.identityMatches(
                        row.identitySha256(), credential))
                .map(row -> management.device(tenantId, row, now()))
                .orElse(new KioskDevice(null, null, null, null, false, null, false,
                        KioskState.UNREGISTERED, false, 0, now()));
    }

    @Transactional(readOnly = true)
    public KioskVisit visit(long tenantId, String identityHash, UUID visitId) {
        DeviceRow device = device(tenantId, identityHash);
        requireReady(tenantId, device);
        VisitRow visit = repository.adminVisit(tenantId, visitId)
                .orElseThrow(WorkplaceVisitKioskService::notFound);
        if (!visit.siteId().equals(device.siteId())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "KIOSK_WRONG_SITE");
        }
        if (visit.state() != VisitState.READY && visit.state() != VisitState.ARRIVED) {
            throw notFound();
        }
        GuestRow guest = repository.guests(tenantId, visitId).stream().findFirst()
                .orElseThrow(WorkplaceVisitKioskService::notFound);
        return new KioskVisit(visitId, guest.maskedLabel(), guest.purpose(), visit.siteId(),
                visit.startsAt(), visit.endsAt(), visit.state(), visit.version());
    }

    @Transactional
    public VisitCommandResult arrive(long tenantId, String identityHash, UUID visitId,
                                     String key, VersionCommand request, String correlationId) {
        return visitMutation(tenantId, identityHash, visitId, key, "KIOSK_ARRIVE", request,
                correlationId, (row, now) -> {
                    requireReservationCurrent(tenantId, row);
                    requireChanged(repository.transition(tenantId, visitId, row.version(),
                            List.of(VisitState.READY), VisitState.ARRIVED, null, null, now));
                    repository.timeline(tenantId, visitId, 0, "VISITOR_ARRIVED",
                            VisitState.ARRIVED, null, now);
                    return CommandState.SUCCEEDED;
                });
    }

    @Transactional
    public VisitCommandResult checkout(long tenantId, String identityHash, UUID visitId,
                                       String key, VersionCommand request, String correlationId) {
        return visitMutation(tenantId, identityHash, visitId, key, "KIOSK_CHECKOUT", request,
                correlationId, (row, now) -> {
                    requireChanged(repository.transition(tenantId, visitId, row.version(),
                            List.of(VisitState.ARRIVED, VisitState.OVERSTAY),
                            VisitState.CHECKED_OUT, null, null, now));
                    repository.timeline(tenantId, visitId, 0, "VISITOR_CHECKED_OUT",
                            VisitState.CHECKED_OUT, null, now);
                    repository.outbox(tenantId, visitId, "REVOKE_ACCESS",
                            visitId + ":revoke:checkout:" + row.version(), null, "PENDING", now);
                    return CommandState.SUCCEEDED;
                });
    }

    @Transactional
    public KioskDevice heartbeat(long tenantId, String identityHash, UUID deviceId,
                                 String key, KioskHeartbeatRequest request,
                                 String correlationId) {
        return deviceMutation(tenantId, identityHash, deviceId, key, "KIOSK_HEARTBEAT",
                request.expectedVersion(), request.toString(), correlationId,
                () -> repository.heartbeat(tenantId, deviceId, request, now()));
    }

    @Transactional
    public KioskDevice requestHelp(long tenantId, String identityHash, UUID deviceId,
                                   String key, VersionCommand request, String correlationId) {
        requireConfirmation(request.explicitConfirmation());
        return deviceMutation(tenantId, identityHash, deviceId, key, "KIOSK_HELP",
                request.expectedVersion(), request.reason(), correlationId,
                () -> repository.requestHelp(tenantId, deviceId, request.expectedVersion(), now()));
    }

    private VisitCommandResult visitMutation(
            long tenantId, String identityHash, UUID visitId, String key, String operation,
            VersionCommand request, String correlationId, Mutation mutation) {
        requireKey(key);
        requireConfirmation(request.explicitConfirmation());
        DeviceRow device = device(tenantId, identityHash);
        requireReady(tenantId, device);
        String scope = operation + ":" + device.id();
        String fp = fingerprint(operation + "\u001f" + visitId + "\u001f"
                + request.expectedVersion() + "\u001f" + request.reason());
        repository.lockCommand(tenantId, 0, scope, key);
        CommandRow replay = repository.command(tenantId, 0, scope, key).orElse(null);
        if (replay != null) {
            if (!replay.fingerprint().equals(fp)) throw conflict(
                    "The Idempotency-Key was already used for a different command.");
            return result(tenantId, replay, true);
        }
        VisitRow row = repository.adminVisit(tenantId, visitId)
                .filter(value -> value.siteId().equals(device.siteId()))
                .orElseThrow(WorkplaceVisitKioskService::notFound);
        if (row.version() != request.expectedVersion()) throw conflict(
                "The visit version changed. Refresh before retrying.");
        CommandState state = mutation.apply(row, now());
        CommandRow receipt = repository.insertCommand(tenantId, 0, visitId, scope, key, fp,
                state, correlationId, now());
        repository.audit(tenantId, visitId, 0, "workplace.visit." + operation.toLowerCase(),
                correlationId, Map.of("deviceId", device.id(), "result", state.name()), now());
        return result(tenantId, receipt, false);
    }

    private KioskDevice deviceMutation(
            long tenantId, String identityHash, UUID deviceId, String key, String operation,
            long expectedVersion, String body, String correlationId, DeviceMutation mutation) {
        requireKey(key);
        DeviceRow device = management.requireDevice(tenantId, deviceId, identityHash);
        String scope = operation + ":" + deviceId;
        String fp = fingerprint(operation + "\u001f" + deviceId + "\u001f"
                + expectedVersion + "\u001f" + body);
        repository.lockCommand(tenantId, 0, scope, key);
        ManagementCommandRow replay = repository.managementCommand(tenantId, 0, scope, key)
                .orElse(null);
        if (replay != null) {
            if (!replay.fingerprint().equals(fp)) throw conflict(
                    "The Idempotency-Key was already used for a different command.");
            return management.device(tenantId, repository.device(tenantId, deviceId)
                    .orElseThrow(WorkplaceVisitKioskService::notFound), now());
        }
        requireChanged(mutation.apply());
        repository.insertManagementCommand(tenantId, 0, scope, key, fp, "DEVICE", deviceId,
                expectedVersion + 1, correlationId, now());
        repository.audit(tenantId, null, 0, "workplace.visit." + operation.toLowerCase(),
                correlationId, Map.of("deviceId", deviceId), now());
        return management.device(tenantId, repository.device(tenantId, deviceId)
                .orElseThrow(WorkplaceVisitKioskService::notFound), now());
    }

    private DeviceRow device(long tenantId, String credential) {
        return repository.deviceByIdentity(tenantId,
                        WorkplaceVisitManagementService.identityHash(credential))
                .filter(row -> WorkplaceVisitManagementService.identityMatches(
                        row.identitySha256(), credential))
                .orElseThrow(WorkplaceVisitKioskService::notFound);
    }

    private void requireReady(long tenantId, DeviceRow device) {
        if (management.kioskState(tenantId, device, now()) != KioskState.READY) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The kiosk device is not ready for visitor processing.");
        }
    }

    private void requireReservationCurrent(long tenantId, VisitRow row) {
        ReservationSourceState source = repository.reservationSource(
                tenantId, row.authority(), row.reservationId())
                .orElseThrow(WorkplaceVisitKioskService::notFound);
        if (!source.active() || source.version() != row.reservationVersion()) {
            throw conflict("The reservation changed before arrival.");
        }
    }

    private VisitCommandResult result(long tenantId, CommandRow receipt, boolean replayed) {
        VisitRow row = repository.adminVisit(tenantId, receipt.visitId())
                .orElseThrow(WorkplaceVisitKioskService::notFound);
        List<GuestRefView> guests = repository.guests(tenantId, row.visitId()).stream()
                .map(g -> new GuestRefView(null, g.maskedLabel(), g.purpose(), g.retention())).toList();
        RequesterVisit projection = new RequesterVisit(row.visitId(),
                new ReservationReference(row.authority(), row.reservationId(), row.reservationVersion()),
                row.visitType(), row.siteId(), row.startsAt(), row.endsAt(),
                repository.zones(tenantId, row.visitId()), guests, row.state(), row.version(),
                row.state() == VisitState.RESULT_UNKNOWN, "/v1/workplace/visits/" + row.visitId(),
                repository.timeline(tenantId, row.visitId()), row.updatedAt());
        return new VisitCommandResult(projection, new CommandReceipt(receipt.commandId(),
                receipt.visitId(), receipt.state(), receipt.statusHref(), replayed,
                receipt.correlationId(), receipt.createdAt()));
    }

    private static void requireChanged(boolean changed) {
        if (!changed) throw conflict("The resource state or version changed. Refresh before retrying.");
    }
    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Explicit confirmation is required.");
    }
    private static void requireKey(String key) {
        if (key == null || !key.matches("[!-~]{1,160}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "An opaque Idempotency-Key of 1-160 ASCII characters is required.");
        }
    }
    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private static BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "The kiosk or visit was not found.");
    }
    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    @FunctionalInterface private interface Mutation {
        CommandState apply(VisitRow row, OffsetDateTime now);
    }
    @FunctionalInterface private interface DeviceMutation { boolean apply(); }
}
