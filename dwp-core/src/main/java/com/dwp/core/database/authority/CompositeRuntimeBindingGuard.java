package com.dwp.core.database.authority;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.ProtectedSchemaAclGranteeGuard;
import com.dwp.core.database.SystemCatalogAuthorityGuard;

import static com.dwp.core.database.authority.StreamAuthorityContract.*;

/**
 * External Control authority inspection ONLY, not an application startup guard. It requires
 * migration datasource suppliers and must never be wired into a production app. A runtime-only
 * app guard and signed current startup proof/freshness/deployment fence are still unimplemented.
 * No Bean, bootstrap, environment lookup, endpoint
 * factory, SET ROLE, migration, or grant operation is performed. The caller owns datasource pools.
 * A successful call is one service's read-only inspection, not thirteen-stream runtime readiness.
 */
public final class CompositeRuntimeBindingGuard {
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int INSPECTION_NETWORK_TIMEOUT_MILLIS = 15_000;
    public static final String EXECUTION_BOUNDARY = "EXTERNAL_CONTROL_AUTHORITY_INSPECTION";

    private CompositeRuntimeBindingGuard() { }

    /** These values must be supplied by the independent deployment authority, never the documents. */
    public record ExternalExpected(String manifestSha256, String receiptSha256,
            String currentControlReference, String previousCompositeReceiptSha256) {
        public ExternalExpected {
            digest(manifestSha256, false); digest(receiptSha256, false);
            reference(currentControlReference); digest(previousCompositeReceiptSha256, true);
        }
    }

    /** A trusted registry entry, not authorization to create a pool at an arbitrary URL. */
    public record RegisteredDataSource(Supplier<DataSource> supplier, String expectedServerAddress,
            int expectedServerPort) {
        public RegisteredDataSource {
            Objects.requireNonNull(supplier);
            require(expectedServerAddress != null
                    && expectedServerAddress.matches("[0-9a-f.:]{2,64}")
                    && expectedServerPort >= 1 && expectedServerPort <= 65535,
                    "trusted server identity required");
        }
    }

    public record Inspection(String service, List<String> inspectedStreams, List<String> disabledStreams,
            String implementationStatus, String executionBoundary) {
        public Inspection {
            inspectedStreams = List.copyOf(inspectedStreams); disabledStreams = List.copyOf(disabledStreams);
            require(ExactStreamTopology.IMPLEMENTATION_STATUS.equals(implementationStatus)
                    && EXECUTION_BOUNDARY.equals(executionBoundary), "inspection must remain external and unwired");
        }
    }

    public static Inspection inspectExternallyControlledService(String service, String manifestDocument, String receiptDocument,
            ExternalExpected expected, Map<String, RegisteredDataSource> trustedRegistry) {
        Objects.requireNonNull(expected);
        StreamAuthorityContract manifest = StreamAuthorityJson.manifest(manifestDocument,
                expected.manifestSha256(), expected.currentControlReference());
        CompositeAuthorityReceipt receipt = StreamAuthorityJson.receipt(receiptDocument, manifest,
                expected.receiptSha256(), expected.currentControlReference(), expected.previousCompositeReceiptSha256());
        require(ExactStreamTopology.SERVICES.contains(service), "unknown service");
        Map<String, RegisteredDataSource> registry = Map.copyOf(Objects.requireNonNull(trustedRegistry));
        Set<String> allowedQualifiers = new HashSet<>();
        for (StreamAuthority stream : manifest.streams()) {
            if (stream.service().equals(service)) {
                allowedQualifiers.add(stream.migrationQualifier());
                stream.runtimePrincipals().forEach(value -> allowedQualifiers.add(value.qualifier()));
            }
        }
        require(allowedQualifiers.containsAll(registry.keySet()), "unknown or foreign datasource qualifier");
        for (StreamAuthority stream : manifest.enabledStreams(service)) {
            require(registry.containsKey(stream.migrationQualifier())
                    && stream.runtimePrincipals().stream().allMatch(value -> registry.containsKey(value.qualifier())),
                    "enabled stream datasource is missing; no fallback permitted");
        }
        Set<DataSource> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (StreamAuthority stream : manifest.enabledStreams(service)) {
            RegisteredDataSource migrationBinding = registry.get(stream.migrationQualifier());
            DataSource migration = supply(migrationBinding);
            require(seen.add(migration), "datasource instance reused across authorities");
            for (RuntimePrincipal runtime : stream.runtimePrincipals()) {
                RegisteredDataSource runtimeBinding = registry.get(runtime.qualifier());
                DataSource application = supply(runtimeBinding);
                require(seen.add(application), "datasource instance reused across authorities");
                inspectStream(manifest, stream, receipt.stream(stream.streamKey()), runtime,
                        migrationBinding, migration, runtimeBinding, application);
            }
        }
        return new Inspection(service, manifest.enabledStreams(service).stream().map(StreamAuthority::streamKey).toList(),
                manifest.streams().stream().filter(value -> value.service().equals(service) && !value.enabled())
                        .map(StreamAuthority::streamKey).toList(), ExactStreamTopology.IMPLEMENTATION_STATUS, EXECUTION_BOUNDARY);
    }

