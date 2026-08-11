package com.dwp.services.provider.governance;

import java.time.Instant;
import java.util.List;

public final class DataGovernanceDtos {

    private DataGovernanceDtos() {
    }

    public record Snapshot(
            Instant generatedAt,
            Summary summary,
            List<DatabaseSummary> databases,
            List<DataAsset> assets,
            List<Relationship> relationships,
            List<LineageEdge> lineage,
            List<Finding> findings) {
    }

    public record Summary(
            int databases,
            int availableDatabases,
            int logicalTables,
            int partitions,
            int columns,
            int foreignKeys,
            int documentedAssets,
            int reviewRequired,
            long totalBytes) {
    }

    public record DatabaseSummary(
            String databaseKey,
            String databaseName,
            String displayName,
            String ownerService,
            String status,
            String error,
            int logicalTables,
            int partitions,
            int views,
            int columns,
            int foreignKeys,
            int documentedAssets,
            int totalAssets,
            long totalBytes,
            List<String> businessDomains) {
    }

    public record DataAsset(
            String assetKey,
            String databaseKey,
            String databaseName,
            String schemaName,
            String objectName,
            String objectType,
            String parentObjectName,
            String businessDomain,
            String ownerService,
            String lifecycleState,
            String criticality,
            String dataClassification,
            String reviewState,
            String description,
            String reviewNote,
            long estimatedRows,
            long totalBytes,
            boolean tenantScoped,
            int constraintCount,
            int indexCount,
            int inboundRelationships,
            int outboundRelationships,
            List<String> primaryKey,
            List<Column> columns) {
    }

    public record Column(
            String name,
            String dataType,
            boolean nullable,
            String defaultValue,
            String description,
            boolean primaryKey,
            boolean foreignKey,
            boolean indexed,
            String classification) {
    }

    public record Relationship(
            String relationshipId,
            String databaseKey,
            String constraintName,
            String sourceAssetKey,
            String targetAssetKey,
            List<String> sourceColumns,
            List<String> targetColumns,
            boolean sourceIndexed) {
    }

    public record LineageEdge(
            String edgeId,
            String edgeKey,
            String sourceAssetKey,
            String targetAssetKey,
            String processKey,
            String edgeType,
            String ownerService,
            String description,
            String evidence,
            String metadata) {
    }

    public record Finding(
            String findingId,
            String severity,
            String category,
            String databaseKey,
            String assetKey,
            String title,
            String detail,
            String recommendation,
            String evidence) {
    }
}
