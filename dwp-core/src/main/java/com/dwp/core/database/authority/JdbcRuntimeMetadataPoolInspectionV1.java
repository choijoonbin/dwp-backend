package com.dwp.core.database.authority;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import com.dwp.core.database.SystemCatalogAuthorityGuard;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/**
 * Foreign catalog read inspection of an already registered pool, not an endpoint factory.
 * Expectations and history policy are independent deployment approvals. This implementation
 * never reads history rows, switches role, grants privileges, or manufactures readOnly proof.
 * It is not registered with Spring; current issuer/producer, eight-service wiring and optional
 * thirteen-stream bootstrap remain OPEN. Native catalog inspection alone is not admission.
 */
public final class JdbcRuntimeMetadataPoolInspectionV1 implements RuntimeMetadataPoolInspectionPortV1 {
    private static final String APP = "n.nspname NOT IN ('pg_catalog','information_schema') "
            + "AND n.nspname NOT LIKE 'pg_temp_%' AND n.nspname NOT LIKE 'pg_toast%'";
    private final Map<String, Binding> registry;
    private final PrivilegeSurfaceCompilerV1 compiler = new PrivilegeSurfaceCompilerV1();

    public record Binding(RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource approvedSource,
            DataSource metadataPool, PrivilegeSurfaceCompilerV1.Policy approvedHistoryPolicy) {
        public Binding {
            require(approvedSource != null && metadataPool != null && approvedHistoryPolicy != null,
                    "metadata independent registry and policy required");
            Set<PrivilegeSurfaceCompilerV1.HistoryRelation> exact = new HashSet<>();
            exact.add(new PrivilegeSurfaceCompilerV1.HistoryRelation(approvedSource.ownerService() + "-main",
                    approvedSource.database(), "public", "flyway_schema_history"));
            if (approvedSource.ownerService().equals("people")) {
                exact.add(new PrivilegeSurfaceCompilerV1.HistoryRelation("people-performance",
                        approvedSource.database(), "hris_performance", "flyway_performance_schema_history"));
            }
            require(new HashSet<>(approvedHistoryPolicy.historyInventory()).equals(exact)
                    && RuntimeMetadataCatalogReadEvidenceJsonV1.historyPolicySha256(approvedHistoryPolicy)
                            .equals(approvedSource.historyPolicySha256()), "metadata scalar source history scope differs");
        }
    }

    public JdbcRuntimeMetadataPoolInspectionV1(List<Binding> independentlyApprovedRegistry) {
        require(independentlyApprovedRegistry != null && !independentlyApprovedRegistry.isEmpty(),
                "metadata independent registry required");
        Map<String, Binding> bindings = new HashMap<>();
        Set<DataSource> pools = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<String> qualifiers = new HashSet<>();
        for (Binding binding : List.copyOf(independentlyApprovedRegistry)) {
            require(binding != null && bindings.putIfAbsent(binding.approvedSource().sourceKey(), binding) == null
                    && pools.add(binding.metadataPool()) && qualifiers.add(binding.approvedSource().qualifier()),
                    "metadata source, qualifier or pool identity repeated");
        }
        registry = Map.copyOf(bindings);
    }

    @Override
    public Optional<NativeObservation> inspect(RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource expected,
            DataSource alreadyRegisteredMetadataPool) {
        if (expected == null || alreadyRegisteredMetadataPool == null) return Optional.empty();
        Binding binding = registry.get(expected.sourceKey());
        if (binding == null || !binding.approvedSource().equals(expected)
                || binding.metadataPool() != alreadyRegisteredMetadataPool) return Optional.empty();
        try (Connection connection = alreadyRegisteredMetadataPool.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            String originalLogin = connection.getMetaData().getUserName();
            if (!autoCommit || !readOnly || !expected.principal().equals(originalLogin)) return Optional.empty();
            int isolation = connection.getTransactionIsolation();
            int networkTimeout = connection.getNetworkTimeout();
            try {
                connection.setNetworkTimeout(Runnable::run, 15_000);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                return inspectSnapshot(connection, binding, originalLogin, autoCommit, readOnly);
            } finally {
                restore(connection, autoCommit, isolation, networkTimeout);
            }
        } catch (SQLException | RuntimeException exception) {
            return Optional.empty(); // Never expose driver inputs, credentials, routine text or causes.
        }
    }

