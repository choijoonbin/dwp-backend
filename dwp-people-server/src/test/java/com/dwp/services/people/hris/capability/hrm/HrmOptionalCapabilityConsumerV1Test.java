package com.dwp.services.people.hris.capability.hrm;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.platform.contracts.hris.capability.v1.*;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HRM START consumer proof; this is an explicit mock, not a production ATS adapter. */
class HrmOptionalCapabilityConsumerV1Test {
    @Test
    void disabledAtsCannotBeCalledWhileWorkerCoreRemainsAvailable() {
        var ats = descriptor();
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(ats));
        var state = new OptionalCapabilityInstallStateV1(
                ats.capabilityId(), InstallStatus.DISABLED,
                new OptionalCapabilityVersionV1(1, 0, 0),
                Set.of(BindingKind.PROVIDER_ADAPTER));
        AtomicInteger atsCalls = new AtomicInteger();
        var requisition = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_HRM, ats.capabilityId(), "HRM.RECRUITING.REQUISITION",
                Map.of(ats.capabilityId(), state));

        assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(requisition, ignored -> atsCalls.incrementAndGet()));
        assertEquals(0, atsCalls.get());

        var workerCore = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_HRM, ats.capabilityId(), "HRM.WORKER.CORE_UPDATE", Map.of());
        String result = guard.execute(workerCore, ignored -> "worker-updated");
        assertEquals("worker-updated", result);
        assertEquals(DecisionKind.ALLOW_UNRELATED_CORE, guard.evaluate(workerCore).kind());
    }

    private static OptionalCapabilityDescriptorV1 descriptor() {
        return new OptionalCapabilityDescriptorV1(
                "CAP.ATS_RECRUITING", Module.HRIS_HRM, Set.of(Module.HRIS_HRM),
                new OptionalCapabilityVersionV1(1, 0, 0), InstallStatus.DISABLED,
                Set.of(), Set.of(BindingKind.PROVIDER_ADAPTER), Set.of(),
                Set.of("HRM.RECRUITING.REQUISITION"),
                Set.of("HRM.WORKER.CORE_UPDATE"),
                "HRIS_CAPABILITY_ATS_UNAVAILABLE", MissingEffect.AFFECTED_OPERATION_ONLY,
                ActivationStage.G6);
    }
}
