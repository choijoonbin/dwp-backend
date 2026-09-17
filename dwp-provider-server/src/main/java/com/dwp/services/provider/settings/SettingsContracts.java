package com.dwp.services.provider.settings;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SettingsContracts {

    private SettingsContracts() {
    }

    public enum ScopeType {
        USER, TENANT, PROVIDER, APPLICATION
    }

    public enum ValueType {
        BOOLEAN, STRING, NUMBER, OBJECT, ARRAY, JSON
    }

    public enum Sensitivity {
        PUBLIC, INTERNAL, CONFIDENTIAL, SECRET
    }

    public enum LifecycleState {
        ACTIVE, DEPRECATED, UNAVAILABLE
    }

    public enum ChangeWorkflow {
        DIRECT, REVIEW_AND_PUBLISH, APPROVE_AND_ACTIVATE, OWNER_MANAGED
    }

    public enum SourceType {
        DEFAULT, PROVIDER_POLICY, TENANT_POLICY, USER_PREFERENCE, ACTIVE_ROLLOUT,
        APPLICATION_OVERRIDE
    }

    public enum ResolutionState {
        RESOLVED, REDACTED, UNSUPPORTED_SETTING, UNSUPPORTED_SCOPE, VALUE_UNAVAILABLE
    }

    public enum ApplicationState {
        NOT_CONFIGURED,
        DRAFT,
        APPROVAL_PENDING,
        READY_TO_PUBLISH,
        PUBLISH_PENDING,
        OBSERVATION_UNSUPPORTED,
        PUBLISHED_UNOBSERVED,
        APPLYING,
        CONVERGED,
        PARTIAL,
        DRIFTED,
        FAILED,
        OBSERVATION_STALE
    }

    public enum DesiredState {
        NONE, DRAFT, PENDING_APPROVAL, APPROVED, PUBLISH_REQUESTED, PUBLISHED, FAILED
    }

    public enum ObservationState {
        APPLIED, FAILED, UNKNOWN
    }

    public record Owner(
            String service,
            String domain,
            String readPermission,
            String managementPath) {

        public Owner {
            service = required(service, "owner service");
            domain = required(domain, "owner domain");
            readPermission = required(readPermission, "owner read permission");
            managementPath = required(managementPath, "owner management path");
        }
    }

    public record ValidationContract(
            ValueType valueType,
            String schemaVersion,
            JsonNode schema,
            boolean ownerRevalidatesOnWrite) {

        public ValidationContract {
            valueType = Objects.requireNonNull(valueType, "valueType");
            schemaVersion = required(schemaVersion, "schema version");
            schema = Objects.requireNonNull(schema, "schema").deepCopy();
        }
    }

    public record ChangeContract(
            String riskTier,
            ChangeWorkflow workflow,
            boolean approvalRequired,
            boolean activationRequired) {

        public ChangeContract {
            riskTier = required(riskTier, "risk tier");
            workflow = Objects.requireNonNull(workflow, "workflow");
            if (activationRequired && !approvalRequired) {
                throw new IllegalArgumentException(
                        "An activation workflow must also require approval.");
            }
        }
    }

    public record Definition(
            String settingId,
            String displayName,
            String description,
            Owner owner,
            Set<ScopeType> supportedScopes,
            ValidationContract validation,
            Sensitivity sensitivity,
            ChangeContract change,
            LifecycleState lifecycleState,
            long definitionVersion) {

        public Definition {
            settingId = required(settingId, "setting ID");
            displayName = required(displayName, "setting display name");
            description = required(description, "setting description");
            owner = Objects.requireNonNull(owner, "owner");
            supportedScopes = Set.copyOf(Objects.requireNonNull(
                    supportedScopes, "supportedScopes"));
            if (supportedScopes.isEmpty()) {
                throw new IllegalArgumentException("At least one setting scope is required.");
            }
            validation = Objects.requireNonNull(validation, "validation");
            sensitivity = Objects.requireNonNull(sensitivity, "sensitivity");
            change = Objects.requireNonNull(change, "change");
            lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
            if (definitionVersion < 0) {
                throw new IllegalArgumentException("Definition version cannot be negative.");
            }
        }
    }

    public record ScopeTarget(
            ScopeType scopeType,
            String scopeId,
            String environment) {

        public ScopeTarget {
            scopeType = Objects.requireNonNull(scopeType, "scopeType");
            scopeId = required(scopeId, "scope ID");
            environment = optional(environment);
        }
    }

    public record Provenance(
            int precedence,
            SourceType sourceType,
            ScopeType sourceScope,
            String sourceId,
            String version,
            boolean locked,
            String decisionCode,
            Instant decidedAt) {

        public Provenance {
            if (precedence < 0) {
                throw new IllegalArgumentException("Provenance precedence cannot be negative.");
            }
            sourceType = Objects.requireNonNull(sourceType, "sourceType");
            sourceScope = Objects.requireNonNull(sourceScope, "sourceScope");
            sourceId = required(sourceId, "provenance source ID");
            version = required(version, "provenance version");
            decisionCode = required(decisionCode, "provenance decision code");
            decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        }
    }

    public record TargetObservation(
            String targetId,
            ObservationState state,
            String observedVersion,
            Instant observedAt,
            Instant lastSuccessAt,
            String errorCode) {

        public TargetObservation {
            targetId = required(targetId, "observation target ID");
            state = Objects.requireNonNull(state, "observation state");
            observedVersion = optional(observedVersion);
            observedAt = Objects.requireNonNull(observedAt, "observedAt");
            errorCode = optional(errorCode);
            if (state == ObservationState.APPLIED && observedVersion == null) {
                throw new IllegalArgumentException(
                        "An applied observation must identify its observed version.");
            }
        }
    }

    public record ApplicationEvidence(
            String desiredVersion,
            DesiredState desiredState,
            String publishedVersion,
            Instant publishAcceptedAt,
            boolean observationSupported,
            int expectedTargetCount,
            List<TargetObservation> observations) {

        public ApplicationEvidence {
            desiredVersion = optional(desiredVersion);
            desiredState = Objects.requireNonNull(desiredState, "desiredState");
            publishedVersion = optional(publishedVersion);
            if ((desiredVersion == null) != (desiredState == DesiredState.NONE)) {
                throw new IllegalArgumentException(
                        "Desired state NONE must match an absent desired version.");
            }
            if (expectedTargetCount < 0) {
                throw new IllegalArgumentException("Expected target count cannot be negative.");
            }
            observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
            if (observations.size() > expectedTargetCount) {
                throw new IllegalArgumentException(
                        "Observed target count cannot exceed the expected target count.");
            }
            if (observations.stream().map(TargetObservation::targetId).distinct().count()
                    != observations.size()) {
                throw new IllegalArgumentException("Observation target IDs must be unique.");
            }
            if (!observationSupported && (!observations.isEmpty() || expectedTargetCount > 0)) {
                throw new IllegalArgumentException(
                        "Unsupported observation cannot include target evidence.");
            }
        }
    }

    public record ApplicationStatus(
            ApplicationState state,
            String desiredVersion,
            DesiredState desiredState,
            String publishedVersion,
            Instant publishAcceptedAt,
            String uniformlyObservedVersion,
            int expectedTargetCount,
            int observedTargetCount,
            int convergedTargetCount,
            int failedTargetCount,
            int driftedTargetCount,
            Instant latestObservationAt,
            boolean stale) {
    }

    public record OwnerSnapshot(
            JsonNode effectiveValue,
            String effectiveVersion,
            List<Provenance> provenance,
            ApplicationEvidence applicationEvidence,
            Instant resolvedAt) {

        public OwnerSnapshot {
            effectiveValue = Objects.requireNonNull(effectiveValue, "effectiveValue").deepCopy();
            effectiveVersion = required(effectiveVersion, "effective version");
            provenance = List.copyOf(Objects.requireNonNull(provenance, "provenance"));
            if (provenance.isEmpty()) {
                throw new IllegalArgumentException("Effective value provenance is required.");
            }
            applicationEvidence = Objects.requireNonNull(
                    applicationEvidence, "applicationEvidence");
            resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
        }
    }

    public record Resolution(
            String settingId,
            ResolutionState resolutionState,
            Definition definition,
            ScopeTarget target,
            JsonNode effectiveValue,
            String effectiveVersion,
            List<Provenance> provenance,
            ApplicationStatus applicationStatus,
            String reasonCode,
            Instant resolvedAt) {

        public Resolution {
            settingId = required(settingId, "setting ID");
            resolutionState = Objects.requireNonNull(resolutionState, "resolutionState");
            provenance = provenance == null ? List.of() : List.copyOf(provenance);
            reasonCode = required(reasonCode, "resolution reason code");
        }
    }

    static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A non-blank " + label + " is required.");
        }
        return value.trim();
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
