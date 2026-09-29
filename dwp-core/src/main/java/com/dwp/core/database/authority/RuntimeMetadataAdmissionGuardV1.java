package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/**
 * Point-in-time metadata admission, distinct from the scalar own-pool ACTIVE lease.
 * The current issuer must linearize its signed nonce/vector against native deployment fence,
 * epoch, own lease and approved source surface. This class does not implement that producer,
 * transport, renewal, Spring registration or thirteen-stream activation. A cached own lease or
 * phase-A signature is NOT metadata authority. No migration credential/factory is accepted.
 */
public final class RuntimeMetadataAdmissionGuardV1 {
    public static final String VERSION = "1.0";
    public static final String PURPOSE = "METADATA_CATALOG_READ_VECTOR";
    public static final String SIGNATURE_DOMAIN = "dwp-runtime-metadata-freshness-claims-v1\n";
    public static final String DOCUMENT_DOMAIN = "dwp-runtime-metadata-freshness-document-v1\n";
    public static final int MAX_DOCUMENT_BYTES = 262_144;
    private static final Duration MAX_LEASE = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    /** Exact source document and policy, including foreign endpoint and compiled ACL approval. */
    public record Slot(String evidenceSha256, RuntimeMetadataCatalogReadEvidenceV1.Claims policy) {
        public Slot { digest(evidenceSha256); require(policy != null, "metadata slot policy missing"); }
    }

    /** A fresh nonce prevents a caller from substituting a previously signed current response. */
    public record CurrentRequest(String schemaVersion, String purpose, String requestId,
            String ownLeaseId, String ownLeaseDocumentSha256, List<Slot> slots) {
        public CurrentRequest {
            require(VERSION.equals(schemaVersion) && PURPOSE.equals(purpose), "metadata current namespace differs");
            uuid(requestId); uuid(ownLeaseId); digest(ownLeaseDocumentSha256);
            slots = ordered(slots, slot -> slot.policy().source().sourceKey(), "metadata current slots");
            require(!slots.isEmpty() && slots.size() <= 4, "metadata current slots empty or excessive");
            var first = slots.getFirst().policy();
            for (Slot slot : slots) require(sameDeployment(first, slot.policy()), "metadata current vector deployment differs");
        }
    }

    public record CurrentClaims(String schemaVersion, String purpose, CurrentRequest request,
            String fenceState, String issuedAt, String expiresAt) {
        public CurrentClaims {
            require(VERSION.equals(schemaVersion) && PURPOSE.equals(purpose) && request != null,
                    "metadata freshness namespace differs");
            require("SERVING".equals(fenceState) && instant(issuedAt).isBefore(instant(expiresAt)),
                    "metadata freshness fence or window invalid");
        }
    }

    public record SignedCurrentProof(CurrentClaims claims, String keyId, String signature) {
        public SignedCurrentProof {
            require(claims != null, "metadata freshness claims missing"); key(keyId); signatureEncoding(signature);
        }
    }

    /** Returns a new signed typed proof, never caller-asserted current=true or raw claims. */
    @FunctionalInterface
    public interface CurrentIssuerPort {
        Optional<String> current(CurrentRequest exactRequest);
        CurrentIssuerPort UNAVAILABLE = request -> Optional.empty();
    }

    /** Not continuing authorization; live renewal/revocation enforcement is outside this API. */
    public record Admission(List<String> sourceKeys, String ownLeaseId, long epoch,
            String freshnessDocumentSha256, Instant validUntil) {
        public Admission {
            sourceKeys = ordered(sourceKeys, value -> value, "metadata admitted sources");
            require(!sourceKeys.isEmpty() && epoch > 0 && validUntil != null, "metadata admission incomplete");
            uuid(ownLeaseId); digest(freshnessDocumentSha256);
        }
    }

