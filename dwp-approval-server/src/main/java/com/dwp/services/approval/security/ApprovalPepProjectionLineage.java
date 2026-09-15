package com.dwp.services.approval.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/** Pinned release envelopes, independent of runtime authority evaluation. */
final class ApprovalPepProjectionLineage {
    static final String W1A_V2_CHECKSUM =
            "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c";
    static final String WORK_V7_CHECKSUM =
            "fe9721ef01164c64e03f8798f89765bdf35e55993cf98ad1f6f9c3611dd8d61a";
    static final String DOCUMENT_V8_CHECKSUM =
            "9449a516a2dbd96106e71963cbda764b80d83f0f61fa861d110d85517adac942";
    static final String EXTENSION_V9_CHECKSUM =
            "02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7";
    static final String RELEASE10_CHECKSUM =
            "1f97638c95a192f0ec7f01053c3965f79b7a3ee4eb9781ea56e3cf8eccc6889b";
    static final String RECOVERY11_CHECKSUM =
            "e9a32c9312feb325db1294e3c00d34a110474a48fba16399eb1fc52b39fc9043";
    static final String RELEASE12_CHECKSUM =
            "65155dcc88f454a0ad2530518f8ec9b0c070afd31d583a19f980dd3d10f78a74";
    static final String RELEASE13_CHECKSUM =
            "3bd67d7b145c5b7c845788c70f8884c8afadedd9920de419ecd1e1d0e8a4c8b0";
    static final String RELEASE14_CHECKSUM =
            "7ee0bac12ddfbc72dda55a5014c67b0798caa68a5ffc73b4be479d06a4590336";
    static final String BASELINE_PROJECTION_CHECKSUM =
            "b6b3d9fa5b4d296d333d05b03c001252a93b229d112322ee49425d26f7f6f83f";
    static final String WORK_PROJECTION_CHECKSUM =
            "5c4901e1d25806cde9448d4f9f5d66040880f4b45fa85c13c2409393bf18a055";
    private static final Map<Integer, Release> RELEASES = Map.of(
            2, new Release(W1A_V2_CHECKSUM, BASELINE_PROJECTION_CHECKSUM, 76, 39, 47),
            7, new Release(WORK_V7_CHECKSUM, WORK_PROJECTION_CHECKSUM, 258, 47, 55),
            8, new Release(DOCUMENT_V8_CHECKSUM,
                    "a2756f33c1ada00ad8355acb95c3534e71b332bbcedf21622b6b4734011ef38e", 275, 64, 72),
            9, new Release(EXTENSION_V9_CHECKSUM,
                    "42eed3ca14abd7fcd5b62f2f3714dd8b62aabaab9b07348cadd33ace829b02bf", 302, 91, 99),
            10, new Release(RELEASE10_CHECKSUM,
                    "aba923415db4dd09b272652f4486273226cd788e8657806db4722cb552d3e6a4", 318, 107, 115),
            11, new Release(RECOVERY11_CHECKSUM,
                    "8fc413036f39f946ab95b5d553d4ed9dfbb5867323890a1d58e74a7099c36ca9", 323, 112, 120),
            12, new Release(RELEASE12_CHECKSUM,
                    "9bfb0c0516c9e4c12ea5ad8be4240b0d2f4b47cd406f87e1e3db6e6886755056", 352, 141, 149),
            13, new Release(RELEASE13_CHECKSUM,
                    "6e0d8f1b0e1488822704a7886d7acfa058f5eb100ed0ae228a483287fbd08303", 355, 144, 152),
            14, new Release(RELEASE14_CHECKSUM,
                    "5797318cfe765d77f89d99837d4da70e0452e97a517b99e0d7aa9a0f806a00d7", 360, 149, 157));

    private ApprovalPepProjectionLineage() { }

    static void validateEnvelope(ObjectMapper mapper, ObjectNode projection, boolean baselineOnly) {
        validateEnvelope(mapper, projection, baselineOnly ? 2 : 7);
    }

    static void validateEnvelope(ObjectMapper mapper, ObjectNode projection, int version) {
        Release release = RELEASES.get(version);
        require(release != null, "Unsupported Approval PEP release");
        require(projection.path("schemaVersion").asInt() == 1
                        && ("approval-pilot-pep-v" + version)
                        .equals(projection.path("projectionKey").asText())
                        && "approval".equals(projection.path("ownerServiceKey").asText()),
                "Unexpected Approval Pilot PEP envelope");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == version
                        && release.registryChecksum().equals(registry.path("sha256").asText()),
                "Approval Pilot registry reference mismatch");
        require(projection.path("sourceRegistryRouteCount").asInt() == release.sourceRoutes()
                        && projection.path("projectedRouteContractCount").asInt()
                        == release.routes()
                        && projection.path("routes").isArray()
                        && projection.path("routes").size() == release.routes()
                        && projection.path("bindingPairCount").asInt() == release.bindings(),
                "Approval Pilot release counts changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null && checksum.asText().equals(sha256(mapper, payload))
                        && checksum.asText().equals(release.projectionChecksum()),
                "Approval Pilot projection checksum mismatch");
    }

    static void validateSuperset(ObjectNode baseline, ObjectNode projection) {
        Set<String> baselineRoutes = index(baseline, "routes", "routeContractKey").keySet();
        for (String section : List.of("capabilities", "accessPolicies",
                "entitlementExpressions", "predicatePolicies", "routes")) {
            String key = switch (section) {
                case "capabilities" -> "contractKey";
                case "accessPolicies" -> "accessPolicyKey";
                case "entitlementExpressions" -> "expressionKey";
                case "predicatePolicies" -> "predicatePolicyKey";
                default -> "routeContractKey";
            };
            Map<String, JsonNode> current = index(projection, section, key);
            for (JsonNode original : baseline.path(section)) {
                JsonNode value = current.get(original.path(key).asText());
                require(value instanceof ObjectNode, "Approval baseline descriptor dropped");
                ObjectNode before = ((ObjectNode) original).deepCopy();
                ObjectNode after = ((ObjectNode) value).deepCopy();
                if (before.has("routeContractKeys")) {
                    Set<String> retained = textValues(before.path("routeContractKeys"));
                    retained.retainAll(baselineRoutes);
                    require(textValues(after.path("routeContractKeys")).containsAll(retained),
                            "Approval baseline reverse-reference dropped");
                    before.remove("routeContractKeys");
                    after.remove("routeContractKeys");
                }
                require(before.equals(after), "Approval immutable baseline descriptor changed");
            }
        }
    }

    private static Map<String, JsonNode> index(ObjectNode root, String section, String key) {
        require(root.path(section).isArray(), "Approval descriptor array missing");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        root.path(section).forEach(value -> require(value.isObject() && value.path(key).isTextual()
                        && !value.path(key).asText().isBlank()
                        && result.putIfAbsent(value.path(key).asText(), value) == null,
                "Approval descriptor key missing or duplicated"));
        return result;
    }

    private static Set<String> textValues(JsonNode value) {
        require(value.isArray(), "Expected a generated string array");
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "Invalid generated string array"));
        return result;
    }

    private record Release(String registryChecksum, String projectionChecksum,
                           int sourceRoutes, int routes, int bindings) { }

    static String sha256(ObjectMapper mapper, JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(canonical(mapper, value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Approval Pilot checksum failed.", exception);
        }
    }

    private static JsonNode canonical(ObjectMapper mapper, JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(mapper, value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(canonical(mapper, item)));
            return result;
        }
        return value.deepCopy();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
