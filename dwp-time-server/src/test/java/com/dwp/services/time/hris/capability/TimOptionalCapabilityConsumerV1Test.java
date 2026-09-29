package com.dwp.services.time.hris.capability;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityAdmissionErrorV1.Code.PREREQUISITE_NOT_READY;
import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.platform.contracts.hris.capability.v1.*;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** TIM START consumer proof; this is an explicit mock, not a production WFM adapter. */
class TimOptionalCapabilityConsumerV1Test {
    private static final OptionalCapabilityVersionV1 V1 =
            new OptionalCapabilityVersionV1(1, 0, 0);

    @Test
    void wfmSkillMatchNeedsWfmAndSkillsWhileTimeCoreStaysIndependent() {
        var skills = descriptor("CAP.SKILLS_GRAPH", Module.HRIS_PER,
                Set.of(Module.HRIS_HRM, Module.HRIS_PER, Module.HRIS_TIM), Set.of(),
                Set.of(BindingKind.PROVIDER_ADAPTER), Set.of("PER.SKILLS.MATCH"),
                Set.of("TIM.TIME.CORE_RECORD"));
        var wfm = descriptor("CAP.WFM_OPTIMIZATION", Module.HRIS_TIM,
                Set.of(Module.HRIS_HRM, Module.HRIS_PER, Module.HRIS_TIM), Set.of(),
                Set.of(BindingKind.PROVIDER_ADAPTER), Set.of("TIM.WFM.OPTIMIZE"), Set.of());
        var match = descriptor("CAP.WFM_SKILL_MATCH", Module.HRIS_TIM,
                Set.of(Module.HRIS_PER, Module.HRIS_TIM),
                Set.of(skills.capabilityId(), wfm.capabilityId()), Set.of(),
                Set.of("TIM.WFM.SKILL_MATCH"), Set.of());
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(skills, wfm, match));
        var states = Map.of(
                match.capabilityId(), enabled(match.capabilityId(), Set.of()),
                wfm.capabilityId(), enabled(wfm.capabilityId(),
                        Set.of(BindingKind.PROVIDER_ADAPTER)),
                skills.capabilityId(), OptionalCapabilityInstallStateV1.uninstalled(
                        skills.capabilityId()));
        AtomicInteger skillsCalls = new AtomicInteger();
        var skillMatch = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_TIM, match.capabilityId(), "TIM.WFM.SKILL_MATCH", states);

        var failure = assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(skillMatch, ignored -> skillsCalls.incrementAndGet()));
        assertEquals(PREREQUISITE_NOT_READY, failure.error().code());

        var core = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_TIM, skills.capabilityId(), "TIM.TIME.CORE_RECORD", states);
        String result = guard.execute(core, ignored -> "time-recorded");
        assertEquals("time-recorded", result);
        assertEquals(0, skillsCalls.get());
    }

    private static OptionalCapabilityInstallStateV1 enabled(
            String capabilityId, Set<BindingKind> bindings) {
        return new OptionalCapabilityInstallStateV1(
                capabilityId, InstallStatus.ENABLED, V1, bindings);
    }

    private static OptionalCapabilityDescriptorV1 descriptor(
            String id, Module owner, Set<Module> consumers, Set<String> dependencies,
            Set<BindingKind> bindings, Set<String> operations,
            Set<String> preservedOperations) {
        return new OptionalCapabilityDescriptorV1(
                id, owner, consumers, V1, InstallStatus.DISABLED,
                dependencies, bindings, Set.of(), operations, preservedOperations,
                "HRIS_CAPABILITY_OPTIONAL_UNAVAILABLE",
                MissingEffect.AFFECTED_OPERATION_ONLY, ActivationStage.G6);
    }
}
