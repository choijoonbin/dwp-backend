package com.dwp.core.database.authority;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/**
 * Signed external native verification evidence, NOT a Control producer or deployment approval.
 * No credential, migration supplier, JDBC URL, endpoint factory or Flyway input is accepted.
 * Scalar nine-stream and successor thirteen-stream policies remain separate external trust anchors.
 */
public record RuntimeStreamStartupSeal(Claims claims, String keyId, String signature) {
    public static final String VERSION = "1.0";

    public RuntimeStreamStartupSeal {
        require(claims != null, "seal claims missing"); key(keyId);
        signatureEncoding(signature);
    }

    public record Claims(String schemaVersion, String topologyRevision, String service,
            String deploymentId, String applicationInstanceId, long epoch, long keyRevision,
            String permitId, String startupChallengeSha256, String controlReference,
            String sourceRevision, String sourceArtifactSha256, String manifestSha256,
            String controlReceiptSha256, String notBefore, String expiresAt,
            List<StreamEvidence> streams, List<RuntimePurpose> runtimePurposes) {
        public Claims {
            require(VERSION.equals(schemaVersion), "unsupported seal version");
            key(topologyRevision); identifier(service); uuid(deploymentId); uuid(applicationInstanceId);
            require(epoch > 0 && keyRevision > 0, "positive deployment/key epoch required");
            uuid(permitId); digest(startupChallengeSha256); RuntimeStartupValues.controlReference(controlReference);
            require(sourceRevision != null && sourceRevision.matches("[0-9a-f]{40}"), "invalid source revision");
            digest(sourceArtifactSha256); digest(manifestSha256); digest(controlReceiptSha256);
            require(instant(notBefore).isBefore(instant(expiresAt)), "seal time window invalid");
            streams = ordered(streams, StreamEvidence::streamKey, "seal streams");
            runtimePurposes = ordered(runtimePurposes, RuntimePurpose::bindingKey, "runtime purposes");
            require(!streams.isEmpty() && !runtimePurposes.isEmpty(), "enabled runtime evidence missing");
            Set<String> streamKeys = streams.stream().map(StreamEvidence::streamKey)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> covered = new HashSet<>();
            Set<String> qualifiers = new HashSet<>();
            Set<String> histories = new HashSet<>();
            for (StreamEvidence stream : streams) require(histories.add(stream.database() + ":"
                    + stream.schema() + ":" + stream.historyTable()), "native history identity reused");
            Set<String> owners = streams.stream().map(StreamEvidence::migrationPrincipal)
                    .collect(java.util.stream.Collectors.toSet());
            for (RuntimePurpose runtime : runtimePurposes) {
                require(streamKeys.containsAll(runtime.streamKeys()), "runtime refers to unknown stream");
                require(qualifiers.add(runtime.qualifier()), "runtime qualifier reused");
                require(!owners.contains(runtime.principal()), "migration owner cannot be a runtime principal");
                if (runtime.purposes().contains("INSIGHTS_QUERY") || runtime.purposes().contains("INSIGHTS_EXECUTION_WRITE")) {
                    require(runtimePurposes.stream().filter(other -> other.principal().equals(runtime.principal())).count() == 1,
                            "Insights query/execution principal must be separate");
                }
                Set<String> coveredSchemas = new HashSet<>();
                for (String streamKey : runtime.streamKeys()) {
                    StreamEvidence stream = streams.stream().filter(s -> s.streamKey().equals(streamKey))
                            .findFirst().orElseThrow();
                    require(runtime.database().equals(stream.database())
                            && runtime.schemas().contains(stream.schema()), "runtime stream/catalog boundary differs");
                    coveredSchemas.add(stream.schema());
                }
                require(coveredSchemas.equals(new HashSet<>(runtime.schemas())), "runtime includes an unsealed schema");
                covered.addAll(runtime.streamKeys());
            }
            require(covered.equals(streamKeys), "stream lacks a runtime binding");
        }
    }

