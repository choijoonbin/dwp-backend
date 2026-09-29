package com.dwp.core.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Canonical definition/ACL fingerprints for owner-bearing protected-schema objects. */
final class ProtectedSchemaDefinitionFingerprint {

    private ProtectedSchemaDefinitionFingerprint() {
    }

    static Map<Key, String> read(Connection connection, List<String> schemas)
            throws SQLException {
        ProtectedSchemaTopologyPolicy.verify(connection, schemas);
        Map<Key, String> fingerprints = new HashMap<>();
        readQuery(connection, schemas, schemaSql(), fingerprints);
        readQuery(connection, schemas, relationSql(), fingerprints);
        readQuery(connection, schemas, routineSql(), fingerprints);
        readQuery(connection, schemas, typeSql(), fingerprints);
        readQuery(connection, schemas, auxiliarySql(), fingerprints);
        bindExtensionMembership(connection, fingerprints);
        return Map.copyOf(fingerprints);
    }

    /**
     * Extension ownership is lifecycle authority, not merely descriptive metadata.  Bind the
     * exact pg_depend edge to every protected object fingerprint so moving an unchanged object
     * between two extensions cannot preserve a previously issued Control seal.
     */
    private static void bindExtensionMembership(
            Connection connection, Map<Key, String> fingerprints) throws SQLException {
        String sql = """
                SELECT CASE dependency.classid
                         WHEN 'pg_namespace'::regclass THEN 'SCHEMA'
                         WHEN 'pg_class'::regclass THEN 'RELATION'
                         WHEN 'pg_proc'::regclass THEN 'ROUTINE'
                         WHEN 'pg_type'::regclass THEN 'TYPE'
                         WHEN 'pg_collation'::regclass THEN 'COLLATION'
                         WHEN 'pg_conversion'::regclass THEN 'CONVERSION'
                         WHEN 'pg_operator'::regclass THEN 'OPERATOR'
                         WHEN 'pg_opclass'::regclass THEN 'OPERATOR_CLASS'
                         WHEN 'pg_opfamily'::regclass THEN 'OPERATOR_FAMILY'
                         WHEN 'pg_statistic_ext'::regclass THEN 'STATISTICS'
                         WHEN 'pg_ts_config'::regclass THEN 'TEXT_SEARCH_CONFIGURATION'
                         WHEN 'pg_ts_dict'::regclass THEN 'TEXT_SEARCH_DICTIONARY'
                         WHEN 'pg_cast'::regclass THEN 'CAST'
                         WHEN 'pg_transform'::regclass THEN 'TRANSFORM'
                         ELSE NULL
                       END AS object_class,
                       dependency.objid::bigint,
                       concat_ws(E'\\x1f', dependency.classid::text,
                           dependency.objid::text, dependency.objsubid::text,
                           dependency.refclassid::text, dependency.refobjid::text,
                           dependency.refobjsubid::text, dependency.deptype::text,
                           extension.oid::text, extension.extname)
                  FROM pg_catalog.pg_depend dependency
                  JOIN pg_catalog.pg_extension extension
                    ON dependency.refclassid='pg_extension'::regclass
                   AND dependency.refobjid=extension.oid
                 WHERE dependency.deptype='e'
                 ORDER BY dependency.classid, dependency.objid,
                          dependency.objsubid, dependency.refobjid
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String objectClass = result.getString(1);
                if (objectClass == null) {
                    continue;
                }
                Key key = new Key(objectClass, result.getLong(2));
                String definition = fingerprints.get(key);
                if (definition != null) {
                    fingerprints.put(key, sha256(
                            definition + '\u001f' + result.getString(3)));
                }
            }
        }
    }

    static ExtensionMemberSeal unconsumedSeal(
            Map<Key, String> fingerprints, Set<Key> consumed) {
        List<Map.Entry<Key, String>> remainder = fingerprints.entrySet().stream()
                .filter(entry -> !consumed.contains(entry.getKey()))
                .sorted(java.util.Comparator
                        .comparing((Map.Entry<Key, String> entry) ->
                                entry.getKey().objectClass())
                        .thenComparingLong(entry -> entry.getKey().oid()))
                .toList();
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<Key, String> entry : remainder) {
            canonical.append(entry.getKey().objectClass()).append('\u001f')
                    .append(entry.getKey().oid()).append('\u001f')
                    .append(entry.getValue()).append('\u001e');
        }
        return new ExtensionMemberSeal(remainder.size(), sha256(canonical.toString()));
    }

    private static void readQuery(
            Connection connection,
            List<String> schemas,
            String sql,
            Map<Key, String> fingerprints) throws SQLException {
        Array schemaArray = connection.createArrayOf("text", schemas.toArray());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, schemaArray);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Key key = new Key(result.getString(1), result.getLong(2));
                    String previous = fingerprints.put(key, sha256(result.getString(3)));
                    if (previous != null) {
                        throw new SQLException(
                                "duplicate protected-object definition fingerprint " + key);
                    }
                }
            }
        } finally {
            schemaArray.free();
        }
    }

    private static String schemaSql() {
        return """
                SELECT 'SCHEMA', namespace.oid::bigint,
                       concat_ws(E'\\x1f',
                           namespace.nspname, namespace.nspowner::text,
                           COALESCE(namespace.nspacl::text, ''),
                           COALESCE((
                               SELECT string_agg(
                                   concat_ws(E'\\x1e', default_acl.oid::text,
                                       default_acl.defaclrole::text,
                                       default_acl.defaclobjtype::text,
                                       COALESCE(default_acl.defaclacl::text, '')),
                                   E'\\x1d' ORDER BY default_acl.oid)
                                 FROM pg_catalog.pg_default_acl default_acl
                                WHERE default_acl.defaclnamespace = namespace.oid
                                   OR (default_acl.defaclnamespace = 0
                                       AND default_acl.defaclrole = namespace.nspowner)
                           ), ''),
                           COALESCE((
                               SELECT string_agg(
                                   concat_ws(E'\\x1e', extension.oid::text,
                                       extension.extname, extension.extversion,
                                       extension.extrelocatable::text,
                                       extension.extowner::text,
                                       COALESCE(extension.extconfig::text, ''),
                                       COALESCE(extension.extcondition::text, '')),
                                   E'\\x1d' ORDER BY extension.extname)
                                 FROM pg_catalog.pg_extension extension
                                WHERE extension.extnamespace = namespace.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(
                                   concat_ws(E'\\x1e', publication.oid::text,
                                       publication.pubname, publication.pubowner::text,
                                       publication.puballtables::text,
                                       publication.pubinsert::text,
                                       publication.pubupdate::text,
                                       publication.pubdelete::text,
                                       publication.pubtruncate::text,
                                       publication.pubviaroot::text),
                                   E'\\x1d' ORDER BY publication.oid)
                                 FROM pg_catalog.pg_publication publication
                                WHERE publication.puballtables
                                   OR EXISTS (
                                       SELECT 1
                                         FROM pg_catalog.pg_publication_namespace membership
                                        WHERE membership.pnpubid=publication.oid
                                          AND membership.pnnspid=namespace.oid)
                           ), ''))
                  FROM pg_catalog.pg_namespace namespace
                 WHERE namespace.nspname = ANY (?::text[])
                   AND namespace.nspname <> 'dwp_migration_control'
                """;
    }

    private static String relationSql() {
        return """
                SELECT 'RELATION', object.oid::bigint,
                       concat_ws(E'\\x1f',
                           namespace.nspname, object.relname, object.relowner::text,
                           object.relkind::text, object.relpersistence::text,
                           object.relam::text, object.reltablespace::text,
                           object.relreplident::text, object.relrowsecurity::text,
                           object.relforcerowsecurity::text, object.relispartition::text,
                           object.relhassubclass::text,
                           COALESCE(object.reloptions::text, ''),
                           COALESCE(object.relacl::text, ''),
                           CASE WHEN object.relkind IN ('v', 'm')
                                THEN pg_catalog.pg_get_viewdef(object.oid, false) ELSE '' END,
                           COALESCE(pg_catalog.pg_get_expr(
                               object.relpartbound, object.oid, false), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          inheritance.inhseqno::text,
                                          inheritance.inhparent::text,
                                          parent_namespace.nspname,
                                          parent.relname),
                                      E'\\x1d'
                                      ORDER BY inheritance.inhseqno,
                                               inheritance.inhparent)
                                 FROM pg_catalog.pg_inherits inheritance
                                 JOIN pg_catalog.pg_class parent
                                   ON parent.oid = inheritance.inhparent
                                 JOIN pg_catalog.pg_namespace parent_namespace
                                   ON parent_namespace.oid = parent.relnamespace
                                WHERE inheritance.inhrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          inheritance.inhseqno::text,
                                          inheritance.inhrelid::text,
                                          child_namespace.nspname,
                                          child.relname,
                                          child.relkind::text,
                                          child.relispartition::text,
                                          COALESCE(pg_catalog.pg_get_expr(
                                              child.relpartbound, child.oid, false), '')),
                                      E'\\x1d'
                                      ORDER BY inheritance.inhseqno,
                                               inheritance.inhrelid)
                                 FROM pg_catalog.pg_inherits inheritance
                                 JOIN pg_catalog.pg_class child
                                   ON child.oid = inheritance.inhrelid
                                 JOIN pg_catalog.pg_namespace child_namespace
                                   ON child_namespace.oid = child.relnamespace
                                WHERE inheritance.inhparent = object.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          attribute.attnum::text, attribute.attname,
                                          attribute.atttypid::text, attribute.atttypmod::text,
                                          attribute.attnotnull::text,
                                          attribute.attisdropped::text,
                                          attribute.attidentity::text,
                                          attribute.attgenerated::text,
                                          attribute.attcollation::text,
                                          attribute.attstorage::text,
                                          attribute.attcompression::text,
                                          attribute.attstattarget::text,
                                          attribute.attinhcount::text,
                                          attribute.attislocal::text,
                                          COALESCE(attribute.attacl::text, ''),
                                          COALESCE(attribute.attoptions::text, ''),
                                          COALESCE(attribute.attfdwoptions::text, ''),
                                          COALESCE(pg_catalog.pg_get_expr(
                                              default_value.adbin,
                                              default_value.adrelid, false), '')),
                                      E'\\x1d' ORDER BY attribute.attnum)
                                 FROM pg_catalog.pg_attribute attribute
                                 LEFT JOIN pg_catalog.pg_attrdef default_value
                                   ON default_value.adrelid = attribute.attrelid
                                  AND default_value.adnum = attribute.attnum
                                WHERE attribute.attrelid = object.oid
                                  AND attribute.attnum > 0
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          constraint_object.oid::text,
                                          constraint_object.conname,
                                          constraint_object.contype::text,
                                          constraint_object.condeferrable::text,
                                          constraint_object.condeferred::text,
                                          constraint_object.convalidated::text,
                                          constraint_object.connoinherit::text,
                                          constraint_object.conparentid::text,
                                          pg_catalog.pg_get_constraintdef(
                                              constraint_object.oid, false)),
                                      E'\\x1d' ORDER BY constraint_object.oid)
                                 FROM pg_catalog.pg_constraint constraint_object
                                WHERE constraint_object.conrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          policy.oid::text, policy.polname,
                                          policy.polcmd::text,
                                          policy.polpermissive::text,
                                          policy.polroles::text,
                                          COALESCE(pg_catalog.pg_get_expr(
                                              policy.polqual, policy.polrelid, false), ''),
                                          COALESCE(pg_catalog.pg_get_expr(
                                              policy.polwithcheck,
                                              policy.polrelid, false), '')),
                                      E'\\x1d' ORDER BY policy.oid)
                                 FROM pg_catalog.pg_policy policy
                                WHERE policy.polrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          trigger_object.oid::text,
                                          trigger_object.tgname,
                                          trigger_object.tgenabled::text,
                                          trigger_object.tgtype::text,
                                          trigger_object.tgfoid::text,
                                          trigger_object.tgargs::text,
                                          trigger_object.tgisinternal::text,
                                          trigger_object.tgconstraint::text,
                                          trigger_object.tgparentid::text,
                                          pg_catalog.pg_get_triggerdef(
                                              trigger_object.oid, false)),
                                      E'\\x1d' ORDER BY trigger_object.oid)
                                 FROM pg_catalog.pg_trigger trigger_object
                                WHERE trigger_object.tgrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          rule_object.oid::text, rule_object.rulename,
                                          rule_object.ev_type::text,
                                          rule_object.is_instead::text,
                                          rule_object.ev_enabled::text,
                                          pg_catalog.pg_get_ruledef(
                                              rule_object.oid, false)),
                                      E'\\x1d' ORDER BY rule_object.oid)
                                 FROM pg_catalog.pg_rewrite rule_object
                                WHERE rule_object.ev_class = object.oid
                           ), ''),
                           COALESCE((
                               SELECT concat_ws(E'\\x1e',
                                          index_object.indrelid::text,
                                          index_object.indisunique::text,
                                          index_object.indnullsnotdistinct::text,
                                          index_object.indisprimary::text,
                                          index_object.indisexclusion::text,
                                          index_object.indimmediate::text,
                                          index_object.indisclustered::text,
                                          index_object.indisvalid::text,
                                          index_object.indisready::text,
                                          index_object.indislive::text,
                                          index_object.indisreplident::text,
                                          index_object.indkey::text,
                                          index_object.indcollation::text,
                                          index_object.indclass::text,
                                          index_object.indoption::text,
                                          pg_catalog.pg_get_indexdef(object.oid))
                                 FROM pg_catalog.pg_index index_object
                                WHERE index_object.indexrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT concat_ws(E'\\x1e', sequence_object.seqtypid::text,
                                          sequence_object.seqstart::text,
                                          sequence_object.seqincrement::text,
                                          sequence_object.seqmax::text,
                                          sequence_object.seqmin::text,
                                          sequence_object.seqcache::text,
                                          sequence_object.seqcycle::text,
                                          COALESCE((
                                              SELECT string_agg(concat_ws(E'\\x1c',
                                                         dependency.classid::text,
                                                         dependency.objid::text,
                                                         dependency.objsubid::text,
                                                         dependency.refclassid::text,
                                                         dependency.refobjid::text,
                                                         dependency.refobjsubid::text,
                                                         dependency.deptype::text),
                                                     E'\\x1b'
                                                     ORDER BY dependency.refobjid,
                                                              dependency.refobjsubid,
                                                              dependency.deptype)
                                                FROM pg_catalog.pg_depend dependency
                                               WHERE dependency.classid='pg_class'::regclass
                                                 AND dependency.objid=object.oid
                                                 AND dependency.refclassid='pg_class'::regclass
                                                 AND dependency.deptype IN ('a', 'i')
                                          ), ''))
                                 FROM pg_catalog.pg_sequence sequence_object
                                WHERE sequence_object.seqrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT concat_ws(E'\\x1e', server.srvname,
                                          foreign_table.ftoptions::text)
                                 FROM pg_catalog.pg_foreign_table foreign_table
                                 JOIN pg_catalog.pg_foreign_server server
                                   ON server.oid = foreign_table.ftserver
                                WHERE foreign_table.ftrelid = object.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          membership.oid::text,
                                          membership.prpubid::text,
                                          publication.pubname,
                                          publication.pubowner::text,
                                          publication.puballtables::text,
                                          publication.pubinsert::text,
                                          publication.pubupdate::text,
                                          publication.pubdelete::text,
                                          publication.pubtruncate::text,
                                          publication.pubviaroot::text,
                                          COALESCE(membership.prattrs::text, ''),
                                          COALESCE(pg_catalog.pg_get_expr(
                                              membership.prqual,
                                              membership.prrelid, false), '')),
                                      E'\\x1d' ORDER BY membership.oid)
                                 FROM pg_catalog.pg_publication_rel membership
                                 JOIN pg_catalog.pg_publication publication
                                   ON publication.oid=membership.prpubid
                                WHERE membership.prrelid=object.oid
                           ), ''))
                  FROM pg_catalog.pg_class object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = object.relnamespace
                 WHERE namespace.nspname = ANY (?::text[])
                   AND namespace.nspname <> 'dwp_migration_control'
                """;
    }

    private static String routineSql() {
        return """
                SELECT 'ROUTINE', routine.oid::bigint,
                       concat_ws(E'\\x1f',
                           namespace.nspname, routine.proname, routine.proowner::text,
                           routine.prokind::text, routine.prolang::text,
                           routine.procost::text, routine.prorows::text,
                           routine.provariadic::text, routine.prosupport::text,
                           routine.prosecdef::text, routine.proleakproof::text,
                           routine.proisstrict::text, routine.proretset::text,
                           routine.provolatile::text, routine.proparallel::text,
                           routine.pronargs::text, routine.pronargdefaults::text,
                           routine.prorettype::text, routine.proargtypes::text,
                           COALESCE(routine.proallargtypes::text, ''),
                           COALESCE(routine.proargmodes::text, ''),
                           COALESCE(routine.proargnames::text, ''),
                           COALESCE(routine.protrftypes::text, ''),
                           COALESCE(routine.prosrc, ''), COALESCE(routine.probin, ''),
                           COALESCE(routine.proconfig::text, ''),
                           COALESCE(routine.proacl::text, ''),
                           CASE WHEN routine.prokind IN ('f', 'p')
                                THEN pg_catalog.pg_get_functiondef(routine.oid) ELSE '' END)
                  FROM pg_catalog.pg_proc routine
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = routine.pronamespace
                 WHERE namespace.nspname = ANY (?::text[])
                   AND namespace.nspname <> 'dwp_migration_control'
                """;
    }

    private static String typeSql() {
        return """
                SELECT 'TYPE', data_type.oid::bigint,
                       concat_ws(E'\\x1f',
                           namespace.nspname, data_type.typname,
                           data_type.typowner::text,
                           data_type.typtype::text, data_type.typcategory::text,
                           data_type.typispreferred::text, data_type.typisdefined::text,
                           data_type.typdelim::text, data_type.typrelid::text,
                           data_type.typsubscript::text, data_type.typelem::text,
                           data_type.typarray::text, data_type.typlen::text,
                           data_type.typbyval::text, data_type.typalign::text,
                           data_type.typstorage::text, data_type.typnotnull::text,
                           data_type.typbasetype::text, data_type.typtypmod::text,
                           data_type.typndims::text, data_type.typcollation::text,
                           COALESCE(data_type.typdefault, ''),
                           COALESCE(data_type.typacl::text, ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          enum_value.oid::text,
                                          enum_value.enumsortorder::text,
                                          enum_value.enumlabel),
                                      E'\\x1d' ORDER BY enum_value.enumsortorder)
                                 FROM pg_catalog.pg_enum enum_value
                                WHERE enum_value.enumtypid = data_type.oid
                           ), ''),
                           COALESCE((
                               SELECT concat_ws(E'\\x1e', range_object.rngsubtype::text,
                                          range_object.rngmultitypid::text,
                                          range_object.rngcollation::text,
                                          range_object.rngsubopc::text,
                                          range_object.rngcanonical::text,
                                          range_object.rngsubdiff::text)
                                 FROM pg_catalog.pg_range range_object
                                WHERE range_object.rngtypid = data_type.oid
                           ), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          constraint_object.oid::text,
                                          constraint_object.conname,
                                          constraint_object.convalidated::text,
                                          pg_catalog.pg_get_constraintdef(
                                              constraint_object.oid, false)),
                                      E'\\x1d' ORDER BY constraint_object.oid)
                                 FROM pg_catalog.pg_constraint constraint_object
                                WHERE constraint_object.contypid = data_type.oid
                           ), ''))
                  FROM pg_catalog.pg_type data_type
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid = data_type.typnamespace
                 WHERE namespace.nspname = ANY (?::text[])
                   AND namespace.nspname <> 'dwp_migration_control'
                   AND data_type.typtype <> 'p'
                   AND data_type.typrelid = 0
                   AND data_type.typelem = 0
                """;
    }

    private static String auxiliarySql() {
        return """
                WITH selected_schema(nspname) AS (SELECT unnest(?::text[]))
                SELECT 'COLLATION', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.collname,
                           object.collowner::text, object.collprovider::text,
                           object.collisdeterministic::text, object.collencoding::text,
                           object.collcollate, object.collctype,
                           COALESCE(to_jsonb(object)->>'colllocale',
                                    to_jsonb(object)->>'colliculocale', ''),
                           COALESCE(object.collversion, ''))
                  FROM pg_catalog.pg_collation object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.collnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'CONVERSION', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.conname,
                           object.conowner::text, object.conforencoding::text,
                           object.contoencoding::text, object.conproc::text,
                           object.condefault::text)
                  FROM pg_catalog.pg_conversion object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.connamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'OPERATOR', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.oprname,
                           object.oprowner::text, object.oprkind::text,
                           object.oprcanmerge::text, object.oprcanhash::text,
                           object.oprleft::text, object.oprright::text,
                           object.oprresult::text, object.oprcom::text,
                           object.oprnegate::text, object.oprcode::text,
                           object.oprrest::text, object.oprjoin::text)
                  FROM pg_catalog.pg_operator object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.oprnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'OPERATOR_CLASS', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.opcname,
                           object.opcowner::text, object.opcmethod::text,
                           object.opcfamily::text, object.opcintype::text,
                           object.opcdefault::text, object.opckeytype::text)
                  FROM pg_catalog.pg_opclass object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.opcnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'OPERATOR_FAMILY', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.opfname,
                           object.opfowner::text, object.opfmethod::text,
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          member.amopstrategy::text,
                                          member.amoppurpose::text,
                                          member.amoplefttype::text,
                                          member.amoprighttype::text,
                                          member.amopsortfamily::text,
                                          member.amopopr::text),
                                      E'\\x1d' ORDER BY member.amopstrategy,
                                          member.amoppurpose, member.amoplefttype,
                                          member.amoprighttype, member.amopopr)
                                 FROM pg_catalog.pg_amop member
                                WHERE member.amopfamily=object.oid), ''),
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          member.amprocnum::text,
                                          member.amproclefttype::text,
                                          member.amprocrighttype::text,
                                          member.amproc::text),
                                      E'\\x1d' ORDER BY member.amprocnum,
                                          member.amproclefttype,
                                          member.amprocrighttype, member.amproc)
                                 FROM pg_catalog.pg_amproc member
                                WHERE member.amprocfamily=object.oid), ''))
                  FROM pg_catalog.pg_opfamily object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.opfnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'STATISTICS', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.stxname,
                           object.stxowner::text, object.stxrelid::text,
                           object.stxkeys::text, object.stxkind::text,
                           COALESCE(object.stxexprs::text, ''))
                  FROM pg_catalog.pg_statistic_ext object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.stxnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'TEXT_SEARCH_CONFIGURATION', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.cfgname,
                           object.cfgowner::text, object.cfgparser::text,
                           COALESCE((
                               SELECT string_agg(concat_ws(E'\\x1e',
                                          mapping.maptokentype::text,
                                          mapping.mapseqno::text,
                                          mapping.mapdict::text),
                                      E'\\x1d' ORDER BY mapping.maptokentype,
                                          mapping.mapseqno, mapping.mapdict)
                                 FROM pg_catalog.pg_ts_config_map mapping
                                WHERE mapping.mapcfg=object.oid), ''))
                  FROM pg_catalog.pg_ts_config object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.cfgnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'TEXT_SEARCH_DICTIONARY', object.oid::bigint,
                       concat_ws(E'\\x1f', namespace.nspname, object.dictname,
                           object.dictowner::text, object.dicttemplate::text,
                           COALESCE(object.dictinitoption, ''))
                  FROM pg_catalog.pg_ts_dict object
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=object.dictnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'CAST', object.oid::bigint,
                       concat_ws(E'\\x1f', object.castsource::text,
                           object.casttarget::text, object.castfunc::text,
                           object.castcontext::text, object.castmethod::text)
                  FROM pg_catalog.pg_cast object
                  JOIN pg_catalog.pg_type source_type
                    ON source_type.oid=object.castsource
                  JOIN pg_catalog.pg_namespace source_namespace
                    ON source_namespace.oid=source_type.typnamespace
                  JOIN pg_catalog.pg_type target_type
                    ON target_type.oid=object.casttarget
                  JOIN pg_catalog.pg_namespace target_namespace
                    ON target_namespace.oid=target_type.typnamespace
                 WHERE source_namespace.nspname IN (SELECT nspname FROM selected_schema)
                    OR target_namespace.nspname IN (SELECT nspname FROM selected_schema)
                UNION ALL
                SELECT 'TRANSFORM', object.oid::bigint,
                       concat_ws(E'\\x1f', object.trftype::text,
                           object.trflang::text, object.trffromsql::text,
                           object.trftosql::text)
                  FROM pg_catalog.pg_transform object
                  JOIN pg_catalog.pg_type data_type ON data_type.oid=object.trftype
                  JOIN pg_catalog.pg_namespace namespace
                    ON namespace.oid=data_type.typnamespace
                 WHERE namespace.nspname IN (SELECT nspname FROM selected_schema)
                """;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record Key(String objectClass, long oid) {
    }

    record ExtensionMemberSeal(int count, String sha256) {
    }
}