    /** Attempt every restoration; discard a connection when any step fails, never return its observation. */
    private static void restore(Connection connection, boolean autoCommit, int isolation, int networkTimeout)
            throws SQLException {
        boolean failed = false;
        try { connection.rollback(); } catch (SQLException | RuntimeException exception) { failed = true; }
        try { connection.setAutoCommit(autoCommit); } catch (SQLException | RuntimeException exception) { failed = true; }
        try { connection.setTransactionIsolation(isolation); } catch (SQLException | RuntimeException exception) { failed = true; }
        try { connection.setNetworkTimeout(Runnable::run, networkTimeout); } catch (SQLException | RuntimeException exception) { failed = true; }
        if (failed) {
            try { connection.abort(Runnable::run); } catch (SQLException | RuntimeException ignored) { /* close still follows */ }
            throw new SQLException("Metadata snapshot restoration rejected");
        }
    }

    private Optional<NativeObservation> inspectSnapshot(Connection connection, Binding binding,
            String originalLogin, boolean autoCommit, boolean readOnly) throws SQLException {
        var expected = binding.approvedSource();
        Identity identity = identity(connection);
        if (!identity.matches(expected)) return Optional.empty();
        boolean[] boundary = boundary(connection);
        if (!boundary[0] || boundary[1] || boundary[2] || boundary[3] || boundary[4]
                || boundary[5] || boundary[6] || boundary[7]) return Optional.empty();
        if (historyPrivilege(connection, binding.approvedHistoryPolicy(), identity.versionNumber())
                || appPrivilege(connection, identity.versionNumber()) || grantOption(connection)) return Optional.empty();
        SystemCatalogAuthorityGuard.verify("RuntimeMetadata", connection, expected.principal(), "metadata-catalog-read");
        String surface = compiler.compile(binding.approvedHistoryPolicy(), compiler.capture(connection,
                binding.approvedHistoryPolicy(), expected.database(), List.of(expected.principal())));
        if (!surface.equals(expected.privilegeSurfaceSha256()) || !identity(connection).equals(identity)) return Optional.empty();
        return Optional.of(new NativeObservation(originalLogin, identity.currentUser(), identity.sessionUser(),
                identity.database(), expected.trustedEndpointId(), identity.address(), identity.port(), identity.version(),
                autoCommit, readOnly, identity.searchPath(), true, true, true,
                false, false, false, false, false, false, false, false, false, false, false, false, false, false,
                PrivilegeSurfaceCompilerV1.VERSION, expected.historyPolicySha256(), surface));
    }

    private record Identity(String currentUser, String sessionUser, String database, String address,
            int port, String version, int versionNumber, List<String> searchPath, String rawSearchPath,
            boolean roleNone, boolean replicationOrigin, boolean transactionReadOnly) {
        boolean matches(RuntimeMetadataCatalogReadEvidenceV1.ExpectedSource expected) {
            return expected.principal().equals(currentUser) && currentUser.equals(sessionUser)
                    && expected.database().equals(database) && expected.serverAddress().equals(address)
                    && expected.serverPort() == port && expected.postgresVersion().equals(version)
                    && expected.searchPath().equals(searchPath) && rawSearchPath.equals("pg_catalog")
                    && roleNone && replicationOrigin && transactionReadOnly;
        }
    }

