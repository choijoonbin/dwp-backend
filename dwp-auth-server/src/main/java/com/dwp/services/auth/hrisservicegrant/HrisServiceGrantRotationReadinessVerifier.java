package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.GrantAuthority;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ReadinessObservation;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.RsaPublicJwk;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ServiceRole;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict verifier for the signed, zero-pending HRM-013 rotation-readiness set. */
public final class HrisServiceGrantRotationReadinessVerifier {
    static final String SCHEMA = "HrisServiceGrantRotationReadinessReceiptV1";
    static final String PROOF_TYPE = "DWP-HRM013-ROTATION-READINESS-JWT";
    static final String PROOF_PURPOSE = "HRIS_SERVICE_GRANT_ROTATION_READINESS";
    private static final int MAX_JSON_BYTES = 16_384;
    private static final long CLOCK_SKEW_SECONDS = 5;
    private static final long MAX_PROOF_SECONDS = 300;
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "schemaName", "schemaVersion", "tenantId", "bindingId",
            "callerWorkloadId", "ownerWorkloadId", "bindingPurpose",
            "serviceWorkloadId", "attestingServiceRole", "oldGrantSetId",
            "quiesceFenceId", "quiesceRevision", "quiesceEffectiveAt",
            "observedDrainActivityRevision", "drainQuietSince",
            "readinessEligibleAt", "readinessObservationSha256",
            "observedGrantRevision", "tenantGrantRevision", "authorizationRevision",
            "pendingCount", "oldestPendingAt", "observedAt", "expiresAt",
            "evidenceSha256", "correlationId");
    private static final Set<String> CLAIM_FIELDS = Set.of(
            "iss", "aud", "sub", "iat", "nbf", "exp", "jti", "requestNonce",
            "tenantId", "bindingId", "callerWorkloadId", "ownerWorkloadId",
            "bindingPurpose", "serviceWorkloadId", "attestingServiceRole",
            "oldGrantSetId", "quiesceFenceId", "quiesceRevision",
            "quiesceEffectiveAt", "observedDrainActivityRevision", "drainQuietSince",
            "readinessEligibleAt", "readinessObservationSha256",
            "observedGrantRevision", "tenantGrantRevision", "authorizationRevision",
            "evidenceSha256", "correlationId", "proofPurpose");
    private static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
    private static final Comparator<VerifiedReceipt> RECEIPT_ORDER = Comparator
            .comparing((VerifiedReceipt value) -> value.target().bindingId())
            .thenComparing(value -> value.target().callerWorkloadId())
            .thenComparing(value -> value.target().ownerWorkloadId())
            .thenComparing(value -> value.target().purpose())
            .thenComparing(value -> value.role().name())
            .thenComparing(VerifiedReceipt::serviceWorkloadId)
            .thenComparing(VerifiedReceipt::evidenceSha256);

    private final ObjectMapper mapper;

    public HrisServiceGrantRotationReadinessVerifier() {
        this(new ObjectMapper());
    }

    public HrisServiceGrantRotationReadinessVerifier(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public record LockedRotation(
            long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target,
            UUID grantSetId,
            long tenantGrantRevision,
            long authorizationRevision,
            UUID quiesceFenceId,
            long quiesceRevision,
            Instant quiesceEffectiveAt,
            Instant databaseNow) {
        public LockedRotation {
            if (tenantId < 1 || tenantGrantRevision < 1 || authorizationRevision < 1
                    || quiesceRevision < 1)
                throw new IllegalArgumentException("rotation revisions are invalid");
            Objects.requireNonNull(target); Objects.requireNonNull(grantSetId);
            Objects.requireNonNull(quiesceFenceId); Objects.requireNonNull(quiesceEffectiveAt);
            Objects.requireNonNull(databaseNow);
        }
    }

    /** Exact carried bytes; neither JSON value may be regenerated before verification. */
    public record ReadinessItem(
            byte[] receiptBytes, byte[] proofClaimBytes, String readinessProof) {
        public ReadinessItem {
            receiptBytes = receiptBytes == null ? null : receiptBytes.clone();
            proofClaimBytes = proofClaimBytes == null ? null : proofClaimBytes.clone();
            Objects.requireNonNull(receiptBytes, "receiptBytes");
            Objects.requireNonNull(proofClaimBytes, "proofClaimBytes");
            Objects.requireNonNull(readinessProof, "readinessProof");
        }
        @Override public byte[] receiptBytes() { return receiptBytes.clone(); }
        @Override public byte[] proofClaimBytes() { return proofClaimBytes.clone(); }
    }

    /** Verified data that the repository must reserve and recheck in the cutover transaction. */
    public record VerifiedReceipt(
            HrisServiceGrantAuthorityV1.TargetTuple target,
            ServiceRole role,
            String serviceWorkloadId,
            String evidenceSha256,
            Hrm013WorkloadProofV1.ReplayReservation replay,
            ReadinessObservation observation,
            Instant observedAt,
            Instant expiresAt,
            UUID correlationId) {
        public VerifiedReceipt {
            Objects.requireNonNull(target); Objects.requireNonNull(role);
            Objects.requireNonNull(serviceWorkloadId); Objects.requireNonNull(evidenceSha256);
            Objects.requireNonNull(replay); Objects.requireNonNull(observation);
            Objects.requireNonNull(observedAt); Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(correlationId);
        }
    }

    public record VerifiedTarget(String rotationReadinessDigest,
            List<VerifiedReceipt> receipts) {
        public VerifiedTarget {
            requireDigest(rotationReadinessDigest);
            receipts = List.copyOf(receipts);
            if (receipts.size() != 2) throw Hrm013SafeErrorV1.invalidState();
        }
    }

    /**
     * Verifies one exact tuple's CALLER and OWNER receipts against data recomputed while locked.
     * Replay reservations are returned, not persisted; the caller must reserve every pair in the
     * same transaction that inserts the successor grant.
     */
    public VerifiedTarget verifySigned(
            LockedRotation locked,
            GrantAuthority authority,
            List<ReadinessObservation> currentObservations,
            List<ReadinessItem> items) {
        Objects.requireNonNull(locked, "locked");
        requireAuthority(locked, authority);
        List<ReadinessObservation> observations = exactObservations(
                locked, currentObservations);
        if (items == null || items.size() != 2)
            throw Hrm013SafeErrorV1.invalidState();
        Map<ServiceRole, ReadinessObservation> byRole = new LinkedHashMap<>();
        for (ReadinessObservation value : observations)
            byRole.put(value.attestingServiceRole(), value);
        List<VerifiedReceipt> verified = new ArrayList<>();
        for (ReadinessItem item : items) {
            VerifiedReceipt value = verifyItem(locked, authority, byRole, item);
            if (verified.stream().anyMatch(existing -> existing.role() == value.role()))
                throw Hrm013SafeErrorV1.invalidState();
            verified.add(value);
        }
        if (!verified.stream().map(VerifiedReceipt::role).collect(
                java.util.stream.Collectors.toUnmodifiableSet()).equals(Set.of(
                ServiceRole.CALLER, ServiceRole.OWNER)))
            throw Hrm013SafeErrorV1.invalidState();
        return new VerifiedTarget(aggregateVerified(verified), verified);
    }

    public static String aggregateVerified(List<VerifiedReceipt> receipts) {
        if (receipts == null || receipts.isEmpty()) throw Hrm013SafeErrorV1.invalidState();
        List<VerifiedReceipt> ordered = receipts.stream().sorted(RECEIPT_ORDER).toList();
        Set<String> identities = new HashSet<>();
        ArrayNode evidence = new ObjectMapper().createArrayNode();
        for (VerifiedReceipt value : ordered) {
            String identity = value.target().bindingId() + '\n'
                    + value.target().callerWorkloadId() + '\n'
                    + value.target().ownerWorkloadId() + '\n' + value.target().purpose() + '\n'
                    + value.role() + '\n' + value.serviceWorkloadId();
            if (!identities.add(identity)) throw Hrm013SafeErrorV1.invalidState();
            evidence.add(value.evidenceSha256());
        }
        return canonicalDigest(evidence);
    }

    static Map<HrisServiceGrantAuthorityV1.TargetTuple, List<ReadinessItem>> groupItemsByTarget(
            List<ReadinessItem> items, List<HrisServiceGrantAuthorityV1.TargetTuple> targets,
            ObjectMapper mapper) {
        if (items == null || targets == null) throw Hrm013SafeErrorV1.invalidState();
        Map<HrisServiceGrantAuthorityV1.TargetTuple, List<ReadinessItem>> grouped =
                new LinkedHashMap<>();
        HrisServiceGrantRotationReadinessVerifier parser =
                new HrisServiceGrantRotationReadinessVerifier(mapper);
        for (ReadinessItem item : items) {
            ObjectNode receipt = parser.strictObject(item.receiptBytes(), RECEIPT_FIELDS, true);
            var target = new HrisServiceGrantAuthorityV1.TargetTuple(
                    text(receipt, "bindingId"), text(receipt, "callerWorkloadId"),
                    text(receipt, "ownerWorkloadId"), text(receipt, "bindingPurpose"));
            grouped.computeIfAbsent(target, ignored -> new ArrayList<>()).add(item);
        }
        if (!grouped.keySet().equals(Set.copyOf(targets)))
            throw Hrm013SafeErrorV1.invalidState();
        return grouped;
    }

    static Map<HrisServiceGrantAuthorityV1.TargetTuple, List<ReadinessObservation>> groupByTarget(
            List<ReadinessObservation> observations,
            List<HrisServiceGrantAuthorityV1.TargetTuple> expectedTargets) {
        if (observations == null) throw Hrm013SafeErrorV1.invalidState();
        Map<HrisServiceGrantAuthorityV1.TargetTuple, List<ReadinessObservation>> grouped =
                new LinkedHashMap<>();
        for (var value : observations) {
            var target = new HrisServiceGrantAuthorityV1.TargetTuple(value.bindingId(),
                    value.callerWorkloadId(), value.ownerWorkloadId(), value.purpose());
            grouped.computeIfAbsent(target, ignored -> new ArrayList<>()).add(value);
        }
        if (!grouped.keySet().equals(Set.copyOf(expectedTargets)))
            throw Hrm013SafeErrorV1.invalidState();
        return grouped;
    }

    static String observationDigest(HrisServiceGrantAuthorityV1.ResolveRequest request,
            UUID grantSetId, long ignoredTenantGrantRevision,
            long ignoredAuthorizationRevision, HrisServiceGrantQuiesceService.Fence fence,
            ServiceRole role, long activityRevision, Instant quietSince, Instant eligibleAt) {
        return observationDigest(request.tenantId(), request.targetTuple(), grantSetId, fence,
                role, activityRevision, quietSince, eligibleAt);
    }

    static String observationDigest(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target, UUID grantSetId,
            HrisServiceGrantQuiesceService.Fence fence, ServiceRole role,
            long activityRevision, Instant quietSince, Instant eligibleAt) {
        if (activityRevision < 0 || !eligibleAt.equals(quietSince.plusSeconds(65)))
            throw Hrm013SafeErrorV1.invalidState();
        ObjectNode value = new ObjectMapper().createObjectNode();
        value.put("tenantId", tenantId);
        value.put("bindingId", target.bindingId());
        value.put("callerWorkloadId", target.callerWorkloadId());
        value.put("ownerWorkloadId", target.ownerWorkloadId());
        value.put("bindingPurpose", target.purpose());
        value.put("serviceWorkloadId", role == ServiceRole.CALLER
                ? target.callerWorkloadId() : target.ownerWorkloadId());
        value.put("attestingServiceRole", role.name());
        value.put("oldGrantSetId", grantSetId.toString());
        value.put("quiesceFenceId", fence.id().toString());
        value.put("quiesceRevision", fence.revision());
        value.put("quiesceEffectiveAt", canonicalInstant(fence.effectiveAt()));
        value.put("observedDrainActivityRevision", activityRevision);
        value.put("drainQuietSince", canonicalInstant(quietSince));
        value.put("readinessEligibleAt", canonicalInstant(eligibleAt));
        return canonicalDigest(value);
    }

    /** RFC 8785 digest of the ordered evidence SHA-256 JSON array. */
    static String aggregateDigest(List<String> readinessDigests) {
        if (readinessDigests == null || readinessDigests.isEmpty())
            throw Hrm013SafeErrorV1.invalidState();
        ArrayNode values = new ObjectMapper().createArrayNode();
        readinessDigests.stream().sorted().forEach(value -> {
            requireDigest(value); values.add(value);
        });
        return canonicalDigest(values);
    }

    private VerifiedReceipt verifyItem(LockedRotation locked, GrantAuthority authority,
            Map<ServiceRole, ReadinessObservation> observations, ReadinessItem item) {
        ObjectNode receipt = strictObject(item.receiptBytes(), RECEIPT_FIELDS, true);
        ObjectNode claims = strictObject(item.proofClaimBytes(), CLAIM_FIELDS, false);
        ProofParts proof = proofParts(item.readinessProof(), item.proofClaimBytes());
        ServiceRole role = role(receipt, "attestingServiceRole");
        ReadinessObservation observation = observations.get(role);
        if (observation == null) throw Hrm013SafeErrorV1.invalidState();
        String workload = role == ServiceRole.CALLER
                ? locked.target().callerWorkloadId() : locked.target().ownerWorkloadId();
        RsaPublicJwk jwk = role == ServiceRole.CALLER
                ? authority.callerSigningPublicJwk() : authority.ownerSigningPublicJwk();
        String keyId = role == ServiceRole.CALLER
                ? authority.callerSigningKeyId() : authority.ownerSigningKeyId();
        requireHeader(proof.header(), keyId);
        verifySignature(proof, rsa(jwk));

        String evidence = text(receipt, "evidenceSha256");
        requireDigest(evidence);
        ObjectNode preimage = receipt.deepCopy(); preimage.remove("evidenceSha256");
        if (!evidence.equals(canonicalDigest(preimage))) throw Hrm013SafeErrorV1.invalidState();
        requireReceipt(locked, authority, observation, receipt, role, workload);
        requireClaims(locked, authority, observation, receipt, claims, role, workload, evidence);

        Instant observedAt = instant(receipt, "observedAt");
        Instant receiptExpiry = instant(receipt, "expiresAt");
        Instant issuedAt = epoch(claims, "iat");
        Instant proofExpiry = epoch(claims, "exp");
        Duration issueDelay;
        try { issueDelay = Duration.between(observedAt, issuedAt); }
        catch (RuntimeException invalid) { throw Hrm013SafeErrorV1.invalidState(); }
        if (issuedAt.getNano() != 0 || !issuedAt.equals(epoch(claims, "nbf"))
                || issueDelay.isNegative()
                || issueDelay.compareTo(Duration.ofSeconds(CLOCK_SKEW_SECONDS)) > 0
                || issuedAt.isAfter(locked.databaseNow().plusSeconds(CLOCK_SKEW_SECONDS))
                || observedAt.isBefore(observation.readinessEligibleAt())
                || observedAt.isAfter(locked.databaseNow().plusSeconds(CLOCK_SKEW_SECONDS))
                || !receiptExpiry.isAfter(locked.databaseNow())
                || !receiptExpiry.isAfter(observedAt) || receiptExpiry.isAfter(proofExpiry)
                || !proofExpiry.isAfter(issuedAt)
                || proofExpiry.isAfter(issuedAt.plusSeconds(MAX_PROOF_SECONDS))) {
            throw Hrm013SafeErrorV1.invalidState();
        }
        UUID jti = uuid(claims, "jti"); UUID nonce = uuid(claims, "requestNonce");
        if (jti.equals(nonce)) throw Hrm013SafeErrorV1.invalidState();
        UUID correlation = uuid(receipt, "correlationId");
        Instant databaseReplayLimit = locked.databaseNow().plusSeconds(MAX_PROOF_SECONDS);
        Instant replayExpiry = proofExpiry.isBefore(databaseReplayLimit)
                ? proofExpiry : databaseReplayLimit;
        var replay = new Hrm013WorkloadProofV1.ReplayReservation(
                locked.tenantId(), locked.target().bindingId(), workload,
                Hrm013WorkloadProofV1.sha256(jti.toString().getBytes(StandardCharsets.US_ASCII)),
                Hrm013WorkloadProofV1.sha256(nonce.toString().getBytes(StandardCharsets.US_ASCII)),
                replayExpiry);
        return new VerifiedReceipt(locked.target(), role, workload, evidence, replay,
                observation, observedAt, receiptExpiry, correlation);
    }

    private void requireReceipt(LockedRotation locked, GrantAuthority authority,
            ReadinessObservation observation, ObjectNode receipt, ServiceRole role,
            String workload) {
        if (!SCHEMA.equals(text(receipt, "schemaName"))
                || integer(receipt, "schemaVersion") != 1
                || integer(receipt, "tenantId") != locked.tenantId()
                || !text(receipt, "bindingId").equals(locked.target().bindingId())
                || !text(receipt, "callerWorkloadId").equals(
                        locked.target().callerWorkloadId())
                || !text(receipt, "ownerWorkloadId").equals(
                        locked.target().ownerWorkloadId())
                || !text(receipt, "bindingPurpose").equals(locked.target().purpose())
                || !text(receipt, "serviceWorkloadId").equals(workload)
                || !text(receipt, "oldGrantSetId").equals(locked.grantSetId().toString())
                || !text(receipt, "quiesceFenceId").equals(
                        locked.quiesceFenceId().toString())
                || integer(receipt, "quiesceRevision") != locked.quiesceRevision()
                || !instant(receipt, "quiesceEffectiveAt").equals(
                        locked.quiesceEffectiveAt())
                || integer(receipt, "observedDrainActivityRevision")
                        != observation.drainActivityRevision()
                || !instant(receipt, "drainQuietSince").equals(observation.drainQuietSince())
                || !instant(receipt, "readinessEligibleAt").equals(
                        observation.readinessEligibleAt())
                || !text(receipt, "readinessObservationSha256").equals(
                        observation.readinessObservationSha256())
                || integer(receipt, "observedGrantRevision") != authority.grantRevision()
                || integer(receipt, "tenantGrantRevision") != locked.tenantGrantRevision()
                || integer(receipt, "authorizationRevision") != locked.authorizationRevision()
                || integer(receipt, "pendingCount") != 0
                || !receipt.get("oldestPendingAt").isNull()
                || locked.databaseNow().isBefore(observation.readinessEligibleAt())) {
            throw Hrm013SafeErrorV1.invalidState();
        }
        receipt.properties().forEach(entry -> {
            if (!"oldestPendingAt".equals(entry.getKey()) && entry.getValue().isNull())
                throw Hrm013SafeErrorV1.invalidState();
        });
        String currentDigest = observationDigest(locked.tenantId(), locked.target(),
                locked.grantSetId(), new HrisServiceGrantQuiesceService.Fence(
                        locked.quiesceFenceId(), locked.grantSetId(),
                        locked.quiesceRevision(), "QUIESCING", locked.quiesceEffectiveAt()),
                role, observation.drainActivityRevision(),
                observation.drainQuietSince(), observation.readinessEligibleAt());
        if (!currentDigest.equals(observation.readinessObservationSha256()))
            throw Hrm013SafeErrorV1.invalidState();
    }

    private void requireClaims(LockedRotation locked, GrantAuthority authority,
            ReadinessObservation observation, ObjectNode receipt, ObjectNode claims,
            ServiceRole role, String workload, String evidence) {
        if (!workload.equals(text(claims, "iss"))
                || !"dwp-auth-server".equals(text(claims, "aud"))
                || !workload.equals(text(claims, "sub"))
                || integer(claims, "tenantId") != locked.tenantId()
                || !text(claims, "bindingId").equals(locked.target().bindingId())
                || !text(claims, "callerWorkloadId").equals(
                        locked.target().callerWorkloadId())
                || !text(claims, "ownerWorkloadId").equals(
                        locked.target().ownerWorkloadId())
                || !text(claims, "bindingPurpose").equals(locked.target().purpose())
                || !text(claims, "serviceWorkloadId").equals(workload)
                || !text(claims, "attestingServiceRole").equals(role.name())
                || !text(claims, "oldGrantSetId").equals(locked.grantSetId().toString())
                || !text(claims, "quiesceFenceId").equals(
                        locked.quiesceFenceId().toString())
                || integer(claims, "quiesceRevision") != locked.quiesceRevision()
                || !instant(claims, "quiesceEffectiveAt").equals(
                        locked.quiesceEffectiveAt())
                || integer(claims, "observedDrainActivityRevision")
                        != observation.drainActivityRevision()
                || !instant(claims, "drainQuietSince").equals(observation.drainQuietSince())
                || !instant(claims, "readinessEligibleAt").equals(
                        observation.readinessEligibleAt())
                || !text(claims, "readinessObservationSha256").equals(
                        observation.readinessObservationSha256())
                || integer(claims, "observedGrantRevision") != authority.grantRevision()
                || integer(claims, "tenantGrantRevision") != locked.tenantGrantRevision()
                || integer(claims, "authorizationRevision") != locked.authorizationRevision()
                || !text(claims, "evidenceSha256").equals(evidence)
                || !text(claims, "correlationId").equals(text(receipt, "correlationId"))
                || !PROOF_PURPOSE.equals(text(claims, "proofPurpose"))) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private void requireAuthority(LockedRotation locked, GrantAuthority authority) {
        if (authority == null || !authority.grantSetId().equals(locked.grantSetId())
                || authority.tenantId() != locked.tenantId()
                || authority.tenantGrantRevision() != locked.tenantGrantRevision()
                || authority.authorizationRevision() != locked.authorizationRevision()
                || !"ADMIT".equals(authority.decision())
                || !authority.bindingId().equals(locked.target().bindingId())
                || !authority.callerWorkloadId().equals(locked.target().callerWorkloadId())
                || !authority.ownerWorkloadId().equals(locked.target().ownerWorkloadId())
                || !authority.purpose().equals(locked.target().purpose())) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private List<ReadinessObservation> exactObservations(
            LockedRotation locked, List<ReadinessObservation> observations) {
        if (observations == null || observations.size() != 2)
            throw Hrm013SafeErrorV1.invalidState();
        Set<ServiceRole> roles = new HashSet<>();
        for (ReadinessObservation observation : observations) {
            if (!roles.add(observation.attestingServiceRole())
                    || observation.tenantId() != locked.tenantId()
                    || !observation.grantSetId().equals(locked.grantSetId())
                    || observation.tenantGrantRevision() != locked.tenantGrantRevision()
                    || observation.authorizationRevision() != locked.authorizationRevision()
                    || !observation.bindingId().equals(locked.target().bindingId())
                    || !observation.callerWorkloadId().equals(
                            locked.target().callerWorkloadId())
                    || !observation.ownerWorkloadId().equals(
                            locked.target().ownerWorkloadId())
                    || !observation.purpose().equals(locked.target().purpose())
                    || !observation.quiesceFenceId().equals(locked.quiesceFenceId())
                    || observation.quiesceRevision() != locked.quiesceRevision()
                    || !observation.quiesceEffectiveAt().equals(locked.quiesceEffectiveAt())
                    || !observation.readinessEligibleAt().equals(
                            observation.drainQuietSince().plusSeconds(65))
                    || locked.databaseNow().isBefore(observation.readinessEligibleAt())) {
                throw Hrm013SafeErrorV1.invalidState();
            }
        }
        if (!roles.equals(Set.of(ServiceRole.CALLER, ServiceRole.OWNER)))
            throw Hrm013SafeErrorV1.invalidState();
        return List.copyOf(observations);
    }

    private ProofParts proofParts(String compact, byte[] proofClaimBytes) {
        String[] segments = compact.split("\\.", -1);
        if (segments.length != 3 || segments[0].isEmpty() || !segments[1].isEmpty()
                || segments[2].isEmpty()) throw Hrm013SafeErrorV1.invalidState();
        byte[] headerBytes = decode(segments[0]);
        ObjectNode header = strictObject(headerBytes, HEADER_FIELDS, false);
        byte[] signature = decode(segments[2]);
        String encodedClaims = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(proofClaimBytes);
        return new ProofParts(header, segments[0] + "." + encodedClaims, signature);
    }

    private static void requireHeader(ObjectNode header, String keyId) {
        if (!"RS256".equals(requiredText(header, "alg"))
                || !PROOF_TYPE.equals(requiredText(header, "typ"))
                || !keyId.equals(requiredText(header, "kid")))
            throw Hrm013SafeErrorV1.invalidState();
    }

    private static void verifySignature(ProofParts proof, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(proof.signingInput().getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(proof.signature())) throw Hrm013SafeErrorV1.invalidState();
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private static PublicKey rsa(RsaPublicJwk jwk) {
        try {
            if (jwk == null || !"RS256".equals(jwk.alg()) || !"RSA".equals(jwk.kty())
                    || !"sig".equals(jwk.use())) throw Hrm013SafeErrorV1.invalidState();
            byte[] modulus = decode(jwk.n()); byte[] exponent = decode(jwk.e());
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(1, modulus),
                            new BigInteger(1, exponent)));
            if (key.getModulus().bitLength() < 2048) throw Hrm013SafeErrorV1.invalidState();
            return key;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private ObjectNode strictObject(byte[] bytes, Set<String> fields, boolean allowOldestNull) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_JSON_BYTES)
            throw Hrm013SafeErrorV1.invalidState();
        try {
            byte[] canonical = Hrm013WorkloadProofV1.rfc8785CanonicalBytes(mapper, bytes);
            if (!Arrays.equals(bytes, canonical)) throw Hrm013SafeErrorV1.invalidState();
            JsonNode parsed = mapper.readTree(canonical);
            if (!(parsed instanceof ObjectNode object) || !fieldNames(object).equals(fields))
                throw Hrm013SafeErrorV1.invalidState();
            object.properties().forEach(entry -> {
                if (entry.getValue().isNull()
                        && !(allowOldestNull && "oldestPendingAt".equals(entry.getKey())))
                    throw Hrm013SafeErrorV1.invalidState();
            });
            return object;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    private static Set<String> fieldNames(ObjectNode value) {
        Set<String> result = new HashSet<>(); value.fieldNames().forEachRemaining(result::add);
        return result;
    }
    static byte[] canonicalBytes(JsonNode value) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                    mapper, mapper.writeValueAsBytes(value));
        } catch (JsonProcessingException invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }
    private static String canonicalDigest(JsonNode value) {
        return Hrm013WorkloadProofV1.sha256(canonicalBytes(value));
    }
    private static byte[] decode(String value) {
        try {
            if (value == null || value.isEmpty() || value.indexOf('=') >= 0
                    || !value.matches("[A-Za-z0-9_-]+"))
                throw Hrm013SafeErrorV1.invalidState();
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value))
                throw Hrm013SafeErrorV1.invalidState();
            return decoded;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }
    private static String text(ObjectNode value, String field) {
        return requiredText(value, field);
    }
    private static String requiredText(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual() || node.textValue().isEmpty())
            throw Hrm013SafeErrorV1.invalidState();
        return node.textValue();
    }
    private static long integer(ObjectNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong())
            throw Hrm013SafeErrorV1.invalidState();
        return node.longValue();
    }
    private static Instant instant(ObjectNode value, String field) {
        String raw = text(value, field);
        try {
            Instant parsed = Instant.parse(raw);
            if (!parsed.toString().equals(raw)) throw Hrm013SafeErrorV1.invalidState();
            return parsed;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }
    private static Instant epoch(ObjectNode value, String field) {
        long raw = integer(value, field);
        try { return Instant.ofEpochSecond(raw); }
        catch (RuntimeException invalid) { throw Hrm013SafeErrorV1.invalidState(); }
    }
    private static UUID uuid(ObjectNode value, String field) {
        String raw = text(value, field);
        try {
            UUID parsed = UUID.fromString(raw);
            if (!parsed.toString().equals(raw)) throw Hrm013SafeErrorV1.invalidState();
            return parsed;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }
    private static ServiceRole role(ObjectNode value, String field) {
        try { return ServiceRole.valueOf(text(value, field)); }
        catch (RuntimeException invalid) { throw Hrm013SafeErrorV1.invalidState(); }
    }
    private static String canonicalInstant(Instant value) {
        return Objects.requireNonNull(value).toString();
    }
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw Hrm013SafeErrorV1.invalidState();
    }
    private record ProofParts(ObjectNode header, String signingInput, byte[] signature) { }
}
