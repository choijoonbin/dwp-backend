package com.dwp.services.provider.governance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DataGovernanceRepository {

    private final JdbcTemplate jdbc;

    public DataGovernanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, AssetAnnotation> annotations() {
        Map<String, AssetAnnotation> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT asset_key, business_domain, owner_service, lifecycle_state,
                       criticality, data_classification, review_state, description, review_note
                  FROM prv_data_asset_annotations
                 ORDER BY asset_key
                """, row -> {
            AssetAnnotation annotation = annotation(row);
            result.put(annotation.assetKey(), annotation);
        });
        return result;
    }

    public List<DataGovernanceDtos.LineageEdge> lineage() {
        return jdbc.query("""
                SELECT data_lineage_edge_id::text, edge_key, source_asset_key,
                       target_asset_key, process_key, edge_type, owner_service,
                       description, evidence, metadata::text
                  FROM prv_data_lineage_edges
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY process_key, edge_key
                """, (row, ignored) -> new DataGovernanceDtos.LineageEdge(
                row.getString(1),
                row.getString(2),
                row.getString(3),
                row.getString(4),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                row.getString(8),
                row.getString(9),
                row.getString(10)));
    }

    private AssetAnnotation annotation(ResultSet row) throws SQLException {
        return new AssetAnnotation(
                row.getString("asset_key"),
                row.getString("business_domain"),
                row.getString("owner_service"),
                row.getString("lifecycle_state"),
                row.getString("criticality"),
                row.getString("data_classification"),
                row.getString("review_state"),
                row.getString("description"),
                row.getString("review_note"));
    }

    public record AssetAnnotation(
            String assetKey,
            String businessDomain,
            String ownerService,
            String lifecycleState,
            String criticality,
            String dataClassification,
            String reviewState,
            String description,
            String reviewNote) {
    }
}
