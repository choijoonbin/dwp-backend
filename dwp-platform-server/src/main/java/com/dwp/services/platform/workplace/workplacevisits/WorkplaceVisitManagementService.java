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
import java.util.function.Function;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.*;

@Service
public class WorkplaceVisitManagementService {
    private final WorkplaceVisitRepository repository;
    private final Clock clock;

    @Autowired
    public WorkplaceVisitManagementService(WorkplaceVisitRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceVisitManagementService(WorkplaceVisitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<VisitPolicy> policies(long tenantId) {
        return repository.policies(tenantId).stream().map(this::policy).toList();
    }

    @Transactional
    public ManagementResult<VisitPolicy> createPolicy(
            long tenantId, long actorId, String key, VisitPolicyRequest request,
            String correlationId) {
        requireCreateVersion(request.expectedVersion());
        return manage(tenantId, actorId, key, "POLICY_CREATE", "POLICY", request,
                request.explicitConfirmation(), correlationId,
                id -> policy(repository.policyById(tenantId, id).orElseThrow(
                        WorkplaceVisitManagementService::notFound)), () -> {
                    UUID id = repository.createPolicy(tenantId, request, now());
                    return new ResourceVersion(id, 1);
                });
    }

    @Transactional
    public ManagementResult<VisitPolicy> updatePolicy(
            long tenantId, long actorId, UUID id, String key, VisitPolicyRequest request,
            String correlationId) {
        return manage(tenantId, actorId, key, "POLICY_UPDATE:" + id, "POLICY", request,
                request.explicitConfirmation(), correlationId,
                resourceId -> policy(repository.policyById(tenantId, resourceId).orElseThrow(
                        WorkplaceVisitManagementService::notFound)), () -> {
                    requireChanged(repository.updatePolicy(tenantId, id, request, now()));
                    return new ResourceVersion(id, request.expectedVersion() + 1);
                });
    }

    @Transactional(readOnly = true)
    public PolicyImpactPreview policyImpact(long tenantId, UUID id) {
        PolicyRow row = repository.policyById(tenantId, id)
                .orElseThrow(WorkplaceVisitManagementService::notFound);
        long count = repository.policyImpact(tenantId, id);
        return new PolicyImpactPreview(id, row.version(), count,
                count == 0 ? List.of() : List.of("FUTURE_VISITS_REQUIRE_REVIEW"), now());
    }

    @Transactional(readOnly = true)
    public List<AccessZone> zones(long tenantId) {
        return repository.accessZones(tenantId).stream().map(this::zone).toList();
    }

    @Transactional
    public ManagementResult<AccessZone> createZone(
            long tenantId, long actorId, String key, AccessZoneRequest request,
            String correlationId) {
        requireCreateVersion(request.expectedVersion());
        return manage(tenantId, actorId, key, "ZONE_CREATE", "ZONE", request,
                request.explicitConfirmation(), correlationId,
                id -> zone(repository.zone(tenantId, id).orElseThrow(
                        WorkplaceVisitManagementService::notFound)), () -> {
                    UUID id = repository.createZone(tenantId, request, now());
                    return new ResourceVersion(id, 1);
                });
    }

    @Transactional
    public ManagementResult<AccessZone> updateZone(
            long tenantId, long actorId, UUID id, String key, AccessZoneRequest request,
            String correlationId) {
        return manage(tenantId, actorId, key, "ZONE_UPDATE:" + id, "ZONE", request,
                request.explicitConfirmation(), correlationId,
                resourceId -> zone(repository.zone(tenantId, resourceId).orElseThrow(
                        WorkplaceVisitManagementService::notFound)), () -> {
                    requireChanged(repository.updateZone(tenantId, id, request, now()));
                    return new ResourceVersion(id, request.expectedVersion() + 1);
                });
    }

    @Transactional(readOnly = true)
    public List<ProviderBinding> providers(long tenantId) {
        OffsetDateTime now = now();
        return repository.providers(tenantId).stream().map(row -> provider(row, now)).toList();
    }

    @Transactional
    public ManagementResult<ProviderBinding> createProvider(
            long tenantId, long actorId, String key, ProviderBindingRequest request,
            String correlationId) {
        requireCreateVersion(request.expectedVersion());
        return manage(tenantId, actorId, key, "PROVIDER_CREATE", "PROVIDER", request,
                request.explicitConfirmation(), correlationId,
                id -> provider(repository.providerById(tenantId, id).orElseThrow(
                        WorkplaceVisitManagementService::notFound), now()), () -> {
                    UUID id = repository.createProvider(tenantId, request, now());
                    return new ResourceVersion(id, 1);
                });
    }

    @Transactional
    public ManagementResult<ProviderBinding> updateProvider(
            long tenantId, long actorId, UUID id, String key, ProviderBindingRequest request,
            String correlationId) {
        return manage(tenantId, actorId, key, "PROVIDER_UPDATE:" + id, "PROVIDER", request,
                request.explicitConfirmation(), correlationId,
                resourceId -> provider(repository.providerById(tenantId, resourceId).orElseThrow(
                        WorkplaceVisitManagementService::notFound), now()), () -> {
                    requireChanged(repository.updateProvider(tenantId, id, request, now()));
                    return new ResourceVersion(id, request.expectedVersion() + 1);
                });
    }

    @Transactional
    public ManagementResult<ProviderBinding> recordProviderEvidence(
            long tenantId, long actorId, UUID id, String key, ProviderEvidenceRequest request,
            String correlationId) {
        if (request.reportedState() == ProviderTruthState.NOT_CONFIGURED) {
            throw invalid("NOT_CONFIGURED is derived from an absent or inactive binding.");
        }
        return manage(tenantId, actorId, key, "PROVIDER_TEST:" + id, "PROVIDER", request,
                request.explicitConfirmation(), correlationId,
                resourceId -> provider(repository.providerById(tenantId, resourceId).orElseThrow(
                        WorkplaceVisitManagementService::notFound), now()), () -> {
                    requireChanged(repository.recordProviderEvidence(tenantId, id, request, now()));
                    return new ResourceVersion(id, request.expectedVersion() + 1);
                });
    }

    @Transactional(readOnly = true)
    public List<KioskDevice> devices(long tenantId) {
        OffsetDateTime now = now();
        return repository.devices(tenantId).stream().map(row -> device(tenantId, row, now)).toList();
    }

    @Transactional
    public ManagementResult<KioskDevice> createDevice(
            long tenantId, long actorId, String key, KioskDeviceRequest request,
            String correlationId) {
        requireCreateVersion(request.expectedVersion());
        return manage(tenantId, actorId, key, "DEVICE_CREATE", "DEVICE", request,
                request.explicitConfirmation(), correlationId,
                id -> device(tenantId, repository.device(tenantId, id).orElseThrow(
                        WorkplaceVisitManagementService::notFound), now()), () -> {
                    UUID id = repository.createDevice(tenantId, request, now());
                    return new ResourceVersion(id, 1);
                });
    }

    @Transactional
    public ManagementResult<KioskDevice> updateDevice(
            long tenantId, long actorId, UUID id, String key, KioskDeviceRequest request,
            String correlationId) {
        return manage(tenantId, actorId, key, "DEVICE_UPDATE:" + id, "DEVICE", request,
                request.explicitConfirmation(), correlationId,
                resourceId -> device(tenantId, repository.device(tenantId, resourceId).orElseThrow(
                        WorkplaceVisitManagementService::notFound), now()), () -> {
                    requireChanged(repository.updateDevice(tenantId, id, request, now()));
                    return new ResourceVersion(id, request.expectedVersion() + 1);
                });
    }

    KioskDevice device(long tenantId, DeviceRow row, OffsetDateTime now) {
        return new KioskDevice(row.id(), row.siteId(), row.policyId(), row.privacyNoticeVersion(),
                row.privacyNoticeAccepted(), row.lastHeartbeatAt(), row.helpRequested(),
                kioskState(tenantId, row, now), row.active(), row.version(), row.updatedAt());
    }

    KioskState kioskState(long tenantId, DeviceRow row, OffsetDateTime now) {
        if (!row.active()) return KioskState.RETIRED;
        if (row.helpRequested()) return KioskState.HELP_REQUESTED;
        if (row.lastHeartbeatAt() == null || row.lastHeartbeatAt().isBefore(now.minusMinutes(2))) {
            return KioskState.OFFLINE;
        }
        if (!row.privacyNoticeAccepted()) return KioskState.PRIVACY_NOTICE_REQUIRED;
        ProviderTruth truth = repository.provider(tenantId, ProviderKind.ACCESS)
                .map(provider -> WorkplaceVisitProviderTruth.evaluate(provider, now))
                .orElseGet(() -> WorkplaceVisitProviderTruth.missing(ProviderKind.ACCESS));
        return truth.state() == ProviderTruthState.READY
                ? KioskState.READY : KioskState.PROVIDER_UNAVAILABLE;
    }

    DeviceRow requireDevice(long tenantId, UUID deviceId, String credential) {
        DeviceRow row = repository.device(tenantId, deviceId)
                .orElseThrow(WorkplaceVisitManagementService::notFound);
        if (!identityMatches(row.identitySha256(), credential)) throw notFound();
        return row;
    }

    static String identityHash(String credential) {
        if (!com.dwp.services.platform.security.PlatformDeviceIdentity
                .validCredential(credential)) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A valid kiosk device identity is required.");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static boolean identityMatches(String expectedHash, String credential) {
        return expectedHash != null && MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                identityHash(credential).getBytes(StandardCharsets.US_ASCII));
    }

    private <T> ManagementResult<T> manage(
            long tenantId, long actorId, String key, String scope, String type, Object request,
            boolean confirmed, String correlationId, Function<UUID, T> loader,
            ResourceMutation mutation) {
        requireKey(key);
        if (!confirmed) throw invalid("Explicit confirmation is required.");
        String fp = fingerprint(scope + "\u001f" + request);
        repository.lockCommand(tenantId, actorId, scope, key);
        ManagementCommandRow replay = repository.managementCommand(
                tenantId, actorId, scope, key).orElse(null);
        if (replay != null) {
            if (!replay.fingerprint().equals(fp)) throw conflict(
                    "The Idempotency-Key was already used for a different command.");
            return new ManagementResult<>(loader.apply(replay.resourceId()), receipt(replay, true));
        }
        ResourceVersion resource = mutation.apply();
        ManagementCommandRow command = repository.insertManagementCommand(
                tenantId, actorId, scope, key, fp, type, resource.id(), resource.version(),
                correlationId, now());
        repository.audit(tenantId, null, actorId, "workplace.visit.management."
                        + scope.toLowerCase(), correlationId,
                Map.of("resourceType", type, "resourceId", resource.id()), now());
        return new ManagementResult<>(loader.apply(resource.id()), receipt(command, false));
    }

    private VisitPolicy policy(PolicyRow row) {
        return new VisitPolicy(row.id(), row.visitType(), row.approvalRequired(), row.ndaRequired(),
                row.identityVerificationRequired(), row.allowedFrom(), row.allowedUntil(),
                row.minimumCollectionFields(), row.retentionDays(), row.active(), row.version(),
                row.updatedAt());
    }

    private AccessZone zone(ZoneRow row) {
        return new AccessZone(row.id(), row.siteId(), row.code(), row.name(), row.accessLevel(),
                row.providerMappingReference(), row.allowedVisitTypes(), row.active(), row.version(),
                row.updatedAt());
    }

    private ProviderBinding provider(ProviderRow row, OffsetDateTime now) {
        ProviderTruth truth = WorkplaceVisitProviderTruth.evaluate(row, now);
        return new ProviderBinding(row.id(), row.kind(), row.providerCode(),
                row.configurationVersion(), row.observedConfigurationVersion(), truth.state(),
                row.evidenceReference(), row.lastSuccessAt(), row.sourceAt(), row.receivedAt(),
                row.manualOwner(), row.manualProcedure(), row.active(), row.version(), row.updatedAt());
    }

    private static ManagementReceipt receipt(ManagementCommandRow row, boolean replayed) {
        return new ManagementReceipt(row.commandId(), row.resourceType(), row.resourceId(),
                row.resourceVersion(), replayed, row.correlationId(), row.createdAt());
    }

    private static void requireCreateVersion(long version) {
        if (version != 0) throw invalid("Create commands require expectedVersion 0.");
    }

    private static void requireChanged(boolean changed) {
        if (!changed) throw conflict("The resource state or version changed. Refresh before retrying.");
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[!-~]{1,160}")) {
            throw invalid("An opaque Idempotency-Key of 1-160 ASCII characters is required.");
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
        return new BaseException(ErrorCode.NOT_FOUND, "The visit management resource was not found.");
    }
    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private record ResourceVersion(UUID id, long version) { }
    @FunctionalInterface private interface ResourceMutation { ResourceVersion apply(); }
}
