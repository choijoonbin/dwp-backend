package com.dwp.core.database.authority;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Credentials and endpoints are deliberately absent. External deployment settings own trust. */
public record StreamAuthorityContract(
        String schemaVersion, String topologyVersion, String controlReference,
        List<String> enabledCapabilities, List<Catalog> catalogs,
        List<StreamAuthority> streams, String manifestSha256) {
    public static final String VERSION = "1.0";
    private static final Set<String> BUILTIN_ARGUMENT_TYPES = Set.of("bigint", "boolean", "bytea",
            "character", "character varying", "date", "double precision", "integer", "interval",
            "json", "jsonb", "numeric", "real", "smallint", "text", "time with time zone",
            "time without time zone", "timestamp with time zone", "timestamp without time zone", "uuid");

    public StreamAuthorityContract {
        require(VERSION.equals(schemaVersion), "unsupported manifest version");
        require(ExactStreamTopology.VERSION.equals(topologyVersion), "unsupported topology version");
        reference(controlReference);
        digest(manifestSha256, false);
        enabledCapabilities = ordered(enabledCapabilities, Function.identity(), "enabledCapabilities");
        require(Set.of("HRIS_CONFIGURATION", "PEOPLE_ANALYTICS", "GOVERNED_AI", "EMPLOYEE_LISTENING")
                .containsAll(enabledCapabilities), "unknown enabled capability");
        catalogs = ordered(catalogs, Catalog::catalogKey, "catalogs");
        streams = ordered(streams, StreamAuthority::streamKey, "streams");
        require(catalogs.stream().map(Catalog::catalogKey).collect(java.util.stream.Collectors.toSet())
                .equals(ExactStreamTopology.SERVICES), "exact eight catalog keys required");
        require(new HashSet<>(catalogs.stream().map(Catalog::database).toList()).size() == 8,
                "catalogs must be distinct, including Auth and Platform");
        require(streams.stream().map(StreamAuthority::streamKey)
                .collect(java.util.stream.Collectors.toSet()).equals(ExactStreamTopology.BY_KEY.keySet()),
                "exact thirteen stream keys required");
        Set<String> principals = new HashSet<>();
        Set<String> qualifiers = new HashSet<>();
        for (StreamAuthority stream : streams) {
            ExactStreamTopology.Stream oracle = ExactStreamTopology.BY_KEY.get(stream.streamKey());
            require(oracle.service().equals(stream.service())
                    && oracle.service().equals(stream.catalogKey())
                    && List.of(oracle.schema()).equals(stream.schemas())
                    && oracle.historyTable().equals(stream.historyTable())
                    && List.of(oracle.migrationLocation()).equals(stream.migrationLocations()),
                    "stream differs from exact topology");
            require(oracle.supplemental() || stream.enabled(), "existing nine streams must remain enabled");
            require(principals.add(stream.migrationPrincipal()), "reused migration/runtime principal");
            require(qualifiers.add(stream.migrationQualifier()), "reused datasource qualifier");
            require(stream.runtimePrincipals().stream().map(RuntimePrincipal::purpose).toList()
                    .equals(oracle.runtimePurposes()), "runtime purpose/cardinality differs from stream");
            for (RuntimePrincipal runtime : stream.runtimePrincipals()) {
                require(principals.add(runtime.principal()), "reused migration/runtime principal");
                require(qualifiers.add(runtime.qualifier()), "reused datasource qualifier");
                require(runtime.searchPath().equals(List.of("pg_catalog", oracle.schema())),
                        "runtime searchPath must be exact");
                for (ObjectGrant grant : runtime.objectGrants()) {
                    require(stream.schemas().contains(grant.schema()), "foreign-schema object grant");
                    require(!grant.name().equals(stream.historyTable()), "runtime history authority forbidden");
                    require(!runtime.readOnly() || grant.privileges().equals(List.of("SELECT"))
                            || (grant.objectClass().equals("TYPE") && grant.privileges().equals(List.of("USAGE"))),
                            "readOnly runtime may only SELECT or use exact types");
                    require(!runtime.readOnly() || !grant.objectClass().equals("ROUTINE"),
                            "readOnly runtime must not invoke unreviewed routines");
                }
                require(!runtime.purpose().equals("INSIGHTS_QUERY") || runtime.readOnly(),
                        "Insights query LOGIN must be readOnly");
                require(!runtime.purpose().equals("INSIGHTS_EXECUTION_WRITE") || !runtime.readOnly(),
                        "Insights execution writer must be a distinct write-capable LOGIN");
            }
        }
        boolean protectedEnabled = enabled(streams, "platform-hris-protected");
        boolean issuerEnabled = enabled(streams, "auth-hris-participation-issuer");
        require(protectedEnabled == issuerEnabled, "protected and issuer require paired deployment");
        require(!protectedEnabled || enabled(streams, "platform-hris-configuration"),
                "protected requires configuration authority");
        require(!enabled(streams, "platform-hris-insights") || enabled(streams, "platform-hris-configuration"),
                "Insights requires configuration authority, not listening adoption");
        Set<String> requiredSupplemental = new HashSet<>();
        if (!enabledCapabilities.isEmpty()) requiredSupplemental.add("platform-hris-configuration");
        if (enabledCapabilities.contains("PEOPLE_ANALYTICS") || enabledCapabilities.contains("GOVERNED_AI")) {
            requiredSupplemental.add("platform-hris-insights");
        }
        if (enabledCapabilities.contains("EMPLOYEE_LISTENING")) {
            requiredSupplemental.addAll(Set.of("platform-hris-insights", "platform-hris-protected",
                    "auth-hris-participation-issuer"));
        }
        require(streams.stream().filter(value -> ExactStreamTopology.BY_KEY.get(value.streamKey()).supplemental()
                        && value.enabled()).map(StreamAuthority::streamKey)
                        .collect(java.util.stream.Collectors.toSet()).equals(requiredSupplemental),
                "enabled capability exact stream dependencies differ");
    }

    public Catalog catalog(String key) {
        return catalogs.stream().filter(value -> value.catalogKey().equals(key)).findFirst()
                .orElseThrow(() -> invalid("unknown catalog"));
    }

    public StreamAuthority stream(String key) {
        return streams.stream().filter(value -> value.streamKey().equals(key)).findFirst()
                .orElseThrow(() -> invalid("unknown stream"));
    }

    public List<StreamAuthority> enabledStreams(String service) {
        require(ExactStreamTopology.SERVICES.contains(service), "unknown service");
        return streams.stream().filter(value -> value.service().equals(service) && value.enabled()).toList();
    }

    public record Catalog(String catalogKey, String database) {
        public Catalog { identifier(catalogKey); identifier(database); }
    }

    public record StreamAuthority(String service, String streamKey, String catalogKey,
            boolean enabled, List<String> schemas, String historyTable, List<String> migrationLocations,
            String migrationPrincipal, String migrationQualifier, List<RuntimePrincipal> runtimePrincipals) {
        public StreamAuthority {
            identifier(service); key(streamKey); identifier(catalogKey);
            schemas = ordered(schemas, Function.identity(), "schemas");
            schemas.forEach(StreamAuthorityContract::identifier);
            identifier(historyTable); identifier(migrationPrincipal); qualifier(migrationQualifier);
            migrationLocations = ordered(migrationLocations, Function.identity(), "migrationLocations");
            runtimePrincipals = ordered(runtimePrincipals, RuntimePrincipal::purpose, "runtimePrincipals");
        }
    }

    public record RuntimePrincipal(String purpose, String principal, String qualifier,
            boolean readOnly, List<String> searchPath, List<ObjectGrant> objectGrants) {
        public RuntimePrincipal {
            require(Set.of("PRIMARY", "PERFORMANCE", "CONFIGURATION", "LISTENING_ADMISSION",
                    "INSIGHTS_QUERY", "INSIGHTS_EXECUTION_WRITE", "PARTICIPATION_ISSUER").contains(purpose), "unknown purpose");
            identifier(principal); StreamAuthorityContract.qualifier(qualifier);
            searchPath = List.copyOf(Objects.requireNonNull(searchPath));
            searchPath.forEach(StreamAuthorityContract::identifier);
            objectGrants = ordered(objectGrants, ObjectGrant::identity, "objectGrants");
        }
    }

    public record ObjectGrant(String objectClass, String schema, String name,
            String identityArguments, List<String> privileges) {
        public ObjectGrant {
            require(Set.of("TABLE", "SEQUENCE", "ROUTINE", "TYPE").contains(objectClass), "unknown grant class");
            identifier(schema); identifier(name);
            require(identityArguments != null && (objectClass.equals("ROUTINE")
                    ? identityArguments.matches("(?:[a-z][a-z0-9_ ]*(?:\\[\\])?(?:,[a-z][a-z0-9_ ]*(?:\\[\\])?)*)?")
                      && !identityArguments.startsWith(" ") && !identityArguments.endsWith(" ")
                      && !identityArguments.contains("  ") : identityArguments.isEmpty()),
                    "routine arguments must be canonical built-in type identities");
            require(!objectClass.equals("ROUTINE") || identityArguments.isEmpty()
                    || java.util.Arrays.stream(identityArguments.split(",", -1)).allMatch(argument ->
                            BUILTIN_ARGUMENT_TYPES.contains(argument.endsWith("[]")
                                    ? argument.substring(0, argument.length() - 2) : argument)),
                    "unregistered routine argument type");
            privileges = ordered(privileges, Function.identity(), "privileges");
            Set<String> allowed = switch (objectClass) {
                case "TABLE" -> Set.of("SELECT", "INSERT", "UPDATE", "DELETE");
                case "SEQUENCE" -> Set.of("SELECT", "USAGE");
                case "ROUTINE" -> Set.of("EXECUTE");
                case "TYPE" -> Set.of("USAGE");
                default -> throw invalid("unknown grant class");
            };
            require(!privileges.isEmpty() && allowed.containsAll(privileges), "unapproved object privilege");
        }
        public String identity() { return objectClass + ":" + schema + "." + name + "(" + identityArguments + ")"; }
    }

    static boolean enabled(List<StreamAuthority> streams, String key) {
        return streams.stream().anyMatch(value -> value.streamKey().equals(key) && value.enabled());
    }
    static <T> List<T> ordered(List<T> input, Function<T, String> key, String name) {
        List<T> values = List.copyOf(Objects.requireNonNull(input, name));
        String previous = null;
        for (T value : values) {
            String current = key.apply(Objects.requireNonNull(value));
            require(current != null && (previous == null || previous.compareTo(current) < 0),
                    name + " must be unique and canonically ordered");
            previous = current;
        }
        return values;
    }
    static void identifier(String value) { require(value != null && value.matches("[a-z][a-z0-9_]{0,62}"), "noncanonical identifier"); }
    static void qualifier(String value) { require(value != null && value.matches("[a-z][a-zA-Z0-9]{0,127}"), "noncanonical qualifier"); }
    static void key(String value) { require(value != null && value.matches("[a-z][a-z0-9-]{0,62}"), "noncanonical stream key"); }
    static void reference(String value) { require(value != null && value.matches("dwp-migration-control-v2:[0-9a-f]{64}"), "invalid Control reference"); }
    static void digest(String value, boolean emptyAllowed) { require(value != null && ((emptyAllowed && value.isEmpty()) || value.matches("[0-9a-f]{64}")), "noncanonical digest"); }
    static void require(boolean condition, String message) { if (!condition) throw invalid(message); }
    static IllegalStateException invalid(String message) { return new IllegalStateException("Stream authority: " + message); }
}
