package com.dwp.core.database.authority;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import com.dwp.core.database.SystemCatalogAuthorityGuard;

/**
 * Native, runtime-pool-only implementation. Constructor inputs are independently approved
 * deployment registry/policy, not discovered JDBC endpoints or live privilege expectations.
 * This class is deliberately not a Spring provider: producer/fence, registration, eight-service
 * wiring and thirteen-stream bootstrap remain OPEN. No migration supplier/credential/factory
 * exists in this API. No history data, SET ROLE, SET SESSION AUTHORIZATION, DDL or grants occur.
 */
public final class JdbcRuntimePoolInspectionV1 implements RuntimePoolInspectionPort {
    private final Map<String, PoolBinding> registry;
    private final PrivilegeSurfaceCompilerV1.Policy policy;
    private final PrivilegeSurfaceCompilerV1 compiler = new PrivilegeSurfaceCompilerV1();

    /** Exact object identity, not DataSource.equals(), establishes which pool may be opened. */
    public record PoolBinding(String qualifier, DataSource runtimePool, String principal,
            String database, String trustedEndpointId, String serverAddress, int serverPort,
            boolean readOnly, List<String> schemas, List<String> searchPath) {
        public PoolBinding {
            Objects.requireNonNull(runtimePool);
            if (qualifier == null || !qualifier.matches("[A-Za-z][A-Za-z0-9]{0,127}")) {
                throw new IllegalArgumentException("Trusted qualifier required");
            }
            identifier(principal);
            identifier(database);
            if (trustedEndpointId == null || trustedEndpointId.isBlank()
                    || serverAddress == null || serverAddress.isBlank()
                    || serverPort < 1 || serverPort > 65535) {
                throw new IllegalArgumentException("Trusted endpoint metadata required");
            }
            schemas = List.copyOf(schemas);
            searchPath = List.copyOf(searchPath);
            schemas.forEach(JdbcRuntimePoolInspectionV1::identifier);
            searchPath.forEach(JdbcRuntimePoolInspectionV1::identifier);
            if (schemas.isEmpty() || new HashSet<>(schemas).size() != schemas.size()
                    || searchPath.isEmpty() || !searchPath.getFirst().equals("pg_catalog")
                    || new HashSet<>(searchPath).size() != searchPath.size()
                    || !schemas.containsAll(searchPath.subList(1, searchPath.size()))) {
                throw new IllegalArgumentException("Trusted schema/search-path policy required");
            }
        }
    }