    private static Identity identity(Connection connection) throws SQLException {
        try (PreparedStatement statement = query(connection, """
                SELECT current_user,session_user,pg_catalog.current_database(),
                       pg_catalog.host(pg_catalog.inet_server_addr()),pg_catalog.inet_server_port(),
                       pg_catalog.current_setting('server_version'),pg_catalog.current_setting('server_version_num')::integer,
                       pg_catalog.current_schemas(false),pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('role')='none',
                       pg_catalog.current_setting('session_replication_role')='origin',
                       pg_catalog.current_setting('transaction_read_only')='on'
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getString(4) == null) throw new SQLException("Native TCP identity required");
            java.sql.Array array = result.getArray(8);
            List<String> path;
            try { path = List.of((String[]) array.getArray()); } finally { array.free(); }
            return new Identity(result.getString(1), result.getString(2), result.getString(3), result.getString(4),
                    result.getInt(5), result.getString(6).split("\\s+", 2)[0], result.getInt(7), path,
                    result.getString(9), result.getBoolean(10), result.getBoolean(11), result.getBoolean(12));
        }
    }

    /** Login, attributes, both membership directions, CREATE, TEMP, foreign CONNECT, schema CREATE, global ownership. */
    private static boolean[] boundary(Connection connection) throws SQLException {
        try (PreparedStatement statement = query(connection, """
                SELECT r.rolcanlogin AND pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'CONNECT'),
                       r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls OR r.rolinherit
                         OR EXISTS(SELECT 1 FROM pg_catalog.pg_parameter_acl p
                           CROSS JOIN LATERAL pg_catalog.aclexplode(p.paracl) a WHERE a.grantee IN (0,r.oid)),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.member=r.oid OR m.roleid=r.oid)
                         OR EXISTS(SELECT 1 FROM pg_catalog.pg_roles x WHERE x.oid<>r.oid
                           AND pg_catalog.pg_has_role(r.oid,x.oid,'MEMBER')),
                       pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'CREATE'),
                       pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'TEMPORARY'),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_database d WHERE d.datallowconn
                         AND d.datname<>pg_catalog.current_database() AND pg_catalog.has_database_privilege(r.oid,d.oid,'CONNECT')),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_namespace n WHERE pg_catalog.has_schema_privilege(r.oid,n.oid,'CREATE')),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_shdepend d
                         WHERE d.refclassid='pg_catalog.pg_authid'::pg_catalog.regclass AND d.refobjid=r.oid AND d.deptype='o')
                         OR EXISTS(SELECT 1 FROM pg_catalog.pg_user_mappings m WHERE m.umuser=r.oid)
                  FROM pg_catalog.pg_roles r WHERE r.rolname=current_user
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Metadata principal unavailable");
            boolean[] values = new boolean[8];
            for (int i = 0; i < values.length; i++) values[i] = result.getBoolean(i + 1);
            return values;
        }
    }

    private static boolean historyPrivilege(Connection connection, PrivilegeSurfaceCompilerV1.Policy policy,
            int version) throws SQLException {
        for (var history : policy.historyInventory()) {
            try (PreparedStatement statement = query(connection, """
                    SELECT c.relkind IN ('r','p'), pg_catalog.has_table_privilege(current_user,c.oid,
                           'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') %s
                           OR EXISTS(SELECT 1 FROM pg_catalog.pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0
                             AND NOT a.attisdropped AND pg_catalog.has_column_privilege(current_user,c.oid,a.attnum,
                               'SELECT,INSERT,UPDATE,REFERENCES'))
                      FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                     WHERE n.nspname=? AND c.relname=?
                    """.formatted(maintain(version)))) {
                statement.setString(1, history.schema()); statement.setString(2, history.table());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) throw new SQLException("Approved history relation unavailable");
                    if (result.getBoolean(2)) return true;
                    if (result.next()) throw new SQLException("Approved history identity ambiguous");
                }
            }
        }
        return false;
    }

    private static boolean appPrivilege(Connection connection, int version) throws SQLException {
        return exists(connection, """
                SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                  WHERE %s AND c.relkind IN ('r','p','v','m','f') AND
                    (pg_catalog.has_table_privilege(current_user,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') %s))
                  OR EXISTS(SELECT 1 FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE %s AND a.attnum>0 AND NOT a.attisdropped
                    AND pg_catalog.has_column_privilege(current_user,c.oid,a.attnum,'SELECT,INSERT,UPDATE,REFERENCES'))
                  OR EXISTS(SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                    WHERE %s AND c.relkind='S' AND pg_catalog.has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE'))
                  OR EXISTS(SELECT 1 FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                    WHERE %s AND pg_catalog.has_function_privilege(current_user,p.oid,'EXECUTE'))
                """.formatted(APP, maintain(version), APP, APP, APP));
    }

    private static String maintain(int version) {
        return version >= 170000 ? " OR pg_catalog.has_table_privilege(current_user,c.oid,'MAINTAIN')" : "";
    }

    private static boolean grantOption(Connection connection) throws SQLException {
        return exists(connection, """
                SELECT EXISTS(SELECT 1 FROM (
                  SELECT n.nspacl AS acl FROM pg_catalog.pg_namespace n
                  UNION ALL SELECT c.relacl FROM pg_catalog.pg_class c
                  UNION ALL SELECT a.attacl FROM pg_catalog.pg_attribute a
                  UNION ALL SELECT p.proacl FROM pg_catalog.pg_proc p
                  UNION ALL SELECT t.typacl FROM pg_catalog.pg_type t
                  UNION ALL SELECT d.defaclacl FROM pg_catalog.pg_default_acl d
                  UNION ALL SELECT d.datacl FROM pg_catalog.pg_database d
                  UNION ALL SELECT p.paracl FROM pg_catalog.pg_parameter_acl p
                  UNION ALL SELECT w.fdwacl FROM pg_catalog.pg_foreign_data_wrapper w
                  UNION ALL SELECT s.srvacl FROM pg_catalog.pg_foreign_server s
                  UNION ALL SELECT m.lomacl FROM pg_catalog.pg_largeobject_metadata m
                ) x CROSS JOIN LATERAL pg_catalog.aclexplode(x.acl) a
                  WHERE a.is_grantable AND a.grantee IN (0,(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user)))
                """);
    }

    private static boolean exists(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = query(connection, sql); ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Native catalog result unavailable");
            return result.getBoolean(1);
        }
    }

    private static PreparedStatement query(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }
}
