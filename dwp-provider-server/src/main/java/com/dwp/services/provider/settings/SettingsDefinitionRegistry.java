package com.dwp.services.provider.settings;

import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SettingsDefinitionRegistry {

    private final List<SettingsOwnerAdapter> adapters;

    public SettingsDefinitionRegistry(List<SettingsOwnerAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
        requireUniqueAuthorities(this.adapters);
    }

    public List<SettingsContracts.Definition> authorizedDefinitions() {
        return authorizedEntries().values().stream()
                .map(Entry::definition)
                .sorted(Comparator.comparing(SettingsContracts.Definition::settingId))
                .toList();
    }

    public Optional<Entry> authorizedEntry(String settingId) {
        return Optional.ofNullable(authorizedEntries().get(settingId));
    }

    private Map<String, Entry> authorizedEntries() {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (SettingsOwnerAdapter adapter : adapters) {
            if (!actor.permissions().contains(adapter.readPermission())) continue;
            for (SettingsContracts.Definition definition : adapter.definitions()) {
                if (!adapter.readPermission().equals(definition.owner().readPermission())) {
                    throw new IllegalStateException(
                            "Setting owner permission differs from its adapter authority: "
                                    + definition.settingId());
                }
                Entry previous = entries.putIfAbsent(
                        definition.settingId(), new Entry(definition, adapter));
                if (previous != null) {
                    throw new IllegalStateException(
                            "Multiple owners registered setting ID " + definition.settingId());
                }
            }
        }
        return Map.copyOf(entries);
    }

    private void requireUniqueAuthorities(List<SettingsOwnerAdapter> candidates) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        candidates.forEach(adapter -> grouped.computeIfAbsent(
                SettingsContracts.required(adapter.authority(), "settings authority"),
                ignored -> new ArrayList<>()).add(adapter.getClass().getName()));
        grouped.forEach((authority, owners) -> {
            if (owners.size() > 1) {
                throw new IllegalArgumentException(
                        "Duplicate settings authority " + authority + ": " + owners);
            }
        });
    }

    public record Entry(
            SettingsContracts.Definition definition,
            SettingsOwnerAdapter adapter) {
    }
}
