package com.dwp.services.provider.governance;

import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

@Component
public class DataGovernanceScanner {

    private static final String OBJECTS_SQL = """
            SELECT namespace.nspname AS schema_name,
                   object.relname AS object_name,
                   CASE
                     WHEN object.relkind = 'p' THEN 'PARTITIONED_TABLE'
                     WHEN object.relkind = 'r' AND parent.oid IS NOT NULL THEN 'PARTITION'
                     WHEN object.relkind = 'r' THEN 'TABLE'
                     WHEN object.relkind = 'v' THEN 'VIEW'
                     WHEN object.relkind = 'm' THEN 'MATERIALIZED_VIEW'
                     ELSE 'OTHER'
                   END AS object_type,
                   parent.relname AS parent_object_name,
                   NULLIF(obj_description(object.oid, 'pg_class'), '') AS description,
                   GREATEST(COALESCE(statistics.n_live_tup, object.reltuples, 0), 0)::bigint AS estimated_rows,
                   CASE WHEN object.relkind IN ('r', 'p', 'm')
                        THEN pg_total_relation_size(object.oid) ELSE 0 END AS total_bytes
              FROM pg_class object
              JOIN pg_namespace namespace ON namespace.oid = object.relnamespace
              LEFT JOIN pg_stat_user_tables statistics ON statistics.relid = object.oid
              LEFT JOIN pg_inherits inheritance ON inheritance.inhrelid = object.oid
              LEFT JOIN pg_class parent ON parent.oid = inheritance.inhparent
             WHERE object.relkind IN ('r', 'p', 'v', 'm')
               AND namespace.nspname NOT LIKE 'pg_%'
               AND namespace.nspname <> 'information_schema'
             ORDER BY namespace.nspname, object.relname
            """;

    private static final String COLUMNS_SQL = """
            SELECT namespace.nspname AS schema_name,
                   object.relname AS object_name,
                   attribute.attnum AS ordinal_position,
                   attribute.attname AS column_name,
                   format_type(attribute.atttypid, attribute.atttypmod) AS data_type,
                   NOT attribute.attnotnull AS nullable,
                   pg_get_expr(default_value.adbin, default_value.adrelid) AS default_value,
                   NULLIF(col_description(object.oid, attribute.attnum), '') AS description
              FROM pg_attribute attribute
              JOIN pg_class object ON object.oid = attribute.attrelid
              JOIN pg_namespace namespace ON namespace.oid = object.relnamespace
              LEFT JOIN pg_attrdef default_value
                ON default_value.adrelid = attribute.attrelid
               AND default_value.adnum = attribute.attnum
             WHERE attribute.attnum > 0
               AND NOT attribute.attisdropped
               AND object.relkind IN ('r', 'p', 'v', 'm')
               AND namespace.nspname NOT LIKE 'pg_%'
               AND namespace.nspname <> 'information_schema'
             ORDER BY namespace.nspname, object.relname, attribute.attnum
            """;

    private static final String CONSTRAINTS_SQL = """
            SELECT constraint_record.conname AS constraint_name,
                   constraint_record.contype AS constraint_type,
                   source_namespace.nspname AS source_schema,
                   source.relname AS source_object,
                   target_namespace.nspname AS target_schema,
                   target.relname AS target_object,
                   ARRAY(
                     SELECT attribute.attname
                       FROM unnest(constraint_record.conkey) WITH ORDINALITY key_column(attnum, position)
                       JOIN pg_attribute attribute
                         ON attribute.attrelid = constraint_record.conrelid
                        AND attribute.attnum = key_column.attnum
                      ORDER BY key_column.position
                   ) AS source_columns,
                   ARRAY(
                     SELECT attribute.attname
                       FROM unnest(constraint_record.confkey) WITH ORDINALITY key_column(attnum, position)
                       JOIN pg_attribute attribute
                         ON attribute.attrelid = constraint_record.confrelid
                        AND attribute.attnum = key_column.attnum
                      ORDER BY key_column.position
                   ) AS target_columns
              FROM pg_constraint constraint_record
              JOIN pg_class source ON source.oid = constraint_record.conrelid
              JOIN pg_namespace source_namespace ON source_namespace.oid = source.relnamespace
              LEFT JOIN pg_class target ON target.oid = constraint_record.confrelid
              LEFT JOIN pg_namespace target_namespace ON target_namespace.oid = target.relnamespace
             WHERE constraint_record.contype IN ('p', 'f', 'u', 'c', 'x')
               AND source_namespace.nspname NOT LIKE 'pg_%'
               AND source_namespace.nspname <> 'information_schema'
             ORDER BY source_namespace.nspname, source.relname, constraint_record.conname
            """;

