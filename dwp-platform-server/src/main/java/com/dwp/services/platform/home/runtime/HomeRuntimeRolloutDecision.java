package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

public record HomeRuntimeRolloutDecision(
        State state,
        String mode,
        Ring ring,
        String revision,
        boolean registryAuthoritative,
        Set<String> allowedProviders,
        Set<String> allowedDefinitions,
        Set<String> allowedActions,
        OffsetDateTime expiresAt) {

    public HomeRuntimeRolloutDecision {
        allowedProviders = Set.copyOf(allowedProviders);
        allowedDefinitions = Set.copyOf(allowedDefinitions);
        allowedActions = Set.copyOf(allowedActions);
    }

    public boolean commandsEnabled() {
        return state == State.COMMAND_CANARY && !allowedActions.isEmpty();
    }

    public boolean actionAllowed(String contractId) {
        return commandsEnabled() && allowedActions.contains(contractId);
    }

    public enum State {
        DISABLED,
        SHADOW_COMPARE,
        READ_ONLY_ACTIVE,
        COMMAND_CANARY;

        static State parse(String raw) {
            try {
                return State.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw unavailable("Trusted Home rollout state is missing or invalid.");
            }
        }
    }

    public enum Ring {
        CONTROL,
        INTERNAL,
        PILOT,
        EARLY_ADOPTER,
        GA;

        static Ring parse(String raw) {
            try {
                return Ring.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw unavailable("Trusted Home rollout ring is missing or invalid.");
            }
        }
    }

    public record TrustedInput(State state, Ring ring, String revision) {
        public static TrustedInput parse(String state, String ring, String revision) {
            String bounded = revision == null ? "" : revision.trim();
            if (!bounded.matches("[A-Za-z0-9._:-]{1,160}")) {
                throw unavailable("Trusted Home rollout revision is missing or invalid.");
            }
            return new TrustedInput(State.parse(state), Ring.parse(ring), bounded);
        }
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }
}
