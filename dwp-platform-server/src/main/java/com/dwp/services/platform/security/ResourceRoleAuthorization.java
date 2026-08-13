package com.dwp.services.platform.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ResourceRoleAuthorization {

    private ResourceRoleAuthorization() {
    }

    public static Set<String> resourcesFor(String header, String... responsibilities) {
        Set<String> accepted = Set.of(responsibilities);
        Set<String> resources = new LinkedHashSet<>();
        if (header == null || header.isBlank()) return resources;
        Arrays.stream(header.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(value -> {
                    int separator = value.indexOf('@');
                    if (separator <= 0 || separator == value.length() - 1) return;
                    String responsibility = value.substring(0, separator).toUpperCase();
                    String resource = value.substring(separator + 1).toUpperCase();
                    if (accepted.contains(responsibility)
                            && resource.matches("[A-Z][A-Z0-9_.-]{2,254}")) {
                        resources.add(resource);
                    }
                });
        return Set.copyOf(resources);
    }

    public static boolean has(String header, String responsibility, String resourceKey) {
        if (resourceKey == null) return false;
        return resourcesFor(header, responsibility).contains(resourceKey.toUpperCase());
    }
}
