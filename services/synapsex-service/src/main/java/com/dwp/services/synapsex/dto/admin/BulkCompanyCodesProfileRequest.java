package com.dwp.services.synapsex.dto.admin;

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
public class BulkCompanyCodesProfileRequest {

    @NotNull(message = "profileId는 필수입니다")
    private Long profileId;

    @NotEmpty(message = "updates는 비어있을 수 없습니다")
    @Valid
    private List<CompanyCodeUpdateDto> updates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyCodeUpdateDto {
        @jakarta.validation.constraints.NotBlank(message = "bukrs는 필수입니다")
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9]{4}", message = "bukrs는 4자리 영숫자여야 합니다")
        private String bukrs;
        private Boolean included;
    }
}