    public JdbcRuntimePoolInspectionV1(List<PoolBinding> approvedRegistry,
            PrivilegeSurfaceCompilerV1.Policy approvedPolicy) {
        Objects.requireNonNull(approvedPolicy);
        Map<String, PoolBinding> bindings = new HashMap<>();
        Set<DataSource> pools = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (PoolBinding binding : List.copyOf(approvedRegistry)) {
            if (bindings.putIfAbsent(binding.qualifier(), binding) != null || !pools.add(binding.runtimePool())) {
                throw new IllegalArgumentException("Duplicate pool binding; scalar aliases belong in one binding");
            }
        }
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("Independent trusted runtime pool registry required");
        }
        registry = Map.copyOf(bindings);
        policy = approvedPolicy;
    }

    @Override
    public Optional<Inspection> inspect(RuntimeStreamStartupSeal.RuntimePurpose expected, DataSource pool) {
        if (expected == null || pool == null) {
            return Optional.empty();
        }
        PoolBinding binding = registry.get(expected.qualifier());
        if (binding == null || binding.runtimePool() != pool || !matches(binding, expected)
                || !approvedHistoryScope(expected)) {
            return Optional.empty();
        }
        try (Connection connection = pool.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            boolean readOnly = connection.isReadOnly();
            String originalLogin = connection.getMetaData().getUserName();
            if (!autoCommit || !binding.principal().equals(originalLogin)) {
                return Optional.empty(); // Never join or rollback an application transaction.
            }
            int isolation = connection.getTransactionIsolation();
            int networkTimeout = connection.getNetworkTimeout();
            try {
                connection.setNetworkTimeout(Runnable::run, 15_000);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setAutoCommit(false);
                return Optional.of(inspectSnapshot(connection, binding, originalLogin, autoCommit, readOnly));
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
                connection.setTransactionIsolation(isolation);
                connection.setNetworkTimeout(Runnable::run, networkTimeout);
            }
        } catch (SQLException | RuntimeException exception) {
            // No credentials, routine text, query result or driver exception is logged/returned.
            return Optional.empty();
        }
    }

    private Inspection inspectSnapshot(Connection connection, PoolBinding binding, String originalLogin,
            boolean autoCommit, boolean jdbcReadOnly) throws SQLException {
        Identity identity = identity(connection);
        List<String> roles = reachableRoles(connection);
        boolean elevatedMembership = false;
        RoleBoundary direct = null;
        for (String role : roles) {
            RoleBoundary boundary = roleBoundary(connection, role, identity.database());
            boolean catalogEscape = false;
            try {
                SystemCatalogAuthorityGuard.verify("RuntimeStartup", connection, role, "runtime-only");
            } catch (IllegalStateException exception) {
                catalogEscape = true;
            }
            boolean history = historyPrivilege(connection, role, identity.database(), identity.versionNumber());
            if (role.equals(identity.currentUser())) {
                direct = boundary.withHistory(history).withCatalogEscape(catalogEscape);
            } else if (boundary.elevated() || boundary.login() || history || catalogEscape || role.startsWith("pg_")) {
                elevatedMembership = true;
            }
        }
        if (direct == null || !schemasExist(connection, binding.schemas())
                || jdbcReadOnly != identity.transactionReadOnly()) {
            throw new SQLException("Incomplete native identity/posture inspection");
        }
        List<PrivilegeSurfaceCompilerV1.SurfaceRow> surface = compiler.capture(
                connection, policy, identity.database(), roles);
        return new Inspection(originalLogin, identity.currentUser(), identity.sessionUser(),
                identity.database(), binding.trustedEndpointId(), identity.address(), identity.port(),
                identity.version(), autoCommit, jdbcReadOnly, identity.searchPath(),
                identity.roleNone() && identity.rawSearchPath().equals(String.join(", ", binding.searchPath())),
                identity.replicationOrigin(), direct.login(),
                direct.attributes() || direct.catalogEscape(), elevatedMembership, direct.create(),
                direct.temporary(), direct.foreignConnect(), direct.schemaCreate(), direct.ownership(),
                direct.history(), compiler.compile(policy, surface));
    }

    private boolean approvedHistoryScope(RuntimeStreamStartupSeal.RuntimePurpose expected) {
        Set<String> approved = new HashSet<>();
        for (PrivilegeSurfaceCompilerV1.HistoryRelation history : policy.historyInventory()) {
            if (history.database().equals(expected.database()) && expected.schemas().contains(history.schema())) {
                approved.add(history.streamKey());
            }
        }
        return approved.containsAll(expected.streamKeys());
    }

    private static boolean matches(PoolBinding binding, RuntimeStreamStartupSeal.RuntimePurpose expected) {
        return binding.principal().equals(expected.principal()) && binding.database().equals(expected.database())
                && binding.trustedEndpointId().equals(expected.trustedEndpointId())
                && binding.serverAddress().equals(expected.serverAddress()) && binding.serverPort() == expected.serverPort()
                && binding.readOnly() == expected.readOnly() && binding.schemas().equals(expected.schemas())
                && binding.searchPath().equals(expected.searchPath());
    }

    private record Identity(String currentUser, String sessionUser, String database, String address,
            int port, String version, int versionNumber, List<String> searchPath, String rawSearchPath,
            boolean roleNone, boolean replicationOrigin, boolean transactionReadOnly) {
    }

    private static Identity identity(Connection connection) throws SQLException {
        try (PreparedStatement statement = query(connection, """
                SELECT current_user,session_user,pg_catalog.current_database(),
                       pg_catalog.host(pg_catalog.inet_server_addr()),pg_catalog.inet_server_port(),
                       pg_catalog.current_setting('server_version'),
                       pg_catalog.current_setting('server_version_num')::integer,
                       pg_catalog.current_schemas(false),pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('role')='none',
                       pg_catalog.current_setting('session_replication_role')='origin',
                       pg_catalog.current_setting('transaction_read_only')='on'
                """); ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getString(4) == null) {
                throw new SQLException("Native TCP identity required");
            }
            java.sql.Array array = result.getArray(8);
            List<String> searchPath;
            try {
                searchPath = List.of((String[]) array.getArray());
            } finally {
                array.free();
            }
            return new Identity(result.getString(1), result.getString(2), result.getString(3),
                    result.getString(4), result.getInt(5), result.getString(6).split("\\s+", 2)[0],
                    result.getInt(7), searchPath, result.getString(9), result.getBoolean(10),
                    result.getBoolean(11), result.getBoolean(12));
        }
    }

    private static List<String> reachableRoles(Connection connection) throws SQLException {
        try (PreparedStatement statement = query(connection, """
                SELECT r.rolname FROM pg_catalog.pg_roles r
                 WHERE r.rolname=current_user OR pg_catalog.pg_has_role(current_user,r.oid,'MEMBER')
                 ORDER BY r.rolname
                """); ResultSet result = statement.executeQuery()) {
            List<String> roles = new ArrayList<>();
            while (result.next()) {
                if (roles.size() >= 128) {
                    throw new SQLException("Reachable role surface exceeds bound");
                }
                roles.add(result.getString(1));
            }
            return List.copyOf(roles);
        }
    }

    private record RoleBoundary(boolean login, boolean attributes, boolean create, boolean temporary,
            boolean foreignConnect, boolean schemaCreate, boolean ownership, boolean history,
            boolean catalogEscape) {
        boolean elevated() {
            return attributes || create || temporary || foreignConnect || schemaCreate || ownership;
        }

        RoleBoundary withHistory(boolean value) {
            return new RoleBoundary(login, attributes, create, temporary, foreignConnect,
                    schemaCreate, ownership, value, catalogEscape);
        }

        RoleBoundary withCatalogEscape(boolean value) {
            return new RoleBoundary(login, attributes, create, temporary, foreignConnect,
                    schemaCreate, ownership, history, value);
        }
    }

    private static RoleBoundary roleBoundary(Connection connection, String role, String database)
            throws SQLException {
        try (PreparedStatement statement = query(connection, """
                SELECT r.rolcanlogin,(r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                       OR r.rolreplication OR r.rolbypassrls OR EXISTS(
                         SELECT 1 FROM pg_catalog.pg_parameter_acl p
                         CROSS JOIN LATERAL pg_catalog.aclexplode(p.paracl) a
                         WHERE a.grantee IN (0,r.oid))),
                       pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'CREATE'),
                       pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'TEMPORARY'),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_database d WHERE d.datallowconn
                         AND d.datname<>? AND pg_catalog.has_database_privilege(r.oid,d.oid,'CONNECT')),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_namespace n
                         WHERE pg_catalog.has_schema_privilege(r.oid,n.oid,'CREATE')),
                       EXISTS(SELECT 1 FROM pg_catalog.pg_shdepend d
                         WHERE d.refclassid='pg_catalog.pg_authid'::pg_catalog.regclass
                           AND d.refobjid=r.oid AND d.deptype='o')
                       OR EXISTS(SELECT 1 FROM pg_catalog.pg_user_mappings m WHERE m.umuser=r.oid)
                  FROM pg_catalog.pg_roles r WHERE r.rolname=?
                """)) {
            statement.setString(1, database);
            statement.setString(2, role);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Native role disappeared");
                }
                return new RoleBoundary(result.getBoolean(1), result.getBoolean(2), result.getBoolean(3),
                        result.getBoolean(4), result.getBoolean(5), result.getBoolean(6), result.getBoolean(7),
                        false, false);
            }
        }
    }

    private boolean historyPrivilege(Connection connection, String role, String database, int version)
            throws SQLException {
        boolean privilege = false;
        for (PrivilegeSurfaceCompilerV1.HistoryRelation history : policy.historyInventory()) {
            if (!history.database().equals(database)) {
                continue;
            }
            String maintain = version >= 170000
                    ? " OR pg_catalog.has_table_privilege(r.oid,c.oid,'MAINTAIN')" : "";
            try (PreparedStatement statement = query(connection, """
                    SELECT c.relkind IN ('r','p'),
                           (pg_catalog.has_table_privilege(r.oid,c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                             %s OR EXISTS(SELECT 1 FROM pg_catalog.pg_attribute a
                               WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
                                 AND pg_catalog.has_column_privilege(r.oid,c.oid,a.attnum,'SELECT,INSERT,UPDATE,REFERENCES')))
                      FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                      CROSS JOIN pg_catalog.pg_roles r WHERE n.nspname=? AND c.relname=? AND r.rolname=?
                    """.formatted(maintain))) {
                statement.setString(1, history.schema());
                statement.setString(2, history.table());
                statement.setString(3, role);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new SQLException("Approved immutable history relation is missing or ambiguous");
                    }
                    privilege |= result.getBoolean(2);
                    if (result.next()) {
                        throw new SQLException("Approved immutable history relation is ambiguous");
                    }
                }
            }
        }
        return privilege;
    }

    private static boolean schemasExist(Connection connection, List<String> schemas) throws SQLException {
        for (String schema : schemas) {
            try (PreparedStatement statement = query(connection,
                    "SELECT EXISTS(SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname=?)")) {
                statement.setString(1, schema);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static PreparedStatement query(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Canonical trusted identifier required");
        }
    }
}
