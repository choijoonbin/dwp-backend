package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/** Rejects explicit protected-schema grants to principals outside a service allowlist. */
public final class ProtectedSchemaAclGranteeGuard {

    private ProtectedSchemaAclGranteeGuard() {
    }

    public static void verify(
            String serviceName,
            DataSource migrationDataSource,
            String objectOwnerPrincipal,
            List<String> schemas,
            Set<String> allowedGrantees) {
        Objects.requireNonNull(migrationDataSource, "migrationDataSource must not be null");
        try (Connection connection = migrationDataSource.getConnection()) {
            verify(serviceName, connection, objectOwnerPrincipal, schemas, allowedGrantees);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " protected-schema ACL grantees",
                    exception);
        }
    }

    /** Verifies an in-flight Control transaction without owning or closing its connection. */
    public static void verify(
            String serviceName,
            Connection connection,
            String objectOwnerPrincipal,
            List<String> schemas,
            Set<String> allowedGrantees) {
        Objects.requireNonNull(connection, "connection must not be null");
        requireIdentifier("objectOwnerPrincipal", objectOwnerPrincipal);
        Objects.requireNonNull(schemas, "schemas must not be null");
        Objects.requireNonNull(allowedGrantees, "allowedGrantees must not be null");
        String sql = """
                WITH protected AS (
                    SELECT namespace.oid
                      FROM pg_catalog.pg_namespace namespace
                     WHERE namespace.nspname = ANY (?::text[])
                ), explicit_grants AS (
                    SELECT 'SCHEMA' AS object_class, namespace.nspname AS identity,
                           acl.grantee, namespace.nspowner AS owner,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_namespace namespace
                      JOIN protected ON protected.oid=namespace.oid
                      CROSS JOIN LATERAL pg_catalog.aclexplode(namespace.nspacl) acl
                    UNION ALL
                    SELECT 'RELATION', namespace.nspname || '.' || relation.relname,
                           acl.grantee, relation.relowner,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_class relation
                      JOIN protected ON protected.oid=relation.relnamespace
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(relation.relacl) acl
                    UNION ALL
                    SELECT 'COLUMN', namespace.nspname || '.' || relation.relname
                               || '.' || attribute.attname,
                           acl.grantee, relation.relowner,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_attribute attribute
                      JOIN pg_catalog.pg_class relation
                        ON relation.oid=attribute.attrelid
                      JOIN protected ON protected.oid=relation.relnamespace
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=relation.relnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                     WHERE attribute.attnum > 0 AND NOT attribute.attisdropped
                    UNION ALL
                    SELECT 'ROUTINE', namespace.nspname || '.' || routine.proname
                               || '(' || pg_catalog.pg_get_function_identity_arguments(
                                      routine.oid) || ')',
                           acl.grantee, routine.proowner,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_proc routine
                      JOIN protected ON protected.oid=routine.pronamespace
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=routine.pronamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(routine.proacl) acl
                    UNION ALL
                    SELECT 'TYPE', namespace.nspname || '.' || data_type.typname,
                           acl.grantee, data_type.typowner,
                           acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_type data_type
                      JOIN protected ON protected.oid=data_type.typnamespace
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=data_type.typnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(data_type.typacl) acl
                    UNION ALL
                    SELECT 'DEFAULT_ACL', default_acl.oid::text, acl.grantee,
                           default_acl.defaclrole, acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_default_acl default_acl
                      LEFT JOIN protected
                        ON protected.oid=default_acl.defaclnamespace
                      CROSS JOIN LATERAL pg_catalog.aclexplode(default_acl.defaclacl) acl
                     WHERE protected.oid IS NOT NULL
                        OR (default_acl.defaclnamespace=0
                            AND default_acl.defaclrole=(
                                SELECT role.oid FROM pg_catalog.pg_roles role
                                 WHERE role.rolname=?))
                )
                SELECT grant_row.object_class, grant_row.identity,
                       COALESCE(role.rolname, 'PUBLIC'), grant_row.privilege_type,
                       grant_row.is_grantable
                  FROM explicit_grants grant_row
                  LEFT JOIN pg_catalog.pg_roles role ON role.oid=grant_row.grantee
                 WHERE grant_row.grantee=0
                    OR (grant_row.grantee<>grant_row.owner AND (
                           role.rolname <> ALL (?::text[])
                        OR grant_row.is_grantable
                        OR (grant_row.object_class='SCHEMA'
                            AND grant_row.privilege_type<>'USAGE')
                        OR (grant_row.object_class='RELATION'
                            AND grant_row.privilege_type NOT IN (
                                'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'USAGE'))
                        OR (grant_row.object_class='COLUMN'
                            AND grant_row.privilege_type NOT IN (
                                'SELECT', 'INSERT', 'UPDATE'))
                        OR (grant_row.object_class='ROUTINE'
                            AND grant_row.privilege_type<>'EXECUTE')
                        OR (grant_row.object_class='TYPE'
                            AND grant_row.privilege_type<>'USAGE')
                        OR (grant_row.object_class='DEFAULT_ACL'
                            AND grant_row.privilege_type NOT IN (
                                'SELECT', 'INSERT', 'UPDATE', 'DELETE',
                                'USAGE', 'EXECUTE'))))
                 ORDER BY grant_row.object_class, grant_row.identity, role.rolname
                """;
        try {
            Array schemaArray = connection.createArrayOf("text", schemas.toArray());
            Array allowedArray = connection.createArrayOf("text", allowedGrantees.toArray());
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setArray(1, schemaArray);
                statement.setString(2, objectOwnerPrincipal);
                statement.setArray(3, allowedArray);
                List<String> violations = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        violations.add(result.getString(1) + ":" + result.getString(2)
                                + "->" + result.getString(3) + ":" + result.getString(4)
                                + (result.getBoolean(5) ? ":GRANTABLE" : ""));
                    }
                }
                if (!violations.isEmpty()) {
                    throw new IllegalStateException(
                            serviceName + " protected-schema ACL contains an unapproved grantee: "
                                    + violations);
                }
            } finally {
                allowedArray.free();
                schemaArray.free();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot verify " + serviceName + " protected-schema ACL grantees",
                    exception);
        }
    }

    private static void requireIdentifier(String name, String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }
}
