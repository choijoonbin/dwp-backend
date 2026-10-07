package com.dwp.platform.contracts.hris.capability.v1;

import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.ActivationStage;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.BindingKind;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.InstallStatus;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.MissingEffect;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable product-owned descriptor. It contains no provider credential or tenant activation. */
public record OptionalCapabilityDescriptorV1(
        String capabilityId,
        Module owner,
        Set<Module> consumers,
        OptionalCapabilityVersionV1 contractVersion,
        InstallStatus defaultState,
        Set<String> capabilityPrerequisites,
        Set<BindingKind> bindingPrerequisites,
        Set<BindingKind> forbiddenBindings,
        Set<String> affectedOperations,
        Set<String> preservedOperations,
        String failureCode,
        MissingEffect missingEffect,
        ActivationStage activationStage) {
    private static final Pattern CAPABILITY_ID = Pattern.compile("CAP\\.[A-Z][A-Z0-9_]*");
    private static final Pattern OPERATION_ID =
            Pattern.compile("[A-Z]+\\.[A-Z0-9_]+(?:\\.[A-Z0-9_]+)+");
    private static final Pattern FAILURE_CODE = Pattern.compile("HRIS_[A-Z0-9_]+");

    public OptionalCapabilityDescriptorV1 {
        requireMatch(capabilityId, CAPABILITY_ID, "capabilityId");
        if (owner == null || contractVersion == null || defaultState != InstallStatus.DISABLED
                || missingEffect == null
                || activationStage != ActivationStage.G6) {
            throw new IllegalArgumentException(
                    "owner, version, disabled default, missing effect and G6 stage are required");
        }
        consumers = immutable(consumers, "consumers");
        capabilityPrerequisites = immutable(capabilityPrerequisites, "capabilityPrerequisites");
        bindingPrerequisites = immutable(bindingPrerequisites, "bindingPrerequisites");
        forbiddenBindings = immutable(forbiddenBindings, "forbiddenBindings");
        affectedOperations = immutable(affectedOperations, "affectedOperations");
        preservedOperations = immutable(preservedOperations, "preservedOperations");
        if (consumers.isEmpty() || !consumers.contains(owner)) {
            throw new IllegalArgumentException("consumers must contain the owner");
        }
        if (affectedOperations.isEmpty()
                || affectedOperations.stream().anyMatch(value -> !OPERATION_ID.matcher(value).matches())) {
            throw new IllegalArgumentException("affected operations must use closed operation identifiers");
        }
        if (preservedOperations.stream().anyMatch(
                value -> !OPERATION_ID.matcher(value).matches())) {
            throw new IllegalArgumentException("preserved operations must use closed operation identifiers");
        }
        if (!java.util.Collections.disjoint(affectedOperations, preservedOperations)) {
            throw new IllegalArgumentException("affected and preserved operations overlap");
        }
        if (capabilityPrerequisites.stream()
                .anyMatch(value -> value == null || !CAPABILITY_ID.matcher(value).matches())) {
            throw new IllegalArgumentException("prerequisite capability identifier is invalid");
        }
        if (!java.util.Collections.disjoint(bindingPrerequisites, forbiddenBindings)) {
            throw new IllegalArgumentException("required and forbidden bindings overlap");
        }
        requireMatch(failureCode, FAILURE_CODE, "failureCode");
    }

    private static <T> Set<T> immutable(Set<T> values, String field) {
        if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException(field + " must be a non-null closed set");
        }
        return Set.copyOf(values);
    }

    private static void requireMatch(String value, Pattern pattern, String field) {
        if (value == null || !value.equals(value.trim()) || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
