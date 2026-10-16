package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferAuthorityPorts.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.*;

@Service
final class ControlledTransferService {

    static final String PURPOSE = "HRIS_INTEGRATION_ADMIN";
    static final String READ_ROUTE =
            "route.hcm.management.controlled-transfer.data";
    static final String CREATE_ROUTE =
            "route.hcm.management.controlled-transfer-create.action";
    static final Set<String> READ_FIELD_GROUPS = Set.of(
            "TRANSFER_METADATA", "REDACTED_PAYLOAD", "COMMAND_RECEIPT");
    static final Set<String> CREATE_FIELD_GROUPS = Set.of(
            "TRANSFER_REQUEST", "OBJECT_REFERENCE", "MALWARE_SCAN_RECEIPT",
            "COMMAND_RECEIPT");
    private static final Set<String> POPULATION_KINDS = Set.of(
            "CONNECTOR_SET", "LEGAL_ENTITY");
    private static final Set<String> CATALOG_FIELDS = Set.of(
            "publicId", "key", "type", "state", "version", "updatedAt");
    private static final Set<String> MEDIA_TYPES = Set.of(
            "text/csv", "application/json", "application/pdf",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final Set<String> CURSOR_PROPERTIES = Set.of(
            "version", "tenantId", "subjectPrincipalPublicId", "routeContractKey",
            "contextKey", "contextScopeKey", "populationScopeDigest",
            "populationKind", "fieldPolicyRevision", "authorizationRevision",
            "decisionRevision", "authorityDigest", "authorityFreshUntil", "asOf",
            "filter", "fields", "freshness", "updatedAt", "publicId", "issuedAt",
            "expiresAt");
    private static final Duration MAX_CURSOR_TTL = Duration.ofMinutes(15);
    private static final long MAX_TRANSFER_BYTES = 5_368_709_120L;

    private final ControlledTransferRepository repository;
    private final TrustedObjectMetadataAuthority metadataAuthority;
    private final SignedMalwareScanAuthority scanAuthority;
    private final EgressPolicyAuthority egressAuthority;
    private final ObjectMapper json;
    private final Clock clock;
    private final byte[] cursorSecret;
    private final Duration cursorTtl;

    @Autowired
    ControlledTransferService(
            ControlledTransferRepository repository,
            TrustedObjectMetadataAuthority metadataAuthority,
            SignedMalwareScanAuthority scanAuthority,
            EgressPolicyAuthority egressAuthority,
            ObjectMapper json,
            @Value("${dwp.hris.controlled-transfer.cursor-secret:}")
            String cursorSecret,
            @Value("${dwp.hris.controlled-transfer.cursor-ttl:PT5M}")
            Duration cursorTtl) {
        this(repository, metadataAuthority, scanAuthority, egressAuthority, json,
                Clock.systemUTC(), cursorSecret, cursorTtl);
    }

    ControlledTransferService(
            ControlledTransferRepository repository,
            TrustedObjectMetadataAuthority metadataAuthority,
            SignedMalwareScanAuthority scanAuthority,
            EgressPolicyAuthority egressAuthority,
            ObjectMapper json,
            Clock clock,
            String cursorSecret,
            Duration cursorTtl) {
        this.repository = repository;
        this.metadataAuthority = metadataAuthority;
        this.scanAuthority = scanAuthority;
        this.egressAuthority = egressAuthority;
        this.json = json;
        this.clock = clock;
        this.cursorSecret = canonicalSecret(cursorSecret);
        this.cursorTtl = cursorTtl;
    }

    Receipt create(AccessSeal access, FileTransferCommand command) {
        requireAccess(access, true);
        requireCommand(command);
        String requestDigest = commandSnapshotDigest(command);

        // The canonical owner key is checked before any authority network call.
        var replay = repository.findReplay(new ReplayProbe(
                access, command.ownerModule(), requestDigest));
        if (replay.isPresent()) return replay.get();

        OffsetDateTime beforeAuthorities = now();
        requireFuture(command.expiresAt(), beforeAuthorities,
                "Transfer expiry must be in the future");
        requireFuture(access.authorityFreshUntil(), beforeAuthorities,
                "Controlled-transfer route authority is stale");

        ToctouBinding binding = command.toctouBinding();
        MalwareScanSnapshot scan = command.malwareScanSnapshot();
        TrustedObjectMetadata trusted = metadataAuthority.resolve(
                new ObjectMetadataRequest(
                        access.tenantId(), access.subjectPrincipalPublicId(),
                        command.objectRef(), command.objectVersion(),
                        binding.uploadSessionId(), binding.storageETag()));
        requireTrustedMetadata(access, command, binding, trusted, beforeAuthorities);

        VerifiedCleanScan verified = scanAuthority.verifySignedCleanSnapshot(
                new SignedScanRequest(access.tenantId(), scan.scannerKey(),
                        scan.scannerVersion(), scan.scannedAt(), scan.objectDigest(),
                        scan.byteSize()));
        requireCleanScan(access, command, scan, trusted, verified, beforeAuthorities);

        VerifiedEgressPolicy egress = null;
        if (command.transferKind() != TransferKind.INGEST) {
            egress = egressAuthority.authorize(new EgressPolicyRequest(
                    access.tenantId(), access.subjectPrincipalPublicId(),
                    command.recipientPolicyRef(), command.egressPolicyRevision(),
                    command.transferKind(), command.classification(), PURPOSE,
                    command.objectRef(), trusted.objectVersion(), trusted.objectDigest(),
                    trusted.byteSize(), trusted.mediaType()));
            requireEgress(access, command, trusted, egress, beforeAuthorities);
        }

        OffsetDateTime acceptedAt = now();
        requireFuture(command.expiresAt(), acceptedAt,
                "Transfer expired while authority was resolved");
        requireFuture(access.authorityFreshUntil(), acceptedAt,
                "Controlled-transfer route authority expired during resolution");
        requireFuture(trusted.freshUntil(), acceptedAt,
                "Object metadata authority expired during resolution");
        requireFuture(verified.freshUntil(), acceptedAt,
                "Malware scan authority expired during resolution");
        if (egress != null) {
            requireFuture(egress.freshUntil(), acceptedAt,
                    "Egress policy authority expired during resolution");
        }

        return repository.create(new PreparedTransfer(
                UUID.randomUUID(), access, command.ownerModule(), command.transferKind(),
                command.recipientPolicyRef(), command.egressPolicyRevision(),
                command.objectRef(), trusted.objectVersion(), trusted.objectDigest(),
                trusted.byteSize(), trusted.mediaType(), trusted.metadataAuthority(),
                trusted.metadataSnapshotDigest(), trusted.authorityDigest(),
                trusted.freshUntil(), trusted.storageETag(), binding.uploadSessionId(),
                verified.scannerKey(), verified.scannerVersion(), verified.scannedAt(),
                verified.authorityDigest(), verified.freshUntil(),
                egress == null ? null : egress.authorityDigest(),
                egress == null ? null : egress.freshUntil(),
                command.classification(), command.expiresAt(), requestDigest,
                acceptedAt));
    }

    CatalogResponse catalog(
            AccessSeal access,
            OffsetDateTime asOf,
            List<String> fields,
            Freshness freshness,
            String filter,
            String encodedCursor,
            int limit) {
        requireAccess(access, false);
        requireCursorConfiguration();
        OffsetDateTime now = now();
        if (asOf == null || asOf.isAfter(now.plusMinutes(1))) {
            throw invalid("asOf is required and cannot be in the future");
        }
        if (fields == null || fields.isEmpty() || fields.size() > 64
                || fields.stream().anyMatch(value -> value == null || value.isBlank()
                || !value.equals(value.strip()))
                || fields.stream().distinct().count() != fields.size()
                || !CATALOG_FIELDS.containsAll(fields)) {
            throw invalid("fields must be unique controlled-transfer catalog fields");
        }
        if (freshness == null) throw invalid("freshness is required");
        if (filter != null && (filter.isBlank() || filter.length() > 1000
                || !filter.equals(filter.strip()))) {
            throw invalid("filter must contain 1 through 1000 trimmed characters");
        }
        if (limit < 1 || limit > 100) {
            throw invalid("limit must be between 1 and 100");
        }
        CursorBinding binding = new CursorBinding(
                access, asOf, canonicalFilter(filter), List.copyOf(new TreeSet<>(fields)),
                freshness);
        Cursor cursor = decodeCursor(encodedCursor, binding, now);
        TransferPage page = repository.catalog(
                access, asOf, filter, cursor, limit);
        // The catalog is read directly from the authoritative transactional store;
        // there is no separately versioned cache watermark that could justify a
        // stale grace period. Both modes therefore close at the verified carrier
        // authority boundary, and the requested mode remains cursor-bound.
        OffsetDateTime freshUntil = access.authorityFreshUntil();
        OffsetDateTime staleAfter = access.authorityFreshUntil();
        return new CatalogResponse(
                asOf, encodeCursor(page.nextCursor(), binding, now), page.items(),
                freshUntil, staleAfter);
    }

    private void requireCommand(FileTransferCommand command) {
        if (command == null || command.ownerModule() == null
                || command.transferKind() == null || command.classification() == null
                || command.toctouBinding() == null
                || command.malwareScanSnapshot() == null
                || command.expiresAt() == null) {
            throw invalid("Closed FileTransferCommand is required");
        }
        if (!PURPOSE.equals(command.purposeCode())) {
            throw invalid("purposeCode must be HRIS_INTEGRATION_ADMIN");
        }
        boolean outbound = command.transferKind() != TransferKind.INGEST;
        if (outbound && (command.recipientPolicyRef() == null
                || command.egressPolicyRevision() == null
                || command.egressPolicyRevision() < 1)) {
            throw invalid("Outbound transfers require recipient policy and egress revision");
        }
        if (!outbound && (command.recipientPolicyRef() != null
                || command.egressPolicyRevision() != null)) {
            throw invalid("INGEST forbids outbound recipient policy fields");
        }
        MalwareScanSnapshot scan = command.malwareScanSnapshot();
        ToctouBinding binding = command.toctouBinding();
        if (!canonical(command.objectRef(), 132)
                || !command.objectRef().matches("^obj_[A-Za-z0-9_-]{16,128}$")
                || !canonical(command.objectVersion(), 128)
                || !digest(command.objectDigest())
                || command.byteSize() < 1 || command.byteSize() > MAX_TRANSFER_BYTES
                || !MEDIA_TYPES.contains(command.mediaType())
                || !canonical(scan.scannerKey(), 64)
                || !canonical(scan.scannerVersion(), 64)
                || !"CLEAN".equals(scan.verdict())
                || scan.scannedAt() == null || scan.scannedAt().isAfter(now())
                || !command.objectDigest().equals(scan.objectDigest())
                || command.byteSize() != scan.byteSize()
                || binding.uploadSessionId() == null
                || !canonical(binding.storageETag(), 256)
                || !command.objectVersion().equals(binding.boundObjectVersion())
                || !command.objectDigest().equals(binding.boundDigest())
                || command.byteSize() != binding.boundByteSize()
                || !digest(binding.snapshotDigest())) {
            throw invalid("Controlled-transfer command snapshot is invalid");
        }
    }

    private void requireTrustedMetadata(
            AccessSeal access,
            FileTransferCommand command,
            ToctouBinding binding,
            TrustedObjectMetadata trusted,
            OffsetDateTime checkedAt) {
        if (trusted == null
                || trusted.tenantId() != access.tenantId()
                || !access.subjectPrincipalPublicId().equals(
                trusted.subjectPrincipalPublicId())
                || !command.objectRef().equals(trusted.objectRef())
                || !binding.uploadSessionId().equals(trusted.uploadSessionId())
                || !command.objectVersion().equals(trusted.objectVersion())
                || !command.objectDigest().equals(trusted.objectDigest())
                || command.byteSize() != trusted.byteSize()
                || !command.mediaType().equals(trusted.mediaType())
                || !binding.storageETag().equals(trusted.storageETag())
                || !binding.boundObjectVersion().equals(trusted.objectVersion())
                || !binding.boundDigest().equals(trusted.objectDigest())
                || binding.boundByteSize() != trusted.byteSize()
                || !binding.snapshotDigest().equals(trusted.metadataSnapshotDigest())
                || !digest(trusted.metadataSnapshotDigest())
                || !digest(trusted.authorityDigest())
                || blank(trusted.metadataAuthority())) {
            throw conflict("CONTROLLED_TRANSFER_OBJECT_SNAPSHOT_CONFLICT");
        }
        requireFuture(trusted.freshUntil(), checkedAt,
                "Object metadata authority is stale");
    }

    private void requireCleanScan(
            AccessSeal access,
            FileTransferCommand command,
            MalwareScanSnapshot scan,
            TrustedObjectMetadata trusted,
            VerifiedCleanScan verified,
            OffsetDateTime checkedAt) {
        if (!"CLEAN".equals(scan.verdict()) || verified == null
                || verified.tenantId() != access.tenantId()
                || !scan.scannerKey().equals(verified.scannerKey())
                || !scan.scannerVersion().equals(verified.scannerVersion())
                || !scan.scannedAt().equals(verified.scannedAt())
                || !trusted.objectDigest().equals(scan.objectDigest())
                || !trusted.objectDigest().equals(verified.objectDigest())
                || trusted.byteSize() != scan.byteSize()
                || trusted.byteSize() != verified.byteSize()
                || !command.objectDigest().equals(scan.objectDigest())
                || !digest(verified.authorityDigest())) {
            throw conflict("CONTROLLED_TRANSFER_SCAN_SNAPSHOT_CONFLICT");
        }
        requireFuture(verified.freshUntil(), checkedAt,
                "Malware scan authority is stale");
    }

    private void requireEgress(
            AccessSeal access,
            FileTransferCommand command,
            TrustedObjectMetadata trusted,
            VerifiedEgressPolicy verified,
            OffsetDateTime checkedAt) {
        if (verified == null || !verified.allowed()
                || verified.tenantId() != access.tenantId()
                || !access.subjectPrincipalPublicId().equals(
                verified.subjectPrincipalPublicId())
                || !command.recipientPolicyRef().equals(verified.recipientPolicyRef())
                || command.egressPolicyRevision() != verified.egressPolicyRevision()
                || command.transferKind() != verified.transferKind()
                || command.classification() != verified.classification()
                || !PURPOSE.equals(verified.purposeCode())
                || !command.objectRef().equals(verified.objectRef())
                || !trusted.objectVersion().equals(verified.objectVersion())
                || !trusted.objectDigest().equals(verified.objectDigest())
                || trusted.byteSize() != verified.byteSize()
                || !trusted.mediaType().equals(verified.mediaType())
                || !digest(verified.authorityDigest())) {
            throw conflict("CONTROLLED_TRANSFER_EGRESS_POLICY_CONFLICT");
        }
        requireFuture(verified.freshUntil(), checkedAt,
                "Egress policy authority is stale");
    }

    private void requireAccess(AccessSeal access, boolean command) {
        OffsetDateTime now = now();
        Set<String> expectedGroups = command ? CREATE_FIELD_GROUPS : READ_FIELD_GROUPS;
        String expectedRoute = command ? CREATE_ROUTE : READ_ROUTE;
        if (access == null || access.tenantId() < 1
                || access.subjectPrincipalPublicId() == null
                || !digest(access.populationScopeDigest())
                || !POPULATION_KINDS.contains(access.populationKind())
                || access.projectionFieldGroups().isEmpty()
                || !expectedGroups.containsAll(access.projectionFieldGroups())
                || (command && !expectedGroups.equals(access.projectionFieldGroups()))
                || (!command && !access.projectionFieldGroups()
                .contains("TRANSFER_METADATA"))
                || access.fieldPolicyRevision() < 1
                || access.authorizationRevision() < 1
                || !expectedRoute.equals(access.routeContractKey())
                || !canonical(access.contextKey(), 500)
                || !HcmEligibilityScopeKey.isCanonical(access.contextScopeKey())
                || !canonical(access.decisionRevision(), 200)
                || !PURPOSE.equals(access.purposeCode())
                || !digest(access.authorityDigest())
                || access.authorityFreshUntil() == null
                || !access.authorityFreshUntil().isAfter(now)
                || access.correlationId() == null
                || (command && (access.idempotencyKey() == null
                || access.idempotencyKey().length() < 16
                || access.idempotencyKey().length() > 128
                || !canonical(access.idempotencyKey(), 128)))
                || !access.populationScopeDigest().equals(populationScopeDigest(
                access.tenantId(), access.subjectPrincipalPublicId(),
                access.populationKind(), access.contextScopeKey(),
                access.decisionRevision()))
                || !access.authorityDigest().equals(authorityDigest(
                access.tenantId(), access.subjectPrincipalPublicId(),
                access.routeContractKey(), access.contextKey(),
                access.contextScopeKey(), access.populationKind(),
                access.populationScopeDigest(), access.projectionFieldGroups(),
                access.fieldPolicyRevision(), access.authorizationRevision(),
                access.decisionRevision(), access.purposeCode(),
                access.authorityFreshUntil()))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Verified controlled-transfer authority is required");
        }
    }

    static String populationScopeDigest(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String populationKind,
            String contextScopeKey,
            String decisionRevision) {
        return sha256(String.join("\u001f",
                "ControlledTransferPopulation.v1", Long.toString(tenantId),
                subjectPrincipalPublicId.toString(), populationKind,
                contextScopeKey, decisionRevision).getBytes(StandardCharsets.UTF_8));
    }

    static String authorityDigest(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String routeContractKey,
            String contextKey,
            String contextScopeKey,
            String populationKind,
            String populationScopeDigest,
            Set<String> projectionFieldGroups,
            long fieldPolicyRevision,
            long authorizationRevision,
            String decisionRevision,
            String purposeCode,
            OffsetDateTime authorityFreshUntil) {
        return sha256(String.join("\u001f",
                "ControlledTransferAuthority.v1", Long.toString(tenantId),
                subjectPrincipalPublicId.toString(), routeContractKey, contextKey,
                contextScopeKey, populationKind, populationScopeDigest,
                String.join(",", new TreeSet<>(projectionFieldGroups)),
                Long.toString(fieldPolicyRevision),
                Long.toString(authorizationRevision), decisionRevision, purposeCode,
                authorityFreshUntil.toInstant().toString())
                .getBytes(StandardCharsets.UTF_8));
    }

    private String commandSnapshotDigest(FileTransferCommand command) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("ownerModule", command.ownerModule().name());
        canonical.put("transferKind", command.transferKind().name());
        canonical.put("recipientPolicyRef", command.recipientPolicyRef());
        canonical.put("egressPolicyRevision", command.egressPolicyRevision());
        canonical.put("objectRef", command.objectRef());
        canonical.put("objectVersion", command.objectVersion());
        canonical.put("objectDigest", command.objectDigest());
        canonical.put("byteSize", command.byteSize());
        canonical.put("mediaType", command.mediaType());
        canonical.put("scannerKey", command.malwareScanSnapshot().scannerKey());
        canonical.put("scannerVersion", command.malwareScanSnapshot().scannerVersion());
        canonical.put("scanVerdict", command.malwareScanSnapshot().verdict());
        canonical.put("scannedAt", command.malwareScanSnapshot().scannedAt());
        canonical.put("scanObjectDigest", command.malwareScanSnapshot().objectDigest());
        canonical.put("scanByteSize", command.malwareScanSnapshot().byteSize());
        canonical.put("uploadSessionId", command.toctouBinding().uploadSessionId());
        canonical.put("storageETag", command.toctouBinding().storageETag());
        canonical.put("boundObjectVersion", command.toctouBinding().boundObjectVersion());
        canonical.put("boundDigest", command.toctouBinding().boundDigest());
        canonical.put("boundByteSize", command.toctouBinding().boundByteSize());
        canonical.put("metadataSnapshotDigest", command.toctouBinding().snapshotDigest());
        canonical.put("classification", command.classification().name());
        canonical.put("purposeCode", command.purposeCode());
        canonical.put("expiresAt", command.expiresAt());
        try {
            return sha256(json.writeValueAsBytes(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Controlled transfer digest failed", exception);
        }
    }

    private String encodeCursor(
            Cursor cursor, CursorBinding binding, OffsetDateTime issuedAt) {
        if (cursor == null) return null;
        OffsetDateTime expiresAt = earliest(
                issuedAt.plus(cursorTtl), binding.access().authorityFreshUntil());
        ObjectNode payload = json.createObjectNode();
        payload.put("version", 1);
        payload.put("tenantId", binding.access().tenantId());
        payload.put("subjectPrincipalPublicId",
                binding.access().subjectPrincipalPublicId().toString());
        payload.put("routeContractKey", binding.access().routeContractKey());
        payload.put("contextKey", binding.access().contextKey());
        payload.put("contextScopeKey", binding.access().contextScopeKey());
        payload.put("populationScopeDigest", binding.access().populationScopeDigest());
        payload.put("populationKind", binding.access().populationKind());
        payload.put("fieldPolicyRevision", binding.access().fieldPolicyRevision());
        payload.put("authorizationRevision", binding.access().authorizationRevision());
        payload.put("decisionRevision", binding.access().decisionRevision());
        payload.put("authorityDigest", binding.access().authorityDigest());
        payload.put("authorityFreshUntil",
                binding.access().authorityFreshUntil().toInstant().toString());
        payload.put("asOf", binding.asOf().toInstant().toString());
        if (binding.filter() == null) payload.putNull("filter");
        else payload.put("filter", binding.filter());
        var fieldArray = payload.putArray("fields");
        binding.fields().forEach(fieldArray::add);
        payload.put("freshness", binding.freshness().name());
        payload.put("updatedAt", cursor.updatedAt().toInstant().toString());
        payload.put("publicId", cursor.publicId().toString());
        payload.put("issuedAt", issuedAt.toInstant().toString());
        payload.put("expiresAt", expiresAt.toInstant().toString());
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Controlled transfer cursor failed", exception);
        }
        return base64(bytes) + "." + base64(hmac(bytes));
    }

    private Cursor decodeCursor(
            String encoded, CursorBinding binding, OffsetDateTime checkedAt) {
        if (encoded == null) return null;
        if (encoded.length() < 32 || encoded.length() > 2048
                || !encoded.equals(encoded.strip())) {
            throw invalid("cursor is invalid");
        }
        try {
            String[] token = encoded.split("\\.", -1);
            if (token.length != 2) throw new IllegalArgumentException();
            byte[] payloadBytes = Base64.getUrlDecoder().decode(token[0]);
            byte[] signature = Base64.getUrlDecoder().decode(token[1]);
            if (!base64(payloadBytes).equals(token[0])
                    || !base64(signature).equals(token[1])
                    || !MessageDigest.isEqual(signature, hmac(payloadBytes))) {
                throw new IllegalArgumentException();
            }
            JsonNode value = json.readTree(payloadBytes);
            if (!(value instanceof ObjectNode payload)) {
                throw new IllegalArgumentException();
            }
            Set<String> names = new TreeSet<>();
            payload.fieldNames().forEachRemaining(names::add);
            if (!CURSOR_PROPERTIES.equals(names) || payload.path("version").asInt() != 1) {
                throw new IllegalArgumentException();
            }
            OffsetDateTime expiresAt = OffsetDateTime.parse(requiredText(payload, "expiresAt"));
            OffsetDateTime issuedAt = OffsetDateTime.parse(requiredText(payload, "issuedAt"));
            OffsetDateTime authorityFreshUntil = OffsetDateTime.parse(
                    requiredText(payload, "authorityFreshUntil"));
            if (!expiresAt.isAfter(checkedAt) || issuedAt.isAfter(checkedAt.plusMinutes(1))
                    || expiresAt.isAfter(issuedAt.plus(cursorTtl))
                    || expiresAt.isAfter(authorityFreshUntil)) {
                throw new IllegalArgumentException();
            }
            List<String> fields = stringArray(payload.path("fields"));
            String filter = payload.path("filter").isNull()
                    ? null : requiredText(payload, "filter");
            if (payload.path("tenantId").asLong(-1) != binding.access().tenantId()
                    || !requiredText(payload, "subjectPrincipalPublicId").equals(
                    binding.access().subjectPrincipalPublicId().toString())
                    || !requiredText(payload, "routeContractKey").equals(
                    binding.access().routeContractKey())
                    || !requiredText(payload, "contextKey").equals(binding.access().contextKey())
                    || !requiredText(payload, "contextScopeKey").equals(
                    binding.access().contextScopeKey())
                    || !requiredText(payload, "populationScopeDigest").equals(
                    binding.access().populationScopeDigest())
                    || !requiredText(payload, "populationKind").equals(
                    binding.access().populationKind())
                    || payload.path("fieldPolicyRevision").asLong(-1)
                    != binding.access().fieldPolicyRevision()
                    || payload.path("authorizationRevision").asLong(-1)
                    != binding.access().authorizationRevision()
                    || !requiredText(payload, "decisionRevision").equals(
                    binding.access().decisionRevision())
                    || !requiredText(payload, "authorityDigest").equals(
                    binding.access().authorityDigest())
                    || !authorityFreshUntil.toInstant().equals(
                    binding.access().authorityFreshUntil().toInstant())
                    || !OffsetDateTime.parse(requiredText(payload, "asOf")).toInstant()
                    .equals(binding.asOf().toInstant())
                    || !java.util.Objects.equals(filter, binding.filter())
                    || !fields.equals(binding.fields())
                    || !requiredText(payload, "freshness").equals(
                    binding.freshness().name())) {
                throw new IllegalArgumentException();
            }
            return new Cursor(
                    OffsetDateTime.parse(requiredText(payload, "updatedAt")),
                    UUID.fromString(requiredText(payload, "publicId")));
        } catch (RuntimeException | java.io.IOException exception) {
            throw invalid("cursor is invalid");
        }
    }

    private List<String> stringArray(JsonNode value) {
        if (!value.isArray()) throw new IllegalArgumentException();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        value.forEach(item -> result.add(requiredText(item, "")));
        return List.copyOf(result);
    }

    private String requiredText(JsonNode source, String name) {
        JsonNode value = name.isEmpty() ? source : source.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException();
        }
        return value.textValue();
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorSecret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", exception);
        }
    }

    private void requireCursorConfiguration() {
        if (cursorSecret.length < 32 || cursorTtl == null || cursorTtl.isZero()
                || cursorTtl.isNegative() || cursorTtl.compareTo(MAX_CURSOR_TTL) > 0) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Controlled-transfer cursor authority is not configured");
        }
    }

    private void requireFuture(
            OffsetDateTime value, OffsetDateTime checkedAt, String message) {
        if (value == null || !value.isAfter(checkedAt)) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
        }
    }

    static long publicRevision(long authorityRevision) {
        long revision = authorityRevision & 9_007_199_254_740_991L;
        return revision == 0 ? 1 : revision;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime earliest(OffsetDateTime left, OffsetDateTime right) {
        return left.isBefore(right) ? left : right;
    }

    private static String canonicalFilter(String value) {
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean digest(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static byte[] canonicalSecret(String value) {
        if (value == null || value.length() < 32 || value.length() > 4_096
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record CursorBinding(
            AccessSeal access,
            OffsetDateTime asOf,
            String filter,
            List<String> fields,
            Freshness freshness) {
    }
}
