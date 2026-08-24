package com.dwp.services.approval.security;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed W1a v2 contract for the seven Approval auditor/legacy-oversight schemas. */
public final class ApprovalProjectionSchemaContract {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Map<String, Definition> DEFINITIONS = Map.of(
            "ApprovalOversightAdminPulseV1", new Definition(
                    "legacy-oversight",
                    "74adb296d5bb6dd47ff11012543bcccaf7fdcb3217fc89f94b82c286ba9e5095"),
            "ApprovalOversightWorkflowV1", new Definition(
                    "legacy-oversight",
                    "165b99298f758b0de7303fffd0af9cbed7d56e9e0ffca96a1071892cf3c9ce8d"),
            "ApprovalOversightFormV1", new Definition(
                    "legacy-oversight",
                    "bb78d6769fd6f8c9e7c4d29d84906978923b31b79786a1b46d1bdb5e9d4ca679"),
            "ApprovalOversightPolicyV1", new Definition(
                    "legacy-oversight",
                    "06d7d41b5223b7d40cda65c295a6fe9322b7122655202574a21947a503a95669"),
            "ApprovalAuditorOperationsV1", new Definition(
                    "auditor",
                    "f04250e9a303775f8494f0a4625141b9da82b08dbd5cf912e9495e4991b774b6"),
            "ApprovalOversightOperationsV1", new Definition(
                    "legacy-oversight",
                    "feb172b3248374f394feb17c030cdcecbb0cb12566fc814f04cfd3b7bcedf384"),
            "ApprovalOversightSignatureV1", new Definition(
                    "legacy-oversight",
                    "5acc481b5d6752be8ab255274bd8ae0d3732fc2a1af40cb9d5b323507be6f13c"));

    private ApprovalProjectionSchemaContract() {
    }

    public static boolean isFieldMaskProfile(String profileKey) {
        return "auditor".equals(profileKey) || "legacy-oversight".equals(profileKey);
    }

    public static boolean matches(
            String profileKey,
            String schemaKey,
            Integer schemaVersion,
            String openApiSchemaSha256,
            Boolean additionalProperties) {
        Definition definition = DEFINITIONS.get(schemaKey);
        return definition != null
                && definition.profileKey().equals(profileKey)
                && Integer.valueOf(1).equals(schemaVersion)
                && openApiSchemaSha256 != null
                && SHA256.matcher(openApiSchemaSha256).matches()
                && definition.openApiSchemaSha256().equals(openApiSchemaSha256)
                && Boolean.FALSE.equals(additionalProperties);
    }

    public static boolean metadataAbsent(
            Integer schemaVersion,
            String openApiSchemaSha256,
            Boolean additionalProperties) {
        return schemaVersion == null
                && openApiSchemaSha256 == null
                && additionalProperties == null;
    }

    public static Set<String> schemaKeys() {
        return DEFINITIONS.keySet();
    }

    public static String expectedSha256(String schemaKey) {
        Definition definition = DEFINITIONS.get(schemaKey);
        return definition == null ? null : definition.openApiSchemaSha256();
    }

    private record Definition(String profileKey, String openApiSchemaSha256) {
    }
}
