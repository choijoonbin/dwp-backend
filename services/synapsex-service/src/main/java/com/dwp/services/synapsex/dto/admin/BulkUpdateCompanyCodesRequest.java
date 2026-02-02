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
public class BulkUpdateCompanyCodesRequest {
    @NotEmpty(message = "items는 비어있을 수 없습니다")
    @Valid
    private List<CompanyCodeItemDto> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyCodeItemDto {
        @jakarta.validation.constraints.NotBlank(message = "bukrs는 필수입니다")
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9]{4}", message = "bukrs는 4자리 영숫자여야 합니다")
        private String bukrs;
        private Boolean enabled;
        private String source;
    }
}
