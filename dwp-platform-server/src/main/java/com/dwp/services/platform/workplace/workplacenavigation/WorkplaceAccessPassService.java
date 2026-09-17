package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceAccessPassDtos.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

@Service
public class WorkplaceAccessPassService {
    static final Duration PASS_TTL = Duration.ofMinutes(20);
    static final Duration PREVIEW_TTL = Duration.ofMinutes(5);
    private static final char[] PAIRING_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int PAIRING_LENGTH = 12;
    private static final int PBKDF2_ITERATIONS = 120_000;

    private final WorkplaceAccessPassRepository repository;
    private final WorkplaceDeviceService deviceService;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public WorkplaceAccessPassService(
            WorkplaceAccessPassRepository repository,
            WorkplaceDeviceService deviceService) {
        this(repository, deviceService, Clock.systemUTC(), new SecureRandom());
    }

    WorkplaceAccessPassService(
            WorkplaceAccessPassRepository repository,
            WorkplaceDeviceService deviceService,
            Clock clock,
            SecureRandom random) {
        this.repository = repository;
        this.deviceService = deviceService;
        this.clock = clock;
        this.random = random;
    }

    @Transactional(readOnly = true)
    public AccessPassContext context(
            long tenantId, long actorId, UUID siteId, UUID resourceId) {
        requireActor(tenantId, actorId);
        OffsetDateTime now = now();
        List<ProviderTruth> providers = accessProviders(tenantId);
        BookingWindow booking = repository.eligibleBooking(tenantId, actorId, resourceId, now)
                .orElse(null);
        AccessPassView pass = repository.latest(tenantId, actorId, siteId, resourceId)
                .map(row -> view(row, now)).orElse(null);
        return new AccessPassContext(pass, providers, booking != null,
                booking == null ? null : booking.endsAt(), now);
    }

