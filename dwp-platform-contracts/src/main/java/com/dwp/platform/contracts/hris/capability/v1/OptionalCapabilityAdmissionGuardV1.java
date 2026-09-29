package com.dwp.platform.contracts.hris.capability.v1;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityAdmissionErrorV1.Code.*;
import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, provider-neutral fail-closed pre-call guard.
 *
 * <p>The guard neither discovers providers nor reads tenant credentials. START callers supply a
 * typed snapshot, and only G4/G6 adapters may later produce that snapshot. An admitted operation
 * receives a least-privilege list of binding references, never a provider object or secret.</p>
 */
public final class OptionalCapabilityAdmissionGuardV1 {
    private static final String CONFIGURATION_OPERATION = "CONFIGURATION.VALIDATE";
    private static final String CONFIGURATION_FAILURE = "HRIS_CAPABILITY_CONFIGURATION_INVALID";
    private static final String UNKNOWN_FAILURE = "HRIS_CAPABILITY_UNKNOWN";

    private final Map<String, OptionalCapabilityDescriptorV1> descriptors;

    public OptionalCapabilityAdmissionGuardV1(
            Collection<OptionalCapabilityDescriptorV1> configuredDescriptors) {
        this.descriptors = validateCatalog(configuredDescriptors);
    }

    public OptionalCapabilityAdmissionDecisionV1 evaluate(
            OptionalCapabilityAdmissionRequestV1 request) {
        if (request == null) {
            throw configuration(INVALID_REQUEST, "REQUEST", "request is absent");
        }
        for (String configuredId : request.installations().keySet()) {
            if (!descriptors.containsKey(configuredId)) {
                return deny(request, DecisionKind.DENY_UNKNOWN_CAPABILITY,
                        UNKNOWN_CAPABILITY, configuredId, UNKNOWN_FAILURE);
            }
        }
        OptionalCapabilityDescriptorV1 descriptor = descriptors.get(request.capabilityId());
        if (descriptor == null) {
            return deny(request, DecisionKind.DENY_UNKNOWN_CAPABILITY,
                    UNKNOWN_CAPABILITY, request.capabilityId(), UNKNOWN_FAILURE);
        }
        if (!descriptor.consumers().contains(request.consumer())) {
            return deny(request, DecisionKind.DENY_INVALID_REQUEST,
                    UNAUTHORIZED_CONSUMER, descriptor.capabilityId(), descriptor.failureCode());
        }
        if (!descriptor.affectedOperations().contains(request.operation())) {
            if (!descriptor.preservedOperations().contains(request.operation())) {
                return deny(request, DecisionKind.DENY_INVALID_REQUEST,
                        UNKNOWN_OPERATION, descriptor.capabilityId(), descriptor.failureCode());
            }
            DecisionKind kind = descriptor.missingEffect()
                    == MissingEffect.JOB_ONLY_MANUAL_PATH_PRESERVED
                    ? DecisionKind.ALLOW_MANUAL_PATH : DecisionKind.ALLOW_UNRELATED_CORE;
            return OptionalCapabilityAdmissionDecisionV1.allow(
                    kind, request.capabilityId(), request.operation(), Set.of());
        }

        Set<OptionalCapabilityAuthorizedBindingV1> authorizedBindings = new HashSet<>();
        AdmissionFailure failure = requireReady(
                descriptor, request.installations(), authorizedBindings, new HashSet<>());
        if (failure != null) {
            return deny(request, failure.kind(), failure.code(),
                    failure.failingCapabilityId(), failure.failureCode());
        }
        return OptionalCapabilityAdmissionDecisionV1.allow(
                DecisionKind.ALLOW_AFFECTED_OPERATION,
                request.capabilityId(), request.operation(), authorizedBindings);
    }

    public <T> T execute(OptionalCapabilityAdmissionRequestV1 request,
            OptionalCapabilityOperationV1<T> operation) {
        OptionalCapabilityAdmissionDecisionV1 decision = evaluate(request);
        if (!decision.allowed()) {
            throw new OptionalCapabilityAdmissionExceptionV1(decision.error());
        }
        if (operation == null) {
            throw configuration(INVALID_REQUEST, request.capabilityId(), "operation callback is absent");
        }
        return operation.execute(decision);
    }

