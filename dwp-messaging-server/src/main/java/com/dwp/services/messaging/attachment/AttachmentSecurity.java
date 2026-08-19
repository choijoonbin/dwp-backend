package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AttachmentSecurity {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "dll", "com", "bat", "cmd", "msi", "scr", "ps1", "sh", "jar",
            "js", "vbs", "apk", "dmg", "iso", "zip", "rar", "7z", "tar", "gz", "bz2", "xz");
    private static final Map<String, Set<String>> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", Set.of("application/pdf")),
            Map.entry("png", Set.of("image/png")),
            Map.entry("jpg", Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("gif", Set.of("image/gif")),
            Map.entry("txt", Set.of("text/plain")),
            Map.entry("csv", Set.of("text/csv", "text/plain")),
            Map.entry("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
            Map.entry("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
            Map.entry("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")));

    private AttachmentSecurity() {
    }

    static ValidatedMetadata validate(String filename, String contentType, long sizeBytes, int maximumMb) {
        String normalized = Normalizer.normalize(filename == null ? "" : filename, Normalizer.Form.NFKC).strip();
        if (normalized.isEmpty() || normalized.length() > 255 || normalized.contains("/")
                || normalized.contains("\\") || normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid("The attachment filename is not safe.");
        }
        int dot = normalized.lastIndexOf('.');
        if (dot <= 0 || dot == normalized.length() - 1) {
            throw invalid("The attachment filename must have a supported extension.");
        }
        String extension = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.matches("[a-z0-9]{1,20}") || BLOCKED_EXTENSIONS.contains(extension)) {
            throw invalid("Executable and archive attachments are not allowed.");
        }
        String declared = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        Set<String> allowed = CONTENT_TYPES.get(extension);
        if (allowed == null || !allowed.contains(declared)) {
            throw invalid("The attachment content type does not match its extension.");
        }
        long maximumBytes = Math.multiplyExact((long) maximumMb, 1024L * 1024L);
        if (sizeBytes <= 0 || sizeBytes > maximumBytes) {
            throw invalid("The attachment exceeds the tenant size policy.");
        }
        return new ValidatedMetadata(filename, normalized, extension, declared, sizeBytes);
    }

    static String newToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    static String hash(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    static String contentHash(byte[] content) {
        return sha256(content);
    }

    static String requestHash(UUID conversationId, ValidatedMetadata metadata) {
        String value = conversationId + "\n" + metadata.normalizedFilename() + "\n"
                + metadata.contentType() + "\n" + metadata.sizeBytes();
        return hash(value);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    record ValidatedMetadata(
            String originalFilename,
            String normalizedFilename,
            String extension,
            String contentType,
            long sizeBytes) {
    }
}
