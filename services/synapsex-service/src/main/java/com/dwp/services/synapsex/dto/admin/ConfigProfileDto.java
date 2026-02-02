package com.dwp.services.synapsex.dto.admin;

import com.dwp.services.synapsex.entity.ConfigProfile;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ConfigProfileDto {
    private Long profileId;
    private Long tenantId;
    private String profileName;
    private String description;
    private Boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;

    public static ConfigProfileDto from(ConfigProfile e) {
        if (e == null) return null;
        return ConfigProfileDto.builder()
                .profileId(e.getProfileId())
                .tenantId(e.getTenantId())
                .profileName(e.getProfileName())
                .description(e.getDescription())
                .isDefault(e.getIsDefault())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
