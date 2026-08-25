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
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "adm_home_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeTemplate extends HomePersonalizationEntity {
    @Id
    @Column(name = "template_id")
    private UUID templateId;
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;
    @Column(name = "template_key", nullable = false, length = 80)
    private String templateKey;
    @Column(name = "name", nullable = false, length = 80)
    private String name;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audience_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode audiencePayload;
    @Column(name = "lifecycle_state", nullable = false, length = 16)
    private String lifecycleState;
    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "layout_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode layoutPayload;
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;
    @Column(name = "published_by")
    private Long publishedBy;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
