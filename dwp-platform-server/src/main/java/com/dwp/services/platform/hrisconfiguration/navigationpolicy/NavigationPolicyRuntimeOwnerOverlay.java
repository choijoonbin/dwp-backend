package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable R6-C historical-owner correction. This catalog never evaluates a permission and
 * therefore cannot grant authority. It can only add a sealed compatibility path after the exact
 * v47 PAGE contract and its canonical path were admitted by the server eligibility provider.
 */
final class NavigationPolicyRuntimeOwnerOverlay {

    static final String REVISION = "R6C_RUNTIME_OWNER_OVERLAY_V1";
    static final String CHECKSUM =
            "42ed206e45fab76f77725ba5d37543e06b8df62da7aa142bb620b032bf552dbd";
    static final long REGISTRY_VERSION = 47;
    static final String REGISTRY_CHECKSUM =
            "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e";

    static final List<OwnerRecord> RECORDS = List.of(
            record(
                    "BASE:team-time", "HRIS-TIM", "dwp-people-server",
                    "/hr/team/time", null, "route.hcm.team.time.page",
                    "GET", "/api/people/v1/hr/team/time", "people", "/v1/hr/team/time",
                    "2ee4cff60ee78cb7ac5d034ea7af0bc7b1782969250e05a2de9fd977e6c8dd48"),
            record(
                    "BASE:team-leave", "HRIS-TIM", "dwp-people-server",
                    "/hr/team/leave", "/hr/team/absence", "route.hcm.team.leave.page",
                    "GET", "/api/people/v1/hr/team/absence", "people",
                    "/v1/hr/team/absence",
                    "71897471bbd7cd0ae24a881c282d92f2c110a0f0ea2ace0cb4596179c474a968"),
            record(
                    "BASE:time-leave-operations", "HRIS-TIM", "dwp-people-server",
                    "/hr/operations/time/leave-operations", "/hr/operations/absence",
                    "route.hcm.operations.time-leave-operations.page", "GET",
                    "/api/people/v1/hr/operations/{domain=ABSENCE}", "people",
                    "/v1/hr/operations/{domain=ABSENCE}",
                    "8bac591712bd9a57a1b8a8269a6ffc5f7d626efef85f2c0bb6be8334b663cb88"),
            record(
                    "BASE:hr-operations-home", "HRIS-PLATFORM", "dwp-people-server",
                    "/hr/operations/home", "/hr/operations",
                    "route.hcm.operations.home.page", "GET",
                    "/api/people/v1/workforce/operations/overview", "people",
                    "/v1/workforce/operations/overview",
                    "9fa6f38cc9a465a0c41ba72335089040a4cb6714950310efa2213727ac2a92f5"));

    private static final Map<String, OwnerRecord> BY_NODE = byNode();

    static {
        if (RECORDS.size() != 4 || BY_NODE.size() != 4
                || RECORDS.stream().anyMatch(value -> !value.evidenceDigest().equals(
                sha256("R6C_OWNER_EVIDENCE_V1|" + value.contractMaterial())))
                || !CHECKSUM.equals(sha256(REVISION + '\n' + RECORDS.stream()
                .map(value -> value.contractMaterial() + '|' + value.evidenceDigest())
                .collect(java.util.stream.Collectors.joining("\n"))))) {
            throw new IllegalStateException("HRIS_NAV_R6C_OWNER_OVERLAY_DIGEST_INVALID");
        }
    }

    private NavigationPolicyRuntimeOwnerOverlay() {
    }

