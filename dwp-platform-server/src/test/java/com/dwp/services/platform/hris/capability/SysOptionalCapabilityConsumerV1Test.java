package com.dwp.services.platform.hris.capability;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.platform.contracts.hris.capability.v1.*;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** SYS START consumer proofs; no credential, provider adapter or tenant activation is wired. */
class SysOptionalCapabilityConsumerV1Test {
    private static final OptionalCapabilityVersionV1 V1 =
            new OptionalCapabilityVersionV1(1, 0, 0);

    @Test
    void connectorBindingFailureBlocksJobAndPreservesManualExport() {
        var connector = descriptor("CAP.ERP_CONNECTOR",
                Set.of(BindingKind.PROVIDER_ADAPTER, BindingKind.CONNECTOR_MAPPING,
                        BindingKind.CONNECTOR_CREDENTIAL), Set.of(),
                Set.of("SYS.CONNECTOR.ERP.RUN"),
                Set.of("SYS.CONNECTOR.MANUAL_EXPORT"),
                MissingEffect.JOB_ONLY_MANUAL_PATH_PRESERVED);
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(connector));
        var partial = new OptionalCapabilityInstallStateV1(
                connector.capabilityId(), InstallStatus.ENABLED, V1,
                Set.of(BindingKind.PROVIDER_ADAPTER));
        AtomicInteger connectorCalls = new AtomicInteger();
        var job = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_SYS, connector.capabilityId(), "SYS.CONNECTOR.ERP.RUN",
                Map.of(connector.capabilityId(), partial));

        assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(job, ignored -> connectorCalls.incrementAndGet()));
        var manual = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_SYS, connector.capabilityId(),
                "SYS.CONNECTOR.MANUAL_EXPORT", Map.of());
        String result = guard.execute(manual, ignored -> "exported");
        assertEquals("exported", result);
        assertEquals(DecisionKind.ALLOW_MANUAL_PATH, guard.evaluate(manual).kind());
        assertEquals(0, connectorCalls.get());
    }

    @Test
    void analyticsAdmissionNeverAuthorizesListeningProtectedBindings() {
        var analytics = descriptor("CAP.ANALYTICS_AI",
                Set.of(BindingKind.TENANT_CONFIG, BindingKind.INSIGHTS_STORE),
                Set.of(BindingKind.PROTECTED_STORE, BindingKind.CURRENT_ISSUER),
                Set.of("SYS.ANALYTICS.AGGREGATE"),
                Set.of(),
                MissingEffect.AFFECTED_OPERATION_ONLY);
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(analytics));
        var installed = new OptionalCapabilityInstallStateV1(
                analytics.capabilityId(), InstallStatus.ENABLED, V1,
                Set.of(BindingKind.TENANT_CONFIG, BindingKind.INSIGHTS_STORE,
                        BindingKind.PROTECTED_STORE, BindingKind.CURRENT_ISSUER));
        var request = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_SYS, analytics.capabilityId(), "SYS.ANALYTICS.AGGREGATE",
                Map.of(analytics.capabilityId(), installed));

        var decision = guard.execute(request, admitted -> admitted);
        assertFalse(decision.authorizedBindings().contains(
                new OptionalCapabilityAuthorizedBindingV1(
                        analytics.capabilityId(), BindingKind.PROTECTED_STORE)));
        assertFalse(decision.authorizedBindings().contains(
                new OptionalCapabilityAuthorizedBindingV1(
                        analytics.capabilityId(), BindingKind.CURRENT_ISSUER)));
    }

    private static OptionalCapabilityDescriptorV1 descriptor(
            String id,
            Set<BindingKind> required,
            Set<BindingKind> forbidden,
            Set<String> operations,
            Set<String> preservedOperations,
            MissingEffect effect) {
        return new OptionalCapabilityDescriptorV1(
                id, Module.HRIS_SYS, Set.of(Module.HRIS_SYS), V1, InstallStatus.DISABLED,
                Set.of(), required, forbidden, operations, preservedOperations,
                "HRIS_CAPABILITY_SYS_UNAVAILABLE", effect, ActivationStage.G6);
    }
}
