package com.dwp.services.platform.home;

import com.dwp.core.entity.BaseEntity;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "adm_home_experiences")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomeExperience extends BaseEntity {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "headline", length = 160)
    private String headline;

    @Column(name = "subheadline", length = 500)
    private String subheadline;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "localized_content", nullable = false, columnDefinition = "jsonb")
    private JsonNode localizedContent = JsonNodeFactory.instance.objectNode();

    @Builder.Default
    @Column(name = "default_locale", nullable = false, length = 32)
    private String defaultLocale = "ko";

    @Builder.Default
    @Column(name = "background_position", nullable = false, length = 16)
    private String backgroundPosition = "CENTER";

    @Builder.Default
    @Column(name = "overlay_opacity", nullable = false)
    private Integer overlayOpacity = 18;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "launchpad_configuration", nullable = false, columnDefinition = "jsonb")
    private JsonNode launchpadConfiguration = JsonNodeFactory.instance.objectNode();

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "composition_policy", nullable = false, columnDefinition = "jsonb")
    private JsonNode compositionPolicy = JsonNodeFactory.instance.objectNode();

    @Column(name = "background_asset_key", length = 320)
    private String backgroundAssetKey;

    @Column(name = "background_original_name", length = 255)
    private String backgroundOriginalName;

    @Column(name = "background_content_type", length = 64)
    private String backgroundContentType;

    @Column(name = "background_size_bytes")
    private Long backgroundSizeBytes;

    @Column(name = "background_sha256", length = 64)
    private String backgroundSha256;

    @Column(name = "background_width")
    private Integer backgroundWidth;

    @Column(name = "background_height")
    private Integer backgroundHeight;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
