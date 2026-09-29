package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Rejects service roles receiving privileges beyond PostgreSQL's initial catalog ACLs. */
public final class SystemCatalogAuthorityGuard {

    private SystemCatalogAuthorityGuard() {
    }

    /** Verifies an existing connection without owning, mutating, or closing it. */
    public static void verify(
            String serviceName,
            Connection connection,
            String principal,
            String purpose) throws SQLException {
        requireText("serviceName", serviceName);
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        requireText("principal", principal);
        requireText("purpose", purpose);
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH principal_role AS (
                    SELECT oid FROM pg_catalog.pg_roles WHERE rolname=?
                ), baseline_acl AS (
                    SELECT initial.classoid, initial.objoid, initial.objsubid,
                           acl.grantee, acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_init_privs initial
                      CROSS JOIN LATERAL pg_catalog.aclexplode(initial.initprivs) acl
                     WHERE initial.privtype='i'
                    UNION ALL
                    SELECT 'pg_class'::regclass, object.oid, 0,
                           acl.grantee, acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_class object
                      CROSS JOIN LATERAL pg_catalog.aclexplode(pg_catalog.acldefault(
                          CASE WHEN object.relkind='S' THEN 's'::"char"
                               ELSE 'r'::"char" END,
                          object.relowner)) acl
                     WHERE NOT EXISTS (
                           SELECT 1 FROM pg_catalog.pg_init_privs initial
                            WHERE initial.privtype='i'
                              AND initial.classoid='pg_class'::regclass
                              AND initial.objoid=object.oid
                              AND initial.objsubid=0)
                    UNION ALL
                    SELECT 'pg_proc'::regclass, routine.oid, 0,
                           acl.grantee, acl.privilege_type, acl.is_grantable
                      FROM pg_catalog.pg_proc routine
                      CROSS JOIN LATERAL pg_catalog.aclexplode(
                          pg_catalog.acldefault('f', routine.proowner)) acl
                     WHERE NOT EXISTS (
                           SELECT 1 FROM pg_catalog.pg_init_privs initial
                            WHERE initial.privtype='i'
                              AND initial.classoid='pg_proc'::regclass
                              AND initial.objoid=routine.oid
                              AND initial.objsubid=0)
                ), violations AS (
                    SELECT 'RELATION' AS object_class,
                           format('%I.%I', namespace.nspname, object.relname) AS identity,
                           acl.privilege_type, acl.is_grantable,
                           COALESCE(grantee.rolname, 'PUBLIC') AS grantee
                      FROM pg_catalog.pg_class object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.relnamespace
                      CROSS JOIN principal_role
                      CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                          object.relacl,
                          pg_catalog.acldefault(
                              CASE WHEN object.relkind='S' THEN 's'::"char"
                                   ELSE 'r'::"char" END,
                              object.relowner))) acl
                      LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                     WHERE namespace.nspname='pg_catalog'
                       AND acl.grantee IN (0, principal_role.oid)
                       AND (acl.grantee=principal_role.oid OR NOT EXISTS (
                           SELECT 1 FROM baseline_acl baseline
                            WHERE baseline.classoid='pg_class'::regclass
                              AND baseline.objoid=object.oid
                              AND baseline.objsubid=0
                              AND baseline.grantee=acl.grantee
                              AND baseline.privilege_type=acl.privilege_type
                              AND baseline.is_grantable=acl.is_grantable))
                    UNION ALL
                    SELECT 'COLUMN',
                           format('%I.%I.%I', namespace.nspname, object.relname,
                                  attribute.attname),
                           acl.privilege_type, acl.is_grantable,
                           COALESCE(grantee.rolname, 'PUBLIC')
                      FROM pg_catalog.pg_attribute attribute
                      JOIN pg_catalog.pg_class object ON object.oid=attribute.attrelid
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=object.relnamespace
                      CROSS JOIN principal_role
                      CROSS JOIN LATERAL pg_catalog.aclexplode(attribute.attacl) acl
                      LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                     WHERE namespace.nspname='pg_catalog'
                       AND attribute.attnum>0 AND NOT attribute.attisdropped
                       AND acl.grantee IN (0, principal_role.oid)
                       AND (acl.grantee=principal_role.oid OR NOT EXISTS (
                           SELECT 1 FROM baseline_acl baseline
                            WHERE baseline.classoid='pg_class'::regclass
                              AND baseline.objoid=object.oid
                              AND baseline.objsubid=attribute.attnum
                              AND baseline.grantee=acl.grantee
                              AND baseline.privilege_type=acl.privilege_type
                              AND baseline.is_grantable=acl.is_grantable))
                    UNION ALL
                    SELECT 'ROUTINE',
                           format('%I.%I(%s)', namespace.nspname, routine.proname,
                                  pg_catalog.pg_get_function_identity_arguments(routine.oid)),
                           acl.privilege_type, acl.is_grantable,
                           COALESCE(grantee.rolname, 'PUBLIC')
                      FROM pg_catalog.pg_proc routine
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid=routine.pronamespace
                      CROSS JOIN principal_role
                      CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
                          routine.proacl,
                          pg_catalog.acldefault('f', routine.proowner))) acl
                      LEFT JOIN pg_catalog.pg_roles grantee ON grantee.oid=acl.grantee
                     WHERE namespace.nspname='pg_catalog'
                       AND acl.grantee IN (0, principal_role.oid)
                       AND (routine.oid>=16384 OR acl.grantee=principal_role.oid
                            OR NOT EXISTS (
                                SELECT 1 FROM baseline_acl baseline
                                 WHERE baseline.classoid='pg_proc'::regclass
                                   AND baseline.objoid=routine.oid
                                   AND baseline.objsubid=0
                                   AND baseline.grantee=acl.grantee
                                   AND baseline.privilege_type=acl.privilege_type
                                   AND baseline.is_grantable=acl.is_grantable))
                )
                SELECT object_class, identity, privilege_type, is_grantable, grantee
                  FROM violations
                 ORDER BY object_class, identity, privilege_type, grantee
                 LIMIT 1
                """)) {
            statement.setString(1, principal);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw failure(serviceName,
                            purpose + " exceeds PostgreSQL's initial system-catalog ACL: "
                                    + result.getString(1) + ":" + result.getString(2)
                                    + ":" + result.getString(3)
                                    + (result.getBoolean(4) ? ":GRANTABLE" : "")
                                    + " via " + result.getString(5));
                }
            }
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static IllegalStateException failure(String serviceName, String detail) {
        return new IllegalStateException(serviceName + " database boundary violation: " + detail);
    }
}