    public record StreamEvidence(String streamKey, String database, String schema,
            String historyTable, String migrationPrincipal, String provenanceMode,
            String adoptionReceiptSha256, String postgresVersion, int pendingMigrationCount, int historyRowCount,
            int historyMaxInstalledRank, String historySha256, int inventoryObjectCount,
            String definitionAclOwnershipSha256, boolean temporaryPrivilegeRevoked) {
        public StreamEvidence {
            key(streamKey); identifier(database); identifier(schema); identifier(historyTable);
            identifier(migrationPrincipal);
            require(Set.of("NATIVE_PREAPPLIED", "ADOPTED_PREAPPLIED").contains(provenanceMode),
                    "invalid native/adoption mode");
            if ("NATIVE_PREAPPLIED".equals(provenanceMode)) {
                require("".equals(adoptionReceiptSha256), "native/adopted evidence is mutually exclusive");
            } else digest(adoptionReceiptSha256);
            require(postgresVersion != null && postgresVersion.matches("[1-9][0-9]*(?:\\.[0-9]+){0,2}"),
                    "invalid PostgreSQL version");
            require(pendingMigrationCount == 0 && historyRowCount >= 0 && historyMaxInstalledRank >= historyRowCount
                    && (historyRowCount != 0 || historyMaxInstalledRank == 0)
                    && inventoryObjectCount > 0, "preapplied full native evidence required");
            digest(historySha256); digest(definitionAclOwnershipSha256);
            require(temporaryPrivilegeRevoked, "steady-state migration TEMP must be denied");
        }
    }

    /**
     * A binding can cover approved existing scalar stream aliases; it does NOT assert distinct roles.
     * The independent deployment policy must exactly approve its purposes, schemas and surface hash.
     */
    public record RuntimePurpose(String bindingKey, List<String> purposes, List<String> streamKeys,
            String qualifier, String principal, String database, String trustedEndpointId,
            String serverAddress, int serverPort, boolean readOnly, List<String> schemas,
            List<String> searchPath, String privilegeSurfaceSha256) {
        public RuntimePurpose {
            key(bindingKey); RuntimeStartupValues.qualifier(qualifier); identifier(principal); identifier(database);
            key(trustedEndpointId);
            require(serverAddress != null && serverAddress.matches("[0-9a-f.:]{2,64}")
                    && serverPort > 0 && serverPort <= 65535, "invalid trusted server identity");
            purposes = ordered(purposes, v -> v, "binding purposes");
            require(!purposes.isEmpty() && Set.of("PRIMARY", "PERFORMANCE", "CONFIGURATION",
                    "INSIGHTS_QUERY", "INSIGHTS_EXECUTION_WRITE", "LISTENING_ADMISSION",
                    "PARTICIPATION_ISSUER").containsAll(purposes), "unknown runtime purpose");
            require(!purposes.contains("INSIGHTS_QUERY") || (readOnly && purposes.size() == 1),
                    "Insights query must be a separate read-only binding");
            require(!purposes.contains("INSIGHTS_EXECUTION_WRITE") || (!readOnly && purposes.size() == 1),
                    "Insights execution must be a separate write binding");
            streamKeys = ordered(streamKeys, v -> v, "binding stream keys");
            streamKeys.forEach(RuntimeStartupValues::key);
            schemas = ordered(schemas, v -> v, "binding schemas");
            schemas.forEach(RuntimeStartupValues::identifier);
            require(!streamKeys.isEmpty() && !schemas.isEmpty(), "runtime stream/schema boundary missing");
            searchPath = List.copyOf(searchPath); searchPath.forEach(RuntimeStartupValues::identifier);
            require(!searchPath.isEmpty() && searchPath.getFirst().equals("pg_catalog")
                    && new HashSet<>(searchPath).size() == searchPath.size()
                    && schemas.containsAll(searchPath.subList(1, searchPath.size())),
                    "runtime search path must be pg_catalog plus an exact approved schema subset");
            digest(privilegeSurfaceSha256);
        }
    }
}