    private static final String INDEXES_SQL = """
            SELECT namespace.nspname AS schema_name,
                   object.relname AS object_name,
                   index_object.relname AS index_name,
                   index_record.indisunique AS unique_index,
                   index_record.indisprimary AS primary_index,
                   ARRAY(
                     SELECT attribute.attname
                       FROM unnest(index_record.indkey) WITH ORDINALITY key_column(attnum, position)
                       JOIN pg_attribute attribute
                         ON attribute.attrelid = index_record.indrelid
                        AND attribute.attnum = key_column.attnum
                      WHERE key_column.attnum > 0
                      ORDER BY key_column.position
                   ) AS indexed_columns
              FROM pg_index index_record
              JOIN pg_class object ON object.oid = index_record.indrelid
              JOIN pg_namespace namespace ON namespace.oid = object.relnamespace
              JOIN pg_class index_object ON index_object.oid = index_record.indexrelid
             WHERE index_record.indisvalid
               AND namespace.nspname NOT LIKE 'pg_%'
               AND namespace.nspname <> 'information_schema'
             ORDER BY namespace.nspname, object.relname, index_object.relname
            """;

    public ScanResult scan(
            DataGovernanceProperties.Source source,
            int queryTimeoutSeconds) {
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", source.getUsername());
        connectionProperties.setProperty("password", source.getPassword());
        connectionProperties.setProperty("ApplicationName", "dwp-provider-data-governance");
        connectionProperties.setProperty("connectTimeout", String.valueOf(queryTimeoutSeconds));

        try (Connection connection = DriverManager.getConnection(
                source.getJdbcUrl(), connectionProperties)) {
            connection.setReadOnly(true);
            Map<String, MutableAsset> assets = objects(connection, source, queryTimeoutSeconds);
            columns(connection, source, assets, queryTimeoutSeconds);
            List<ConstraintInfo> constraints = constraints(
                    connection, source, assets, queryTimeoutSeconds);
            Map<String, List<IndexInfo>> indexes = indexes(
                    connection, source, queryTimeoutSeconds);
            applyIndexes(assets, indexes);
            List<MutableRelationship> relationships = relationships(
                    source, constraints, indexes);
            return new ScanResult(source, assets, relationships);
        } catch (SQLException exception) {
            String state = exception.getSQLState() == null ? "unknown" : exception.getSQLState();
            throw new IllegalStateException(
                    "Metadata scan failed for " + source.getKey() + " (SQL state " + state + ")",
                    exception);
        }
    }

