package com.dwp.services.provider.settings;

import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsDefinitionRegistryTest {

    @AfterEach
    void clearContext() {
        ProviderRequestContext.clear();
    }

    @Test
    void rejectsDuplicateAdapterAuthoritiesAtConstruction() {
        assertThatThrownBy(() -> new SettingsDefinitionRegistry(List.of(
                owner("same", "one.setting"), owner("same", "two.setting"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate settings authority same");
    }

    @Test
    void rejectsDuplicateSettingIdsAcrossAuthorizedOwners() {
        ProviderRequestContext.set(new ProviderRequestContext.Actor(
                1L, 2L, 3L, "operator", Set.of("PROVIDER_ADMIN"),
                Set.of("EXAMPLE_READ"),
                UUID.fromString("00000000-0000-0000-0000-000000000001")));
        SettingsDefinitionRegistry registry = new SettingsDefinitionRegistry(List.of(
                owner("one", "duplicate.setting"),
                owner("two", "duplicate.setting")));

        assertThatThrownBy(registry::authorizedDefinitions)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple owners registered setting ID duplicate.setting");
    }

    private SettingsOwnerAdapter owner(String authority, String settingId) {
        SettingsContracts.Definition definition = new SettingsContracts.Definition(
                settingId, settingId, "Registry test setting.",
                new SettingsContracts.Owner(
                        "example", "EXAMPLE", "EXAMPLE_READ", "/settings"),
                Set.of(SettingsContracts.ScopeType.PROVIDER),
                new SettingsContracts.ValidationContract(
                        SettingsContracts.ValueType.STRING, "v1",
                        JsonMapper.builder().build().createObjectNode(), true),
                SettingsContracts.Sensitivity.INTERNAL,
                new SettingsContracts.ChangeContract(
                        "L1", SettingsContracts.ChangeWorkflow.OWNER_MANAGED,
                        false, false),
                SettingsContracts.LifecycleState.ACTIVE, 1);
        return new SettingsOwnerAdapter() {
            @Override
            public String authority() {
                return authority;
            }

            @Override
            public String readPermission() {
                return "EXAMPLE_READ";
            }

            @Override
            public List<SettingsContracts.Definition> definitions() {
                return List.of(definition);
            }

            @Override
            public SettingsContracts.OwnerSnapshot resolve(
                    SettingsContracts.Definition ignored,
                    SettingsContracts.ScopeTarget target) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
