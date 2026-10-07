package com.dwp.core.database.authority;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Current scalar topology, not the future thirteen-stream manifest or runtime registration. */
public record ScalarNineRuntimeRegistryContract(String schemaVersion, String topologyRevision,
        String controlReference, List<Catalog> catalogs, List<Stream> streams,
        List<RuntimeStreamStartupSeal.RuntimePurpose> runtimeBindings) {
    public static final String VERSION = "1.0";
    public static final String TOPOLOGY = "scalar-nine-runtime-v1";
    public static final Set<String> SERVICES = Set.of("approval", "auth", "notification", "payroll",
            "people", "platform", "provider", "time");

    public ScalarNineRuntimeRegistryContract {
        require(VERSION.equals(schemaVersion) && TOPOLOGY.equals(topologyRevision), "not scalar nine topology");
        RuntimeStartupValues.controlReference(controlReference);
        catalogs = ordered(catalogs, Catalog::service, "catalogs");
        streams = ordered(streams, Stream::streamKey, "streams");
        runtimeBindings = ordered(runtimeBindings, RuntimeStreamStartupSeal.RuntimePurpose::bindingKey, "runtime bindings");
        require(catalogs.stream().map(Catalog::service).collect(java.util.stream.Collectors.toSet()).equals(SERVICES),
                "exact scalar eight catalogs required");
        require(catalogs.stream().map(Catalog::database).distinct().count() == 8, "catalog alias forbidden");
        String suffix = catalogs.get(0).database().substring(("dwp_" + catalogs.get(0).service()).length());
        require(catalogs.stream().allMatch(c -> c.database().equals("dwp_" + c.service() + suffix)),
                "cross instance catalog forbidden");
        Set<String> keys = new HashSet<>();
        SERVICES.forEach(service -> keys.add(service + "-main"));
        keys.add("people-performance");
        require(streams.stream().map(Stream::streamKey).collect(java.util.stream.Collectors.toSet()).equals(keys),
                "exact scalar nine stream keys required");
        require(runtimeBindings.size() == 8, "eight runtime bindings required, not thirteen roles");
        Set<String> qualifiers = new HashSet<>();
        for (RuntimeStreamStartupSeal.RuntimePurpose runtime : runtimeBindings) {
            String service = SERVICES.stream().filter(s -> (s + "-runtime").equals(runtime.bindingKey()))
                    .findFirst().orElseThrow(() -> failure("unknown scalar binding"));
            String database = catalogs.stream().filter(c -> c.service().equals(service)).findFirst().orElseThrow().database();
            List<String> covered = service.equals("people") ? List.of("people-main", "people-performance") : List.of(service + "-main");
            require(runtime.principal().equals("dwp_" + service + "_runtime") && runtime.database().equals(database)
                    && runtime.streamKeys().equals(covered) && !runtime.readOnly()
                    && runtime.purposes().equals(service.equals("people") ? List.of("PERFORMANCE", "PRIMARY") : List.of("PRIMARY"))
                    && runtime.schemas().equals(service.equals("people") ? List.of("hris_performance", "public") : List.of("public"))
                    && runtime.searchPath().equals(List.of("pg_catalog", "public")), "unapproved scalar alias or purpose");
            require(qualifiers.add(runtime.qualifier()), "reused runtime qualifier");
        }
    }

    public Catalog catalog(String service) {
        return catalogs.stream().filter(c -> c.service().equals(service)).findFirst()
                .orElseThrow(() -> failure("unknown scalar service"));
    }

    public record Catalog(String service, String database) {
        public Catalog {
            require(SERVICES.contains(service), "unknown scalar service"); identifier(database);
            require(database.equals("dwp_" + service) || database.matches("dwp_" + service + "_[a-z][a-z0-9_]{0,31}"),
                    "noncanonical scalar catalog");
        }
    }

    public record Stream(String service, String streamKey, String schema, String historyTable,
            String migrationLocation, String migrationPrincipal) {
        public Stream {
            require(SERVICES.contains(service), "unknown scalar stream owner");
            boolean performance = service.equals("people") && "people-performance".equals(streamKey);
            require(performance || (service + "-main").equals(streamKey), "unknown scalar stream");
            require(schema.equals(performance ? "hris_performance" : "public")
                    && historyTable.equals(performance ? "flyway_performance_schema_history" : "flyway_schema_history")
                    && migrationLocation.equals(performance ? "classpath:db/performance-migration" : "classpath:db/migration")
                    && migrationPrincipal.equals("dwp_" + service + "_migration"), "scalar stream differs from oracle");
        }
    }
}
