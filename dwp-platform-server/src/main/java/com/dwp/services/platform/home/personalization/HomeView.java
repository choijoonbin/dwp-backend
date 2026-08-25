package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.util.UUID;
import java.time.OffsetDateTime;

@Entity
@Table(name = "usr_home_views")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SQLRestriction("deleted_at IS NULL")
public class HomeView extends HomePersonalizationEntity {

    @Id
    @Column(name = "view_id", nullable = false)
    private UUID viewId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "surface_key", nullable = false, length = 80)
    private String surfaceKey;

    @Column(name = "view_key", nullable = false, length = 80)
    private String viewKey;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean defaultView;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode layoutPayload;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Builder.Default
    @Column(name = "integrity_state", nullable = false, length = 24)
    private String integrityState = "VALID";

    @Builder.Default
    @Column(name = "is_customized", nullable = false)
    private boolean customized = true;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;
}