    private static DataSource supply(RegisteredDataSource binding) {
        try { return Objects.requireNonNull(binding.supplier().get()); }
        catch (Exception exception) { throw invalid("registered datasource unavailable; no fallback permitted"); }
    }

    /** Package-private pilot seam. It is not a public partial-readiness approval API. */
    static void inspectStream(StreamAuthorityContract manifest, StreamAuthority stream,
            CompositeAuthorityReceipt.StreamSeal seal, RegisteredDataSource migrationBinding,
            DataSource migration, RegisteredDataSource runtimeBinding, DataSource application) {
        require(stream.runtimePrincipals().size() == 1, "single-purpose pilot only");
        inspectStream(manifest, stream, seal, stream.runtimePrincipals().getFirst(),
                migrationBinding, migration, runtimeBinding, application);
    }

    static void inspectStream(StreamAuthorityContract manifest, StreamAuthority stream,
            CompositeAuthorityReceipt.StreamSeal seal, RuntimePrincipal runtime,
            RegisteredDataSource migrationBinding, DataSource migration,
            RegisteredDataSource runtimeBinding, DataSource application) {
        require(stream.enabled() && migration != application, "distinct enabled datasource identities required");
        require(stream.runtimePrincipals().contains(runtime), "runtime purpose/principal/qualifier is not in exact authority");
        require(StreamAuthorityJson.authorityDigest(stream).equals(seal.authoritySha256())
                && manifest.catalog(stream.catalogKey()).database().equals(seal.database())
                && stream.migrationPrincipal().equals(seal.migrationPrincipal()), "pilot stream seal identity differs");
        try (Connection control = migration.getConnection(); Connection request = application.getConnection();
             NetworkBoundary controlNetwork = NetworkBoundary.open(control);
             NetworkBoundary requestNetwork = NetworkBoundary.open(request)) {
            Identity migrationIdentity = identity(controlNetwork.connection(), stream.migrationPrincipal(), false,
                    seal.database(), seal.postgresVersion(), migrationBinding);
            Identity runtimeIdentity = identity(requestNetwork.connection(), runtime.principal(), runtime.readOnly(),
                    seal.database(), seal.postgresVersion(), runtimeBinding);
            require(migrationIdentity.equals(runtimeIdentity), "migration/runtime catalog endpoint differs");
            // Never issue SET ROLE or rewrite search_path to make an incorrect binding appear valid.
            readOnlyInspection(control);
            readOnlyInspection(request);
            try {
                roleBoundary(control, stream.migrationPrincipal(), true, stream.schemas());
                roleBoundary(request, runtime.principal(), false, stream.schemas());
                pathBoundary(control, List.of("pg_catalog", stream.schemas().getFirst()));
                pathBoundary(request, runtime.searchPath());
                exactRuntimeAcl(request, stream, runtime);
                ProtectedSchemaAclGranteeGuard.verify(stream.service(), control,
                        stream.migrationPrincipal(), stream.schemas(), stream.runtimePrincipals().stream()
                                .map(RuntimePrincipal::principal).collect(java.util.stream.Collectors.toSet()));
                nativeSeal(control, stream, seal);
            } finally {
                control.rollback(); request.rollback();
            }
        } catch (SQLException exception) {
            // Do not attach driver exceptions: URLs, usernames and accidental passwords can be embedded.
            throw invalid("read-only database binding inspection failed");
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException && exception.getCause() == null
                    && exception.getMessage() != null && exception.getMessage().startsWith("Stream authority: ")) {
                throw exception;
            }
            // Reused guards can wrap a driver exception. Preserve rejection but never expose its
            // cause/connection string; only our value-free boundary diagnostics may pass through.
            throw invalid("native ownership/ACL boundary inspection rejected");
        }
    }

    private record Identity(String serverAddress, int serverPort, String database) { }

    /** Bounds existing exhaustive inventory queries too; restore pool-owned state before close. */
    private record NetworkBoundary(Connection connection, int originalTimeout) implements AutoCloseable {
        static NetworkBoundary open(Connection connection) throws SQLException {
            int timeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, INSPECTION_NETWORK_TIMEOUT_MILLIS);
            return new NetworkBoundary(connection, timeout);
        }
        @Override public void close() throws SQLException {
            if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, originalTimeout);
        }
    }

    private static Identity identity(Connection connection, String principal, boolean expectedReadOnly,
            String database, String postgresVersion, RegisteredDataSource binding) throws SQLException {
        require(connection.getAutoCommit() && connection.isReadOnly() == expectedReadOnly,
                "pool transaction/readOnly posture differs");
        require(principal.equals(connection.getMetaData().getUserName()),
                "original authenticated JDBC LOGIN differs; current_user/session_user disguise prohibited");
        try (PreparedStatement statement = query(connection, """
                SELECT current_database(), current_user, session_user,
                       pg_catalog.host(inet_server_addr()), inet_server_port(),
                       current_setting('server_version'), current_setting('transaction_read_only'),
                       current_setting('session_replication_role'), current_setting('role')
                """); ResultSet result = statement.executeQuery()) {
            require(result.next() && database.equals(result.getString(1))
                    && principal.equals(result.getString(2)) && principal.equals(result.getString(3)),
                    "actual catalog/current_user/session_user differs; SET ROLE prohibited");
            require(binding.expectedServerAddress().equals(result.getString(4))
                    && binding.expectedServerPort() == result.getInt(5), "actual trusted server identity differs");
            String actualVersion = result.getString(6).split(" ", 2)[0];
            require(postgresVersion.equals(actualVersion)
                    && (expectedReadOnly ? "on" : "off").equals(result.getString(7))
                    && "origin".equals(result.getString(8)) && "none".equals(result.getString(9)),
                    "server/session/readOnly/role posture differs");
            return new Identity(result.getString(4), result.getInt(5), result.getString(1));
        }
    }

    private static void readOnlyInspection(Connection connection) throws SQLException {
        connection.setReadOnly(true); connection.setAutoCommit(false);
    }

    private static void roleBoundary(Connection connection, String principal, boolean migration,
                                     List<String> schemas) throws SQLException {
        try (PreparedStatement statement = query(connection, """
                SELECT role.oid, role.rolcanlogin, role.rolsuper, role.rolcreatedb,
                       role.rolcreaterole, role.rolreplication, role.rolbypassrls,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members membership
                                WHERE membership.member=role.oid OR membership.roleid=role.oid),
                       pg_catalog.has_database_privilege(role.oid,current_database(),'CONNECT'),
                       pg_catalog.has_database_privilege(role.oid,current_database(),'CREATE'),
                       pg_catalog.has_database_privilege(role.oid,current_database(),'TEMP'),
                       EXISTS (SELECT 1 FROM pg_catalog.pg_database catalog
                                WHERE catalog.datname<>current_database() AND NOT catalog.datistemplate
                                  AND pg_catalog.has_database_privilege(role.oid,catalog.oid,'CONNECT'))
                  FROM pg_catalog.pg_roles role WHERE role.rolname=?
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getBoolean(2), "direct LOGIN principal required");
                for (int index : List.of(3,4,5,6,7,8,10,11,12)) {
                    require(!result.getBoolean(index), "principal has elevated/membership/foreign catalog authority");
                }
                require(result.getBoolean(9), "principal lacks exact catalog CONNECT");
            }
        }
        Array schemaArray = connection.createArrayOf("text", schemas.toArray());
        try (PreparedStatement statement = query(connection, """
                SELECT namespace.nspname, owner.rolname,
                       pg_catalog.has_schema_privilege(?,namespace.oid,'USAGE'),
                       pg_catalog.has_schema_privilege(?,namespace.oid,'CREATE')
                  FROM pg_catalog.pg_namespace namespace
                  JOIN pg_catalog.pg_roles owner ON owner.oid=namespace.nspowner
                 WHERE namespace.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                   AND namespace.nspname<>'information_schema'
                 ORDER BY namespace.nspname
                """)) {
            statement.setString(1, principal); statement.setString(2, principal);
            int found = 0;
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    boolean ownedStream = schemas.contains(result.getString(1));
                    if (ownedStream) found++;
                    require(result.getBoolean(3) == ownedStream
                            && result.getBoolean(4) == (migration && ownedStream), "schema usage/create surface differs");
                    require(!principal.equals(result.getString(2)) || (migration && ownedStream),
                            "principal owns foreign/runtime schema");
                    if (ownedStream && migration) require(principal.equals(result.getString(2)), "schema owner differs");
                }
            }
            require(found == schemas.size(), "required schema missing");
        }
        try (PreparedStatement statement = query(connection, """
                SELECT count(*) FROM pg_catalog.pg_shdepend dependency
                  JOIN pg_catalog.pg_roles role ON role.oid=dependency.refobjid
                 WHERE dependency.refclassid='pg_authid'::regclass AND dependency.deptype='o'
                   AND role.rolname=?
                   AND (?=false OR NOT (
                     (dependency.classid='pg_namespace'::regclass AND dependency.objid IN (
                       SELECT oid FROM pg_catalog.pg_namespace WHERE nspname=ANY (?::text[]))) OR
                     (dependency.classid='pg_class'::regclass AND dependency.objid IN (
                       SELECT object.oid FROM pg_catalog.pg_class object
                        JOIN pg_catalog.pg_namespace n ON n.oid=object.relnamespace WHERE n.nspname=ANY (?::text[]))) OR
                     (dependency.classid='pg_proc'::regclass AND dependency.objid IN (
                       SELECT object.oid FROM pg_catalog.pg_proc object
                        JOIN pg_catalog.pg_namespace n ON n.oid=object.pronamespace WHERE n.nspname=ANY (?::text[]))) OR
                     (dependency.classid='pg_type'::regclass AND dependency.objid IN (
                       SELECT object.oid FROM pg_catalog.pg_type object
                        JOIN pg_catalog.pg_namespace n ON n.oid=object.typnamespace WHERE n.nspname=ANY (?::text[]))) OR
                     (dependency.classid='pg_default_acl'::regclass AND dependency.objid IN (
                       SELECT object.oid FROM pg_catalog.pg_default_acl object
                        WHERE object.defaclnamespace=0 OR object.defaclnamespace IN (
                          SELECT oid FROM pg_catalog.pg_namespace WHERE nspname=ANY (?::text[]))))))
                """)) {
            statement.setString(1, principal); statement.setBoolean(2, migration);
            for (int index=3; index<=7; index++) statement.setArray(index, schemaArray);
            zero(statement, "principal owns unapproved database-wide objects");
        } finally { schemaArray.free(); }
        try (PreparedStatement statement = query(connection, """
                SELECT count(*) FROM pg_catalog.pg_parameter_acl parameter
                  CROSS JOIN LATERAL pg_catalog.aclexplode(parameter.paracl) acl
                  JOIN pg_catalog.pg_roles role ON role.rolname=?
                 WHERE acl.grantee IN (0,role.oid)
                """)) {
            statement.setString(1, principal); zero(statement, "principal has parameter authority");
        }
        SystemCatalogAuthorityGuard.verify("composite-authority", connection, principal, "direct LOGIN");
    }

    private static void pathBoundary(Connection connection, List<String> expected) throws SQLException {
        try (PreparedStatement statement = query(connection,
                "SELECT current_setting('search_path'), current_schemas(false)" ); ResultSet result=statement.executeQuery()) {
            require(result.next() && String.join(", ", expected).equals(result.getString(1))
                    && expected.equals(List.of((String[]) result.getArray(2).getArray())), "exact searchPath differs");
        }
        try (PreparedStatement statement = query(connection, """
                WITH settings AS (
                    SELECT unnest(COALESCE(role.rolconfig,ARRAY[]::text[])) value
                      FROM pg_catalog.pg_roles role WHERE role.rolname=current_user
                    UNION ALL SELECT unnest(setting.setconfig)
                      FROM pg_catalog.pg_db_role_setting setting
                     WHERE setting.setdatabase IN (0,(SELECT oid FROM pg_catalog.pg_database WHERE datname=current_database()))
                       AND setting.setrole IN (0,(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user)))
                SELECT value FROM settings WHERE lower(split_part(value,'=',1)) IN
                  ('search_path','session_replication_role','role')
                """); ResultSet result=statement.executeQuery()) {
            while(result.next()) {
                String value=result.getString(1);
                require(value.equals("search_path="+String.join(", ",expected))
                        || value.equals("session_replication_role=origin") || value.equals("role=none"),
                        "persistent role/database setting permits shadowing or role bypass");
            }
        }
    }

    private static void exactRuntimeAcl(Connection connection, StreamAuthority stream,
                                         RuntimePrincipal runtime) throws SQLException {
        Set<String> actual = new HashSet<>();
        // Inspect effective relation/routine/custom-type privileges across ALL non-system schemas,
        // not merely the current stream. The direct LOGIN has no membership/ownership escape.
        String sql="""
                WITH namespaces AS (
                  SELECT oid,nspname FROM pg_catalog.pg_namespace
                   WHERE nspname NOT LIKE 'pg\\_%' ESCAPE '\\' AND nspname<>'information_schema'),
                privileges AS (
                  SELECT CASE WHEN object.relkind='S' THEN 'SEQUENCE' ELSE 'TABLE' END object_class,
                         n.nspname,object.relname name,'' arguments,privilege,
                         CASE WHEN object.relkind='S' THEN
                           pg_catalog.has_sequence_privilege(current_user,object.oid,privilege)
                           ELSE pg_catalog.has_table_privilege(current_user,object.oid,privilege) END allowed
                    FROM pg_catalog.pg_class object JOIN namespaces n ON n.oid=object.relnamespace
                    CROSS JOIN LATERAL unnest(CASE WHEN object.relkind='S' THEN
                      ARRAY['SELECT','USAGE','UPDATE'] ELSE
                      ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'] END) privilege
                   WHERE object.relkind IN ('r','p','v','m','f','S')
                  UNION ALL
                  SELECT 'ROUTINE',n.nspname,routine.proname,
                         replace(pg_catalog.oidvectortypes(routine.proargtypes),', ',','),'EXECUTE',
                         pg_catalog.has_function_privilege(current_user,routine.oid,'EXECUTE')
                    FROM pg_catalog.pg_proc routine JOIN namespaces n ON n.oid=routine.pronamespace
                  UNION ALL
                  SELECT 'TYPE',n.nspname,object.typname,'','USAGE',
                         pg_catalog.has_type_privilege(current_user,object.oid,'USAGE')
                    FROM pg_catalog.pg_type object JOIN namespaces n ON n.oid=object.typnamespace
                   WHERE object.typrelid=0 AND object.typelem=0 AND object.typtype<>'p')
                SELECT object_class,nspname,name,arguments,privilege FROM privileges WHERE allowed
                """;
        try (PreparedStatement statement=query(connection,sql);ResultSet result=statement.executeQuery()) {
            while(result.next()) {
                String identity=result.getString(1)+":"+result.getString(2)+"."+result.getString(3)
                        +"("+result.getString(4)+"):"+result.getString(5);
                require(actual.add(identity),"duplicate actual object privilege");
            }
        }
        Set<String> expected=new HashSet<>();
        for(ObjectGrant grant:runtime.objectGrants()) for(String privilege:grant.privileges()) {
            expected.add(grant.identity()+":"+privilege);
        }
        require(actual.equals(expected),"runtime object ACL surface differs from exact allowlist");
        // Column grants, grant-options and defaults are not a supported v1 surface. Reject even
        // when a current full-table grant hides them. No PUBLIC execute/type grant is a fallback.
        try (PreparedStatement statement=query(connection,"""
                WITH role AS (SELECT oid FROM pg_catalog.pg_roles WHERE rolname=current_user),
                direct AS (
                  SELECT acl.grantee,acl.is_grantable,acl.privilege_type,'RELATION' object_class
                    FROM pg_catalog.pg_class object JOIN pg_catalog.pg_namespace n ON n.oid=object.relnamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(object.relacl) acl
                   WHERE n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\' AND n.nspname<>'information_schema'
                  UNION ALL SELECT acl.grantee,acl.is_grantable,acl.privilege_type,'COLUMN'
                    FROM pg_catalog.pg_attribute attribute JOIN pg_catalog.pg_class object ON object.oid=attribute.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid=object.relnamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                   WHERE n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\' AND n.nspname<>'information_schema'
                  UNION ALL SELECT acl.grantee,acl.is_grantable,acl.privilege_type,'DEFAULT'
                    FROM pg_catalog.pg_default_acl object
                    CROSS JOIN LATERAL pg_catalog.aclexplode(object.defaclacl) acl)
                SELECT count(*) FROM direct CROSS JOIN role WHERE grantee IN (0,role.oid)
                  AND (is_grantable OR object_class IN ('COLUMN','DEFAULT') OR privilege_type='MAINTAIN')
                """)) { zero(statement,"runtime column/default/grant-option authority unsupported"); }
    }

    private static void nativeSeal(Connection connection, StreamAuthority stream,
                                   CompositeAuthorityReceipt.StreamSeal seal) throws SQLException {
        MigrationAdoptionGuard.Contract contract=new MigrationAdoptionGuard.Contract(stream.streamKey(),
                stream.schemas().getFirst(),stream.historyTable(),stream.schemas());
        // Identifiers were fixed by the independent topology oracle; no caller-supplied SQL is used.
        String relation="\""+contract.historySchema()+"\".\""+contract.historyTable()+"\"";
        try(PreparedStatement statement=query(connection,"SELECT count(*),COALESCE(max(installed_rank),0),"
                +"count(*) FILTER (WHERE NOT success OR installed_by<>?) FROM "+relation)) {
            statement.setString(1,stream.migrationPrincipal());
            try(ResultSet result=statement.executeQuery()) {
                require(result.next()&&result.getInt(1)==seal.historyRowCount()
                        &&result.getInt(2)==seal.historyMaxInstalledRank()&&result.getInt(3)==0,
                        "native history rank/count/principal/success differs");
            }
        }
        MigrationAdoptionGuard.HistoryDigest history=MigrationAdoptionGuard.digestHistory(connection,contract,
                seal.historyMaxInstalledRank(),0,stream.migrationPrincipal());
        MigrationAdoptionGuard.InventoryDigest inventory=MigrationAdoptionGuard.digestLiveInventory(connection,
                contract,stream.migrationPrincipal());
        require(history.allSuccessful()&&history.postLegacyOwnedByMigration()
                &&history.sha256().equals(seal.historySha256())
                &&inventory.objectCount()==seal.inventoryObjectCount()
                &&inventory.sha256().equals(seal.definitionAclOwnershipInventorySha256()),
                "native definition/ACL/ownership/history seal differs");
    }

    private static PreparedStatement query(Connection connection,String sql) throws SQLException {
        PreparedStatement statement=connection.prepareStatement(sql);
        try { statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS); return statement; }
        catch(SQLException exception) { statement.close(); throw exception; }
    }
    private static void zero(PreparedStatement statement,String message) throws SQLException {
        try(ResultSet result=statement.executeQuery()) { require(result.next()&&result.getLong(1)==0,message); }
    }
}
