package com.dwp.core.database.authority;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Foreign catalog READ purpose only. No owned stream, credentials, activation or absolute data-blind claim. */
public record RuntimeMetadataCatalogReadEvidenceV1(Claims claims, String keyId, String signature) {
    public static final String VERSION = "1.0";
    public static final String PURPOSE = "METADATA_CATALOG_READ";
    public static final String SIGNATURE_DOMAIN = "dwp-runtime-metadata-catalog-read-claims-v1\n";
    public static final String DOCUMENT_DOMAIN = "dwp-runtime-metadata-catalog-read-evidence-v1\n";
    public static final Set<String> SOURCES = Set.of("auth", "people", "platform", "provider");

    public RuntimeMetadataCatalogReadEvidenceV1 {
        require(claims != null, "metadata claims required"); key(keyId); signatureEncoding(signature);
    }

    public record Claims(String schemaVersion, String purpose, String topologyRevision, String consumerService,
            String deploymentId, String applicationInstanceId, long epoch, long keyRevision, String permitId,
            String startupChallengeSha256, String controlReference, String baseOwnSealSha256,
            String sourceRevision, String sourceArtifactSha256, String manifestSha256,
            String notBefore, String expiresAt, ExpectedSource source) {
        public Claims {
            require(VERSION.equals(schemaVersion) && PURPOSE.equals(purpose)
                    && ScalarNineRuntimeRegistryContract.TOPOLOGY.equals(topologyRevision)
                    && "provider".equals(consumerService), "metadata namespace or consumer differs");
            uuid(deploymentId); uuid(applicationInstanceId); uuid(permitId);
            require(epoch > 0 && keyRevision > 0, "metadata epoch or key revision invalid");
            RuntimeStartupValues.controlReference(controlReference);
            digest(startupChallengeSha256); digest(baseOwnSealSha256); digest(sourceArtifactSha256); digest(manifestSha256);
            require(sourceRevision != null && sourceRevision.matches("[0-9a-f]{40}"), "metadata source revision invalid");
            require(instant(notBefore).isBefore(instant(expiresAt)), "metadata validity window invalid");
            require(source != null, "metadata source required");
        }
    }

    public record ExpectedSource(String sourceKey, String ownerService, String qualifier, String principal,
            String database, String trustedEndpointId, String serverAddress, int serverPort, String postgresVersion,
            boolean readOnly, boolean autoCommit, List<String> searchPath, String compilerVersion,
            String historyPolicySha256, String privilegeSurfaceSha256) {
        public ExpectedSource {
            require(SOURCES.contains(sourceKey) && sourceKey.equals(ownerService), "metadata source slot differs");
            RuntimeStartupValues.qualifier(qualifier); identifier(principal); identifier(database); key(trustedEndpointId);
            require(principal.equals("dwp_provider_metadata_" + (sourceKey.equals("provider") ? "self" : sourceKey)),
                    "metadata principal cannot be PRIMARY or foreign slot");
            require(database.equals("dwp_" + ownerService) || database.matches("dwp_" + ownerService + "_[a-z][a-z0-9_]{0,31}"),
                    "metadata catalog owner differs");
            require(serverAddress != null && serverAddress.matches("[0-9a-f.:]{2,64}")
                    && serverPort > 0 && serverPort <= 65535, "metadata server endpoint invalid");
            require(postgresVersion != null && postgresVersion.matches("[1-9][0-9]*(?:\\.[0-9]+){0,2}"), "metadata postgres version invalid");
            searchPath = List.copyOf(Objects.requireNonNull(searchPath));
            require(readOnly && autoCommit && searchPath.equals(List.of("pg_catalog")), "metadata pool posture invalid");
            require(PrivilegeSurfaceCompilerV1.VERSION.equals(compilerVersion), "metadata compiler version differs");
            digest(historyPolicySha256); digest(privilegeSurfaceSha256);
        }
    }
}