    public Optional<Admission> verifySources(Map<String, String> signedEvidenceBySourceKey,
            Map<String, RuntimeMetadataCatalogReadEvidenceVerifierV1.TrustAnchor> activeAnchorsBySourceKey,
            Map<String, DataSource> alreadyRegisteredActivePoolsByQualifier,
            RuntimeOnlyStartupGuard.Admission ownAdmission, RuntimeMetadataPoolInspectionPortV1 inspector,
            CurrentIssuerPort currentIssuer, RuntimeStartupFreshnessPort ownFreshness, Clock deploymentClock) {
        try {
            require(signedEvidenceBySourceKey != null && activeAnchorsBySourceKey != null
                    && alreadyRegisteredActivePoolsByQualifier != null, "metadata active registry missing");
            signedEvidenceBySourceKey = Map.copyOf(signedEvidenceBySourceKey);
            activeAnchorsBySourceKey = Map.copyOf(activeAnchorsBySourceKey);
            alreadyRegisteredActivePoolsByQualifier = Map.copyOf(alreadyRegisteredActivePoolsByQualifier);
            if (signedEvidenceBySourceKey.isEmpty() && activeAnchorsBySourceKey.isEmpty()
                    && alreadyRegisteredActivePoolsByQualifier.isEmpty()) return Optional.empty(); // Disabled means zero opens.
            require(inspector != null && currentIssuer != null && ownFreshness != null && deploymentClock != null
                    && ownAdmission != null, "metadata native and current authority ports required");
            require(!activeAnchorsBySourceKey.isEmpty()
                    && signedEvidenceBySourceKey.keySet().equals(activeAnchorsBySourceKey.keySet()), "metadata active source set differs");
            List<String> keys = activeAnchorsBySourceKey.keySet().stream().sorted().toList();
            List<RuntimeMetadataCatalogReadEvidenceVerifierV1.VerifiedEvidence> verified = new ArrayList<>();
            List<Slot> slots = new ArrayList<>();
            Set<DataSource> pools = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Set<String> qualifiers = new java.util.HashSet<>();
            Instant now = capture(deploymentClock, null);
            var anchor = activeAnchorsBySourceKey.get(keys.getFirst());
            require(anchor != null, "metadata active anchor missing");
            var basis = anchor.expectedClaims();
            verifyOwnAdmission(ownAdmission, basis, now);
            for (String source : keys) {
                var sourceAnchor = activeAnchorsBySourceKey.get(source);
                require(sourceAnchor != null && source.equals(sourceAnchor.expectedClaims().source().sourceKey())
                        && sameDeployment(basis, sourceAnchor.expectedClaims())
                        && anchor.trustedKeys().equals(sourceAnchor.trustedKeys()), "metadata active approvals differ");
                String qualifier = sourceAnchor.expectedClaims().source().qualifier();
                DataSource pool = alreadyRegisteredActivePoolsByQualifier.get(qualifier);
                require(pool != null && pools.add(pool) && qualifiers.add(qualifier), "metadata approved pool missing or repeated");
                String document = signedEvidenceBySourceKey.get(source);
                var evidence = RuntimeMetadataCatalogReadEvidenceJsonV1.parse(document);
                verified.add(RuntimeMetadataCatalogReadEvidenceVerifierV1.verify(document, sourceAnchor, fixed(now)));
                slots.add(new Slot(RuntimeMetadataCatalogReadEvidenceJsonV1.evidenceSha256(evidence), sourceAnchor.expectedClaims()));
            }
            require(qualifiers.equals(alreadyRegisteredActivePoolsByQualifier.keySet()), "metadata unrelated pools supplied");
            var reservation = new RuntimeStartupFreshnessPort.Reservation("provider", basis.deploymentId(),
                    basis.applicationInstanceId(), basis.epoch(), basis.permitId(), basis.startupChallengeSha256(), basis.baseOwnSealSha256());
            var activation = new RuntimeStartupFreshnessPort.Activation(reservation, ownAdmission.leaseId());
            String ownDocument = available(ownFreshness.current(activation));
            now = capture(deploymentClock, now);
            RuntimeStartupLease initialOwn = ownLease(ownDocument, anchor, ownAdmission, now);
            CurrentRequest initialRequest = request(ownAdmission.leaseId(), ownDocument, slots);
            String initialDocument = available(currentIssuer.current(initialRequest));
            now = capture(deploymentClock, now);
            SignedCurrentProof initialVector = currentProof(initialDocument, initialRequest, anchor.trustedKeys(), now,
                    instant(initialOwn.claims().expiresAt()));
            Instant initialExpiry = minimum(instant(initialOwn.claims().expiresAt()), instant(initialVector.claims().expiresAt()));
            recheckEvidence(signedEvidenceBySourceKey, activeAnchorsBySourceKey, keys, now, initialExpiry);
            for (var evidence : verified) {
                var source = evidence.claims().source();
                var observation = inspector.inspect(source, alreadyRegisteredActivePoolsByQualifier.get(source.qualifier()));
                require(observation != null && observation.isPresent(), "metadata native inspection unavailable");
                RuntimeMetadataCatalogReadEvidenceVerifierV1.verifyNativeObservation(evidence, observation.orElseThrow());
                now = capture(deploymentClock, now);
                recheckEvidence(signedEvidenceBySourceKey, activeAnchorsBySourceKey, keys, now, initialExpiry);
            }
            String finalOwnDocument = available(ownFreshness.current(activation));
            now = capture(deploymentClock, now);
            RuntimeStartupLease finalOwn = ownLease(finalOwnDocument, anchor, ownAdmission, now);
            require(!instant(finalOwn.claims().issuedAt()).isBefore(instant(initialOwn.claims().issuedAt())),
                    "metadata own current issuance regressed");
            CurrentRequest finalRequest = request(ownAdmission.leaseId(), finalOwnDocument, slots);
            String finalDocument = available(currentIssuer.current(finalRequest));
            now = capture(deploymentClock, now);
            SignedCurrentProof finalVector = currentProof(finalDocument, finalRequest, anchor.trustedKeys(), now,
                    instant(finalOwn.claims().expiresAt()));
            require(!instant(finalVector.claims().issuedAt()).isBefore(instant(initialVector.claims().issuedAt())),
                    "metadata current issuance regressed");
            // A new signed response cannot revive an expired initial admission or earlier source evidence.
            ownLease(finalOwnDocument, anchor, ownAdmission, now);
            recheckEvidence(signedEvidenceBySourceKey, activeAnchorsBySourceKey, keys, now, initialExpiry);
            Instant validUntil = minimum(initialExpiry, instant(finalVector.claims().expiresAt()));
            for (var evidence : verified) validUntil = minimum(validUntil, instant(evidence.claims().expiresAt()));
            return Optional.of(new Admission(keys, ownAdmission.leaseId(), basis.epoch(), proofSha256(finalVector), validUntil));
        } catch (Exception exception) {
            throw failure("metadata admission rejected"); // No input, pool/clock/provider sentinel or nested cause escapes.
        }
    }

