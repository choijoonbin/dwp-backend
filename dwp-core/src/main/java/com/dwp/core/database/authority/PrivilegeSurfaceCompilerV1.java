package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned native metadata compiler, not an approval issuer. It never reads history rows.
 * The policy and expected digest must be independently approved deployment inputs; capture()
 * returns observed evidence only. Provider registration, producer/fence and thirteen-stream
 * integration remain unimplemented. Routine text is hashed in memory and is never returned.
 */
public final class PrivilegeSurfaceCompilerV1 {
    public static final String VERSION = "DWP_RUNTIME_PRIVILEGE_SURFACE_V1";
    private static final int MAX_ROWS = 250_000;
    private static final String APP = "n.nspname NOT IN ('pg_catalog','information_schema') "
            + "AND n.nspname NOT LIKE 'pg_temp_%' AND n.nspname NOT LIKE 'pg_toast%'";

    public record HistoryRelation(String streamKey, String database, String schema, String table) {
        public HistoryRelation {
            text(streamKey);
            identifier(database);
            identifier(schema);
            identifier(table);
        }
    }

    public record Policy(String compilerVersion, List<HistoryRelation> historyInventory) {
        public Policy {
            if (!VERSION.equals(compilerVersion)) {
                throw new IllegalArgumentException("Unsupported privilege compiler version");
            }
            historyInventory = List.copyOf(historyInventory);
            if (historyInventory.isEmpty()) {
                throw new IllegalArgumentException("Independently approved history inventory is required");
            }
            Set<String> streams = new HashSet<>();
            Set<String> objects = new HashSet<>();
            for (HistoryRelation history : historyInventory) {
                if (!streams.add(history.streamKey()) || !objects.add(history.database() + "/"
                        + history.schema() + "/" + history.table())) {
                    throw new IllegalArgumentException("Duplicate approved history identity");
                }
            }
        }
    }

    public record SurfaceRow(String objectClass, String identity, List<String> fields) {
        public SurfaceRow {
            text(objectClass);
            text(identity);
            fields = List.copyOf(fields);
            fields.forEach(Objects::requireNonNull);
        }
    }

