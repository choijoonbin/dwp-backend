package com.dwp.platform.contracts.hris.capability.v1;

import com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.Module;
import java.util.Map;

/** Closed per-operation request; absence from installations means uninstalled. */
public record OptionalCapabilityAdmissionRequestV1(
        Module consumer,
        String capabilityId,
        String operation,
        Map<String, OptionalCapabilityInstallStateV1> installations) {
    public OptionalCapabilityAdmissionRequestV1 {
        if (consumer == null || !text(capabilityId) || !text(operation) || installations == null
                || installations.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                        || entry.getValue() == null
                        || !entry.getKey().equals(entry.getValue().capabilityId()))) {
            throw new IllegalArgumentException("capability request must be closed and key-consistent");
        }
        installations = Map.copyOf(installations);
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim())
                && value.length() <= 200 && value.chars().noneMatch(Character::isISOControl);
    }
}