    @Transactional
    public AccessPassPreview preview(
            long tenantId,
            long actorId,
            String idempotencyKey,
            AccessPassPreviewRequest request) {
        requireActor(tenantId, actorId);
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(request.commandType(), request.passId(), request.siteId(),
                request.floorId(), request.resourceId(), request.destinationPoiId(),
                request.expectedPassVersion());
        AccessPassPreviewRow replay = repository.previewByKey(tenantId, actorId, key).orElse(null);
        if (replay != null) {
            if (!replay.requestFingerprint().equals(fingerprint)) {
                throw conflict("The Idempotency-Key was used for a different access-pass preview.");
            }
            return previewView(replay);
        }

        OffsetDateTime now = now();
        repository.expireActive(tenantId, actorId, request.resourceId(), now);
        List<String> impact = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        if (request.commandType() != AccessPassCommandType.REVOKE
                && !repository.destinationMatches(tenantId, request.siteId(), request.floorId(),
                request.resourceId(), request.destinationPoiId())) {
            limitations.add("DESTINATION_NOT_IN_PUBLISHED_GRAPH");
        }

        BookingWindow booking = repository.eligibleBooking(
                tenantId, actorId, request.resourceId(), now).orElse(null);
        AccessPassRow pass = request.passId() == null ? null
                : repository.pass(tenantId, actorId, request.passId()).orElse(null);
        if (request.commandType() == AccessPassCommandType.ISSUE) {
            if (request.passId() != null || request.expectedPassVersion() != 0) {
                limitations.add("INITIAL_VERSION_MUST_BE_ZERO");
            }
            AccessPassRow active = repository.latest(
                    tenantId, actorId, request.siteId(), request.resourceId()).orElse(null);
            if (active != null && view(active, now).state() == AccessPassState.ACTIVE) {
                limitations.add("ACTIVE_PASS_ALREADY_EXISTS");
            }
            impact.add("NEW_ONE_TIME_CREDENTIAL_ISSUED");
        } else {
            if (pass == null || pass.state() != AccessPassState.ACTIVE
                    || !pass.siteId().equals(request.siteId())
                    || !pass.floorId().equals(request.floorId())
                    || !pass.resourceId().equals(request.resourceId())
                    || !pass.destinationPoiId().equals(request.destinationPoiId())) {
                limitations.add("ACTIVE_PASS_NOT_FOUND");
            } else if (pass.version() != request.expectedPassVersion()) {
                limitations.add("PASS_VERSION_CHANGED");
            }
            if (request.commandType() == AccessPassCommandType.ROTATE) {
                impact.add("CURRENT_CREDENTIAL_INVALIDATED");
                impact.add("NEW_ONE_TIME_CREDENTIAL_ISSUED");
            } else {
                impact.add("CURRENT_CREDENTIAL_REVOKED_IMMEDIATELY");
            }
        }

        List<ProviderTruth> providers = accessProviders(tenantId);
        ProviderTruth nfc = provider(providers, ProviderCapability.NFC);
        ProviderTruth gate = provider(providers, ProviderCapability.SPEED_GATE);
        boolean nfcEnabled = ready(nfc);
        boolean qrEnabled = ready(gate);
        if (request.commandType() != AccessPassCommandType.REVOKE) {
            if (!nfcEnabled) limitations.add("NFC_PROVIDER_NOT_READY");
            if (!qrEnabled) limitations.add("KIOSK_PROVIDER_NOT_READY");
            if (!nfcEnabled && !qrEnabled) limitations.add("ACCESS_PROVIDER_NOT_CONFIGURED");
            if (booking == null) limitations.add("ELIGIBLE_BOOKING_NOT_FOUND");
        }
        impact.add("AUDIT_EVIDENCE_RECORDED");

        boolean eligible = limitations.stream().noneMatch(value ->
                !value.equals("NFC_PROVIDER_NOT_READY") && !value.equals("KIOSK_PROVIDER_NOT_READY"))
                && (request.commandType() == AccessPassCommandType.REVOKE || nfcEnabled || qrEnabled);
        AccessPassPreviewRow row = new AccessPassPreviewRow(
                UUID.randomUUID(), tenantId, actorId, key, fingerprint, request.commandType(),
                request.passId(), request.siteId(), request.floorId(), request.resourceId(),
                request.destinationPoiId(), booking == null ? null : booking.bookingId(),
                request.expectedPassVersion(), nfcEnabled, qrEnabled,
                nfcEnabled ? nfc.providerCode() : null,
                nfcEnabled ? nfc.configurationVersion() : null,
                nfcEnabled ? nfc.evidenceReference() : null,
                qrEnabled ? gate.providerCode() : null,
                qrEnabled ? gate.configurationVersion() : null,
                qrEnabled ? gate.evidenceReference() : null,
                eligible,
                List.copyOf(impact), List.copyOf(limitations), now.plus(PREVIEW_TTL), now);
        repository.insertPreview(row);
        return previewView(row);
    }

