package com.dwp.services.provider.resourcegovernance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactCapabilityDelta;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactClientCompatibility;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactCompatibilitySummary;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.ArtifactDependencyCompatibility;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactManifestRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.CreateArtifactRolloutPlanRequest;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceDtos.RollbackReadiness;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactEvidenceRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.ArtifactRow;
import com.dwp.services.provider.resourcegovernance.ResourceGovernanceRepository.PlanRow;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies typed validation and derives internal artifact-governance evidence. */
final class ArtifactGovernanceRules {

    void validateArtifactDefinition(CreateArtifactManifestRequest request) {
        validateEvidenceObject(request.manifest(), "Artifact manifest");
        validateEvidenceObject(request.compatibilityPolicy(), "Compatibility policy");
        validateCompatibilityPolicy(request.compatibilityPolicy());
        rejectInlineSecretMaterial(request.manifest());
        rejectInlineSecretMaterial(request.compatibilityPolicy());
    }

    void validatePlanDefinition(CreateArtifactRolloutPlanRequest request) {
        validateEvidenceObject(request.targetScope(), "Rollout target scope");
        validateTargetScope(request.targetScope());
        if (!request.stages().isArray() || request.stages().isEmpty()
                || request.stages().size() > 20
                || !allStageObjects(request.stages())) {
            throw invalid("A rollout plan needs between one and twenty structured stages.");
        }
        validateRolloutStages(request.stages());
        if ("DECLARED".equals(request.rollbackFeasibility())) {
            if (request.rollbackPlan() == null || !request.rollbackPlan().isObject()) {
                throw invalid("A declared rollback requires a structured rollback plan.");
            }
            validateRollbackPlan(request.rollbackPlan());
        } else if (request.rollbackPlan() != null) {
            throw invalid("Only declared rollback feasibility can include a rollback plan.");
        }
        rejectInlineSecretMaterial(request.targetScope());
        rejectInlineSecretMaterial(request.stages());
        if (request.rollbackPlan() != null) rejectInlineSecretMaterial(request.rollbackPlan());
    }

    void requireApprovedCompatibleArtifact(ArtifactRow artifact) {
        if (!"APPROVED".equals(artifact.lifecycleState())
                || !"COMPATIBLE".equals(artifact.compatibilityState())) {
            throw invalid("Only an independently approved compatible artifact can enter a rollout plan.");
        }
        if (!"UNAVAILABLE".equals(artifact.signatureState())
                || !"UNAVAILABLE".equals(artifact.distributionState())) {
            throw invalid("The artifact external-boundary state is not recognized.");
        }
    }

    void validateEvidenceObject(JsonNode evidence, String label) {
        if (evidence == null || !evidence.isObject()) {
            throw invalid(label + " must be a JSON object.");
        }
    }

