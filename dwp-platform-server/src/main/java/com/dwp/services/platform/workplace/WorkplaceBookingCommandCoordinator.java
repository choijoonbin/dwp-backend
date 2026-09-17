package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

final class WorkplaceBookingCommandCoordinator {

    static final String CHECK_IN = "CHECK_IN";
    static final String CANCEL = "CANCEL";
    static final String RELEASE = "RELEASE";
    static final String RELOCATE = "RELOCATE";

    private final WorkplaceBookingRepository repository;

    WorkplaceBookingCommandCoordinator(WorkplaceBookingRepository repository) {
        this.repository = repository;
    }

    WorkplaceDtos.Booking execute(
            Long tenantId,
            Long actorId,
            UUID bookingId,
            String commandType,
            String idempotencyKey,
            String correlationId,
            List<?> fingerprintValues,
            Runnable replayAuthorization,
            Supplier<Completion> operation) {
        String key = requireIdempotencyKey(idempotencyKey);
        String fingerprint = fingerprint(commandType, bookingId, fingerprintValues);
        repository.lockBookingCommand(tenantId, actorId, key);
        WorkplaceBookingRepository.BookingCommandRow existing = repository
                .bookingCommand(tenantId, actorId, key).orElse(null);
        if (existing != null) {
            if (!existing.bookingId().equals(bookingId)
                    || !existing.commandType().equals(commandType)
                    || !existing.requestFingerprint().equals(fingerprint)) {
                throw conflict("The idempotency key was already used with a different booking command.");
            }
            replayAuthorization.run();
            return existing.result();
        }

        Completion completed = operation.get();
        if (completed == null || completed.result() == null || completed.auditEventId() == null
                || !completed.result().bookingId().equals(bookingId)) {
            throw new IllegalStateException("The Workplace booking command did not produce durable evidence.");
        }
        repository.completeBookingCommand(
                tenantId, actorId, bookingId, commandType, key, fingerprint,
                completed.result(), completed.auditEventId(), correlationId,
                OffsetDateTime.now());
        return completed.result();
    }

    static String requireIdempotencyKey(String value) {
        if (value == null || value.isEmpty()) {
            throw invalid("Idempotency-Key is required.");
        }
        if (value.length() > 160 || value.chars().anyMatch(character ->
                character < 0x21 || character > 0x7e)) {
            throw invalid("Idempotency-Key must contain 1 to 160 visible ASCII characters.");
        }
        return value;
    }

    static String normalizedText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String fingerprint(String commandType, UUID bookingId, List<?> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, commandType);
            append(digest, bookingId);
            for (Object value : values) append(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void append(MessageDigest digest, Object value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        String normalized = value instanceof OffsetDateTime dateTime
                ? dateTime.toInstant().toString()
                : value instanceof TemporalAccessor temporal
                        ? temporal.toString()
                        : value.toString();
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    record Completion(WorkplaceDtos.Booking result, UUID auditEventId) {
    }
}
