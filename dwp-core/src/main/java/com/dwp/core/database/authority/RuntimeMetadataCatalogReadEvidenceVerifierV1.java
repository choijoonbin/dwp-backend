package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Signature/external policy proof only, not a reserve/activate freshness composition or native provider. */
public final class RuntimeMetadataCatalogReadEvidenceVerifierV1 {
    private RuntimeMetadataCatalogReadEvidenceVerifierV1() { }

    public static VerifiedEvidence verify(String document, TrustAnchor anchor, Clock deploymentClock) {
        require(anchor != null && deploymentClock != null, "metadata independent anchor and clock required");
        var evidence = RuntimeMetadataCatalogReadEvidenceJsonV1.parse(document);
        verifySignature(evidence, anchor.trustedKeys());
        RuntimeStartupSealSignatureVerifier.verifySeal(anchor.approvedBaseOwnSeal(), anchor.trustedKeys());
        require(evidence.claims().equals(anchor.expectedClaims()), "metadata independent deployment policy differs");
        Instant now;
        try { now = deploymentClock.instant(); require(now != null, "metadata clock unavailable"); }
        catch (Exception exception) { throw failure("metadata clock unavailable"); }
        Instant start = instant(evidence.claims().notBefore()); Instant end = instant(evidence.claims().expiresAt());
        require(!now.isBefore(start) && now.isBefore(end)
                && Duration.between(start, end).compareTo(anchor.maximumEvidenceLifetime()) <= 0,
                "metadata evidence expired, future or unbounded");
        return new VerifiedEvidence(evidence.claims());
    }

    public static void verifyNativeObservation(VerifiedEvidence externallyVerifiedEvidence,
            RuntimeMetadataPoolInspectionPortV1.NativeObservation observation) {
        require(externallyVerifiedEvidence != null && observation != null, "metadata native proof unavailable");
        var expected = externallyVerifiedEvidence.claims().source();
        require(expected.principal().equals(observation.originalJdbcLogin())
                && expected.principal().equals(observation.currentUser()) && expected.principal().equals(observation.sessionUser())
                && expected.database().equals(observation.database()) && expected.trustedEndpointId().equals(observation.trustedEndpointId())
                && expected.serverAddress().equals(observation.serverAddress()) && expected.serverPort() == observation.serverPort()
                && expected.postgresVersion().equals(observation.postgresVersion())
                && expected.readOnly() == observation.readOnly() && expected.autoCommit() == observation.autoCommit()
                && expected.searchPath().equals(observation.searchPath()) && expected.compilerVersion().equals(observation.compilerVersion())
                && expected.historyPolicySha256().equals(observation.historyPolicySha256())
                && expected.privilegeSurfaceSha256().equals(observation.privilegeSurfaceSha256()), "metadata native identity or surface differs");
        require(observation.postgresVersion() != null && observation.postgresVersion().matches("[1-9][0-9]*(?:\\.[0-9]+){0,2}")
                && observation.directLogin() && observation.roleSettingNone() && observation.replicationRoleOrigin()
                && !observation.elevatedRoleAttributes() && !observation.anyMembership() && !observation.databaseCreate()
                && !observation.databaseTemporary() && !observation.foreignDatabaseConnect() && !observation.anySchemaCreate()
                && !observation.anyObjectOwnership() && !observation.anyHistoryPrivilege() && !observation.applicationRelationPrivilege()
                && !observation.applicationColumnPrivilege() && !observation.applicationSequencePrivilege()
                && !observation.applicationRoutineExecute() && !observation.anyGrantOption() && !observation.catalogMutationAuthority(),
                "metadata native authority exceeds catalog read purpose");
    }

    /** Cannot be constructed from raw claims. Not a live lease, admission or continuing authorization. */
    public static final class VerifiedEvidence {
        private final RuntimeMetadataCatalogReadEvidenceV1.Claims claims;
        private VerifiedEvidence(RuntimeMetadataCatalogReadEvidenceV1.Claims claims) { this.claims = claims; }
        public RuntimeMetadataCatalogReadEvidenceV1.Claims claims() { return claims; }
    }

