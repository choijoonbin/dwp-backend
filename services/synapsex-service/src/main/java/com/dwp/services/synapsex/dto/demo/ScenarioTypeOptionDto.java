package com.dwp.services.synapsex.dto.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 시연 시나리오 유형 1건 (프론트 드롭다운용).
 * GET /synapse/demo/scenario-types 응답 항목.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "시연 시나리오 유형 코드·라벨")
public class ScenarioTypeOptionDto {

    @Schema(description = "API 요청 시 사용할 코드값", example = "SPLIT_PAYMENT")
    private String code;

    @Schema(description = "표시용 한글 라벨", example = "한도 우회 분할 결제 의심")
    private String label;
}
