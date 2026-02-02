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
public class BulkCurrenciesProfileRequest {

    @NotNull(message = "profileId는 필수입니다")
    private Long profileId;

    @NotEmpty(message = "updates는 비어있을 수 없습니다")
    @Valid
    private List<CurrencyUpdateDto> updates;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyUpdateDto {
        @jakarta.validation.constraints.NotBlank(message = "currencyCode는 필수입니다")
        @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z]{3,5}", message = "currencyCode는 3~5자리 영문이어야 합니다")
        private String currencyCode;
        private Boolean included;
        @jakarta.validation.constraints.Pattern(regexp = "ALLOW|FX_REQUIRED|FX_LOCKED", message = "fxControlMode는 ALLOW, FX_REQUIRED, FX_LOCKED 중 하나")
        private String fxControlMode;
    }
}
