package com.dwp.services.platform.productivity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.ResourceKind;

abstract class ProductivityServiceSupport {
    private static final SecureRandom RANDOM = new SecureRandom();

    protected static boolean validRedirect(String value) {
        if (isBlank(value)) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getFragment() != null) return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    protected static boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException | NullPointerException exception) {
            return false;
        }
    }

    protected static String randomUrlValue(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    protected static String sha256Url(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    protected static String oauthAad(Long tenantId, Long userId, String stateHash) {
        return "oauth:" + tenantId + ":" + userId + ":" + stateHash;
    }

    protected static String subjectTokenAad(Long tenantId, Long userId, UUID connectorId) {
        return "subject-token:" + tenantId + ":" + userId + ":" + connectorId;
    }

    protected static String cursorAad(
            Long tenantId,
            ProductivityRepository.SubjectRecord subject,
            ResourceKind resourceKind) {
        return "cursor:" + tenantId + ":" + subject.subjectId() + ":" + resourceKind;
    }

    protected static String itemAad(ProductivityRepository.ItemRecord item, String field) {
        return itemAad(
                item.tenantId(), item.userId(), item.connectorId(),
                item.resourceKind(), item.sourceIdHash()) + ":" + field;
    }

    protected static String itemAad(
            Long tenantId,
            Long userId,
            UUID connectorId,
            ResourceKind resourceKind,
            String sourceHash) {
        return "item:" + tenantId + ":" + userId + ":" + connectorId
                + ":" + resourceKind + ":" + sourceHash;
    }

    protected static String safeMessage(String code) {
        return switch (code) {
            case "GRAPH_AUTHENTICATION_REQUIRED", "OAUTH_REQUEST_REJECTED" ->
                    "Microsoft 365 authorization must be renewed.";
            case "GRAPH_RATE_LIMITED" ->
                    "Microsoft Graph asked the connector to retry later.";
            case "GRAPH_CURSOR_RESET_REQUIRED" ->
                    "The provider delta cursor expired and requires a controlled reset.";
            case "GRAPH_UNAVAILABLE" ->
                    "Microsoft Graph is temporarily unavailable.";
            case "GRAPH_ITEM_ID_MISSING", "GRAPH_ITEM_TIME_INVALID" ->
                    "A provider item was skipped because required metadata was invalid.";
            default -> "The productivity connector could not complete this operation.";
        };
    }

    protected static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
