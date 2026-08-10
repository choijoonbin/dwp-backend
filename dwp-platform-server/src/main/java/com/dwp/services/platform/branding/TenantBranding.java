package com.dwp.services.platform.branding;

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

@Entity
@Table(name = "adm_tenant_branding")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantBranding extends BaseEntity {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "organization_name", length = 160)
    private String organizationName;

    @Column(name = "logo_asset_key", length = 320)
    private String logoAssetKey;

    @Column(name = "logo_original_name", length = 255)
    private String logoOriginalName;

    @Column(name = "logo_content_type", length = 64)
    private String logoContentType;

    @Column(name = "logo_size_bytes")
    private Long logoSizeBytes;

    @Column(name = "logo_sha256", length = 64)
    private String logoSha256;

    @Column(name = "logo_width")
    private Integer logoWidth;

    @Column(name = "logo_height")
    private Integer logoHeight;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