    /**
     * Restricts an already-authorized canonical candidate set. No candidate means no output;
     * unknown paths, route drift, registry drift, or owner drift fail closed.
     */
    static Map<String, List<String>> seal(
            Map<String, List<String>> authorizedCanonicalCandidates,
            Map<String, PageAuthorityContract> exactPageContracts) {
        if (authorizedCanonicalCandidates == null || exactPageContracts == null) {
            throw new IllegalArgumentException("HRIS_NAV_R6C_OWNER_OVERLAY_INPUT_REQUIRED");
        }
        if (!exactPageContracts.keySet().equals(BY_NODE.keySet())
                || !BY_NODE.keySet().containsAll(authorizedCanonicalCandidates.keySet())) {
            throw new IllegalArgumentException("HRIS_NAV_R6C_OWNER_OVERLAY_SET_MISMATCH");
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (OwnerRecord record : RECORDS) {
            PageAuthorityContract contract = exactPageContracts.get(record.nodeId());
            validateContract(record, contract);
            List<String> candidates = authorizedCanonicalCandidates.get(record.nodeId());
            if (candidates == null) continue;
            if (!candidates.equals(List.of(record.canonicalPath()))) {
                throw new IllegalArgumentException(
                        "HRIS_NAV_R6C_OWNER_OVERLAY_CANDIDATE_MISMATCH");
            }
            ArrayList<String> paths = new ArrayList<>();
            paths.add(record.canonicalPath());
            if (record.compatibilityPath() != null) {
                paths.add(record.compatibilityPath());
            }
            result.put(record.nodeId(), List.copyOf(paths));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void validateContract(
            OwnerRecord record, PageAuthorityContract contract) {
        if (contract == null || contract.registryVersion() != REGISTRY_VERSION
                || !REGISTRY_CHECKSUM.equals(contract.registryChecksum())
                || !record.nodeId().equals(contract.nodeId())
                || !record.canonicalPath().equals(contract.canonicalPath())
                || !record.routeContractKey().equals(contract.routeContractKey())
                || !record.method().equals(contract.method())
                || !record.gatewayPath().equals(contract.gatewayPath())
                || !record.serviceKey().equals(contract.serviceKey())
                || !record.servicePath().equals(contract.servicePath())
                || !record.effectiveRuntimeOwner().equals(
                "dwp-" + contract.serviceKey() + "-server")) {
            throw new IllegalArgumentException(
                    "HRIS_NAV_R6C_OWNER_OVERLAY_PAGE_CONTRACT_MISMATCH");
        }
    }

    private static OwnerRecord record(
            String nodeId,
            String historicalOwner,
            String effectiveRuntimeOwner,
            String canonicalPath,
            String compatibilityPath,
            String routeContractKey,
            String method,
            String gatewayPath,
            String serviceKey,
            String servicePath,
            String evidenceDigest) {
        return new OwnerRecord(
                nodeId, historicalOwner, effectiveRuntimeOwner, canonicalPath,
                compatibilityPath, routeContractKey, method, gatewayPath, serviceKey,
                servicePath, REGISTRY_VERSION, REGISTRY_CHECKSUM, evidenceDigest);
    }

    private static Map<String, OwnerRecord> byNode() {
        LinkedHashMap<String, OwnerRecord> result = new LinkedHashMap<>();
        RECORDS.forEach(record -> {
            if (result.putIfAbsent(record.nodeId(), record) != null) {
                throw new IllegalStateException("HRIS_NAV_R6C_OWNER_OVERLAY_NODE_DUPLICATE");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    record PageAuthorityContract(
            String nodeId,
            String canonicalPath,
            String routeContractKey,
            String method,
            String gatewayPath,
            String serviceKey,
            String servicePath,
            long registryVersion,
            String registryChecksum) {
    }

    record OwnerRecord(
            String nodeId,
            String historicalOwner,
            String effectiveRuntimeOwner,
            String canonicalPath,
            String compatibilityPath,
            String routeContractKey,
            String method,
            String gatewayPath,
            String serviceKey,
            String servicePath,
            long registryVersion,
            String registryChecksum,
            String evidenceDigest) {

        String contractMaterial() {
            return String.join("|", nodeId, historicalOwner, effectiveRuntimeOwner,
                    canonicalPath, compatibilityPath == null ? "" : compatibilityPath,
                    routeContractKey, method, gatewayPath, serviceKey, servicePath,
                    Long.toString(registryVersion), registryChecksum);
        }
    }
}
