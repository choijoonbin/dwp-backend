package com.dwp.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared deterministic encoding for the opaque scope keys emitted by Auth and
 * resolved again by an owner-service PEP.
 *
 * <p>The key is deliberately actor and tenant bound. It is an opaque selector,
 * not a bearer credential; the Gateway and owner service must still revalidate
 * current authority for every request.</p>
 */
public final class ProductSurfaceScopeKey {

    private ProductSurfaceScopeKey() {
    }

    public static String resourceSet(
            long tenantId,
            long actorId,
            String productKey,
            String surfaceKey,
            String resourceSetKey) {
        return key(tenantId, actorId, productKey, surfaceKey, resourceSetKey, "RESOURCE_SET");
    }

    public static String key(
            long tenantId,
            long actorId,
            String productKey,
            String surfaceKey,
            String source,
            String kind) {
        if (tenantId <= 0 || actorId <= 0) {
            throw new IllegalArgumentException("tenantId and actorId must be positive.");
        }
        requireText(productKey, "productKey", 120);
        requireText(surfaceKey, "surfaceKey", 160);
        requireText(source, "scope source", 240);
        requireText(kind, "scope kind", 80);
        String material = tenantId + "\n" + actorId + "\n"
                + productKey + "\n" + surfaceKey + "\n" + source + "\n" + kind;
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return "scope-" + digest.substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void requireText(String value, String label, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || !value.equals(value.trim())
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " is not canonical.");
        }
    }
}
