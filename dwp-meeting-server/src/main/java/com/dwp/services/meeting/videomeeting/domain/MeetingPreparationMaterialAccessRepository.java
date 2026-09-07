package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingPreparationMaterialAccessModels.Material;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingPreparationMaterialAccessRepository {

    private final JdbcTemplate jdbc;

    MeetingPreparationMaterialAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Material> forUpdate(long tenantId, UUID meetingId, UUID materialId) {
        return jdbc.query("""
                SELECT material_id, tenant_id, meeting_id, display_name, content_type,
                       reference_provider, opaque_reference, source_version, classification,
                       content_sha256, retention_until, lifecycle_state, version
                  FROM vm_meeting_preparation_materials
                 WHERE tenant_id = ? AND meeting_id = ? AND material_id = ?
                 FOR UPDATE
                """, (row, index) -> new Material(
                        row.getObject("material_id", UUID.class), row.getLong("tenant_id"),
                        row.getObject("meeting_id", UUID.class), row.getString("display_name"),
                        row.getString("content_type"), row.getString("reference_provider"),
                        row.getString("opaque_reference"), row.getString("source_version"),
                        row.getString("classification"), row.getString("content_sha256"),
                        row.getObject("retention_until", java.time.OffsetDateTime.class),
                        row.getString("lifecycle_state"), row.getLong("version")),
                tenantId, meetingId, materialId).stream().findFirst();
    }
}