    @Transactional
    public AccessPassCommandResult execute(
            long tenantId,
            long actorId,
            String idempotencyKey,
            ConfirmAccessPassCommandRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        String key = requireKey(idempotencyKey);
        requireCorrelation(correlationId);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String reason = request.reason().trim();
        String fingerprint = fingerprint(request.previewId(), request.expectedPassVersion(), reason,
                request.explicitConfirmation());
        AccessPassCommandRow replay = repository.commandByKey(tenantId, actorId, key).orElse(null);
        if (replay != null) {
            if (!replay.requestFingerprint().equals(fingerprint)) {
                throw conflict("The Idempotency-Key was used for a different access-pass command.");
            }
            AccessPassRow pass = repository.pass(tenantId, actorId, replay.passId())
                    .orElseThrow(() -> notFound("The access pass no longer exists."));
            return new AccessPassCommandResult(receipt(replay), view(pass, now()), null, null, true);
        }

        OffsetDateTime now = now();
        AccessPassPreviewRow preview = repository.preview(tenantId, actorId, request.previewId())
                .orElseThrow(() -> notFound("The access-pass preview was not found."));
        if (!preview.eligible() || preview.expiresAt().isBefore(now)) {
            throw conflict("The access-pass preview is ineligible or expired; create a new preview.");
        }
        if (preview.expectedPassVersion() != request.expectedPassVersion()) {
            throw conflict("The access-pass version changed; create a new preview.");
        }
        if (preview.commandType() != AccessPassCommandType.REVOKE) {
            if (!repository.destinationMatches(tenantId, preview.siteId(), preview.floorId(),
                    preview.resourceId(), preview.destinationPoiId())) {
                throw conflict("The published destination changed; create a new preview.");
            }
            List<ProviderTruth> currentProviders = accessProviders(tenantId);
            ProviderTruth currentNfc = provider(currentProviders, ProviderCapability.NFC);
            ProviderTruth currentGate = provider(currentProviders, ProviderCapability.SPEED_GATE);
            if (!sameProviderSnapshot(preview.nfcEnabled(), preview.nfcProviderCode(),
                    preview.nfcProviderConfigurationVersion(),
                    preview.nfcProviderEvidenceReference(), currentNfc)
                    || !sameProviderSnapshot(preview.qrEnabled(), preview.qrProviderCode(),
                    preview.qrProviderConfigurationVersion(),
                    preview.qrProviderEvidenceReference(), currentGate)) {
                throw conflict("Access-provider evidence changed; create a new preview.");
            }
        }

        String rawCredential = null;
        String rawPairingCode = null;
        AccessPassRow pass;
        if (preview.commandType() == AccessPassCommandType.REVOKE) {
            if (!repository.revoke(preview, now)) {
                throw conflict("The access pass changed; refresh before revoking it.");
            }
            pass = repository.pass(tenantId, actorId, preview.passId()).orElseThrow();
        } else {
            BookingWindow booking = repository.eligibleBooking(
                    tenantId, actorId, preview.resourceId(), now)
                    .orElseThrow(() -> conflict("The eligible booking window has ended."));
            OffsetDateTime credentialExpiry = earlier(now.plus(PASS_TTL), booking.endsAt());
            rawCredential = credential();
            String credentialHash = sha256(rawCredential);
            String pairingHash = null;
            String pairingSalt = null;
            if (preview.qrEnabled()) {
                rawPairingCode = pairingCode();
                byte[] salt = new byte[16];
                random.nextBytes(salt);
                pairingSalt = HexFormat.of().formatHex(salt);
                pairingHash = pairingHash(rawPairingCode, salt);
            }
            if (preview.commandType() == AccessPassCommandType.ISSUE) {
                try {
                    pass = repository.insertPass(preview, UUID.randomUUID(), credentialHash,
                            lastFour(rawCredential), pairingHash, pairingSalt, credentialExpiry, now);
                } catch (DataIntegrityViolationException changed) {
                    throw conflict("An active access pass already exists; refresh before retrying.");
                }
            } else {
                if (!repository.rotate(preview, credentialHash, lastFour(rawCredential), pairingHash,
                        pairingSalt, credentialExpiry, now)) {
                    throw conflict("The access pass changed; refresh before rotating it.");
                }
                pass = repository.pass(tenantId, actorId, preview.passId()).orElseThrow();
            }
        }

        AccessPassCommandRow command = new AccessPassCommandRow(
                UUID.randomUUID(), tenantId, actorId, pass.passId(), preview.previewId(),
                preview.commandType(), key, fingerprint, AccessPassCommandState.SUCCEEDED,
                reason, null, correlationId, 1, now, now, now);
        repository.insertCommand(command);
        repository.audit(tenantId, actorId,
                "navigation.access-pass." + preview.commandType().name().toLowerCase(),
                pass.passId(), correlationId, preview.commandType(), pass.state(), now);
        return new AccessPassCommandResult(
                receipt(command), view(pass, now), rawCredential, rawPairingCode, false);
    }

    @Transactional(readOnly = true)
    public AccessPassCommandResult command(long tenantId, long actorId, UUID commandId) {
        requireActor(tenantId, actorId);
        AccessPassCommandRow command = repository.command(tenantId, actorId, commandId)
                .orElseThrow(() -> notFound("The access-pass command was not found."));
        AccessPassRow pass = repository.pass(tenantId, actorId, command.passId())
                .orElseThrow(() -> notFound("The access pass was not found."));
        return new AccessPassCommandResult(receipt(command), view(pass, now()), null, null, true);
    }

    @Transactional(readOnly = true)
    public List<AccessPassAuditEvent> auditEvents(
            long tenantId, long actorId, UUID passId, Integer limit) {
        requireActor(tenantId, actorId);
        int bounded = limit == null ? 20 : Math.max(1, Math.min(100, limit));
        if (passId != null && repository.pass(tenantId, actorId, passId).isEmpty()) {
            throw notFound("The access pass was not found.");
        }
        return repository.auditEvents(tenantId, actorId, passId, bounded);
    }