    public Map<String, OptionalCapabilityDescriptorV1> descriptors() {
        return descriptors;
    }

    private AdmissionFailure requireReady(
            OptionalCapabilityDescriptorV1 descriptor,
            Map<String, OptionalCapabilityInstallStateV1> installations,
            Set<OptionalCapabilityAuthorizedBindingV1> authorizedBindings,
            Set<String> evaluated) {
        if (!evaluated.add(descriptor.capabilityId())) {
            return null;
        }
        OptionalCapabilityInstallStateV1 install = installations.get(descriptor.capabilityId());
        if (install == null || install.status() == InstallStatus.UNINSTALLED) {
            return unavailable(descriptor, CAPABILITY_UNINSTALLED, DecisionKind.DENY_UNINSTALLED);
        }
        if (install.status() == InstallStatus.DISABLED) {
            return unavailable(descriptor, CAPABILITY_DISABLED, DecisionKind.DENY_DISABLED);
        }
        if (!compatible(descriptor.contractVersion(), install.installedVersion())) {
            return unavailable(descriptor, VERSION_INCOMPATIBLE, DecisionKind.DENY_VERSION);
        }
        if (!install.availableBindings().containsAll(descriptor.bindingPrerequisites())) {
            return unavailable(descriptor, REQUIRED_BINDING_MISSING,
                    DecisionKind.DENY_MISSING_BINDING);
        }
        for (BindingKind kind : descriptor.bindingPrerequisites()) {
            if (!descriptor.forbiddenBindings().contains(kind)) {
                authorizedBindings.add(new OptionalCapabilityAuthorizedBindingV1(
                        descriptor.capabilityId(), kind));
            }
        }
        for (String prerequisiteId : descriptor.capabilityPrerequisites()) {
            OptionalCapabilityDescriptorV1 prerequisite = descriptors.get(prerequisiteId);
            AdmissionFailure nested = requireReady(
                    prerequisite, installations, authorizedBindings, evaluated);
            if (nested != null) {
                return new AdmissionFailure(DecisionKind.DENY_MISSING_CAPABILITY,
                        PREREQUISITE_NOT_READY, nested.failingCapabilityId(),
                        descriptor.failureCode());
            }
        }
        return null;
    }

    private static AdmissionFailure unavailable(
            OptionalCapabilityDescriptorV1 descriptor,
            OptionalCapabilityAdmissionErrorV1.Code code,
            DecisionKind kind) {
        return new AdmissionFailure(kind, code,
                descriptor.capabilityId(), descriptor.failureCode());
    }

    private static boolean compatible(
            OptionalCapabilityVersionV1 required,
            OptionalCapabilityVersionV1 installed) {
        return installed != null && installed.major() == required.major()
                && installed.compareTo(required) >= 0;
    }

    private static OptionalCapabilityAdmissionDecisionV1 deny(
            OptionalCapabilityAdmissionRequestV1 request,
            DecisionKind kind,
            OptionalCapabilityAdmissionErrorV1.Code code,
            String failingCapabilityId,
            String failureCode) {
        OptionalCapabilityAdmissionErrorV1 error = new OptionalCapabilityAdmissionErrorV1(
                code, request.capabilityId(), request.operation(), failingCapabilityId, failureCode);
        return OptionalCapabilityAdmissionDecisionV1.deny(
                kind, request.capabilityId(), request.operation(), error);
    }

