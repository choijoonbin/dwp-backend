package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "usr_home_view_revisions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeViewRevision {

    @Id
    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "view_id", nullable = false)
    private UUID viewId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "revision_number", nullable = false)
    private Long revisionNumber;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode snapshot;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "change_summary", length = 240)
    private String changeSummary;

    @Column(name = "command_id")
    private UUID commandId;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Builder.Default
    @Column(name = "restorable", nullable = false)
    private boolean restorable = true;
}
