package com.dwp.services.people.hris.capability.per;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityAdmissionErrorV1.Code.CAPABILITY_UNINSTALLED;
import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.platform.contracts.hris.capability.v1.*;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** PER START consumer proof; this is an explicit mock, not a production LMS adapter. */
class PerOptionalCapabilityConsumerV1Test {
    @Test
    void uninstalledLmsCannotReceiveAssignmentButGoalCoreIsIndependent() {
        var lms = descriptor();
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(lms));
        AtomicInteger lmsCalls = new AtomicInteger();
        var assign = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_PER, lms.capabilityId(), "PER.LEARNING.ASSIGN",
                Map.of(lms.capabilityId(),
                        OptionalCapabilityInstallStateV1.uninstalled(lms.capabilityId())));

        var failure = assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(assign, ignored -> lmsCalls.incrementAndGet()));
        assertEquals(CAPABILITY_UNINSTALLED, failure.error().code());
        assertEquals(0, lmsCalls.get());

        var goalCore = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_PER, lms.capabilityId(), "PER.GOAL.CORE_UPDATE", Map.of());
        String result = guard.execute(goalCore, ignored -> "goal-updated");
        assertEquals("goal-updated", result);
        assertTrue(guard.evaluate(goalCore).authorizedBindings().isEmpty());
    }

    private static OptionalCapabilityDescriptorV1 descriptor() {
        return new OptionalCapabilityDescriptorV1(
                "CAP.LMS_LEARNING", Module.HRIS_PER, Set.of(Module.HRIS_PER),
                new OptionalCapabilityVersionV1(1, 0, 0), InstallStatus.DISABLED,
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER), Set.of(),
                Set.of("PER.LEARNING.ASSIGN"),
                Set.of("PER.GOAL.CORE_UPDATE"),
                "HRIS_CAPABILITY_LMS_UNAVAILABLE", MissingEffect.AFFECTED_OPERATION_ONLY,
                ActivationStage.G6);
    }
}
