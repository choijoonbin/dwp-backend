package com.dwp.services.provider.settings;

import java.util.List;

public interface SettingsOwnerAdapter {

    String authority();

    String readPermission();

    List<SettingsContracts.Definition> definitions();

    default SettingsContracts.ScopeTarget canonicalTarget(
            SettingsContracts.ScopeTarget target) {
        return target;
    }

    SettingsContracts.OwnerSnapshot resolve(
            SettingsContracts.Definition definition,
            SettingsContracts.ScopeTarget target);
}
