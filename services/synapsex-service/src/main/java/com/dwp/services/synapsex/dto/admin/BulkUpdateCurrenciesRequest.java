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
public class BulkUpdateCurrenciesRequest {
    @NotEmpty(message = "items는 비어있을 수 없습니다")
    @Valid
    private List<CurrencyItemDto> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrencyItemDto {
        @jakarta.validation.constraints.NotBlank(message = "waers는 필수입니다")
        @jakarta.validation.constraints.Pattern(regexp = "[A-Z]{3,5}", message = "waers는 3~5자리 대문자여야 합니다")
        private String waers;
        private Boolean enabled;
        @jakarta.validation.constraints.Pattern(regexp = "ALLOW|FX_REQUIRED|FX_LOCKED", message = "fxControlMode는 ALLOW, FX_REQUIRED, FX_LOCKED 중 하나")
        private String fxControlMode;
    }
}