    private Map<String, MutableAsset> objects(
            Connection connection,
            DataGovernanceProperties.Source source,
            int timeout) throws SQLException {
        Map<String, MutableAsset> result = new LinkedHashMap<>();
        try (PreparedStatement statement = statement(connection, OBJECTS_SQL, timeout);
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                String schema = row.getString("schema_name");
                String name = row.getString("object_name");
                String key = assetKey(source.getKey(), schema, name);
                result.put(key, new MutableAsset(
                        key,
                        source.getKey(),
                        source.getDatabaseName(),
                        schema,
                        name,
                        row.getString("object_type"),
                        row.getString("parent_object_name"),
                        row.getString("description"),
                        row.getLong("estimated_rows"),
                        row.getLong("total_bytes")));
            }
        }
        return result;
    }

    private void columns(
            Connection connection,
            DataGovernanceProperties.Source source,
            Map<String, MutableAsset> assets,
            int timeout) throws SQLException {
        try (PreparedStatement statement = statement(connection, COLUMNS_SQL, timeout);
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                String key = assetKey(
                        source.getKey(), row.getString("schema_name"), row.getString("object_name"));
                MutableAsset asset = assets.get(key);
                if (asset == null) continue;
                asset.columns.add(new MutableColumn(
                        row.getString("column_name"),
                        row.getString("data_type"),
                        row.getBoolean("nullable"),
                        row.getString("default_value"),
                        row.getString("description")));
            }
        }
    }

    private List<ConstraintInfo> constraints(
            Connection connection,
            DataGovernanceProperties.Source source,
            Map<String, MutableAsset> assets,
            int timeout) throws SQLException {
        List<ConstraintInfo> result = new ArrayList<>();
        try (PreparedStatement statement = statement(connection, CONSTRAINTS_SQL, timeout);
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                String sourceKey = assetKey(
                        source.getKey(), row.getString("source_schema"), row.getString("source_object"));
                MutableAsset asset = assets.get(sourceKey);
                if (asset == null) continue;
                String type = row.getString("constraint_type");
                List<String> sourceColumns = strings(row.getArray("source_columns"));
                String targetSchema = row.getString("target_schema");
                String targetObject = row.getString("target_object");
                String targetKey = targetSchema == null || targetObject == null
                        ? null
                        : assetKey(source.getKey(), targetSchema, targetObject);
                List<String> targetColumns = strings(row.getArray("target_columns"));
                asset.constraintCount++;
                if ("p".equals(type)) {
                    asset.primaryKey.addAll(sourceColumns);
                    asset.columns.forEach(column -> column.primaryKey = sourceColumns.contains(column.name));
                }
                if ("f".equals(type)) {
                    asset.columns.forEach(column -> column.foreignKey = sourceColumns.contains(column.name));
                }
                result.add(new ConstraintInfo(
                        row.getString("constraint_name"),
                        type,
                        sourceKey,
                        targetKey,
                        sourceColumns,
                        targetColumns));
            }
        }
        return result;
    }

    private Map<String, List<IndexInfo>> indexes(
            Connection connection,
            DataGovernanceProperties.Source source,
            int timeout) throws SQLException {
        Map<String, List<IndexInfo>> result = new LinkedHashMap<>();
        try (PreparedStatement statement = statement(connection, INDEXES_SQL, timeout);
             ResultSet row = statement.executeQuery()) {
            while (row.next()) {
                String key = assetKey(
                        source.getKey(), row.getString("schema_name"), row.getString("object_name"));
                result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new IndexInfo(
                        row.getString("index_name"),
                        row.getBoolean("unique_index"),
                        row.getBoolean("primary_index"),
                        strings(row.getArray("indexed_columns"))));
            }
        }
        return result;
    }

    private void applyIndexes(
            Map<String, MutableAsset> assets,
            Map<String, List<IndexInfo>> indexes) {
        assets.values().forEach(asset -> {
            List<IndexInfo> objectIndexes = indexes.getOrDefault(asset.assetKey, List.of());
            asset.indexCount = objectIndexes.size();
            Set<String> indexedColumns = new LinkedHashSet<>();
            objectIndexes.forEach(index -> indexedColumns.addAll(index.columns));
            asset.columns.forEach(column -> column.indexed = indexedColumns.contains(column.name));
        });
    }

    private List<MutableRelationship> relationships(
            DataGovernanceProperties.Source source,
            List<ConstraintInfo> constraints,
            Map<String, List<IndexInfo>> indexes) {
        return constraints.stream()
                .filter(constraint -> "f".equals(constraint.type))
                .map(constraint -> new MutableRelationship(
                        "fk:" + source.getKey() + ":" + constraint.constraintName,
                        source.getKey(),
                        constraint.constraintName,
                        constraint.sourceAssetKey,
                        constraint.targetAssetKey,
                        constraint.sourceColumns,
                        constraint.targetColumns,
                        indexes.getOrDefault(constraint.sourceAssetKey, List.of()).stream()
                                .anyMatch(index -> startsWith(index.columns, constraint.sourceColumns))))
                .toList();
    }

    private PreparedStatement statement(
            Connection connection,
            String sql,
            int timeout) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(Math.max(1, timeout));
        statement.setFetchSize(500);
        return statement;
    }

    private boolean startsWith(List<String> indexed, List<String> required) {
        return indexed.size() >= required.size()
                && indexed.subList(0, required.size()).equals(required);
    }

    private List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        if (value instanceof String[] strings) return List.copyOf(Arrays.asList(strings));
        if (value instanceof Object[] objects) {
            return Arrays.stream(objects).map(String::valueOf).toList();
        }
        return List.of();
    }

    static String assetKey(String databaseKey, String schemaName, String objectName) {
        return databaseKey + "." + schemaName + "." + objectName;
    }

    record ScanResult(
            DataGovernanceProperties.Source source,
            Map<String, MutableAsset> assets,
            List<MutableRelationship> relationships) {
    }

    static final class MutableAsset {
        final String assetKey;
        final String databaseKey;
        final String databaseName;
        final String schemaName;
        final String objectName;
        final String objectType;
        final String parentObjectName;
        final String sourceDescription;
        final long estimatedRows;
        final long totalBytes;
        final List<MutableColumn> columns = new ArrayList<>();
        final List<String> primaryKey = new ArrayList<>();
        int constraintCount;
        int indexCount;

        MutableAsset(
                String assetKey,
                String databaseKey,
                String databaseName,
                String schemaName,
                String objectName,
                String objectType,
                String parentObjectName,
                String sourceDescription,
                long estimatedRows,
                long totalBytes) {
            this.assetKey = assetKey;
            this.databaseKey = databaseKey;
            this.databaseName = databaseName;
            this.schemaName = schemaName;
            this.objectName = objectName;
            this.objectType = objectType;
            this.parentObjectName = parentObjectName;
            this.sourceDescription = sourceDescription;
            this.estimatedRows = estimatedRows;
            this.totalBytes = totalBytes;
        }
    }

    static final class MutableColumn {
        final String name;
        final String dataType;
        final boolean nullable;
        final String defaultValue;
        final String description;
        boolean primaryKey;
        boolean foreignKey;
        boolean indexed;

        MutableColumn(
                String name,
                String dataType,
                boolean nullable,
                String defaultValue,
                String description) {
            this.name = name;
            this.dataType = dataType;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.description = description;
        }
    }

    record MutableRelationship(
            String relationshipId,
            String databaseKey,
            String constraintName,
            String sourceAssetKey,
            String targetAssetKey,
            List<String> sourceColumns,
            List<String> targetColumns,
            boolean sourceIndexed) {
    }

    private record ConstraintInfo(
            String constraintName,
            String type,
            String sourceAssetKey,
            String targetAssetKey,
            List<String> sourceColumns,
            List<String> targetColumns) {
    }

    private record IndexInfo(
            String name,
            boolean unique,
            boolean primary,
            List<String> columns) {
    }
}