    @Transactional
    public AccessPassPairingReceipt pair(
            long tenantId,
            UUID deviceId,
            String deviceIdentity,
            String idempotencyKey,
            String correlationId,
            AccessPassPairingRequest request) {
        requireTenant(tenantId);
        String key = requireKey(idempotencyKey);
        requireCorrelation(correlationId);
        String code = request.pairingCode() == null ? "" : request.pairingCode().trim();
        if (!code.matches("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$")) {
            throw forbidden("The access-pass pairing request could not be verified.");
        }
        String requestFingerprint = fingerprint(deviceId, request.passId());
        DeviceProjection projection = deviceService.projection(tenantId, deviceId, deviceIdentity);
        DeviceView device = projection.roomPanel() == null
                ? projection.statusBoard().device() : projection.roomPanel().device();
        AccessPassPairingReceiptRow replay = repository.pairingReceiptByKey(
                tenantId, deviceId, key).orElse(null);
        if (replay != null) {
            if (!replay.requestFingerprint().equals(requestFingerprint)
                    || !replay.passId().equals(request.passId())) {
                throw conflict("The Idempotency-Key was used for a different pairing request.");
            }
            return pairingReceipt(replay, true);
        }

        OffsetDateTime now = now();
        AccessPassRow pass = repository.passForPairing(tenantId, request.passId())
                .orElseThrow(() -> forbidden(
                        "The access-pass pairing request could not be verified."));
        boolean sameScope = pass.siteId().equals(device.siteId())
                && pass.floorId().equals(device.floorId());
        boolean pairable = pass.state() == AccessPassState.ACTIVE
                && pass.expiresAt().isAfter(now)
                && pass.qrEnabled()
                && pass.pairingCodeHash() != null
                && pass.pairingCodeSalt() != null
                && pass.pairingAttemptCount() < 5
                && pass.pairingLockedAt() == null
                && pass.pairingConsumedAt() == null;
        if (!sameScope || !pairable) {
            throw forbidden("The access-pass pairing request could not be verified.");
        }
        String candidateHash;
        try {
            candidateHash = pairingHash(code, HexFormat.of().parseHex(pass.pairingCodeSalt()));
        } catch (RuntimeException invalidEvidence) {
            throw new IllegalStateException("Persisted pairing evidence is invalid.", invalidEvidence);
        }
        if (!MessageDigest.isEqual(
                HexFormat.of().parseHex(pass.pairingCodeHash()),
                HexFormat.of().parseHex(candidateHash))) {
            repository.recordPairingFailure(tenantId, pass.passId(), pass.version(), now);
            throw forbidden("The access-pass pairing request could not be verified.");
        }
        if (!repository.consumePairing(tenantId, pass.passId(), deviceId, pass.version(),
                pass.pairingCodeHash(), now)) {
            throw conflict("The access pass changed; restart pairing with a current pass.");
        }
        AccessPassRow paired = repository.passForPairing(tenantId, pass.passId()).orElseThrow();
        AccessPassPairingReceiptRow receipt = new AccessPassPairingReceiptRow(
                UUID.randomUUID(), tenantId, paired.passId(), deviceId, key, correlationId,
                requestFingerprint,
                paired.siteId(), paired.floorId(), paired.resourceId(), paired.version(),
                paired.expiresAt(), now);
        repository.insertPairingReceipt(receipt);
        repository.auditPairing(tenantId, paired.ownerUserId(), paired.passId(), deviceId,
                receipt.pairingReceiptId(), correlationId, now);
        return pairingReceipt(receipt, false);
    }

    private List<ProviderTruth> accessProviders(long tenantId) {
        return deviceService.providerTruth(tenantId).stream()
                .filter(value -> value.capability() == ProviderCapability.NFC
                        || value.capability() == ProviderCapability.SPEED_GATE)
                .toList();
    }

    private static ProviderTruth provider(
            List<ProviderTruth> values, ProviderCapability capability) {
        return values.stream().filter(value -> value.capability() == capability)
                .findFirst().orElse(null);
    }

