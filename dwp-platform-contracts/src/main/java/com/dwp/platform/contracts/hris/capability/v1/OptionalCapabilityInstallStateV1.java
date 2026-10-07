package com.dwp.platform.contracts.hris.capability.v1;

import static com.dwp.platform.contracts.hris.capability.v1.OptionalCapabilityTypesV1.*;

import java.util.Set;

/** Runtime-neutral input snapshot. Secrets and live provider objects are deliberately excluded. */
public record OptionalCapabilityInstallStateV1(
        String capabilityId,
        InstallStatus status,
        OptionalCapabilityVersionV1 installedVersion,
        Set<BindingKind> availableBindings) {
    public OptionalCapabilityInstallStateV1 {
        if (capabilityId == null || capabilityId.isBlank() || status == null
                || availableBindings == null || availableBindings.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("closed install-state fields are required");
        }
        availableBindings = Set.copyOf(availableBindings);
        if (status == InstallStatus.UNINSTALLED
                && (installedVersion != null || !availableBindings.isEmpty())) {
            throw new IllegalArgumentException("uninstalled capability cannot expose version or bindings");
        }
        if (status != InstallStatus.UNINSTALLED && installedVersion == null) {
            throw new IllegalArgumentException("installed capability requires its observed version");
        }
    }

    public static OptionalCapabilityInstallStateV1 uninstalled(String capabilityId) {
        return new OptionalCapabilityInstallStateV1(
                capabilityId, InstallStatus.UNINSTALLED, null, Set.of());
    }
}