    public record TrustAnchor(RuntimeMetadataCatalogReadEvidenceV1.Claims expectedClaims,
            RuntimeStreamStartupSeal approvedBaseOwnSeal, String externallyExpectedBaseOwnSealSha256, Map<String, PublicKey> trustedKeys,
            Duration maximumEvidenceLifetime) {
        public TrustAnchor {
            require(expectedClaims != null && trustedKeys != null && !trustedKeys.isEmpty(), "metadata external policy required");
            require(approvedBaseOwnSeal != null, "metadata base own seal required");
            digest(externallyExpectedBaseOwnSealSha256);
            require(RuntimeStartupSealJson.sealSha256(approvedBaseOwnSeal).equals(externallyExpectedBaseOwnSealSha256)
                    && expectedClaims.baseOwnSealSha256().equals(externallyExpectedBaseOwnSealSha256), "metadata external base own seal differs");
            var base = approvedBaseOwnSeal.claims();
            require(base.service().equals("provider") && base.topologyRevision().equals(expectedClaims.topologyRevision())
                    && base.deploymentId().equals(expectedClaims.deploymentId()) && base.applicationInstanceId().equals(expectedClaims.applicationInstanceId())
                    && base.epoch() == expectedClaims.epoch() && base.keyRevision() == expectedClaims.keyRevision()
                    && base.permitId().equals(expectedClaims.permitId()) && base.startupChallengeSha256().equals(expectedClaims.startupChallengeSha256())
                    && base.controlReference().equals(expectedClaims.controlReference()) && base.sourceRevision().equals(expectedClaims.sourceRevision())
                    && base.sourceArtifactSha256().equals(expectedClaims.sourceArtifactSha256()) && base.manifestSha256().equals(expectedClaims.manifestSha256())
                    && base.streams().stream().map(RuntimeStreamStartupSeal.StreamEvidence::streamKey).toList().equals(java.util.List.of("provider-main"))
                    && base.runtimePurposes().size() == 1 && base.runtimePurposes().get(0).purposes().equals(java.util.List.of("PRIMARY")),
                    "metadata base own deployment identity differs");
            var ownRuntime = base.runtimePurposes().get(0);
            require(base.streams().get(0).migrationPrincipal().equals("dwp_provider_migration")
                    && base.streams().get(0).schema().equals("public")
                    && base.streams().get(0).historyTable().equals("flyway_schema_history")
                    && ownRuntime.bindingKey().equals("provider-runtime")
                    && ownRuntime.principal().equals("dwp_provider_runtime") && !ownRuntime.readOnly()
                    && ownRuntime.streamKeys().equals(java.util.List.of("provider-main"))
                    && ownRuntime.database().equals(base.streams().get(0).database())
                    && ownRuntime.schemas().equals(java.util.List.of("public"))
                    && ownRuntime.searchPath().equals(java.util.List.of("pg_catalog", "public")), "metadata cannot substitute base PRIMARY authority");
            String ownDatabase = base.streams().get(0).database();
            require(ownDatabase.equals("dwp_provider") || ownDatabase.matches("dwp_provider_[a-z][a-z0-9_]{0,31}"),
                    "metadata base own catalog invalid");
            require(!instant(expectedClaims.notBefore()).isBefore(instant(base.notBefore()))
                    && !instant(expectedClaims.expiresAt()).isAfter(instant(base.expiresAt()))
                    && Duration.between(instant(base.notBefore()), instant(base.expiresAt())).compareTo(Duration.ofMinutes(5)) <= 0,
                    "metadata outlives or uses unbounded base own seal");
            String suffix = ownDatabase.substring("dwp_provider".length());
            require(base.streams().get(0).database().equals("dwp_provider" + suffix)
                    && expectedClaims.source().database().equals("dwp_" + expectedClaims.source().ownerService() + suffix),
                    "metadata cross instance source forbidden");
            trustedKeys = Map.copyOf(trustedKeys);
            trustedKeys.forEach((id, key) -> { RuntimeStartupValues.key(id); require(key != null
                    && (key.getAlgorithm().equals("Ed25519") || key.getAlgorithm().equals("EdDSA")), "metadata trust key invalid"); });
            require(maximumEvidenceLifetime != null && !maximumEvidenceLifetime.isNegative() && !maximumEvidenceLifetime.isZero()
                    && maximumEvidenceLifetime.compareTo(Duration.ofMinutes(5)) <= 0, "metadata lifetime policy invalid");
        }
    }

    private static void verifySignature(RuntimeMetadataCatalogReadEvidenceV1 evidence, Map<String, PublicKey> keys) {
        try {
            PublicKey key = keys.get(evidence.keyId()); require(key != null, "metadata signing key not trusted");
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
            verifier.update((RuntimeMetadataCatalogReadEvidenceV1.SIGNATURE_DOMAIN
                    + RuntimeMetadataCatalogReadEvidenceJsonV1.canonical(evidence.claims())).getBytes(StandardCharsets.UTF_8));
            require(verifier.verify(Base64.getUrlDecoder().decode(evidence.signature())), "metadata signature rejected");
        } catch (Exception exception) { throw failure("metadata signature rejected"); }
    }
}