    private static boolean ready(ProviderTruth value) {
        return value != null && value.state() == ProviderTruthState.HEALTHY
                && value.providerCode() != null && value.configurationVersion() > 0
                && value.evidenceReference() != null;
    }

    private static boolean sameProviderSnapshot(
            boolean enabled,
            String providerCode,
            Long configurationVersion,
            String evidenceReference,
            ProviderTruth current) {
        if (!enabled) return true;
        return ready(current)
                && Objects.equals(providerCode, current.providerCode())
                && Objects.equals(configurationVersion, current.configurationVersion())
                && Objects.equals(evidenceReference, current.evidenceReference());
    }

    private AccessPassView view(AccessPassRow row, OffsetDateTime now) {
        AccessPassState state = row.state() == AccessPassState.ACTIVE
                && !row.expiresAt().isAfter(now) ? AccessPassState.EXPIRED : row.state();
        return new AccessPassView(row.passId(), row.sourceBookingId(), row.siteId(), row.floorId(),
                row.resourceId(), row.destinationPoiId(), state, row.credentialLastFour(),
                row.pairingCodeHash() != null, row.nfcEnabled(), row.qrEnabled(),
                row.nfcProviderCode(), row.nfcProviderConfigurationVersion(),
                row.nfcProviderEvidenceReference(), row.qrProviderCode(),
                row.qrProviderConfigurationVersion(), row.qrProviderEvidenceReference(),
                row.version(), row.issuedAt(), row.expiresAt(),
                row.revokedAt(), row.updatedAt());
    }

    private static AccessPassPreview previewView(AccessPassPreviewRow row) {
        return new AccessPassPreview(row.previewId(), row.commandType(), row.passId(), row.siteId(),
                row.floorId(), row.resourceId(), row.destinationPoiId(), row.expectedPassVersion(),
                row.nfcEnabled(), row.qrEnabled(), row.eligible(), row.impact(), row.limitations(),
                row.expiresAt(), row.createdAt());
    }

    private static AccessPassCommandReceipt receipt(AccessPassCommandRow row) {
        return new AccessPassCommandReceipt(row.commandId(), row.passId(), row.commandType(),
                row.state(), row.reason(), row.resultCode(), row.version(),
                row.state() == AccessPassCommandState.RESULT_UNKNOWN,
                "/v1/workplace/navigation/access-pass/commands/" + row.commandId(),
                row.correlationId(), row.acceptedAt(), row.completedAt(), row.updatedAt());
    }

    private static AccessPassPairingReceipt pairingReceipt(
            AccessPassPairingReceiptRow row, boolean replayed) {
        return new AccessPassPairingReceipt(
                row.pairingReceiptId(), row.passId(), row.deviceId(), row.siteId(), row.floorId(),
                row.resourceId(), row.passVersion(), row.correlationId(), row.expiresAt(),
                row.pairedAt(), replayed);
    }

    private String credential() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return "DWP1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String pairingCode() {
        char[] value = new char[PAIRING_LENGTH];
        for (int index = 0; index < value.length; index++) {
            value[index] = PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)];
        }
        return new String(value);
    }

    private static String pairingHash(String rawCode, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(rawCode.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        try {
            return HexFormat.of().formatHex(
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
                            .getEncoded());
        } catch (Exception exception) {
            throw new IllegalStateException("Could not derive the pairing-code verifier.", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String fingerprint(Object... values) {
        StringBuilder builder = new StringBuilder();
        for (Object value : values) builder.append(value).append('\u0000');
        return sha256(builder.toString());
    }

    private static String lastFour(String value) {
        return value.substring(value.length() - 4);
    }

    private static OffsetDateTime earlier(OffsetDateTime left, OffsetDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private static String requireKey(String value) {
        if (value == null || !value.matches("^[!-~]{1,160}$")) {
            throw invalid("A bounded visible-ASCII Idempotency-Key is required.");
        }
        return value;
    }

    private static void requireCorrelation(String value) {
        if (value != null && !value.matches("^[!-~]{1,160}$")) {
            throw invalid("X-Correlation-ID must be bounded visible ASCII.");
        }
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw new BaseException(
                ErrorCode.UNAUTHORIZED, "A positive actor identifier is required.");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant identifier is required.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }
}