    private static Map<String, OptionalCapabilityDescriptorV1> validateCatalog(
            Collection<OptionalCapabilityDescriptorV1> configuredDescriptors) {
        if (configuredDescriptors == null || configuredDescriptors.isEmpty()) {
            throw configuration(INVALID_CONFIGURATION, "CATALOG", "catalog is absent");
        }
        Map<String, OptionalCapabilityDescriptorV1> indexed = new LinkedHashMap<>();
        Map<String, String> operations = new HashMap<>();
        for (OptionalCapabilityDescriptorV1 descriptor : configuredDescriptors) {
            if (descriptor == null) {
                throw configuration(INVALID_CONFIGURATION, "CATALOG", "descriptor is absent");
            }
            if (indexed.putIfAbsent(descriptor.capabilityId(), descriptor) != null) {
                throw configuration(DUPLICATE_CAPABILITY, descriptor.capabilityId(), "duplicate id");
            }
            for (String operation : descriptor.affectedOperations()) {
                String previous = operations.putIfAbsent(operation, descriptor.capabilityId());
                if (previous != null) {
                    throw configuration(OPERATION_COLLISION, descriptor.capabilityId(), operation);
                }
            }
        }
        for (OptionalCapabilityDescriptorV1 descriptor : indexed.values()) {
            for (String prerequisite : descriptor.capabilityPrerequisites()) {
                if (!indexed.containsKey(prerequisite)) {
                    throw configuration(UNKNOWN_PREREQUISITE,
                            descriptor.capabilityId(), prerequisite);
                }
            }
        }
        rejectCycles(indexed);
        rejectForbiddenDependencyBindings(indexed);
        return Map.copyOf(indexed);
    }

    private static void rejectForbiddenDependencyBindings(
            Map<String, OptionalCapabilityDescriptorV1> indexed) {
        Map<String, Set<BindingKind>> cache = new HashMap<>();
        for (OptionalCapabilityDescriptorV1 descriptor : indexed.values()) {
            Set<BindingKind> transitive = transitiveBindings(descriptor, indexed, cache);
            if (!java.util.Collections.disjoint(descriptor.forbiddenBindings(), transitive)) {
                throw configuration(FORBIDDEN_BINDING_DEPENDENCY,
                        descriptor.capabilityId(), "forbidden transitive binding");
            }
        }
    }

    private static Set<BindingKind> transitiveBindings(
            OptionalCapabilityDescriptorV1 descriptor,
            Map<String, OptionalCapabilityDescriptorV1> indexed,
            Map<String, Set<BindingKind>> cache) {
        Set<BindingKind> cached = cache.get(descriptor.capabilityId());
        if (cached != null) {
            return cached;
        }
        Set<BindingKind> bindings = new HashSet<>(descriptor.bindingPrerequisites());
        for (String dependency : descriptor.capabilityPrerequisites()) {
            bindings.addAll(transitiveBindings(indexed.get(dependency), indexed, cache));
        }
        Set<BindingKind> immutable = Set.copyOf(bindings);
        cache.put(descriptor.capabilityId(), immutable);
        return immutable;
    }

    private static void rejectCycles(Map<String, OptionalCapabilityDescriptorV1> indexed) {
        Map<String, Visit> visits = new HashMap<>();
        for (String capabilityId : indexed.keySet()) {
            visit(capabilityId, indexed, visits, new ArrayList<>());
        }
    }

    private static void visit(
            String capabilityId,
            Map<String, OptionalCapabilityDescriptorV1> indexed,
            Map<String, Visit> visits,
            List<String> path) {
        if (visits.get(capabilityId) == Visit.COMPLETE) {
            return;
        }
        if (visits.get(capabilityId) == Visit.ACTIVE) {
            throw configuration(DEPENDENCY_CYCLE, capabilityId,
                    String.join("->", path) + "->" + capabilityId);
        }
        visits.put(capabilityId, Visit.ACTIVE);
        path.add(capabilityId);
        for (String dependency : indexed.get(capabilityId).capabilityPrerequisites()) {
            visit(dependency, indexed, visits, path);
        }
        path.remove(path.size() - 1);
        visits.put(capabilityId, Visit.COMPLETE);
    }

    private static OptionalCapabilityAdmissionExceptionV1 configuration(
            OptionalCapabilityAdmissionErrorV1.Code code,
            String capabilityId,
            String detail) {
        return new OptionalCapabilityAdmissionExceptionV1(
                new OptionalCapabilityAdmissionErrorV1(
                        code, capabilityId, CONFIGURATION_OPERATION,
                        capabilityId, CONFIGURATION_FAILURE + ":" + detail));
    }

    private enum Visit {
        ACTIVE,
        COMPLETE
    }

    private record AdmissionFailure(
            DecisionKind kind,
            OptionalCapabilityAdmissionErrorV1.Code code,
            String failingCapabilityId,
            String failureCode) {
    }
}
