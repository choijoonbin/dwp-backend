package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationSnapshot;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionCommand;

/** Input and lifecycle guards shared by payroll-foundation service commands. */
final class PayrollFoundationServiceRules {

    private PayrollFoundationServiceRules() {
    }

    static void requireExpectedVersion(ConfigurationSnapshot current, long expectedVersion) {
        if (expectedVersion <= 0 || current.version() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Payroll configuration version changed. Refresh and retry.");
        }
    }

    static void requireEditable(ConfigurationSnapshot snapshot) {
        if (snapshot.status() == Lifecycle.PUBLISHED || snapshot.status() == Lifecycle.REVERSED) {
            throw conflict("Published or reversed configurations are immutable.");
        }
    }

    static VersionCommand requireCommand(VersionCommand command) {
        if (command == null || command.expectedVersion() <= 0) {
            throw invalid("A positive expectedVersion is required.");
        }
        return command;
    }

    static FoundationDefinition requireDefinition(FoundationDefinition definition) {
        if (definition == null) {
            throw invalid("A payroll foundation definition is required.");
        }
        return definition;
    }

    static String normalizeCorrelation(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return null;
        }
        String normalized = correlationId.strip();
        if (normalized.length() > 160 || !normalized.matches("[!-~]+")) {
            throw invalid("X-Correlation-ID must contain at most 160 visible ASCII characters.");
        }
        return normalized;
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
