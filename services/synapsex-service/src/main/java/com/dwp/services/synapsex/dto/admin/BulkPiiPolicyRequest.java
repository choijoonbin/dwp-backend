package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class BulkPiiPolicyRequest {
    @NotNull(message = "profileId는 필수입니다")
    private Long profileId;
    @Valid
    private List<PiiPolicyItem> items;

    @Data
    public static class PiiPolicyItem {
        @jakarta.validation.constraints.NotBlank(message = "fieldKey는 필수입니다")
        private String fieldKey;  // field_name in DB
        @jakarta.validation.constraints.Pattern(regexp = "ALLOW|MASK|HASH_ONLY|ENCRYPT|FORBID", message = "handling은 ALLOW, MASK, HASH_ONLY, ENCRYPT, FORBID 중 하나여야 합니다")
        private String handling;
        private String maskRule;
        private String hashRule;
        private String encryptRule;
        private String note;
    }
}
