package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Admission floor for explicit ACLs carried by non-login auxiliary authority roles.
 *
 * <p>The exact object identities and ACL bytes remain part of the protected-object
 * definition fingerprint sealed by Migration Control. This guard independently
 * prevents a first seal from admitting a privilege outside the role-specific
 * object-class/schema surface. Owner-self ACL rows are excluded because they add
 * no authority beyond ownership, which is separately sealed and allowlisted.</p>
 */
public final class AuxiliaryRoleAclGuard {

    private static final Set<String> OBJECT_CLASSES = Set.of(
            "SCHEMA", "RELATION", "COLUMN", "ROUTINE", "TYPE", "DEFAULT_ACL");

    public record AllowedPrivilege(
            String grantee,
            String objectClass,
            String schema,
            String objectIdentity,
            String privilege) {

        public AllowedPrivilege {
            requireIdentifier("grantee", grantee);
            requireIdentifier("schema", schema);
            objectClass = requireUpperToken("objectClass", objectClass);
            requireText("objectIdentity", objectIdentity);
            privilege = requireUpperToken("privilege", privilege);
            if (!OBJECT_CLASSES.contains(objectClass)) {
                throw new IllegalArgumentException(
                        "objectClass is not an ACL-bearing protected object class");
            }
        }

        public AllowedPrivilege(
                String grantee, String objectClass, String schema, String privilege) {
            this(grantee, objectClass, schema, "*", privilege);
        }

        private boolean admits(AllowedPrivilege actual) {
            return grantee.equals(actual.grantee)
                    && objectClass.equals(actual.objectClass)
                    && schema.equals(actual.schema)
                    && privilege.equals(actual.privilege)
                    && ("*".equals(objectIdentity)
                            || objectIdentity.equals(actual.objectIdentity));
        }
    }

    private AuxiliaryRoleAclGuard() {
    }

    public static void verify(
            String serviceName,
            DataSource dataSource,
            List<String> protectedSchemas,
            Set<String> auxiliaryRoles,
            Set<AllowedPrivilege> allowedPrivileges) {
        verify(
                serviceName,
                dataSource,
                protectedSchemas,
                auxiliaryRoles,
                allowedPrivileges,
                Set.of());
    }

