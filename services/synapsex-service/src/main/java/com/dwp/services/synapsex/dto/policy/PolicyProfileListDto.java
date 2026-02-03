package com.dwp.services.synapsex.dto.policy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyProfileListDto {
    private List<ProfileSummaryDto> profiles;
    private Long defaultProfileId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileSummaryDto {
        private Long profileId;
        private String profileName;
        private String description;
        private Boolean isDefault;
        private Instant createdAt;
    }
}
