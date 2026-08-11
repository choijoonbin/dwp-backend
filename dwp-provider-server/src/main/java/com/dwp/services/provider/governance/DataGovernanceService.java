package com.dwp.services.provider.governance;

import com.dwp.services.provider.governance.DataGovernanceRepository.AssetAnnotation;
import com.dwp.services.provider.governance.DataGovernanceScanner.MutableAsset;
import com.dwp.services.provider.governance.DataGovernanceScanner.MutableColumn;
import com.dwp.services.provider.governance.DataGovernanceScanner.MutableRelationship;
import com.dwp.services.provider.governance.DataGovernanceScanner.ScanResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DataGovernanceService {

    private final DataGovernanceProperties properties;
    private final DataGovernanceScanner scanner;
    private final DataGovernanceRepository repository;
    private volatile CachedSnapshot cache;

    public DataGovernanceService(
            DataGovernanceProperties properties,
            DataGovernanceScanner scanner,
            DataGovernanceRepository repository) {
        this.properties = properties;
        this.scanner = scanner;
        this.repository = repository;
    }

    public DataGovernanceDtos.Snapshot snapshot() {
        return snapshot(false);
    }

    public synchronized DataGovernanceDtos.Snapshot refresh() {
        return snapshot(true);
    }

    private DataGovernanceDtos.Snapshot snapshot(boolean force) {
        Instant now = Instant.now();
        CachedSnapshot current = cache;
        if (!force && current != null
                && current.createdAt().plus(properties.getCacheTtl()).isAfter(now)) {
            return current.snapshot();
        }
        DataGovernanceDtos.Snapshot generated = build(now);
        cache = new CachedSnapshot(now, generated);
        return generated;
    }

    private DataGovernanceDtos.Snapshot build(Instant generatedAt) {
        Map<String, AssetAnnotation> annotations = repository.annotations();
        List<ScanResult> scans = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (DataGovernanceProperties.Source source : properties.getSources()) {
            try {
                scans.add(scanner.scan(source, properties.getQueryTimeoutSeconds()));
            } catch (IllegalStateException exception) {
                errors.put(source.getKey(), exception.getMessage());
            }
        }

        List<MutableRelationship> mutableRelationships = scans.stream()
                .flatMap(scan -> scan.relationships().stream())
                .toList();
        Map<String, Integer> inbound = new HashMap<>();
        Map<String, Integer> outbound = new HashMap<>();
        mutableRelationships.forEach(relationship -> {
            outbound.merge(relationship.sourceAssetKey(), 1, Integer::sum);
            inbound.merge(relationship.targetAssetKey(), 1, Integer::sum);
        });

        List<DataGovernanceDtos.DataAsset> assets = scans.stream()
                .flatMap(scan -> scan.assets().values().stream()
                        .map(asset -> asset(
                                scan.source(), asset, annotations.get(asset.assetKey), inbound, outbound)))
                .sorted(Comparator.comparing(DataGovernanceDtos.DataAsset::assetKey))
                .toList();
        Map<String, DataGovernanceDtos.DataAsset> assetsByKey = new LinkedHashMap<>();
        assets.forEach(asset -> assetsByKey.put(asset.assetKey(), asset));

        List<DataGovernanceDtos.Relationship> relationships = mutableRelationships.stream()
                .map(this::relationship)
                .sorted(Comparator.comparing(DataGovernanceDtos.Relationship::relationshipId))
                .toList();
        List<DataGovernanceDtos.LineageEdge> lineage = repository.lineage();
        List<DataGovernanceDtos.Finding> findings = findings(
                properties.getSources(), errors, assets, relationships, annotations, lineage, assetsByKey);
        List<DataGovernanceDtos.DatabaseSummary> databases = databases(
                properties.getSources(), scans, errors, assets, relationships);

        int logicalTables = (int) assets.stream().filter(this::logicalTable).count();
        int partitions = (int) assets.stream()
                .filter(asset -> "PARTITION".equals(asset.objectType())).count();
        int columns = assets.stream().mapToInt(asset -> asset.columns().size()).sum();
        int documented = (int) assets.stream()
                .filter(asset -> hasText(asset.description())).count();
        long bytes = databases.stream().mapToLong(DataGovernanceDtos.DatabaseSummary::totalBytes).sum();
        DataGovernanceDtos.Summary summary = new DataGovernanceDtos.Summary(
                properties.getSources().size(),
                properties.getSources().size() - errors.size(),
                logicalTables,
                partitions,
                columns,
                relationships.size(),
                documented,
                findings.size(),
                bytes);
        return new DataGovernanceDtos.Snapshot(
                generatedAt, summary, databases, assets, relationships, lineage, findings);
    }

    private DataGovernanceDtos.DataAsset asset(
            DataGovernanceProperties.Source source,
            MutableAsset asset,
            AssetAnnotation annotation,
            Map<String, Integer> inbound,
            Map<String, Integer> outbound) {
        String domain = value(annotation == null ? null : annotation.businessDomain(),
                domain(asset.databaseKey, asset.objectName));
        String owner = value(annotation == null ? null : annotation.ownerService(),
                source.getOwnerService());
        String classification = value(
                annotation == null ? null : annotation.dataClassification(),
                classification(asset));
        String objectType = "flyway_schema_history".equals(asset.objectName)
                ? "SYSTEM_TABLE" : asset.objectType;
        return new DataGovernanceDtos.DataAsset(
                asset.assetKey,
                asset.databaseKey,
                asset.databaseName,
                asset.schemaName,
                asset.objectName,
                objectType,
                asset.parentObjectName,
                domain,
                owner,
                value(annotation == null ? null : annotation.lifecycleState(), "ACTIVE"),
                value(annotation == null ? null : annotation.criticality(), criticality(asset)),
                classification,
                value(annotation == null ? null : annotation.reviewState(), "DISCOVERED"),
                value(annotation == null ? null : annotation.description(), asset.sourceDescription),
                annotation == null ? null : annotation.reviewNote(),
                asset.estimatedRows,
                asset.totalBytes,
                asset.columns.stream().anyMatch(column ->
                        "tenant_id".equals(column.name) || "provider_tenant_id".equals(column.name)),
                asset.constraintCount,
                asset.indexCount,
                inbound.getOrDefault(asset.assetKey, 0),
                outbound.getOrDefault(asset.assetKey, 0),
                List.copyOf(asset.primaryKey),
                asset.columns.stream().map(this::column).toList());
    }

    private DataGovernanceDtos.Column column(MutableColumn column) {
        return new DataGovernanceDtos.Column(
                column.name,
                column.dataType,
                column.nullable,
                column.defaultValue,
                column.description,
                column.primaryKey,
                column.foreignKey,
                column.indexed,
                columnClassification(column.name));
    }

    private DataGovernanceDtos.Relationship relationship(MutableRelationship relationship) {
        return new DataGovernanceDtos.Relationship(
                relationship.relationshipId(),
                relationship.databaseKey(),
                relationship.constraintName(),
                relationship.sourceAssetKey(),
                relationship.targetAssetKey(),
                relationship.sourceColumns(),
                relationship.targetColumns(),
                relationship.sourceIndexed());
    }

    private List<DataGovernanceDtos.DatabaseSummary> databases(
            List<DataGovernanceProperties.Source> sources,
            List<ScanResult> scans,
            Map<String, String> errors,
            List<DataGovernanceDtos.DataAsset> assets,
            List<DataGovernanceDtos.Relationship> relationships) {
        Map<String, ScanResult> scansByKey = new HashMap<>();
        scans.forEach(scan -> scansByKey.put(scan.source().getKey(), scan));
        return sources.stream().map(source -> {
            List<DataGovernanceDtos.DataAsset> scopedAssets = assets.stream()
                    .filter(asset -> source.getKey().equals(asset.databaseKey())).toList();
            long logicalTables = scopedAssets.stream().filter(this::logicalTable).count();
            long partitions = scopedAssets.stream()
                    .filter(asset -> "PARTITION".equals(asset.objectType())).count();
            long views = scopedAssets.stream()
                    .filter(asset -> asset.objectType().contains("VIEW")).count();
            int columns = scopedAssets.stream().mapToInt(asset -> asset.columns().size()).sum();
            int foreignKeys = (int) relationships.stream()
                    .filter(item -> source.getKey().equals(item.databaseKey())).count();
            int documented = (int) scopedAssets.stream()
                    .filter(asset -> hasText(asset.description())).count();
            long bytes = scopedAssets.stream().mapToLong(DataGovernanceDtos.DataAsset::totalBytes).sum();
            List<String> domains = scopedAssets.stream()
                    .map(DataGovernanceDtos.DataAsset::businessDomain)
                    .filter(this::hasText)
                    .distinct()
                    .sorted()
                    .toList();
            boolean available = scansByKey.containsKey(source.getKey());
            return new DataGovernanceDtos.DatabaseSummary(
                    source.getKey(),
                    source.getDatabaseName(),
                    source.getDisplayName(),
                    source.getOwnerService(),
                    available ? "AVAILABLE" : "UNAVAILABLE",
                    errors.get(source.getKey()),
                    (int) logicalTables,
                    (int) partitions,
                    (int) views,
                    columns,
                    foreignKeys,
                    documented,
                    scopedAssets.size(),
                    bytes,
                    domains);
        }).toList();
    }

    private List<DataGovernanceDtos.Finding> findings(
            List<DataGovernanceProperties.Source> sources,
            Map<String, String> errors,
            List<DataGovernanceDtos.DataAsset> assets,
            List<DataGovernanceDtos.Relationship> relationships,
            Map<String, AssetAnnotation> annotations,
            List<DataGovernanceDtos.LineageEdge> lineage,
            Map<String, DataGovernanceDtos.DataAsset> assetsByKey) {
        List<DataGovernanceDtos.Finding> result = new ArrayList<>();
        sources.forEach(source -> {
            String error = errors.get(source.getKey());
            if (error != null) {
                result.add(finding(
                        "SOURCE_UNAVAILABLE", "CRITICAL", source.getKey(), null,
                        "Metadata source is unavailable", error,
                        "Restore the read-only metadata connection before approving schema changes.",
                        source.getDatabaseName()));
            }
        });

        for (DataGovernanceDtos.DataAsset asset : assets) {
            if ("REVIEW_REQUIRED".equals(asset.reviewState())) {
                result.add(finding(
                        "OWNERSHIP_REVIEW", "HIGH", asset.databaseKey(), asset.assetKey(),
                        "Schema asset requires an ownership decision",
                        value(asset.reviewNote(), "The asset has not completed governance review."),
                        "Assign a runtime owner and either activate, consolidate, or retire it through a versioned migration.",
                        asset.lifecycleState()));
            }
            if (logicalTable(asset) && asset.primaryKey().isEmpty()) {
                result.add(finding(
                        "MISSING_PRIMARY_KEY", "HIGH", asset.databaseKey(), asset.assetKey(),
                        "Logical table has no primary key",
                        "Stable identity is required for reliable updates, replication, and lineage.",
                        "Add a primary key after checking duplicate rows and downstream contracts.",
                        asset.objectName()));
            }
            if (!"PARTITION".equals(asset.objectType())
                    && !"SYSTEM_TABLE".equals(asset.objectType())
                    && !hasText(asset.description())) {
                result.add(finding(
                        "MISSING_DOCUMENTATION", "LOW", asset.databaseKey(), asset.assetKey(),
                        "Business description is missing",
                        "The source catalog and curated provider annotation are both empty.",
                        "Document purpose, ownership, retention, and tenant boundary before the next review.",
                        asset.schemaName() + "." + asset.objectName()));
            }
            List<String> localTimestamps = asset.columns().stream()
                    .filter(column -> column.dataType().startsWith("timestamp without time zone"))
                    .map(DataGovernanceDtos.Column::name)
                    .toList();
            if (!localTimestamps.isEmpty() && !"SYSTEM_TABLE".equals(asset.objectType())) {
                result.add(finding(
                        "TIMEZONE_AMBIGUITY", "MEDIUM", asset.databaseKey(), asset.assetKey(),
                        "Timestamp columns have no time-zone semantics",
                        "Affected columns: " + String.join(", ", localTimestamps),
                        "Define a UTC conversion contract and migrate to timestamptz in a compatibility release.",
                        String.valueOf(localTimestamps.size())));
            }
        }

        relationships.stream().filter(relationship -> !relationship.sourceIndexed()).forEach(relationship -> {
            DataGovernanceDtos.DataAsset source = assetsByKey.get(relationship.sourceAssetKey());
            String severity = source != null && source.estimatedRows() >= 1000 ? "MEDIUM" : "LOW";
            result.add(finding(
                    "UNINDEXED_FOREIGN_KEY", severity, relationship.databaseKey(),
                    relationship.sourceAssetKey(), "Foreign-key lookup has no leading index",
                    relationship.constraintName() + " uses "
                            + String.join(", ", relationship.sourceColumns()),
                    "Validate delete/join workload and add a leading index when the relationship is operationally hot.",
                    relationship.targetAssetKey()));
        });

        Map<String, List<DataGovernanceDtos.Relationship>> canonical = new LinkedHashMap<>();
        relationships.forEach(relationship -> canonical.computeIfAbsent(
                relationship.sourceAssetKey() + "|" + relationship.targetAssetKey()
                        + "|" + relationship.sourceColumns() + "|" + relationship.targetColumns(),
                ignored -> new ArrayList<>()).add(relationship));
        canonical.values().stream().filter(group -> group.size() > 1).forEach(group -> {
            DataGovernanceDtos.Relationship first = group.get(0);
            result.add(finding(
                    "DUPLICATE_FOREIGN_KEY", "HIGH", first.databaseKey(), first.sourceAssetKey(),
                    "Equivalent foreign-key constraints are duplicated",
                    group.stream().map(DataGovernanceDtos.Relationship::constraintName)
                            .sorted().toList().toString(),
                    "Keep one named constraint and remove the duplicate in a versioned migration.",
                    first.targetAssetKey()));
        });

        annotations.values().stream()
                .filter(annotation -> !assetsByKey.containsKey(annotation.assetKey()))
                .forEach(annotation -> result.add(finding(
                        "ANNOTATION_DRIFT", "MEDIUM", databaseKey(annotation.assetKey()), annotation.assetKey(),
                        "Curated asset is absent from the live catalog",
                        "The governance annotation remains but the physical object was not discovered.",
                        "Confirm the source connection, rename mapping, or retire the stale annotation.",
                        annotation.assetKey())));
        lineage.stream()
                .filter(edge -> !assetsByKey.containsKey(edge.sourceAssetKey())
                        || !assetsByKey.containsKey(edge.targetAssetKey()))
                .forEach(edge -> result.add(finding(
                        "LINEAGE_DRIFT", "HIGH", databaseKey(edge.sourceAssetKey()), edge.sourceAssetKey(),
                        "Lineage endpoint is absent from the live catalog",
                        edge.sourceAssetKey() + " -> " + edge.targetAssetKey(),
                        "Repair or retire the lineage contract after validating the owning process.",
                        edge.evidence())));
        return result.stream()
                .sorted(Comparator.comparingInt((DataGovernanceDtos.Finding item) ->
                                severityOrder(item.severity()))
                        .thenComparing(DataGovernanceDtos.Finding::category)
                        .thenComparing(DataGovernanceDtos.Finding::findingId))
                .toList();
    }

    private DataGovernanceDtos.Finding finding(
            String category,
            String severity,
            String databaseKey,
            String assetKey,
            String title,
            String detail,
            String recommendation,
            String evidence) {
        String identity = String.join(":",
                category,
                value(assetKey, databaseKey),
                value(detail, "none"),
                value(evidence, "none"));
        return new DataGovernanceDtos.Finding(
                UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                severity, category, databaseKey,
                assetKey, title, detail, recommendation, evidence);
    }

    private String domain(String databaseKey, String objectName) {
        if ("flyway_schema_history".equals(objectName)) return "Schema management";
        return switch (databaseKey) {
            case "auth" -> authDomain(objectName);
            case "people" -> peopleDomain(objectName);
            case "platform" -> platformDomain(objectName);
            case "provider" -> providerDomain(objectName);
            default -> "Unclassified";
        };
    }

    private String authDomain(String name) {
        if (name.startsWith("sys_scim")) return "Identity provisioning";
        if (name.startsWith("sys_identity")) return "Identity federation";
        if (name.startsWith("sys_auth") || name.startsWith("sys_login")
                || name.startsWith("sys_account")) return "Authentication and sessions";
        if (name.contains("role") || name.contains("permission") || name.contains("resource")) {
            return "Authorization governance";
        }
        if (name.startsWith("sys_audit")) return "Audit delivery";
        return "Identity core";
    }

    private String peopleDomain(String name) {
        if (name.startsWith("int_")) return "HR integration";
        if (name.contains("scenario")) return "Organization design";
        if (name.contains("organization") || name.contains("position")) {
            return "Organization structure";
        }
        if (name.startsWith("sys_")) return name.contains("audit")
                ? "Audit delivery" : "People service foundation";
        return "Workforce records";
    }

    private String platformDomain(String name) {
        if (name.startsWith("sys_api")) return "API observability";
        if (name.startsWith("sys_audit")) return "Enterprise audit";
        if (name.startsWith("sys_code")) return "Product code contracts";
        if (name.startsWith("usr_")) return "Personalization";
        if (name.contains("reference")) return "Tenant reference data";
        if (name.contains("branding") || name.contains("home") || name.contains("announcement")
                || name.contains("navigation") || name.contains("registry")) {
            return "Tenant experience";
        }
        return "Tenant administration";
    }

    private String providerDomain(String name) {
        if (name.contains("subscription") || name.contains("plan") || name.contains("entitlement")) {
            return "Commercial and entitlements";
        }
        if (name.contains("incident") || name.contains("health") || name.contains("objective")
                || name.contains("maintenance")) return "Service reliability";
        if (name.contains("operation") || name.contains("approval")) return "Change control";
        if (name.contains("support")) return "Privileged support";
        if (name.contains("operator") || name.contains("audit")) return "Provider governance";
        if (name.contains("configuration")) return "Configuration governance";
        return "Tenant lifecycle";
    }

    private String classification(MutableAsset asset) {
        String table = asset.objectName.toLowerCase(Locale.ROOT);
        if (table.contains("private") || table.contains("credential") || table.contains("token")
                || table.contains("session") || table.contains("audit")) return "RESTRICTED";
        if (asset.columns.stream().anyMatch(column ->
                "RESTRICTED".equals(columnClassification(column.name)))) return "RESTRICTED";
        if (asset.columns.stream().anyMatch(column ->
                "CONFIDENTIAL".equals(columnClassification(column.name)))) return "CONFIDENTIAL";
        return "INTERNAL";
    }

    private String columnClassification(String columnName) {
        String name = columnName.toLowerCase(Locale.ROOT);
        if (containsAny(name, "password", "secret", "token", "credential", "private",
                "salary", "national_id", "resident", "bank", "payload")) return "RESTRICTED";
        if (containsAny(name, "email", "phone", "address", "birth", "given_name",
                "family_name", "display_name", "principal", "session", "actor_id",
                "user_id", "person_id")) return "CONFIDENTIAL";
        return "INTERNAL";
    }

    private String criticality(MutableAsset asset) {
        String name = asset.objectName.toLowerCase(Locale.ROOT);
        if (name.contains("tenant") || name.contains("user") || name.contains("person")
                || name.contains("audit_events")) return "CRITICAL";
        if (name.contains("role") || name.contains("permission") || name.contains("outbox")
                || name.contains("operation") || name.contains("assignment")) return "HIGH";
        return "MEDIUM";
    }

    private boolean logicalTable(DataGovernanceDtos.DataAsset asset) {
        return "TABLE".equals(asset.objectType()) || "PARTITIONED_TABLE".equals(asset.objectType());
    }

    private boolean containsAny(String value, String... patterns) {
        for (String pattern : patterns) {
            if (value.contains(pattern)) return true;
        }
        return false;
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            default -> 3;
        };
    }

    private String databaseKey(String assetKey) {
        int separator = assetKey.indexOf('.');
        return separator < 0 ? assetKey : assetKey.substring(0, separator);
    }

    private String value(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record CachedSnapshot(Instant createdAt, DataGovernanceDtos.Snapshot snapshot) {
    }
}