    public static void verify(
            String serviceName,
            DataSource dataSource,
            List<String> protectedSchemas,
            Set<String> auxiliaryRoles,
            Set<AllowedPrivilege> allowedPrivileges,
            Set<AllowedPrivilege> requiredPrivileges) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        try (Connection connection = dataSource.getConnection()) {
            verify(
                    serviceName,
                    connection,
                    protectedSchemas,
                    auxiliaryRoles,
                    allowedPrivileges,
                    requiredPrivileges);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " auxiliary-role ACL boundary",
                    exception);
        }
    }

    /** Verifies an in-flight Control transaction without closing its connection. */
    public static void verify(
            String serviceName,
            Connection connection,
            List<String> protectedSchemas,
            Set<String> auxiliaryRoles,
            Set<AllowedPrivilege> allowedPrivileges) {
        verify(
                serviceName,
                connection,
                protectedSchemas,
                auxiliaryRoles,
                allowedPrivileges,
                Set.of());
    }

    /**
     * Verifies the ACL admission floor and the allowed entries that must be
     * present in the steady state. A required wildcard entry is appropriate
     * only for a singleton surface such as a named schema; object-specific
     * requirements should carry their exact identity. Exact ACL bytes remain
     * sealed by the protected inventory fingerprint.
     */
    public static void verify(
            String serviceName,
            Connection connection,
            List<String> protectedSchemas,
            Set<String> auxiliaryRoles,
            Set<AllowedPrivilege> allowedPrivileges,
            Set<AllowedPrivilege> requiredPrivileges) {
        requireText("serviceName", serviceName);
        Objects.requireNonNull(connection, "connection must not be null");
        Set<String> schemas = canonicalIdentifiers("protectedSchemas", protectedSchemas);
        Set<String> roles = canonicalIdentifiers("auxiliaryRoles", auxiliaryRoles);
        Objects.requireNonNull(allowedPrivileges, "allowedPrivileges must not be null");
        Objects.requireNonNull(requiredPrivileges, "requiredPrivileges must not be null");
        for (AllowedPrivilege allowed : allowedPrivileges) {
            if (!roles.contains(allowed.grantee()) || !schemas.contains(allowed.schema())) {
                throw new IllegalArgumentException(
                        "Allowed auxiliary ACL privilege is outside the declared role/schema set");
            }
        }
        if (!allowedPrivileges.containsAll(requiredPrivileges)) {
            throw new IllegalArgumentException(
                    "Required auxiliary ACL privileges must be an allowed subset");
        }
        if (roles.isEmpty()) {
            if (!allowedPrivileges.isEmpty() || !requiredPrivileges.isEmpty()) {
                throw new IllegalArgumentException(
                        "Allowed auxiliary ACL privileges require auxiliary roles");
            }
            return;
        }

        String sql = """
                WITH auxiliary AS (
                    SELECT role.oid, role.rolname
                      FROM pg_catalog.pg_roles role
                     WHERE role.rolname=ANY (?::text[])
                ), explicit_grants AS (
                    SELECT 'SCHEMA'::text AS object_class,
                           namespace.nspname::text AS object_schema,
                           namespace.nspname::text AS object_identity,
                           namespace.nspowner AS object_owner,
                           acl.grantee, acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_namespace namespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) acl
                    UNION ALL
                    SELECT 'RELATION', namespace.nspname,
                           namespace.nspname || '.' || relation.relname,
                           relation.relowner, acl.grantee,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_class relation
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(relation.relacl) acl
                    UNION ALL
                    SELECT 'COLUMN', namespace.nspname,
                           namespace.nspname || '.' || relation.relname || '.'
                               || attribute.attname,
                           relation.relowner, acl.grantee,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_attribute attribute
                      JOIN pg_catalog.pg_class relation
                        ON relation.oid=attribute.attrelid
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                     WHERE attribute.attnum>0 AND NOT attribute.attisdropped
                    UNION ALL
                    SELECT 'ROUTINE', namespace.nspname,
                           namespace.nspname || '.' || routine.proname || '('
                               || pg_catalog.pg_get_function_identity_arguments(routine.oid)
                               || ')',
                           routine.proowner, acl.grantee,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_proc routine
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=routine.pronamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(routine.proacl) acl
                    UNION ALL
                    SELECT 'TYPE', namespace.nspname,
                           namespace.nspname || '.' || data_type.typname,
                           data_type.typowner, acl.grantee,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_type data_type
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=data_type.typnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(data_type.typacl) acl
                    UNION ALL
                    SELECT 'DEFAULT_ACL', COALESCE(namespace.nspname, '<global>'),
                           default_acl.oid::text, default_acl.defaclrole,
                           acl.grantee, acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_default_acl default_acl
                      LEFT JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=default_acl.defaclnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(
                          default_acl.defaclacl) acl
                )
                SELECT auxiliary.rolname, grant_row.object_class,
                       grant_row.object_schema, grant_row.object_identity,
                       grant_row.privilege_type, grant_row.is_grantable
                  FROM explicit_grants grant_row
                  JOIN auxiliary ON auxiliary.oid=grant_row.grantee
                 WHERE grant_row.grantee<>grant_row.object_owner
                 ORDER BY auxiliary.rolname, grant_row.object_class,
                          grant_row.object_schema, grant_row.object_identity,
                          grant_row.privilege_type
                """;
        try {
            Array roleArray = connection.createArrayOf("text", roles.toArray());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setArray(1, roleArray);
                List<String> violations = new ArrayList<>();
                Set<AllowedPrivilege> observed = new LinkedHashSet<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if ("<global>".equals(result.getString(3))
                                || !schemas.contains(result.getString(3))) {
                            violations.add(result.getString(1) + ":"
                                    + result.getString(2) + ":"
                                    + result.getString(3) + ":"
                                    + result.getString(4) + ":" + result.getString(5));
                            continue;
                        }
                        AllowedPrivilege actual = new AllowedPrivilege(
                                result.getString(1),
                                result.getString(2),
                                result.getString(3),
                                result.getString(4),
                                result.getString(5));
                        List<AllowedPrivilege> admitted = allowedPrivileges.stream()
                                .filter(allowed -> allowed.admits(actual))
                                .toList();
                        if (result.getBoolean(6) || admitted.size() != 1) {
                            violations.add(actual.grantee() + ":" + actual.objectClass()
                                    + ":" + result.getString(4) + ":"
                                    + actual.privilege()
                                    + (result.getBoolean(6) ? ":GRANTABLE" : ""));
                        } else {
                            observed.add(admitted.getFirst());
                        }
                    }
                }
                if (!violations.isEmpty()) {
                    throw new IllegalStateException(
                            serviceName
                                    + " auxiliary-role ACL exceeds the exact privilege surface: "
                                    + violations);
                }
                Set<AllowedPrivilege> missing = new LinkedHashSet<>(requiredPrivileges);
                missing.removeAll(observed);
                if (!missing.isEmpty()) {
                    throw new IllegalStateException(
                            serviceName
                                    + " auxiliary-role ACL is missing required steady privileges: "
                                    + missing);
                }
            } finally {
                roleArray.free();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " auxiliary-role ACL boundary",
                    exception);
        }
    }

    private static Set<String> canonicalIdentifiers(String name, Iterable<String> values) {
        Objects.requireNonNull(values, name + " must not be null");
        Set<String> canonical = new LinkedHashSet<>();
        for (String value : values) {
            requireIdentifier(name, value);
            if (!canonical.add(value)) {
                throw new IllegalArgumentException(name + " must be unique");
            }
        }
        return Set.copyOf(canonical);
    }

    private static String requireUpperToken(String name, String value) {
        requireText(name, value);
        if (!value.matches("[A-Z][A-Z_]{0,31}")
                || !value.equals(value.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(name + " must be a canonical upper-case token");
        }
        return value;
    }

    private static void requireIdentifier(String name, String value) {
        requireText(name, value);
        if (!value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
