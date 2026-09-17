package com.dwp.services.platform.dwaion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Map;
import java.util.UUID;

/** Trusted bridge contracts for a reviewed DWAI-ON proposal and its owning domain command. */
public final class PlatformDwaionHandoff {
    private static final Map<String, String> DOMAINS = Map.of(
            "CALENDAR.EVENT.CREATE", "CALENDAR",
            "MAIL.DRAFT.CREATE", "MAIL",
            "SERVICE.REQUEST.CREATE", "SERVICE");
    private static final Map<String, String> OPERATIONS = Map.of(
            "CALENDAR.EVENT.CREATE", "EVENT_CREATE",
            "MAIL.DRAFT.CREATE", "DRAFT_CREATE",
            "SERVICE.REQUEST.CREATE", "REQUEST_CREATE");

    private PlatformDwaionHandoff() {
    }

    public record Binding(
            int version,
            UUID handoffId,
            UUID proposalId,
            String actionKey,
            long handoffVersion) {

        public Binding {
            if (version != 1 || handoffId == null || proposalId == null
                    || !DOMAINS.containsKey(actionKey) || handoffVersion < 1) {
                throw invalid();
            }
        }

        public static Binding optional(
                UUID handoffId,
                UUID proposalId,
                String actionKey,
                Long handoffVersion,
                String expectedAction) {
            if (handoffId == null && proposalId == null && actionKey == null
                    && handoffVersion == null) return null;
            if (handoffId == null || proposalId == null || actionKey == null
                    || handoffVersion == null || !actionKey.equals(expectedAction)) {
                throw invalid();
            }
            return new Binding(1, handoffId, proposalId, actionKey, handoffVersion);
        }
    }

    public record Identity(
            String authSessionId,
            UUID personPublicId,
            String roles,
            String permissions) {

        public Identity {
            if (!canonical(authSessionId, 160)) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "A verified authentication session is required for the DWAI-ON handoff.");
            }
        }
    }

    public record Effect(
            String domain,
            String operation,
            UUID resourceId,
            long resourceVersion,
            String status) {

        public Effect {
            if (domain == null || operation == null || resourceId == null
                    || resourceVersion < 0 || !canonical(status, 40)) {
                throw invalid();
            }
        }

        public static Effect forBinding(
                Binding binding,
                UUID resourceId,
                long resourceVersion,
                String status) {
            if (binding == null) return null;
            return new Effect(
                    DOMAINS.get(binding.actionKey()),
                    OPERATIONS.get(binding.actionKey()),
                    resourceId,
                    resourceVersion,
                    status);
        }
    }

    static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= maximum && value.indexOf(',') < 0
                && value.codePoints().noneMatch(character -> character < 32 || character == 127);
    }

    static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The DWAI-ON proposal handoff binding is invalid.");
    }

    public static BaseException unavailable() {
        return new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The durable DWAI-ON completion bridge is unavailable.");
    }
}
