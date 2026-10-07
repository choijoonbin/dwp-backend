package com.dwp.core.database.authority;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Unwired successor topology. This does not replace ControlPlan's scalar v2 oracle. */
public final class ExactStreamTopology {
    public static final String VERSION = "hris-stream-topology-v1";
    public static final String IMPLEMENTATION_STATUS = "UNWIRED_DISABLED_SCAFFOLD";

    public record Stream(String service, String streamKey, String schema, String historyTable,
                         String migrationLocation, String purpose, boolean supplemental) {
        /** Exact role cardinality is independent from the number of migration streams. */
        public List<String> runtimePurposes() {
            return streamKey.equals("platform-hris-insights")
                    ? List.of("INSIGHTS_EXECUTION_WRITE", "INSIGHTS_QUERY") : List.of(purpose);
        }
    }

    public static final List<Stream> STREAMS = List.of(
            main("approval"), main("auth"),
            new Stream("auth", "auth-hris-participation-issuer", "hris_participation_issuer",
                    "flyway_hris_participation_issuer_history",
                    "classpath:db/hris-participation-issuer-migration", "PARTICIPATION_ISSUER", true),
            main("notification"), main("payroll"), main("people"),
            new Stream("people", "people-performance", "hris_performance",
                    "flyway_performance_schema_history", "classpath:db/performance-migration",
                    "PERFORMANCE", false),
            new Stream("platform", "platform-hris-configuration", "hris_configuration",
                    "flyway_hris_configuration_history", "classpath:db/hris-configuration-migration",
                    "CONFIGURATION", true),
            new Stream("platform", "platform-hris-insights", "hris_insights",
                    "flyway_hris_insights_history", "classpath:db/hris-insights-migration",
                    "INSIGHTS_QUERY", true),
            new Stream("platform", "platform-hris-protected", "hris_listening_protected",
                    "flyway_hris_listening_protected_history",
                    "classpath:db/hris-listening-protected-migration", "LISTENING_ADMISSION", true),
            main("platform"), main("provider"), main("time"))
            .stream().sorted(java.util.Comparator.comparing(Stream::streamKey)).toList();

    public static final Map<String, Stream> BY_KEY = STREAMS.stream()
            .collect(Collectors.toUnmodifiableMap(Stream::streamKey, Function.identity()));
    public static final Set<String> SERVICES = Set.of(
            "approval", "auth", "notification", "payroll", "people", "platform", "provider", "time");

    private ExactStreamTopology() { }

    private static Stream main(String service) {
        return new Stream(service, service + "-main", "public", "flyway_schema_history",
                "classpath:db/migration", "PRIMARY", false);
    }
}
