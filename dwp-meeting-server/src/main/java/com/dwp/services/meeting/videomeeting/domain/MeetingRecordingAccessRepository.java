package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingAccessModels.RecordingArtifact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
class MeetingRecordingAccessRepository {

    private final JdbcTemplate jdbc;

    MeetingRecordingAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<RecordingArtifact> recordingArtifactForUpdate(
            long tenantId, UUID meetingId, UUID artifactId) {
        return jdbc.query("""
                SELECT artifact_id, tenant_id, meeting_id, artifact_state,
                       storage_provider, object_key, content_type, size_bytes,
                       sha256, retention_until, version
                  FROM vm_meeting_artifacts
                 WHERE tenant_id = ? AND meeting_id = ? AND artifact_id = ?
                   AND artifact_type = 'RECORDING'
                 FOR UPDATE
                """, this::artifact, tenantId, meetingId, artifactId)
                .stream().findFirst();
    }

    private RecordingArtifact artifact(ResultSet row, int index) throws SQLException {
        long sizeBytes = row.getLong("size_bytes");
        Long nullableSizeBytes = row.wasNull() ? null : sizeBytes;
        return new RecordingArtifact(
                row.getObject("artifact_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("meeting_id", UUID.class), row.getString("artifact_state"),
                row.getString("storage_provider"), row.getString("object_key"),
                row.getString("content_type"), nullableSizeBytes,
                row.getString("sha256"),
                row.getObject("retention_until", java.time.OffsetDateTime.class),
                row.getLong("version"));
    }
}
