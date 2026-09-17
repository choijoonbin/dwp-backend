package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;

/** Stateless validation and identity helpers shared by the device orchestration paths. */
final class WorkplaceDeviceSupport {
    private WorkplaceDeviceSupport() { }

    static List<String> impact(DeviceCommandType type) {
        return switch (type) {
            case FORCE_SYNC -> List.of("Schedule and policy projections will refresh.");
            case CLEAR_CACHE -> List.of("Local non-secret display cache will be cleared.");
            case REBOOT -> List.of("The device surface will be temporarily unavailable.");
            case SAFETY_TAKEOVER -> List.of("The normal surface will be replaced by a safety notice.");
            case CLEAR_SAFETY -> List.of("The active safety notice will be cleared.");
            case UNBIND -> List.of("The device will lose its site, floor and resource binding.");
        };
    }

    static void validatePayload(DeviceCommandType commandType, Map<String, String> payload) {
        if (payload == null) throw invalid("A device command payload is required.");
        if (commandType != DeviceCommandType.SAFETY_TAKEOVER) {
            if (!payload.isEmpty()) {
                throw invalid("This device command does not accept payload fields.");
            }
            return;
        }
        if (!payload.keySet().equals(java.util.Set.of("message", "direction"))) {
            throw invalid("Safety takeover accepts only message and direction.");
        }
        String message = payload.get("message");
        String direction = payload.get("direction");
        if (message == null || message.isBlank() || message.length() > 1000
                || direction == null || direction.isBlank() || direction.length() > 500) {
            throw invalid("Safety takeover message and direction must be bounded non-blank text.");
        }
    }

    static DeviceCommandState state(OutcomeState state) {
        return switch (state) {
            case SUCCEEDED -> DeviceCommandState.SUCCEEDED;
            case FAILED -> DeviceCommandState.FAILED;
            case RESULT_UNKNOWN -> DeviceCommandState.RESULT_UNKNOWN;
        };
    }

    static String identityHash(String identity) {
        if (identity == null || identity.length() < 32 || identity.length() > 512) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A bounded device identity is required.");
        }
        return fingerprint(identity);
    }

    static boolean matchesIdentityHash(String expectedHash, String identity) {
        byte[] expected = expectedHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = identityHash(identity).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw new BaseException(ErrorCode.UNAUTHORIZED,
                "A positive actor identifier is required.");
    }

    static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant identifier is required.");
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }
}
