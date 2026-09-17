package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

final class WorkplaceNavigationAdminCommandSupport {
    private final WorkplaceDeviceRepository repository;

    WorkplaceNavigationAdminCommandSupport(WorkplaceDeviceRepository repository) {
        this.repository = repository;
    }

    <T> Attempt<T> begin(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String commandType,
            String resultType,
            Class<T> resultClass,
            Object... fingerprintValues) {
        String key = requireKey(idempotencyKey);
        String fingerprint = fingerprint(commandType, fingerprintValues);
        repository.lockAdminCommand(tenantId, actorId, key);
        WorkplaceDeviceRepository.AdminCommandRow row = repository.adminCommand(
                tenantId, actorId, key).orElse(null);
        if (row == null) {
            return new Attempt<>(tenantId, actorId, key, commandType,
                    resultType, fingerprint, null);
        }
        if (!row.commandType().equals(commandType)
                || !row.requestFingerprint().equals(fingerprint)
                || !row.resultType().equals(resultType)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key was already used for another administrator command.");
        }
        return new Attempt<>(tenantId, actorId, key, commandType, resultType, fingerprint,
                repository.adminCommandResult(row, resultClass));
    }

    <T> void save(
            Attempt<T> attempt,
            String resourceType,
            UUID resourceId,
            T result,
            UUID auditEventId,
            String correlationId,
            OffsetDateTime now) {
        repository.saveAdminCommand(attempt.tenantId(), attempt.actorId(), attempt.commandType(),
                resourceType, resourceId, attempt.idempotencyKey(), attempt.fingerprint(),
                attempt.resultType(), result, auditEventId, correlationId, now);
    }

    static String requireKey(String value) {
        if (value == null || !value.matches("[!-~]{1,160}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "An opaque Idempotency-Key of 1-160 visible ASCII characters is required.");
        }
        return value;
    }

    static void requireConfirmation(boolean confirmed) {
        if (!confirmed) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Explicit confirmation is required.");
        }
    }

    static UUID deterministicId(String value) {
        return UUID.nameUUIDFromBytes(
                ("navigation:" + value).getBytes(StandardCharsets.UTF_8));
    }

    private static String fingerprint(String commandType, Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, commandType);
            for (Object value : values) update(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void update(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update("N;".getBytes(StandardCharsets.UTF_8));
        } else if (value instanceof Map<?, ?> map) {
            digest.update("M{".getBytes(StandardCharsets.UTF_8));
            map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .forEach(entry -> {
                        update(digest, String.valueOf(entry.getKey()));
                        update(digest, entry.getValue());
                    });
            digest.update((byte) '}');
        } else {
            String encoded = String.valueOf(value);
            digest.update((value.getClass().getName() + ":" + encoded.length() + ":")
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(encoded.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ';');
        }
    }

    record Attempt<T>(
            long tenantId,
            long actorId,
            String idempotencyKey,
            String commandType,
            String resultType,
            String fingerprint,
            T replay) { }
}
