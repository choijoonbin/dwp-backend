package com.dwp.services.auth.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Shared vocabulary and structural assertions for product authorization contracts. */
final class ProductAuthorizationContractRules {

    static final Set<String> ACCESS_MODES =
            Set.of("NORMAL", "ELEVATED", "PROVIDER_SUPPORT");
    static final Set<String> TARGET_KINDS =
            Set.of("SELF", "OBJECT", "RELATIONSHIP", "TARGET_POPULATION", "CONFIG_SCOPE");
    static final Set<String> ROUTE_KINDS = Set.of("PAGE", "DATA", "ACTION");
    static final Set<String> SERVICE_KEYS = Set.of(
            "agent", "approval", "auth", "meeting", "messaging",
            "notification", "payroll", "people", "platform", "space", "time");
    static final Pattern CONTEXT_PATTERN =
            Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    static final Pattern CHECKSUM_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    static final Map<String, String> APPROVAL_FIELD_MASK_SCHEMA_PROFILES = Map.of(
            "ApprovalOversightAdminPulseV1", "legacy-oversight",
            "ApprovalOversightWorkflowV1", "legacy-oversight",
            "ApprovalOversightFormV1", "legacy-oversight",
            "ApprovalOversightPolicyV1", "legacy-oversight",
            "ApprovalAuditorOperationsV1", "auditor",
            "ApprovalOversightOperationsV1", "legacy-oversight",
            "ApprovalOversightSignatureV1", "legacy-oversight");

    private ProductAuthorizationContractRules() {
    }

    static <T> Map<String, T> index(
            List<T> values,
            Function<T, String> keyExtractor,
            String label) {
        require(values != null, label + " list is required.");
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String key = keyExtractor.apply(value);
            require(text(key) && result.putIfAbsent(key, value) == null,
                    "Duplicate or empty " + label + " key: " + key);
        }
        return result;
    }

    static boolean nonEmptyUniqueSubset(List<String> values, Set<String> allowed) {
        return values != null && !values.isEmpty()
                && new HashSet<>(values).size() == values.size() && allowed.containsAll(values);
    }

    static List<String> sorted(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) values.forEach(result::add);
        return result.stream().sorted().toList();
    }

    static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    static <K, V> Map<K, V> nullSafeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    static void revision(String owner, int policyVersion, String lifecycleState, String key) {
        require(text(owner) && policyVersion == 1
                        && Set.of("ACTIVE", "RETIRED").contains(lifecycleState),
                key + ": invalid revision metadata.");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