    private static void verifyOwnAdmission(RuntimeOnlyStartupGuard.Admission admission,
            RuntimeMetadataCatalogReadEvidenceV1.Claims basis, Instant now) {
        require("provider".equals(admission.service()) && basis.deploymentId().equals(admission.deploymentId())
                && basis.applicationInstanceId().equals(admission.applicationInstanceId()) && basis.epoch() == admission.epoch()
                && basis.baseOwnSealSha256().equals(admission.sealSha256()) && admission.validUntil() != null
                && now.isBefore(instant(admission.validUntil())), "metadata own admission differs or expired");
        uuid(admission.leaseId());
    }

    private static RuntimeStartupLease ownLease(String document,
            RuntimeMetadataCatalogReadEvidenceVerifierV1.TrustAnchor anchor, RuntimeOnlyStartupGuard.Admission admission, Instant now) {
        RuntimeStartupLease lease = RuntimeStartupSealJson.lease(document);
        require(lease != null, "metadata own current lease missing");
        RuntimeStartupSealSignatureVerifier.verifyLease(lease, anchor.trustedKeys());
        var actual = lease.claims(); var basis = anchor.expectedClaims();
        require(actual.phase().equals("ACTIVE") && actual.service().equals("provider")
                && actual.deploymentId().equals(basis.deploymentId()) && actual.applicationInstanceId().equals(basis.applicationInstanceId())
                && actual.epoch() == basis.epoch() && actual.keyRevision() == basis.keyRevision() && actual.permitId().equals(basis.permitId())
                && actual.startupChallengeSha256().equals(basis.startupChallengeSha256()) && actual.sealSha256().equals(basis.baseOwnSealSha256())
                && actual.leaseId().equals(admission.leaseId()) && actual.fenceState().equals("SERVING")
                && instant(actual.expiresAt()).equals(instant(admission.validUntil()))
                && !instant(actual.issuedAt()).isBefore(instant(anchor.approvedBaseOwnSeal().claims().notBefore()))
                && !instant(actual.expiresAt()).isAfter(instant(anchor.approvedBaseOwnSeal().claims().expiresAt())),
                "metadata own current authority differs");
        window(actual.issuedAt(), actual.expiresAt(), now);
        return lease;
    }

