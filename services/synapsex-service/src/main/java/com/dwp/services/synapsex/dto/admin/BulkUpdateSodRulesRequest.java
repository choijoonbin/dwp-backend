package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUpdateSodRulesRequest {
    @NotEmpty(message = "items는 비어있을 수 없습니다")
    @Valid
    private List<SodRuleItemDto> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SodRuleItemDto {
        @jakarta.validation.constraints.NotBlank(message = "ruleKey는 필수입니다")
        private String ruleKey;
        private Boolean enabled;
        @jakarta.validation.constraints.Pattern(regexp = "INFO|WARN|BLOCK", message = "severity는 INFO, WARN, BLOCK 중 하나")
        private String severity;
        private String title;
        private String description;
        private List<String> appliesTo;
    }
}
