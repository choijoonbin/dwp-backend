package com.dwp.services.platform.widgetregistry.internal.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Public control-plane facade over the same closed manifest validator used at trusted ingress. */
public final class WidgetRegistryManifestContract {
    private WidgetRegistryManifestContract() {}

    public static ValidatedManifest validate(JsonNode manifest) {
        try {
            String ownerProductKey = WidgetRegistryManifestValidator.validate(manifest);
            byte[] canonical = WidgetRegistryCanonicalJson.encode(
                    manifest.toString().getBytes(StandardCharsets.UTF_8));
            return new ValidatedManifest(
                    sha256(canonical),
                    manifest.path("definitionKey").textValue(),
                    ownerProductKey,
                    manifest.path("owner").path("sourceAppResourceKey").textValue(),
                    manifest.path("renderer").path("rendererKey").textValue(),
                    manifest.path("renderer").path("minimumHostApiVersion").intValue(),
                    manifest.path("privacy").path("classification").textValue());
        } catch (WidgetRegistryBindingException exception) {
            throw new IllegalArgumentException("Manifest does not satisfy WidgetManifestV1.", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record ValidatedManifest(
            String manifestHash,
            String definitionKey,
            String ownerProductKey,
            String sourceAppResourceKey,
            String rendererKey,
            int minimumHostApiVersion,
            String classification) {}
}