    private static SignedCurrentProof currentProof(String document, CurrentRequest request,
            Map<String, PublicKey> trustedKeys, Instant now, Instant ownExpiry) {
        SignedCurrentProof proof = parse(document);
        try {
            PublicKey key = trustedKeys.get(proof.keyId()); require(key != null, "metadata current signing key untrusted");
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
            verifier.update((SIGNATURE_DOMAIN + canonical(proof.claims())).getBytes(StandardCharsets.UTF_8));
            require(verifier.verify(Base64.getUrlDecoder().decode(proof.signature())), "metadata current signature differs");
        } catch (Exception exception) { throw failure("metadata current signature rejected"); }
        require(proof.claims().request().equals(request), "metadata current nonce, lease or vector differs");
        window(proof.claims().issuedAt(), proof.claims().expiresAt(), now);
        require(!instant(proof.claims().expiresAt()).isAfter(ownExpiry), "metadata vector outlives own lease");
        return proof;
    }

    private static void window(String issuedAt, String expiresAt, Instant now) {
        Instant start = instant(issuedAt); Instant end = instant(expiresAt);
        require(!now.isBefore(start) && now.isBefore(end) && Duration.between(start, end).compareTo(MAX_LEASE) <= 0,
                "metadata current response future, expired or unbounded");
    }

    private static void recheckEvidence(Map<String, String> documents,
            Map<String, RuntimeMetadataCatalogReadEvidenceVerifierV1.TrustAnchor> anchors, List<String> keys,
            Instant now, Instant initialExpiry) {
        require(now.isBefore(initialExpiry), "metadata initial admission expired during inspection");
        for (String key : keys) RuntimeMetadataCatalogReadEvidenceVerifierV1.verify(documents.get(key), anchors.get(key), fixed(now));
    }

    private static boolean sameDeployment(RuntimeMetadataCatalogReadEvidenceV1.Claims left,
            RuntimeMetadataCatalogReadEvidenceV1.Claims right) {
        return left.deploymentId().equals(right.deploymentId()) && left.applicationInstanceId().equals(right.applicationInstanceId())
                && left.epoch() == right.epoch() && left.keyRevision() == right.keyRevision() && left.permitId().equals(right.permitId())
                && left.startupChallengeSha256().equals(right.startupChallengeSha256()) && left.controlReference().equals(right.controlReference())
                && left.baseOwnSealSha256().equals(right.baseOwnSealSha256()) && left.sourceRevision().equals(right.sourceRevision())
                && left.sourceArtifactSha256().equals(right.sourceArtifactSha256()) && left.manifestSha256().equals(right.manifestSha256());
    }

    private static CurrentRequest request(String ownLeaseId, String ownDocument, List<Slot> slots) {
        return new CurrentRequest(VERSION, PURPOSE, UUID.randomUUID().toString(), ownLeaseId,
                sha256("dwp-runtime-own-active-lease-document-v1\n" + canonical(RuntimeStartupSealJson.lease(ownDocument))), slots);
    }

    private static String available(Optional<String> result) {
        require(result != null && result.isPresent(), "metadata current authority unavailable");
        return result.orElseThrow();
    }

    private static Instant capture(Clock clock, Instant previous) {
        Instant now = clock.instant();
        require(now != null && (previous == null || !now.isBefore(previous)), "metadata deployment clock unavailable or regressed");
        return now;
    }

    private static Clock fixed(Instant now) { return Clock.fixed(now, ZoneOffset.UTC); }
    private static Instant minimum(Instant left, Instant right) { return left.isBefore(right) ? left : right; }

    public static SignedCurrentProof parse(String document) {
        require(document != null && !document.isEmpty() && document.length() <= MAX_DOCUMENT_BYTES
                && document.getBytes(StandardCharsets.UTF_8).length <= MAX_DOCUMENT_BYTES, "metadata current document missing or oversized");
        try {
            SignedCurrentProof proof = JSON.readValue(document, SignedCurrentProof.class);
            require(proof != null && canonical(proof).equals(document), "metadata current document not canonical");
            return proof;
        } catch (Exception exception) { throw failure("strict metadata current document rejected"); }
    }

    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw failure("metadata current serialization rejected"); }
    }

    public static String proofSha256(SignedCurrentProof proof) {
        require(proof != null, "metadata signed current proof missing");
        return sha256(DOCUMENT_DOMAIN + canonical(proof));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw failure("metadata current digest unavailable"); }
    }
}
