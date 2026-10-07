package com.dwp.platform.contracts.hris.capability.v1;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityAdmissionErrorV1.Code.*;
import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OptionalCapabilityAdmissionGuardV1Test {
    private static final OptionalCapabilityVersionV1 V1 =
            new OptionalCapabilityVersionV1(1, 0, 0);

    @Test
    void absentOrDisabledCapabilityDeniesAffectedCallButPreservesUnrelatedCore() {
        var ats = descriptor("CAP.ATS_RECRUITING", Module.HRIS_HRM,
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER), Set.of(),
                Set.of("HRM.RECRUITING.REQUISITION"),
                Set.of("HRM.WORKER.CORE_UPDATE"),
                MissingEffect.AFFECTED_OPERATION_ONLY, "HRIS_CAPABILITY_ATS_UNAVAILABLE");
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(ats));
        AtomicInteger providerCalls = new AtomicInteger();

        var absent = request(ats.capabilityId(), "HRM.RECRUITING.REQUISITION", Map.of());
        var absentFailure = assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(absent, ignored -> providerCalls.incrementAndGet()));
        assertEquals(CAPABILITY_UNINSTALLED, absentFailure.error().code());

        var disabled = request(ats.capabilityId(), "HRM.RECRUITING.REQUISITION",
                Map.of(ats.capabilityId(), disabled(ats.capabilityId(), Set.of(
                        BindingKind.PROVIDER_ADAPTER))));
        var disabledFailure = assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(disabled, ignored -> providerCalls.incrementAndGet()));
        assertEquals(CAPABILITY_DISABLED, disabledFailure.error().code());

        AtomicInteger coreCalls = new AtomicInteger();
        var core = request(ats.capabilityId(), "HRM.WORKER.CORE_UPDATE", Map.of());
        int coreResult = guard.execute(core, ignored -> coreCalls.incrementAndGet());
        assertEquals(1, coreResult);
        assertEquals(1, coreCalls.get());
        assertEquals(0, providerCalls.get());
        assertTrue(guard.evaluate(core).authorizedBindings().isEmpty());
    }

    @Test
    void missingBindingAndVersionDriftDenyBeforeProviderCall() {
        var lms = descriptor("CAP.LMS_LEARNING", Module.HRIS_PER,
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER), Set.of(),
                Set.of("PER.LEARNING.ASSIGN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_LMS_UNAVAILABLE");
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(lms));
        AtomicInteger providerCalls = new AtomicInteger();

        var missingBinding = request(lms.capabilityId(), "PER.LEARNING.ASSIGN",
                Map.of(lms.capabilityId(), enabled(lms.capabilityId(), V1, Set.of())));
        assertEquals(REQUIRED_BINDING_MISSING,
                denied(guard, missingBinding, providerCalls).error().code());

        for (OptionalCapabilityVersionV1 incompatible : List.of(
                new OptionalCapabilityVersionV1(0, 9, 9),
                new OptionalCapabilityVersionV1(2, 0, 0))) {
            var request = request(lms.capabilityId(), "PER.LEARNING.ASSIGN",
                    Map.of(lms.capabilityId(), enabled(lms.capabilityId(), incompatible,
                            Set.of(BindingKind.PROVIDER_ADAPTER))));
            assertEquals(VERSION_INCOMPATIBLE,
                    denied(guard, request, providerCalls).error().code());
        }

        var compatible = request(lms.capabilityId(), "PER.LEARNING.ASSIGN",
                Map.of(lms.capabilityId(), enabled(lms.capabilityId(),
                        new OptionalCapabilityVersionV1(1, 1, 0),
                        Set.of(BindingKind.PROVIDER_ADAPTER))));
        int providerResult = guard.execute(
                compatible, ignored -> providerCalls.incrementAndGet());
        assertEquals(1, providerResult);
        assertEquals(1, providerCalls.get());
    }

    @Test
    void dependencyClosureDeniesWfmSkillMatchWithoutSkillsButLeavesCoreUntouched() {
        var skills = descriptor("CAP.SKILLS_GRAPH", Module.HRIS_PER,
                Set.of(Module.HRIS_HRM, Module.HRIS_PER, Module.HRIS_TIM),
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER), Set.of(),
                Set.of("PER.SKILLS.MATCH"),
                Set.of("TIM.TIME.CORE_RECORD", "HRM.ASSIGNMENT.CORE_UPDATE"),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_SKILLS_UNAVAILABLE");
        var wfm = descriptor("CAP.WFM_OPTIMIZATION", Module.HRIS_TIM,
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER), Set.of(),
                Set.of("TIM.WFM.OPTIMIZE"), Set.of(), MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_WFM_UNAVAILABLE");
        var match = descriptor("CAP.WFM_SKILL_MATCH", Module.HRIS_TIM,
                Set.of(skills.capabilityId(), wfm.capabilityId()), Set.of(), Set.of(),
                Set.of("TIM.WFM.SKILL_MATCH"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_WFM_SKILLS_UNAVAILABLE");
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(skills, wfm, match));
        var installs = Map.of(
                match.capabilityId(), enabled(match.capabilityId(), V1, Set.of()),
                wfm.capabilityId(), enabled(wfm.capabilityId(), V1,
                        Set.of(BindingKind.PROVIDER_ADAPTER)),
                skills.capabilityId(), OptionalCapabilityInstallStateV1.uninstalled(
                        skills.capabilityId()));
        AtomicInteger skillCalls = new AtomicInteger();

        var matchRequest = request(match.capabilityId(), "TIM.WFM.SKILL_MATCH", installs);
        var failure = denied(guard, matchRequest, skillCalls);
        assertEquals(PREREQUISITE_NOT_READY, failure.error().code());
        assertEquals(skills.capabilityId(), failure.error().failingCapabilityId());

        var readyInstalls = Map.of(
                match.capabilityId(), enabled(match.capabilityId(), V1, Set.of()),
                wfm.capabilityId(), enabled(wfm.capabilityId(), V1,
                        Set.of(BindingKind.PROVIDER_ADAPTER)),
                skills.capabilityId(), enabled(skills.capabilityId(), V1,
                        Set.of(BindingKind.PROVIDER_ADAPTER)));
        var readyMatch = request(
                match.capabilityId(), "TIM.WFM.SKILL_MATCH", readyInstalls);
        var readyDecision = guard.execute(readyMatch, admission -> admission);
        assertEquals(Set.of(
                new OptionalCapabilityAuthorizedBindingV1(
                        wfm.capabilityId(), BindingKind.PROVIDER_ADAPTER),
                new OptionalCapabilityAuthorizedBindingV1(
                        skills.capabilityId(), BindingKind.PROVIDER_ADAPTER)),
                readyDecision.authorizedBindings());

        var timeCore = request(skills.capabilityId(), "TIM.TIME.CORE_RECORD", installs);
        var hrCore = request(skills.capabilityId(), "HRM.ASSIGNMENT.CORE_UPDATE", installs);
        int timeResult = guard.execute(timeCore, ignored -> 7);
        int hrResult = guard.execute(hrCore, ignored -> 9);
        assertEquals(7, timeResult);
        assertEquals(9, hrResult);
        assertEquals(0, skillCalls.get());
    }

    @Test
    void countryAndConnectorFailuresRemainFeatureLocal() {
        var country = descriptor("CAP.COUNTRY_PAY_RULES", Module.HRIS_PAY,
                Set.of(), Set.of(BindingKind.COUNTRY_RULE_PROVIDER), Set.of(),
                Set.of("PAY.STATUTORY.CALCULATE"),
                Set.of("PAY.PAYROLL.GENERIC_AUTHOR"),
                MissingEffect.STATUTORY_ONLY_GENERIC_PAYROLL_PRESERVED,
                "HRIS_COUNTRY_PAY_PACK_UNAVAILABLE");
        var connector = descriptor("CAP.ERP_CONNECTOR", Module.HRIS_SYS,
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER,
                        BindingKind.CONNECTOR_MAPPING, BindingKind.CONNECTOR_CREDENTIAL),
                Set.of(), Set.of("SYS.CONNECTOR.ERP.RUN"),
                Set.of("SYS.CONNECTOR.MANUAL_EXPORT"),
                MissingEffect.JOB_ONLY_MANUAL_PATH_PRESERVED,
                "HRIS_CONNECTOR_ERP_UNAVAILABLE");
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(country, connector));
        AtomicInteger externalCalls = new AtomicInteger();

        var statutory = request(country.capabilityId(), "PAY.STATUTORY.CALCULATE",
                Map.of(country.capabilityId(), enabled(country.capabilityId(), V1, Set.of())));
        assertEquals(REQUIRED_BINDING_MISSING,
                denied(guard, statutory, externalCalls).error().code());
        int genericResult = guard.execute(request(country.capabilityId(),
                "PAY.PAYROLL.GENERIC_AUTHOR", Map.of()), ignored -> 1);
        assertEquals(1, genericResult);

        var connectorJob = request(connector.capabilityId(), "SYS.CONNECTOR.ERP.RUN",
                Map.of(connector.capabilityId(), enabled(connector.capabilityId(), V1,
                        Set.of(BindingKind.PROVIDER_ADAPTER))));
        assertEquals(REQUIRED_BINDING_MISSING,
                denied(guard, connectorJob, externalCalls).error().code());
        var manualDecision = guard.evaluate(request(connector.capabilityId(),
                "SYS.CONNECTOR.MANUAL_EXPORT", Map.of()));
        assertEquals(DecisionKind.ALLOW_MANUAL_PATH, manualDecision.kind());
        assertEquals(0, externalCalls.get());
    }

    @Test
    void analyticsCannotReceiveListeningBindingsWhileListeningRequiresEveryOwner() {
        Set<BindingKind> allListeningBindings = Set.of(
                BindingKind.TENANT_CONFIG, BindingKind.INSIGHTS_STORE,
                BindingKind.PROTECTED_STORE, BindingKind.CURRENT_ISSUER);
        var analytics = descriptor("CAP.ANALYTICS_AI", Module.HRIS_SYS,
                Set.of(), Set.of(BindingKind.TENANT_CONFIG, BindingKind.INSIGHTS_STORE),
                Set.of(BindingKind.PROTECTED_STORE, BindingKind.CURRENT_ISSUER),
                Set.of("SYS.ANALYTICS.AGGREGATE"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_ANALYTICS_AI_UNAVAILABLE");
        var listening = descriptor("CAP.EMPLOYEE_LISTENING", Module.HRIS_SYS,
                Set.of(), allListeningBindings, Set.of(),
                Set.of("SYS.LISTENING.COLLECT"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_LISTENING_PREREQUISITE_UNAVAILABLE");
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(analytics, listening));
        AtomicInteger protectedCalls = new AtomicInteger();
        AtomicInteger issuerCalls = new AtomicInteger();

        var analyticsRequest = request(analytics.capabilityId(), "SYS.ANALYTICS.AGGREGATE",
                Map.of(analytics.capabilityId(), enabled(
                        analytics.capabilityId(), V1, allListeningBindings)));
        AtomicInteger analyticsCalls = new AtomicInteger();
        var analyticsDecision = guard.execute(analyticsRequest, admission -> {
            analyticsCalls.incrementAndGet();
            admission.authorizedBindings().forEach(binding -> {
                if (binding.kind() == BindingKind.PROTECTED_STORE) {
                    protectedCalls.incrementAndGet();
                }
                if (binding.kind() == BindingKind.CURRENT_ISSUER) {
                    issuerCalls.incrementAndGet();
                }
            });
            return admission;
        });
        assertEquals(Set.of(
                new OptionalCapabilityAuthorizedBindingV1(
                        analytics.capabilityId(), BindingKind.TENANT_CONFIG),
                new OptionalCapabilityAuthorizedBindingV1(
                        analytics.capabilityId(), BindingKind.INSIGHTS_STORE)),
                analyticsDecision.authorizedBindings());
        assertEquals(1, analyticsCalls.get());
        assertEquals(0, protectedCalls.get());
        assertEquals(0, issuerCalls.get());

        AtomicInteger listeningCalls = new AtomicInteger();
        for (BindingKind missing : allListeningBindings) {
            Set<BindingKind> remaining = allListeningBindings.stream()
                    .filter(binding -> binding != missing)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var listeningRequest = request(
                    listening.capabilityId(), "SYS.LISTENING.COLLECT",
                    Map.of(listening.capabilityId(), enabled(
                            listening.capabilityId(), V1, remaining)));
            assertEquals(REQUIRED_BINDING_MISSING,
                    denied(guard, listeningRequest, listeningCalls).error().code());
        }
        assertEquals(0, listeningCalls.get());

        var readyListening = request(listening.capabilityId(), "SYS.LISTENING.COLLECT",
                Map.of(listening.capabilityId(), enabled(
                        listening.capabilityId(), V1, allListeningBindings)));
        var listeningDecision = guard.execute(readyListening, admission -> admission);
        assertEquals(allListeningBindings, listeningDecision.authorizedBindings().stream()
                .map(OptionalCapabilityAuthorizedBindingV1::kind)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @Test
    void unknownDuplicateUnknownDependencyOperationCollisionAndCycleRejectClosed() {
        var base = descriptor("CAP.BASE", Module.HRIS_SYS, Set.of(), Set.of(), Set.of(),
                Set.of("SYS.BASE.RUN"), Set.of(), MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_BASE_UNAVAILABLE");
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(base));
        var unknown = guard.evaluate(request("CAP.UNKNOWN", "SYS.UNKNOWN.RUN", Map.of()));
        assertEquals(DecisionKind.DENY_UNKNOWN_CAPABILITY, unknown.kind());
        assertEquals(UNKNOWN_CAPABILITY, unknown.error().code());
        var unknownOperation = guard.evaluate(request(
                base.capabilityId(), "SYS.BASE.UNREGISTERED", Map.of()));
        assertEquals(DecisionKind.DENY_INVALID_REQUEST, unknownOperation.kind());
        assertEquals(UNKNOWN_OPERATION, unknownOperation.error().code());
        var foreignConsumer = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_PAY, base.capabilityId(), "SYS.BASE.RUN", Map.of());
        assertEquals(UNAUTHORIZED_CONSUMER, guard.evaluate(foreignConsumer).error().code());

        assertConfiguration(DUPLICATE_CAPABILITY,
                () -> new OptionalCapabilityAdmissionGuardV1(List.of(base, base)));
        var unknownDependency = descriptor("CAP.DEPENDENT", Module.HRIS_SYS,
                Set.of("CAP.MISSING"), Set.of(), Set.of(), Set.of("SYS.DEPENDENT.RUN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_DEPENDENT_UNAVAILABLE");
        assertConfiguration(UNKNOWN_PREREQUISITE,
                () -> new OptionalCapabilityAdmissionGuardV1(List.of(unknownDependency)));
        var collision = descriptor("CAP.COLLISION", Module.HRIS_SYS,
                Set.of(), Set.of(), Set.of(), Set.of("SYS.BASE.RUN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_COLLISION_UNAVAILABLE");
        assertConfiguration(OPERATION_COLLISION,
                () -> new OptionalCapabilityAdmissionGuardV1(List.of(base, collision)));

        var protectedDependency = descriptor("CAP.PROTECTED_DEPENDENCY", Module.HRIS_SYS,
                Set.of(), Set.of(BindingKind.PROTECTED_STORE), Set.of(),
                Set.of("SYS.PROTECTED.RUN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_PROTECTED_UNAVAILABLE");
        var isolatedAnalytics = descriptor("CAP.ISOLATED_ANALYTICS", Module.HRIS_SYS,
                Set.of(protectedDependency.capabilityId()), Set.of(BindingKind.INSIGHTS_STORE),
                Set.of(BindingKind.PROTECTED_STORE), Set.of("SYS.ISOLATED.RUN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY,
                "HRIS_CAPABILITY_ISOLATED_UNAVAILABLE");
        assertConfiguration(FORBIDDEN_BINDING_DEPENDENCY,
                () -> new OptionalCapabilityAdmissionGuardV1(
                        List.of(protectedDependency, isolatedAnalytics)));

        var cycleA = descriptor("CAP.CYCLE_A", Module.HRIS_SYS,
                Set.of("CAP.CYCLE_B"), Set.of(), Set.of(), Set.of("SYS.CYCLE_A.RUN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY, "HRIS_CAPABILITY_CYCLE_A_UNAVAILABLE");
        var cycleB = descriptor("CAP.CYCLE_B", Module.HRIS_SYS,
                Set.of("CAP.CYCLE_A"), Set.of(), Set.of(), Set.of("SYS.CYCLE_B.RUN"), Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY, "HRIS_CAPABILITY_CYCLE_B_UNAVAILABLE");
        assertConfiguration(DEPENDENCY_CYCLE,
                () -> new OptionalCapabilityAdmissionGuardV1(List.of(cycleA, cycleB)));
    }

    private static OptionalCapabilityAdmissionExceptionV1 denied(
            OptionalCapabilityAdmissionGuardV1 guard,
            OptionalCapabilityAdmissionRequestV1 request,
            AtomicInteger providerCalls) {
        OptionalCapabilityAdmissionExceptionV1 failure = assertThrows(
                OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(request, ignored -> providerCalls.incrementAndGet()));
        assertEquals(0, providerCalls.get());
        assertTrue(failure.error().failureCode().startsWith("HRIS_"));
        return failure;
    }

    private static void assertConfiguration(
            OptionalCapabilityAdmissionErrorV1.Code code, Runnable configuration) {
        OptionalCapabilityAdmissionExceptionV1 failure = assertThrows(
                OptionalCapabilityAdmissionExceptionV1.class, configuration::run);
        assertEquals(code, failure.error().code());
    }

    private static OptionalCapabilityAdmissionRequestV1 request(
            String capabilityId,
            String operation,
            Map<String, OptionalCapabilityInstallStateV1> installations) {
        return new OptionalCapabilityAdmissionRequestV1(
                moduleFor(operation), capabilityId, operation, installations);
    }

    private static Module moduleFor(String operation) {
        if (operation.startsWith("HRM.")) return Module.HRIS_HRM;
        if (operation.startsWith("PER.")) return Module.HRIS_PER;
        if (operation.startsWith("PAY.")) return Module.HRIS_PAY;
        if (operation.startsWith("TIM.")) return Module.HRIS_TIM;
        return Module.HRIS_SYS;
    }

    private static OptionalCapabilityInstallStateV1 enabled(
            String capabilityId,
            OptionalCapabilityVersionV1 version,
            Set<BindingKind> bindings) {
        return new OptionalCapabilityInstallStateV1(
                capabilityId, InstallStatus.ENABLED, version, bindings);
    }

    private static OptionalCapabilityInstallStateV1 disabled(
            String capabilityId, Set<BindingKind> bindings) {
        return new OptionalCapabilityInstallStateV1(
                capabilityId, InstallStatus.DISABLED, V1, bindings);
    }

    private static OptionalCapabilityDescriptorV1 descriptor(
            String capabilityId,
            Module owner,
            Set<String> dependencies,
            Set<BindingKind> required,
            Set<BindingKind> forbidden,
            Set<String> operations,
            Set<String> preservedOperations,
            MissingEffect effect,
            String failureCode) {
        return descriptor(capabilityId, owner, Set.of(owner), dependencies, required,
                forbidden, operations, preservedOperations, effect, failureCode);
    }

    private static OptionalCapabilityDescriptorV1 descriptor(
            String capabilityId,
            Module owner,
            Set<Module> consumers,
            Set<String> dependencies,
            Set<BindingKind> required,
            Set<BindingKind> forbidden,
            Set<String> operations,
            Set<String> preservedOperations,
            MissingEffect effect,
            String failureCode) {
        return new OptionalCapabilityDescriptorV1(
                capabilityId, owner, consumers, V1, InstallStatus.DISABLED,
                dependencies, required, forbidden, operations, preservedOperations, failureCode,
                effect, ActivationStage.G6);
    }
}