    /** Length-framed UTF-8 avoids delimiter, NULL, row-order and argument-list ambiguity. */
    public String compile(Policy policy, List<SurfaceRow> rows) {
        Objects.requireNonNull(policy);
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("Privilege surface is empty or exceeds its bound");
        }
        List<SurfaceRow> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(SurfaceRow::objectClass).thenComparing(SurfaceRow::identity));
        MessageDigest digest = sha256();
        frame(digest, VERSION);
        Set<String> keys = new HashSet<>();
        for (SurfaceRow row : ordered) {
            if (!keys.add(row.objectClass() + "\u0000" + row.identity())) {
                throw new IllegalArgumentException("Duplicate privilege surface object");
            }
            frame(digest, row.objectClass());
            frame(digest, row.identity());
            frame(digest, Integer.toString(row.fields().size()));
            row.fields().forEach(field -> frame(digest, field));
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Reads pg_catalog only, on the already authenticated runtime connection. */
    public List<SurfaceRow> capture(Connection connection, Policy policy, String database,
            List<String> reachableRoles) throws SQLException {
        Objects.requireNonNull(policy);
        List<SurfaceRow> rows = new ArrayList<>();
        rows.add(new SurfaceRow("COMPILER", VERSION, List.of(database)));
        for (HistoryRelation history : policy.historyInventory()) {
            if (history.database().equals(database)) {
                rows.add(new SurfaceRow("APPROVED_HISTORY", history.streamKey(),
                        List.of(history.database(), history.schema(), history.table())));
            }
        }
        for (String role : reachableRoles) {
            captureForRole(connection, rows, role);
        }
        collect(connection, rows, "RELATION", """
                SELECT c.oid::text,n.nspname,c.relname,c.relkind::text,
                       pg_catalog.pg_get_userbyid(c.relowner),COALESCE(c.relacl::text,''),
                       c.relrowsecurity::text,c.relforcerowsecurity::text
                  FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE %s AND c.relkind IN ('r','p','v','m','f','S')
                """.formatted(APP), null, false);
        collect(connection, rows, "COLUMN", """
                SELECT c.oid::text||':'||a.attnum::text,n.nspname,c.relname,a.attname,
                       pg_catalog.format_type(a.atttypid,a.atttypmod),COALESCE(a.attacl::text,'')
                  FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE %s AND a.attnum>0 AND NOT a.attisdropped
                """.formatted(APP), null, false);
        collect(connection, rows, "ROUTINE", """
                SELECT p.oid::text,n.nspname,p.proname,
                       pg_catalog.pg_get_function_identity_arguments(p.oid),
                       pg_catalog.pg_get_userbyid(p.proowner),p.prokind::text,p.prosecdef::text,
                       p.proleakproof::text,p.provolatile::text,p.proparallel::text,
                       pg_catalog.format_type(p.prorettype,NULL),l.lanname,
                       COALESCE(p.proacl::text,'<DEFAULT_PUBLIC_EXECUTE>'),
                       COALESCE(p.proconfig::text,''),p.prosrc,COALESCE(p.probin,''),
                       COALESCE(p.proargnames::text,''),COALESCE(p.proallargtypes::text,''),
                       COALESCE(p.proargmodes::text,''),COALESCE(p.proargdefaults::text,'')
                  FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                  JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE %s
                """.formatted(APP), null, true);
        collect(connection, rows, "TYPE", """
                SELECT t.oid::text,n.nspname,t.typname,t.typtype::text,
                       pg_catalog.pg_get_userbyid(t.typowner),COALESCE(t.typacl::text,'<DEFAULT_PUBLIC_USAGE>'),
                       t.typbasetype::text,t.typelem::text,t.typrelid::text
                  FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
                 WHERE %s
                """.formatted(APP), null, false);
        collect(connection, rows, "DEFAULT_ACL", """
                SELECT d.oid::text,pg_catalog.pg_get_userbyid(d.defaclrole),
                       COALESCE(n.nspname,'<GLOBAL>'),d.defaclobjtype::text,d.defaclacl::text
                  FROM pg_catalog.pg_default_acl d LEFT JOIN pg_catalog.pg_namespace n
                    ON n.oid=d.defaclnamespace
                """, null, false);
        collect(connection, rows, "EXTENSION", """
                SELECT e.oid::text,e.extname,pg_catalog.pg_get_userbyid(e.extowner),
                       n.nspname,e.extversion,e.extrelocatable::text
                  FROM pg_catalog.pg_extension e JOIN pg_catalog.pg_namespace n ON n.oid=e.extnamespace
                """, null, false);
        collect(connection, rows, "LARGE_OBJECT_ACL", """
                SELECT m.oid::text,pg_catalog.pg_get_userbyid(m.lomowner),
                       COALESCE(m.lomacl::text,'<DEFAULT_OWNER_ONLY>')
                  FROM pg_catalog.pg_largeobject_metadata m
                """, null, false);
        collect(connection, rows, "FOREIGN_WRAPPER", """
                SELECT w.oid::text,w.fdwname,pg_catalog.pg_get_userbyid(w.fdwowner),
                       w.fdwhandler::text,w.fdwvalidator::text,COALESCE(w.fdwacl::text,''),
                       COALESCE(w.fdwoptions::text,'') FROM pg_catalog.pg_foreign_data_wrapper w
                """, null, false);
        collect(connection, rows, "FOREIGN_SERVER", """
                SELECT s.oid::text,s.srvname,pg_catalog.pg_get_userbyid(s.srvowner),
                       s.srvfdw::text,COALESCE(s.srvacl::text,''),COALESCE(s.srvoptions::text,''),
                       COALESCE(s.srvtype,''),COALESCE(s.srvversion,'')
                  FROM pg_catalog.pg_foreign_server s
                """, null, false);
        collect(connection, rows, "USER_MAPPING", """
                SELECT m.umid::text,m.usename,m.srvname,COALESCE(m.umoptions::text,'<NOT_VISIBLE>')
                  FROM pg_catalog.pg_user_mappings m
                """, null, false);
        return List.copyOf(rows);
    }

    private static void captureForRole(Connection connection, List<SurfaceRow> rows, String role)
            throws SQLException {
        collect(connection, rows, "ROLE:" + role, """
                SELECT r.oid::text,r.rolname,r.rolsuper::text,r.rolcreatedb::text,r.rolcreaterole::text,
                       r.rolreplication::text,r.rolbypassrls::text,r.rolcanlogin::text,r.rolinherit::text,
                       r.rolconnlimit::text,COALESCE(r.rolvaliduntil::text,'')
                  FROM pg_catalog.pg_roles r WHERE r.rolname=?
                """, role, false);
        collect(connection, rows, "ROLE_CONFIG:" + role, """
                SELECT s.setdatabase::text||':'||s.setrole::text,COALESCE(d.datname,'<GLOBAL>'),
                       s.setconfig::text FROM pg_catalog.pg_db_role_setting s
                  LEFT JOIN pg_catalog.pg_database d ON d.oid=s.setdatabase
                 WHERE s.setrole IN (0,(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?))
                """, role, false);
        collect(connection, rows, "MEMBERSHIP:" + role, """
                SELECT m.roleid::text||':'||m.member::text||':'||m.grantor::text,
                       pg_catalog.pg_get_userbyid(m.roleid),pg_catalog.pg_get_userbyid(m.member),
                       pg_catalog.pg_get_userbyid(m.grantor),m.admin_option::text,
                       m.inherit_option::text,m.set_option::text
                  FROM pg_catalog.pg_auth_members m
                 WHERE m.member=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?)
                    OR m.roleid=(SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?)
                """, role, false);
        collect(connection, rows, "DATABASE_ACCESS:" + role, """
                SELECT d.oid::text,d.datname,d.datallowconn::text,
                       pg_catalog.has_database_privilege(r.oid,d.oid,'CONNECT')::text,
                       pg_catalog.has_database_privilege(r.oid,d.oid,'CREATE')::text,
                       pg_catalog.has_database_privilege(r.oid,d.oid,'TEMPORARY')::text
                  FROM pg_catalog.pg_database d CROSS JOIN pg_catalog.pg_roles r WHERE r.rolname=?
                """, role, false);
        collect(connection, rows, "SCHEMA_ACCESS:" + role, """
                SELECT n.oid::text,n.nspname,pg_catalog.pg_get_userbyid(n.nspowner),
                       COALESCE(n.nspacl::text,''),
                       pg_catalog.has_schema_privilege(r.oid,n.oid,'USAGE')::text,
                       pg_catalog.has_schema_privilege(r.oid,n.oid,'CREATE')::text
                  FROM pg_catalog.pg_namespace n CROSS JOIN pg_catalog.pg_roles r
                 WHERE r.rolname=? AND n.nspname NOT LIKE 'pg_temp_%'
                   AND n.nspname NOT LIKE 'pg_toast%'
                """, role, false);
        collect(connection, rows, "RELATION_ACCESS:" + role, """
                SELECT c.oid::text,n.nspname,c.relname,c.relkind::text,
                       COALESCE(c.relacl::text,''),
                       CASE WHEN c.relkind='S' THEN
                         ARRAY[pg_catalog.has_sequence_privilege(r.oid,c.oid,'USAGE'),
                               pg_catalog.has_sequence_privilege(r.oid,c.oid,'SELECT'),
                               pg_catalog.has_sequence_privilege(r.oid,c.oid,'UPDATE')]::text
                       ELSE ARRAY[pg_catalog.has_table_privilege(r.oid,c.oid,'SELECT'),
                         pg_catalog.has_table_privilege(r.oid,c.oid,'INSERT'),
                         pg_catalog.has_table_privilege(r.oid,c.oid,'UPDATE'),
                         pg_catalog.has_table_privilege(r.oid,c.oid,'DELETE'),
                         pg_catalog.has_table_privilege(r.oid,c.oid,'TRUNCATE'),
                         pg_catalog.has_table_privilege(r.oid,c.oid,'REFERENCES'),
                         pg_catalog.has_table_privilege(r.oid,c.oid,'TRIGGER')]::text END
                  FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                  CROSS JOIN pg_catalog.pg_roles r WHERE r.rolname=? AND %s
                   AND c.relkind IN ('r','p','v','m','f','S')
                """.formatted(APP), role, false);
        collect(connection, rows, "COLUMN_ACCESS:" + role, """
                SELECT c.oid::text||':'||a.attnum::text,n.nspname,c.relname,a.attname,
                       COALESCE(a.attacl::text,''),
                       ARRAY[pg_catalog.has_column_privilege(r.oid,c.oid,a.attnum,'SELECT'),
                         pg_catalog.has_column_privilege(r.oid,c.oid,a.attnum,'INSERT'),
                         pg_catalog.has_column_privilege(r.oid,c.oid,a.attnum,'UPDATE'),
                         pg_catalog.has_column_privilege(r.oid,c.oid,a.attnum,'REFERENCES')]::text
                  FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                  JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                  CROSS JOIN pg_catalog.pg_roles r WHERE r.rolname=? AND %s
                   AND c.relkind IN ('r','p','v','m','f') AND a.attnum>0 AND NOT a.attisdropped
                """.formatted(APP), role, false);
        collect(connection, rows, "ROUTINE_ACCESS:" + role, """
                SELECT p.oid::text,n.nspname,p.proname,
                       pg_catalog.pg_get_function_identity_arguments(p.oid),
                       COALESCE(p.proacl::text,'<DEFAULT_PUBLIC_EXECUTE>'),
                       pg_catalog.has_function_privilege(r.oid,p.oid,'EXECUTE')::text
                  FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
                  CROSS JOIN pg_catalog.pg_roles r WHERE r.rolname=? AND %s
                """.formatted(APP), role, false);
        collect(connection, rows, "TYPE_ACCESS:" + role, """
                SELECT t.oid::text,n.nspname,t.typname,COALESCE(t.typacl::text,'<DEFAULT_PUBLIC_USAGE>'),
                       pg_catalog.has_type_privilege(r.oid,t.oid,'USAGE')::text
                  FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace
                  CROSS JOIN pg_catalog.pg_roles r WHERE r.rolname=? AND %s
                """.formatted(APP), role, false);
        collect(connection, rows, "PARAMETER_ACL:" + role, """
                SELECT p.oid::text,p.parname,COALESCE(p.paracl::text,'')
                  FROM pg_catalog.pg_parameter_acl p CROSS JOIN pg_catalog.pg_roles r
                 WHERE r.rolname=? AND EXISTS (SELECT 1 FROM pg_catalog.aclexplode(p.paracl) a
                   WHERE a.grantee IN (0,r.oid))
                """, role, false);
    }

    private static void collect(Connection connection, List<SurfaceRow> rows, String objectClass,
            String sql, String role, boolean digestFields) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            if (role != null) {
                statement.setString(1, role);
                if (objectClass.startsWith("MEMBERSHIP:")) {
                    statement.setString(2, role);
                }
            }
            try (ResultSet result = statement.executeQuery()) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    if (rows.size() >= MAX_ROWS) {
                        throw new SQLException("Privilege metadata exceeds bound");
                    }
                    List<String> fields = new ArrayList<>();
                    for (int column = 2; column <= columns; column++) {
                        String value = result.getString(column);
                        if (value == null) {
                            throw new SQLException("Unexpected NULL privilege metadata");
                        }
                        boolean sensitive = (digestFields && column >= 14)
                                || (objectClass.startsWith("ROLE_CONFIG:") && column == 3)
                                || (objectClass.equals("FOREIGN_WRAPPER") && column == 7)
                                || (objectClass.equals("FOREIGN_SERVER") && column == 6)
                                || (objectClass.equals("USER_MAPPING") && column == 4);
                        fields.add(sensitive ? digestText(value) : value);
                    }
                    rows.add(new SurfaceRow(objectClass, result.getString(1), fields));
                }
            }
        }
    }

    private static String digestText(String value) {
        return java.util.HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Canonical database identifier required");
        }
    }

    private static void text(String value) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Nonempty canonical metadata identity required");
        }
    }
}
