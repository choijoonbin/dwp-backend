package com.dwp.services.synapsex.dto.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkSodRulesProfileRequest {

    @NotNull(message = "profileId는 필수입니다")
    private Long profileId;

    @NotEmpty(message = "updates는 비어있을 수 없습니다")
    @Valid
    private List<SodRuleUpdateDto> updates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SodRuleUpdateDto {
        @jakarta.validation.constraints.NotBlank(message = "ruleKey는 필수입니다")
        private String ruleKey;
        private Boolean isEnabled;
        @jakarta.validation.constraints.Pattern(regexp = "INFO|WARN|BLOCK", message = "severity는 INFO, WARN, BLOCK 중 하나")
        private String severity;
        private JsonNode configJson;
    }
}
