package com.dwp.core.database;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exhaustive policy for schema-scoped PostgreSQL objects that can carry owner authority. */
final class ProtectedSchemaObjectInventory {

    private static final int SCHEMA_BINDINGS = 19;

    private ProtectedSchemaObjectInventory() {
    }

    static List<Entry> read(Connection connection, List<String> schemas) throws SQLException {
        String sql = """
                SELECT object_class, object_oid, object_schema, object_identity, owner
                  FROM (
                    SELECT 'SCHEMA'::text, namespace.oid::bigint, namespace.nspname,
                           namespace.nspname, owner.rolname
                      FROM pg_catalog.pg_namespace namespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = namespace.nspowner
                     WHERE namespace.nspname = ANY (?::text[])
                    UNION ALL
                    SELECT 'RELATION', object.oid::bigint, namespace.nspname,
                           format('%s:%I.%I', object.relkind, namespace.nspname, object.relname),
                           owner.rolname
                      FROM pg_catalog.pg_class object
                      JOIN pg_catalog.pg_namespace namespace ON namespace.oid = object.relnamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = object.relowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member('pg_class'::regclass, object.oid)
                    UNION ALL
                    SELECT 'ROUTINE', routine.oid::bigint, namespace.nspname,
                           format('%I.%I(%s)', namespace.nspname, routine.proname,
                                  pg_get_function_identity_arguments(routine.oid)), owner.rolname
                      FROM pg_catalog.pg_proc routine
                      JOIN pg_catalog.pg_namespace namespace ON namespace.oid = routine.pronamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = routine.proowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member('pg_proc'::regclass, routine.oid)
                    UNION ALL
                    SELECT 'TYPE', data_type.oid::bigint, namespace.nspname,
                           format('%s:%I.%I', data_type.typtype,
                                  namespace.nspname, data_type.typname), owner.rolname
                      FROM pg_catalog.pg_type data_type
                      JOIN pg_catalog.pg_namespace namespace ON namespace.oid = data_type.typnamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = data_type.typowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND data_type.typtype <> 'p'
                       AND data_type.typrelid = 0
                       AND data_type.typelem = 0
                       AND NOT extension_member('pg_type'::regclass, data_type.oid)
                    UNION ALL
                    SELECT 'COLLATION', collation_object.oid::bigint, namespace.nspname,
                           format('%I.%I', namespace.nspname, collation_object.collname),
                           owner.rolname
                      FROM pg_catalog.pg_collation collation_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = collation_object.collnamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = collation_object.collowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_collation'::regclass, collation_object.oid)
                    UNION ALL
                    SELECT 'CONVERSION', conversion_object.oid::bigint, namespace.nspname,
                           format('%I.%I', namespace.nspname, conversion_object.conname),
                           owner.rolname
                      FROM pg_catalog.pg_conversion conversion_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = conversion_object.connamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = conversion_object.conowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_conversion'::regclass, conversion_object.oid)
                    UNION ALL
                    SELECT 'OPERATOR', operator_object.oid::bigint, namespace.nspname,
                           format('%I.%I(%s,%s)', namespace.nspname, operator_object.oprname,
                                  operator_object.oprleft::regtype,
                                  operator_object.oprright::regtype), owner.rolname
                      FROM pg_catalog.pg_operator operator_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = operator_object.oprnamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = operator_object.oprowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_operator'::regclass, operator_object.oid)
                    UNION ALL
                    SELECT 'OPERATOR_CLASS', operator_class.oid::bigint, namespace.nspname,
                           format('%I.%I:%s', namespace.nspname, operator_class.opcname,
                                  access_method.amname), owner.rolname
                      FROM pg_catalog.pg_opclass operator_class
                      JOIN pg_catalog.pg_namespace namespace ON namespace.oid = operator_class.opcnamespace
                      JOIN pg_catalog.pg_am access_method ON access_method.oid = operator_class.opcmethod
                      JOIN pg_catalog.pg_roles owner ON owner.oid = operator_class.opcowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member('pg_opclass'::regclass, operator_class.oid)
                    UNION ALL
                    SELECT 'OPERATOR_FAMILY', operator_family.oid::bigint, namespace.nspname,
                           format('%I.%I:%s', namespace.nspname, operator_family.opfname,
                                  access_method.amname), owner.rolname
                      FROM pg_catalog.pg_opfamily operator_family
                      JOIN pg_catalog.pg_namespace namespace ON namespace.oid = operator_family.opfnamespace
                      JOIN pg_catalog.pg_am access_method ON access_method.oid = operator_family.opfmethod
                      JOIN pg_catalog.pg_roles owner ON owner.oid = operator_family.opfowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member('pg_opfamily'::regclass, operator_family.oid)
                    UNION ALL
                    SELECT 'STATISTICS', statistics_object.oid::bigint, namespace.nspname,
                           format('%I.%I', namespace.nspname, statistics_object.stxname),
                           owner.rolname
                      FROM pg_catalog.pg_statistic_ext statistics_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = statistics_object.stxnamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = statistics_object.stxowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_statistic_ext'::regclass, statistics_object.oid)
                    UNION ALL
                    SELECT 'TEXT_SEARCH_CONFIGURATION', configuration_object.oid::bigint,
                           namespace.nspname,
                           format('%I.%I', namespace.nspname,
                                  configuration_object.cfgname), owner.rolname
                      FROM pg_catalog.pg_ts_config configuration_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = configuration_object.cfgnamespace
                      JOIN pg_catalog.pg_roles owner
                        ON owner.oid = configuration_object.cfgowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_ts_config'::regclass, configuration_object.oid)
                    UNION ALL
                    SELECT 'TEXT_SEARCH_DICTIONARY', dictionary_object.oid::bigint,
                           namespace.nspname,
                           format('%I.%I', namespace.nspname,
                                  dictionary_object.dictname), owner.rolname
                      FROM pg_catalog.pg_ts_dict dictionary_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = dictionary_object.dictnamespace
                      JOIN pg_catalog.pg_roles owner
                        ON owner.oid = dictionary_object.dictowner
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_ts_dict'::regclass, dictionary_object.oid)
                    UNION ALL
                    SELECT 'CAST', cast_object.oid::bigint,
                           CASE WHEN source_namespace.nspname = ANY (?::text[])
                                THEN source_namespace.nspname
                                ELSE target_namespace.nspname END,
                           format('%s->%s:%s:%s:%s',
                                  cast_object.castsource::regtype,
                                  cast_object.casttarget::regtype,
                                  cast_object.castmethod,
                                  cast_object.castcontext,
                                  cast_object.castfunc::regprocedure),
                           CASE WHEN source_namespace.nspname = ANY (?::text[])
                                THEN source_owner.rolname ELSE target_owner.rolname END
                      FROM pg_catalog.pg_cast cast_object
                      JOIN pg_catalog.pg_type source_type
                        ON source_type.oid = cast_object.castsource
                      JOIN pg_catalog.pg_namespace source_namespace
                        ON source_namespace.oid = source_type.typnamespace
                      JOIN pg_catalog.pg_roles source_owner
                        ON source_owner.oid = source_type.typowner
                      JOIN pg_catalog.pg_type target_type
                        ON target_type.oid = cast_object.casttarget
                      JOIN pg_catalog.pg_namespace target_namespace
                        ON target_namespace.oid = target_type.typnamespace
                      JOIN pg_catalog.pg_roles target_owner
                        ON target_owner.oid = target_type.typowner
                     WHERE (source_namespace.nspname = ANY (?::text[])
                            OR target_namespace.nspname = ANY (?::text[]))
                       AND NOT extension_member('pg_cast'::regclass, cast_object.oid)
                    UNION ALL
                    SELECT 'TRANSFORM', transform_object.oid::bigint, namespace.nspname,
                           format('%s:%I:%s:%s', transform_object.trftype::regtype,
                                  language.lanname,
                                  transform_object.trffromsql::regprocedure,
                                  transform_object.trftosql::regprocedure), owner.rolname
                      FROM pg_catalog.pg_transform transform_object
                      JOIN pg_catalog.pg_type data_type
                        ON data_type.oid = transform_object.trftype
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = data_type.typnamespace
                      JOIN pg_catalog.pg_roles owner ON owner.oid = data_type.typowner
                      JOIN pg_catalog.pg_language language
                        ON language.oid = transform_object.trflang
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_transform'::regclass, transform_object.oid)
                    UNION ALL
                    SELECT 'UNSUPPORTED_TEXT_SEARCH_PARSER', parser_object.oid::bigint,
                           namespace.nspname,
                           format('%I.%I', namespace.nspname,
                                  parser_object.prsname), '<unsupported>'
                      FROM pg_catalog.pg_ts_parser parser_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = parser_object.prsnamespace
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_ts_parser'::regclass, parser_object.oid)
                    UNION ALL
                    SELECT 'UNSUPPORTED_TEXT_SEARCH_TEMPLATE', template_object.oid::bigint,
                           namespace.nspname,
                           format('%I.%I', namespace.nspname,
                                  template_object.tmplname), '<unsupported>'
                      FROM pg_catalog.pg_ts_template template_object
                      JOIN pg_catalog.pg_namespace namespace
                        ON namespace.oid = template_object.tmplnamespace
                     WHERE namespace.nspname = ANY (?::text[])
                       AND NOT extension_member(
                               'pg_ts_template'::regclass, template_object.oid)
                  ) inventory(object_class, object_oid, object_schema, object_identity, owner)
                 ORDER BY object_class, object_schema, object_identity, object_oid
                """.replace(
                        "extension_member(",
                        "EXISTS (SELECT 1 FROM pg_catalog.pg_depend dependency WHERE "
                                + "dependency.classid = ");
        // Complete the compact extension-member macro expansion deterministically.
        sql = sql.replace(", object.oid)",
                        " AND dependency.objid = object.oid AND dependency.deptype = 'e')")
                .replace(", routine.oid)",
                        " AND dependency.objid = routine.oid AND dependency.deptype = 'e')")
                .replace(", data_type.oid)",
                        " AND dependency.objid = data_type.oid AND dependency.deptype = 'e')")
                .replace(", collation_object.oid)",
                        " AND dependency.objid = collation_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", conversion_object.oid)",
                        " AND dependency.objid = conversion_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", operator_object.oid)",
                        " AND dependency.objid = operator_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", operator_class.oid)",
                        " AND dependency.objid = operator_class.oid AND dependency.deptype = 'e')")
                .replace(", operator_family.oid)",
                        " AND dependency.objid = operator_family.oid AND dependency.deptype = 'e')")
                .replace(", statistics_object.oid)",
                        " AND dependency.objid = statistics_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", configuration_object.oid)",
                        " AND dependency.objid = configuration_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", dictionary_object.oid)",
                        " AND dependency.objid = dictionary_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", cast_object.oid)",
                        " AND dependency.objid = cast_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", transform_object.oid)",
                        " AND dependency.objid = transform_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", parser_object.oid)",
                        " AND dependency.objid = parser_object.oid"
                                + " AND dependency.deptype = 'e')")
                .replace(", template_object.oid)",
                        " AND dependency.objid = template_object.oid"
                                + " AND dependency.deptype = 'e')");
        Array schemaArray = connection.createArrayOf("text", schemas.toArray());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int parameter = 1; parameter <= SCHEMA_BINDINGS; parameter++) {
                statement.setArray(parameter, schemaArray);
            }
            Map<ProtectedSchemaDefinitionFingerprint.Key, String> definitions =
                    ProtectedSchemaDefinitionFingerprint.read(connection, schemas);
            Set<ProtectedSchemaDefinitionFingerprint.Key> consumedDefinitions =
                    new LinkedHashSet<>();
            try (ResultSet result = statement.executeQuery()) {
                java.util.ArrayList<Entry> entries = new java.util.ArrayList<>();
                while (result.next()) {
                    String objectClass = result.getString(1);
                    long objectOid = result.getLong(2);
                    String identity = result.getString(4);
                    ProtectedSchemaDefinitionFingerprint.Key definitionKey =
                            new ProtectedSchemaDefinitionFingerprint.Key(
                                    objectClass, objectOid);
                    String definition = definitions.get(definitionKey);
                    if (definition != null) {
                        consumedDefinitions.add(definitionKey);
                        identity += "|definition-sha256=" + definition;
                    }
                    entries.add(new Entry(
                            objectClass, objectOid, result.getString(3),
                            identity, result.getString(5)));
                }
                ProtectedSchemaDefinitionFingerprint.ExtensionMemberSeal extensionSeal =
                        ProtectedSchemaDefinitionFingerprint.unconsumedSeal(
                                definitions, consumedDefinitions);
                entries.replaceAll(entry -> "SCHEMA".equals(entry.objectClass())
                        ? new Entry(
                                entry.objectClass(), entry.oid(), entry.schema(),
                                entry.identity() + "|extension-member-count="
                                        + extensionSeal.count()
                                        + "|extension-member-sha256="
                                        + extensionSeal.sha256(),
                                entry.owner())
                        : entry);
                return List.copyOf(entries);
            }
        } finally {
            schemaArray.free();
        }
    }

    static Ownership inspect(
            Connection connection,
            List<String> schemas,
            String principal) throws SQLException {
        return inspect(connection, schemas, Set.of(principal));
    }

    static Ownership inspect(
            Connection connection,
            List<String> schemas,
            Set<String> approvedOwners) throws SQLException {
        List<Entry> entries = read(connection, schemas);
        long owned = entries.stream()
                .filter(entry -> approvedOwners.contains(entry.owner()))
                .count();
        long unowned = entries.size() - owned;
        long missingSchemas = schemas.stream()
                .filter(schema -> entries.stream().noneMatch(entry ->
                        "SCHEMA".equals(entry.objectClass()) && schema.equals(entry.schema())))
                .count();
        return new Ownership(owned, unowned + missingSchemas);
    }

    static boolean hasMembershipInAnotherOwner(
            Connection connection,
            List<String> schemas) throws SQLException {
        Set<String> owners = new LinkedHashSet<>();
        for (Entry entry : read(connection, schemas)) {
            owners.add(entry.owner());
        }
        owners.remove(currentUser(connection));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_has_role(current_user, ?, 'MEMBER')")) {
            for (String owner : owners) {
                statement.setString(1, owner);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String currentUser(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT current_user");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("current_user returned no row");
            }
            return result.getString(1);
        }
    }

    record Entry(
            String objectClass,
            long oid,
            String schema,
            String identity,
            String owner) {
    }

    record Ownership(long owned, long unowned) {
    }
}