    void rejectInlineSecretMaterial(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                JsonNode value = entry.getValue();
                if (key.matches(".*(password|secret|token|credential|privatekey|apikey|api_key).*")) {
                    if (!value.isTextual() || !value.asText().startsWith("secret://")) {
                        throw invalid("Inline secret material is not allowed in provider governance evidence.");
                    }
                }
                rejectInlineSecretMaterial(value);
            });
        } else if (node.isArray()) {
            node.forEach(this::rejectInlineSecretMaterial);
        }
    }

    private boolean allStageObjects(JsonNode stages) {
        var elements = stages.elements();
        while (elements.hasNext()) {
            if (!elements.next().isObject()) return false;
        }
        return true;
    }

    ArtifactCompatibilitySummary compatibility(ArtifactRow row) {
        JsonNode policy = row.compatibilityPolicy();
        JsonNode evidence = row.compatibilityEvidence();
        JsonNode schemaPolicy = policy.path("schema");
        JsonNode schemaEvidence = evidence.path("schema");
        var schema = new ResourceGovernanceDtos.ArtifactSchemaCompatibility(
                textOr(schemaEvidence, "state", "NOT_EVALUATED"),
                textOr(schemaEvidence, "currentVersion", textOr(schemaPolicy, "currentVersion", null)),
                textOr(schemaEvidence, "targetVersion", textOr(schemaPolicy, "targetVersion", null)),
                textOr(schemaEvidence, "migrationState", textOr(schemaPolicy, "migrationState", "UNAVAILABLE")));

        List<ArtifactClientCompatibility> clients = new ArrayList<>();
        JsonNode clientSource = evidence.path("clients").isArray()
                ? evidence.path("clients") : policy.path("clients");
        if (clientSource.isArray()) clientSource.forEach(item -> clients.add(new ArtifactClientCompatibility(
                textOr(item, "clientType", "UNDECLARED"),
                textOr(item, "minimumVersion", "UNDECLARED"),
                textOr(item, "state", "NOT_EVALUATED"))));

        List<ArtifactDependencyCompatibility> dependencies = new ArrayList<>();
        JsonNode dependencySource = evidence.path("dependencies").isArray()
                ? evidence.path("dependencies") : policy.path("dependencies");
        if (dependencySource.isArray()) dependencySource.forEach(item ->
                dependencies.add(new ArtifactDependencyCompatibility(
                        textOr(item, "dependencyKey", "UNDECLARED"),
                        textOr(item, "requiredVersion", "UNDECLARED"),
                        textOr(item, "observedVersion", null),
                        textOr(item, "state", "NOT_EVALUATED"))));

        JsonNode capabilityEvidence = evidence.path("capabilities");
        ArtifactCapabilityDelta capabilities = new ArtifactCapabilityDelta(
                textArray(capabilityEvidence, "added"),
                textArray(capabilityEvidence, "removed"),
                textArray(capabilityEvidence, "increased"));
        JsonNode rollbackEvidence = evidence.path("rollbackReadiness");
        RollbackReadiness rollback = new RollbackReadiness(
                textOr(rollbackEvidence, "state", "NOT_EVALUATED"),
                textArray(rollbackEvidence, "reasons"),
                textOr(rollbackEvidence, "executionBoundary", "INTERNAL_PLAN_ONLY"));
        return new ArtifactCompatibilitySummary(
                schema, List.copyOf(clients), List.copyOf(dependencies), capabilities, rollback);
    }

    RollbackReadiness planRollbackReadiness(
            PlanRow row,
            List<ArtifactEvidenceRow> evidence) {
        if ("UNAVAILABLE".equals(row.rollbackFeasibility())) {
            return new RollbackReadiness(
                    "UNAVAILABLE", List.of("ROLLBACK_NOT_AVAILABLE"), "EXTERNAL_EXECUTOR_UNAVAILABLE");
        }
        if ("NOT_DECLARED".equals(row.rollbackFeasibility())) {
            return new RollbackReadiness(
                    "NOT_DECLARED", List.of("ROLLBACK_PLAN_REQUIRED_FOR_RECOVERY"),
                    "EXTERNAL_EXECUTOR_UNAVAILABLE");
        }
        boolean passed = evidence.stream().anyMatch(item ->
                "ROLLBACK_FEASIBILITY".equals(item.evidenceType())
                        && "PASSED".equals(item.evidenceState()));
        return new RollbackReadiness(
                passed ? "INTERNALLY_READY" : "EVIDENCE_REQUIRED",
                passed ? List.of() : List.of("ROLLBACK_FEASIBILITY_EVIDENCE_REQUIRED"),
                "EXTERNAL_EXECUTOR_UNAVAILABLE");
    }

    private void validateCompatibilityPolicy(JsonNode policy) {
        JsonNode schema = requireObject(policy, "schema", "Compatibility policy");
        requireText(schema, "currentVersion", "Compatibility schema policy");
        requireText(schema, "targetVersion", "Compatibility schema policy");
        requireEnum(schema, "migrationState", "Compatibility schema policy",
                Set.of("ADDITIVE_ONLY", "DUAL_WRITE", "MANUAL_REVIEW", "UNAVAILABLE"));

        JsonNode clients = requireArray(policy, "clients", "Compatibility policy");
        Set<String> clientKeys = new HashSet<>();
        clients.forEach(item -> {
            if (!item.isObject()) throw invalid("Every client compatibility requirement must be an object.");
            String key = requireText(item, "clientType", "Client compatibility requirement");
            requireText(item, "minimumVersion", "Client compatibility requirement");
            if (!clientKeys.add(key)) throw invalid("Client compatibility types must be unique.");
        });

        JsonNode dependencies = requireArray(policy, "dependencies", "Compatibility policy");
        Set<String> dependencyKeys = new HashSet<>();
        dependencies.forEach(item -> {
            if (!item.isObject()) throw invalid("Every dependency compatibility requirement must be an object.");
            String key = requireText(item, "dependencyKey", "Dependency compatibility requirement");
            requireText(item, "requiredVersion", "Dependency compatibility requirement");
            if (!dependencyKeys.add(key)) throw invalid("Dependency keys must be unique.");
        });

        JsonNode capabilities = requireObject(policy, "capabilities", "Compatibility policy");
        requireBoolean(capabilities, "allowAdded", "Capability policy");
        requireBoolean(capabilities, "allowRemoved", "Capability policy");
        requireBoolean(capabilities, "allowIncreased", "Capability policy");
        JsonNode rollback = requireObject(policy, "rollback", "Compatibility policy");
        requireBoolean(rollback, "required", "Rollback policy");
        requireEnum(rollback, "strategy", "Rollback policy",
                Set.of("TRAFFIC_REVERT", "CONFIG_REVERT", "MANUAL_RESTORE", "UNAVAILABLE"));
    }

    void validateCompatibilityEvidence(JsonNode policy, JsonNode evidence) {
        JsonNode schemaPolicy = policy.path("schema");
        JsonNode schema = requireObject(evidence, "schema", "Compatibility evidence");
        requireCompatibilityState(schema, "Schema compatibility evidence");
        requireMatchingPolicyText(
                schemaPolicy, schema, "currentVersion", "Schema compatibility evidence");
        requireMatchingPolicyText(
                schemaPolicy, schema, "targetVersion", "Schema compatibility evidence");
        requireEnum(schema, "migrationState", "Schema compatibility evidence",
                Set.of("ADDITIVE_ONLY", "DUAL_WRITE", "MANUAL_REVIEW", "UNAVAILABLE"));
        requireMatchingPolicyText(
                schemaPolicy, schema, "migrationState", "Schema compatibility evidence");
        if ("UNAVAILABLE".equals(schema.path("migrationState").asText())
                && "PASSED".equals(schema.path("state").asText())) {
            throw invalid("Unavailable schema migration evidence cannot be asserted as passed.");
        }

        JsonNode clients = requireArray(evidence, "clients", "Compatibility evidence");
        validateEvidenceCoverage(
                policy.path("clients"), clients, "clientType", "minimumVersion", "client");
        JsonNode dependencies = requireArray(evidence, "dependencies", "Compatibility evidence");
        validateEvidenceCoverage(
                policy.path("dependencies"), dependencies, "dependencyKey", "requiredVersion", "dependency");
        dependencies.forEach(item -> requireText(
                item, "observedVersion", "Dependency compatibility evidence"));

        JsonNode capabilities = requireObject(evidence, "capabilities", "Compatibility evidence");
        requireTextArray(capabilities, "added", "Capability evidence");
        requireTextArray(capabilities, "removed", "Capability evidence");
        requireTextArray(capabilities, "increased", "Capability evidence");
        JsonNode rollback = requireObject(evidence, "rollbackReadiness", "Compatibility evidence");
        requireEnum(rollback, "state", "Rollback readiness",
                Set.of("READY", "REVIEW_REQUIRED", "BLOCKED", "UNAVAILABLE"));
        requireTextArray(rollback, "reasons", "Rollback readiness");
        String boundary = requireText(rollback, "executionBoundary", "Rollback readiness");
        if (!"INTERNAL_PLAN_ONLY".equals(boundary)) {
            throw invalid("Rollback compatibility evidence must remain inside the internal plan boundary.");
        }
        if ("UNAVAILABLE".equals(policy.path("rollback").path("strategy").asText())
                && "READY".equals(rollback.path("state").asText())) {
            throw invalid("An unavailable rollback strategy cannot be asserted as ready.");
        }
    }

    private void validateEvidenceCoverage(
            JsonNode requirements,
            JsonNode evidence,
            String keyField,
            String versionField,
            String label) {
        Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
        evidence.forEach(item -> {
            if (!item.isObject()) throw invalid("Every " + label + " compatibility evidence item must be an object.");
            String key = requireText(item, keyField, label + " compatibility evidence");
            requireText(item, versionField, label + " compatibility evidence");
            requireCompatibilityState(item, label + " compatibility evidence");
            if (byKey.put(key, item) != null) {
                throw invalid("Compatibility evidence keys must be unique: " + key + ".");
            }
        });
        requirements.forEach(requirement -> {
            String key = requirement.path(keyField).asText();
            JsonNode observed = byKey.get(key);
            if (observed == null) {
                throw invalid("Compatibility evidence is missing the declared " + label + ": " + key + ".");
            }
            if (!requirement.path(versionField).asText().equals(observed.path(versionField).asText())) {
                throw invalid("Compatibility evidence changed the declared " + label
                        + " version for " + key + ".");
            }
        });
        if (byKey.size() != requirements.size()) {
            throw invalid("Compatibility evidence contains an undeclared " + label + ".");
        }
    }

    private void requireMatchingPolicyText(
            JsonNode policy,
            JsonNode evidence,
            String field,
            String label) {
        String observed = requireText(evidence, field, label);
        if (!policy.path(field).asText().equals(observed)) {
            throw invalid(label + " field " + field + " does not match the declared policy.");
        }
    }

    String deriveCompatibilityState(JsonNode policy, JsonNode evidence) {
        List<String> states = new ArrayList<>();
        states.add(evidence.path("schema").path("state").asText());
        evidence.path("clients").forEach(item -> states.add(item.path("state").asText()));
        evidence.path("dependencies").forEach(item -> states.add(item.path("state").asText()));
        states.add(evidence.path("rollbackReadiness").path("state").asText());
        JsonNode capabilityPolicy = policy.path("capabilities");
        JsonNode capabilities = evidence.path("capabilities");
        boolean forbiddenCapabilityChange =
                (!capabilityPolicy.path("allowAdded").asBoolean() && !capabilities.path("added").isEmpty())
                        || (!capabilityPolicy.path("allowRemoved").asBoolean()
                        && !capabilities.path("removed").isEmpty())
                        || (!capabilityPolicy.path("allowIncreased").asBoolean()
                        && !capabilities.path("increased").isEmpty());
        boolean requiredRollbackMissing = policy.path("rollback").path("required").asBoolean(false)
                && !"READY".equals(evidence.path("rollbackReadiness").path("state").asText());
        if (forbiddenCapabilityChange || requiredRollbackMissing || states.contains("BLOCKED")) {
            return "BLOCKED";
        }
        if (states.stream().anyMatch(state -> !"PASSED".equals(state) && !"READY".equals(state))) {
            return "REVIEW_REQUIRED";
        }
        return "COMPATIBLE";
    }

    private void validateTargetScope(JsonNode targetScope) {
        requireText(targetScope, "environmentKey", "Rollout target scope");
        requireTextArray(targetScope, "tenantKeys", "Rollout target scope");
        requireTextArray(targetScope, "cohortKeys", "Rollout target scope");
        int percentage = requireInt(targetScope, "targetPercentage", "Rollout target scope");
        if (percentage < 1 || percentage > 100) {
            throw invalid("Rollout target percentage must be between 1 and 100.");
        }
    }

    private void validateRolloutStages(JsonNode stages) {
        int previousPercentage = 0;
        Set<String> keys = new HashSet<>();
        for (JsonNode stage : stages) {
            String key = requireText(stage, "stageKey", "Rollout stage");
            if (!keys.add(key)) throw invalid("Rollout stage keys must be unique.");
            int percentage = requireInt(stage, "targetPercentage", "Rollout stage");
            if (percentage <= previousPercentage || percentage > 100) {
                throw invalid("Rollout-stage percentages must increase and cannot exceed 100.");
            }
            int observationMinutes = requireInt(stage, "minimumObservationMinutes", "Rollout stage");
            if (observationMinutes < 0) {
                throw invalid("Rollout-stage observation minutes cannot be negative.");
            }
            requireBoolean(stage, "approvalGate", "Rollout stage");
            previousPercentage = percentage;
        }
    }

    private void validateRollbackPlan(JsonNode rollbackPlan) {
        requireEnum(rollbackPlan, "strategy", "Rollback plan",
                Set.of("TRAFFIC_REVERT", "CONFIG_REVERT", "MANUAL_RESTORE"));
        requireText(rollbackPlan, "targetVersion", "Rollback plan");
        requireEnum(rollbackPlan, "dataHandling", "Rollback plan",
                Set.of("PRESERVE_CURRENT_SCHEMA", "RESTORE_SNAPSHOT", "MANUAL_RECONCILIATION"));
        JsonNode checks = requireTextArray(rollbackPlan, "validationChecks", "Rollback plan");
        if (checks.isEmpty()) throw invalid("A rollback plan needs at least one validation check.");
        requireTextArray(rollbackPlan, "manualSteps", "Rollback plan");
    }

    void requireInternalRolloutReadiness(
            PlanRow plan,
            List<ArtifactEvidenceRow> evidence) {
        if (evidence.stream().anyMatch(item -> "FAILED".equals(item.evidenceState()))) {
            throw invalid("A rollout plan with failed internal evidence cannot be marked ready.");
        }
        boolean preflight = evidence.stream().anyMatch(item ->
                "PRE_FLIGHT".equals(item.evidenceType()) && "PASSED".equals(item.evidenceState()));
        if (!preflight) {
            throw invalid("Passed internal pre-flight evidence is required before marking a plan ready.");
        }
        if ("DECLARED".equals(plan.rollbackFeasibility())) {
            boolean rollback = evidence.stream().anyMatch(item ->
                    "ROLLBACK_FEASIBILITY".equals(item.evidenceType())
                            && "PASSED".equals(item.evidenceState()));
            if (!rollback) {
                throw invalid("Passed rollback-feasibility evidence is required for a declared rollback plan.");
            }
        }
    }

    private JsonNode requireObject(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw invalid(label + " requires an object field: " + field + ".");
        return value;
    }

    private JsonNode requireArray(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isArray()) throw invalid(label + " requires an array field: " + field + ".");
        return value;
    }

    private JsonNode requireTextArray(JsonNode parent, String field, String label) {
        JsonNode value = requireArray(parent, field, label);
        value.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw invalid(label + " field " + field + " must contain non-empty strings.");
            }
        });
        return value;
    }

    private String requireText(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw invalid(label + " requires a non-empty text field: " + field + ".");
        }
        return value.asText();
    }

    private void requireBoolean(JsonNode parent, String field, String label) {
        if (!parent.path(field).isBoolean()) {
            throw invalid(label + " requires a boolean field: " + field + ".");
        }
    }

    private int requireInt(JsonNode parent, String field, String label) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber()) {
            throw invalid(label + " requires an integer field: " + field + ".");
        }
        return value.asInt();
    }

    private void requireEnum(JsonNode parent, String field, String label, Set<String> values) {
        String value = requireText(parent, field, label);
        if (!values.contains(value)) {
            throw invalid(label + " field " + field + " has an unsupported value.");
        }
    }

    private void requireCompatibilityState(JsonNode item, String label) {
        requireEnum(item, "state", label,
                Set.of("PASSED", "REVIEW_REQUIRED", "BLOCKED", "UNAVAILABLE"));
    }

    private String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private List<String> textArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) result.add(item.asText());
        });
        return List.copyOf(result);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
