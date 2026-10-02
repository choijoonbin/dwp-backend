package com.dwp.migration.control;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

record ControlEnvironment(
        Mode mode,
        ControlPlan plan,
        String jdbcUrl,
        String database,
        String bootstrapPrincipal,
        String bootstrapPassword,
        String migrationPrincipal,
        String migrationPassword,
        String runtimePrincipal,
        String runtimePassword,
        String projectionPublisherPrincipal,
        String projectionPublisherPassword,
        String controlReference,
        Map<String, String> previousReceiptSha256ByStream,
        String previousControlReference,
        Map<String, StreamSeal> previousNativeSeals,
        String previousRunReceiptSha256) {

    ControlEnvironment {
        String expectedPublisher = plan.projectionPublisherPrincipal();
        if (expectedPublisher.isEmpty()) {
            if (!projectionPublisherPrincipal.isEmpty()
                    || !projectionPublisherPassword.isEmpty()) {
                throw new IllegalStateException(
                        "Projection publisher credentials are not approved for this service");
            }
        } else if (!expectedPublisher.equals(projectionPublisherPrincipal)
                || projectionPublisherPassword == null
                || projectionPublisherPassword.isBlank()) {
            throw new IllegalStateException(
                    "Exact projection publisher credentials are required for this service");
        }
        if (controlReference == null
                || !controlReference.matches("dwp-migration-control-v2:[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Migration Control reference is not canonical");
        }
        previousReceiptSha256ByStream = Map.copyOf(previousReceiptSha256ByStream);
        if (previousReceiptSha256ByStream.values().stream()
                .anyMatch(value -> value == null || !value.matches("[0-9a-f]{64}"))) {
            throw new IllegalStateException(
                    "Previous adoption receipt digest is not canonical");
        }
        if (previousReceiptSha256ByStream.isEmpty()
                != previousControlReference.isEmpty()) {
            throw new IllegalStateException(
                    "Previous Control receipts and reference must be supplied together");
        }
        previousNativeSeals = Map.copyOf(previousNativeSeals);
        Set<String> expectedStreams = plan.streams().stream()
                .map(StreamPlan::streamKey)
                .collect(Collectors.toUnmodifiableSet());
        if ((!previousReceiptSha256ByStream.isEmpty()
                        && !previousReceiptSha256ByStream.keySet().equals(expectedStreams))
                || (!previousNativeSeals.isEmpty()
                        && !previousNativeSeals.keySet().equals(expectedStreams))) {
            throw new IllegalStateException(
                    "Previous Control stream seals must cover the exact service plan");
        }
        for (Map.Entry<String, StreamSeal> entry : previousNativeSeals.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().streamKey())) {
                throw new IllegalStateException(
                        "Previous native Control seal key is inconsistent");
            }
        }
        boolean hasPreviousState = !previousReceiptSha256ByStream.isEmpty()
                || !previousNativeSeals.isEmpty();
        if (hasPreviousState == previousRunReceiptSha256.isEmpty()) {
            throw new IllegalStateException(
                    "Previous database seals and run receipt must be supplied together");
        }
        if (!previousReceiptSha256ByStream.isEmpty() && !previousNativeSeals.isEmpty()) {
            throw new IllegalStateException(
                    "Adopted and native previous Control seals are mutually exclusive");
        }
        if (!previousControlReference.isEmpty()
                && !previousControlReference.matches(
                        "dwp-migration-control-v2:[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Previous Migration Control reference is not canonical");
        }
        if (!previousRunReceiptSha256.isEmpty()
                && !previousRunReceiptSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                    "Previous Migration Control run receipt is not canonical");
        }
        if (!previousReceiptSha256ByStream.isEmpty()
                && mode != Mode.ADOPT_OR_UPGRADE) {
            throw new IllegalStateException(
                    "Adoption seals are valid only for adoption upgrades");
        }
        if (!previousNativeSeals.isEmpty()
                && mode != Mode.STRICT_FRESH
                && mode != Mode.NOTIFICATION_FRESH
                && mode != Mode.PEOPLE_FRESH) {
            throw new IllegalStateException(
                    "Native seals are valid only for native Control runs");
        }
    }

    ControlEnvironment(
            Mode mode,
            ControlPlan plan,
            String jdbcUrl,
            String database,
            String bootstrapPrincipal,
            String bootstrapPassword,
            String migrationPrincipal,
            String migrationPassword,
            String runtimePrincipal,
            String runtimePassword,
            String controlReference,
            Map<String, String> previousReceiptSha256ByStream,
            String previousControlReference,
            Map<String, StreamSeal> previousNativeSeals,
            String previousRunReceiptSha256) {
        this(
                mode,
                plan,
                jdbcUrl,
                database,
                bootstrapPrincipal,
                bootstrapPassword,
                migrationPrincipal,
                migrationPassword,
                runtimePrincipal,
                runtimePassword,
                "",
                "",
                controlReference,
                previousReceiptSha256ByStream,
                previousControlReference,
                previousNativeSeals,
                previousRunReceiptSha256);
    }

    static ControlEnvironment load() {
        Mode mode = Mode.valueOf(ControlValues.environment("DWP_MIGRATION_CONTROL_MODE"));
        String service = ControlValues.identifier(
                ControlValues.environment("DWP_MIGRATION_CONTROL_SERVICE"));
        return new ControlEnvironment(
                mode,
                ControlPlan.forService(service),
                ControlValues.environment("DWP_MIGRATION_CONTROL_JDBC_URL"),
                ControlValues.identifier(
                        ControlValues.environment("DWP_MIGRATION_CONTROL_DATABASE")),
                ControlValues.identifier(ControlValues.environment(
                        "DWP_MIGRATION_CONTROL_BOOTSTRAP_PRINCIPAL")),
                ControlValues.environment("DWP_MIGRATION_CONTROL_BOOTSTRAP_PASSWORD"),
                ControlValues.identifier(ControlValues.environment(
                        "DWP_MIGRATION_CONTROL_MIGRATION_PRINCIPAL")),
                ControlValues.environment("DWP_MIGRATION_CONTROL_MIGRATION_PASSWORD"),
                ControlValues.identifier(ControlValues.environment(
                        "DWP_MIGRATION_CONTROL_RUNTIME_PRINCIPAL")),
                ControlValues.environment("DWP_MIGRATION_CONTROL_RUNTIME_PASSWORD"),
                projectionPublisherPrincipal(ControlPlan.forService(service)),
                projectionPublisherPassword(ControlPlan.forService(service)),
                ControlValues.environment("DWP_MIGRATION_CONTROL_REFERENCE"),
                previousReceipts(ControlPlan.forService(service)),
                System.getenv().getOrDefault(
                        "DWP_MIGRATION_CONTROL_PREVIOUS_CONTROL_REFERENCE", ""),
                previousNativeSeals(ControlPlan.forService(service)),
                optionalSha256("DWP_MIGRATION_CONTROL_PREVIOUS_RUN_RECEIPT_SHA256"));
    }

    ControlEnvironment withControlCredentials(ControlCredentials credentials) {
        return new ControlEnvironment(
                mode,
                plan,
                jdbcUrl,
                database,
                bootstrapPrincipal,
                bootstrapPassword,
                migrationPrincipal,
                credentials.migrationPassword(),
                runtimePrincipal,
                credentials.runtimePassword(),
                projectionPublisherPrincipal,
                credentials.projectionPublisherPassword(),
                controlReference,
                previousReceiptSha256ByStream,
                previousControlReference,
                previousNativeSeals,
                previousRunReceiptSha256);
    }

    boolean hasProjectionPublisher() {
        return !projectionPublisherPrincipal.isEmpty();
    }

    private static String projectionPublisherPrincipal(ControlPlan plan) {
        if (plan.projectionPublisherPrincipal().isEmpty()) {
            requireAbsent("DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PRINCIPAL");
            return "";
        }
        return ControlValues.identifier(ControlValues.environment(
                "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PRINCIPAL"));
    }

    private static String projectionPublisherPassword(ControlPlan plan) {
        if (plan.projectionPublisherPrincipal().isEmpty()) {
            requireAbsent("DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PASSWORD");
            return "";
        }
        return ControlValues.environment(
                "DWP_MIGRATION_CONTROL_PROJECTION_PUBLISHER_PASSWORD");
    }

    private static void requireAbsent(String key) {
        if (System.getenv().containsKey(key)) {
            throw new IllegalStateException(
                    key + " is forbidden for a service without a projection publisher");
        }
    }

    private static Map<String, String> previousReceipts(ControlPlan plan) {
        String raw = System.getenv().getOrDefault(
                "DWP_MIGRATION_CONTROL_PREVIOUS_RECEIPTS", "");
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> receipts = new LinkedHashMap<>();
        for (String entry : raw.split(",", -1)) {
            String[] fields = entry.split("=", -1);
            if (fields.length != 2
                    || plan.streams().stream().noneMatch(
                            stream -> stream.streamKey().equals(fields[0]))
                    || !fields[1].matches("[0-9a-f]{64}")
                    || receipts.putIfAbsent(fields[0], fields[1]) != null) {
                throw new IllegalStateException(
                        "Previous Control receipt map is not canonical");
            }
        }
        return Map.copyOf(receipts);
    }

    private static Map<String, StreamSeal> previousNativeSeals(ControlPlan plan) {
        String raw = System.getenv().getOrDefault(
                "DWP_MIGRATION_CONTROL_PREVIOUS_NATIVE_SEALS", "");
        if (raw.isEmpty()) {
            return Map.of();
        }
        Map<String, StreamSeal> seals = new LinkedHashMap<>();
        for (String entry : raw.split(",", -1)) {
            String[] fields = entry.split(":", -1);
            if (fields.length != 6
                    || plan.streams().stream().noneMatch(
                            stream -> stream.streamKey().equals(fields[0]))
                    || !fields[1].matches("0|[1-9][0-9]*")
                    || !fields[2].matches("0|[1-9][0-9]*")
                    || !fields[3].matches("[0-9a-f]{64}")
                    || !fields[4].matches("[1-9][0-9]*")
                    || !fields[5].matches("[0-9a-f]{64}")) {
                throw new IllegalStateException(
                        "Previous native Control seal map is not canonical");
            }
            StreamSeal seal = new StreamSeal(
                    fields[0],
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),
                    fields[3],
                    Integer.parseInt(fields[4]),
                    fields[5],
                    "");
            if (seals.putIfAbsent(fields[0], seal) != null) {
                throw new IllegalStateException(
                        "Previous native Control seal map contains duplicates");
            }
        }
        return Map.copyOf(seals);
    }

    private static String optionalSha256(String key) {
        String value = System.getenv().getOrDefault(key, "");
        if (!value.isEmpty() && !value.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(key + " must be a lowercase SHA-256");
        }
        return value;
    }

    enum Mode {
        STRICT_FRESH,
        NOTIFICATION_FRESH,
        PEOPLE_FRESH,
        ADOPT_OR_UPGRADE
    }
}
