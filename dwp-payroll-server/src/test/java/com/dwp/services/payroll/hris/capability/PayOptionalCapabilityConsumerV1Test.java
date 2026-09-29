package com.dwp.services.payroll.hris.capability;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityAdmissionErrorV1.Code.REQUIRED_BINDING_MISSING;
import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.platform.contracts.hris.capability.v1.*;
import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** PAY START consumer proof; this is not a certified country-pack implementation. */
class PayOptionalCapabilityConsumerV1Test {
    @Test
    void missingCountryPackBlocksStatutoryOnlyAndPreservesGenericAuthoring() {
        var country = descriptor();
        var guard = new OptionalCapabilityAdmissionGuardV1(List.of(country));
        var enabledWithoutPack = new OptionalCapabilityInstallStateV1(
                country.capabilityId(), InstallStatus.ENABLED,
                new OptionalCapabilityVersionV1(1, 0, 0), Set.of());
        AtomicInteger countryPackCalls = new AtomicInteger();
        var statutory = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_PAY, country.capabilityId(), "PAY.STATUTORY.CALCULATE",
                Map.of(country.capabilityId(), enabledWithoutPack));

        var failure = assertThrows(OptionalCapabilityAdmissionExceptionV1.class,
                () -> guard.execute(statutory, ignored -> countryPackCalls.incrementAndGet()));
        assertEquals(REQUIRED_BINDING_MISSING, failure.error().code());
        assertEquals(0, countryPackCalls.get());

        var generic = new OptionalCapabilityAdmissionRequestV1(
                Module.HRIS_PAY, country.capabilityId(),
                "PAY.PAYROLL.GENERIC_AUTHOR", Map.of());
        String result = guard.execute(generic, ignored -> "draft-created");
        assertEquals("draft-created", result);
        assertEquals(0, countryPackCalls.get());
    }

    private static OptionalCapabilityDescriptorV1 descriptor() {
        return new OptionalCapabilityDescriptorV1(
                "CAP.COUNTRY_PAY_RULES", Module.HRIS_PAY, Set.of(Module.HRIS_PAY),
                new OptionalCapabilityVersionV1(1, 0, 0), InstallStatus.DISABLED,
                Set.of(), Set.of(BindingKind.COUNTRY_RULE_PROVIDER), Set.of(),
                Set.of("PAY.STATUTORY.CALCULATE"),
                Set.of("PAY.PAYROLL.GENERIC_AUTHOR"),
                "HRIS_COUNTRY_PAY_PACK_UNAVAILABLE",
                MissingEffect.STATUTORY_ONLY_GENERIC_PAYROLL_PRESERVED,
                ActivationStage.G6);
    }
}
