package com.dwp.services.synapsex.dto.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 시연용 위반/정상 시나리오 데이터 생성 요청.
 * 규정집 기반: WEEKEND_MEAL(제11조②), OVER_LIMIT(제14조②), LATE_NIGHT(제11조②), NORMAL(정상).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "시연 위반 시나리오 생성 요청")
public class GenerateViolationRequest {

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "시나리오 유형", example = "WEEKEND_MEAL")
    private ScenarioType scenarioType;

    @Min(1)
    @Max(10)
    @Builder.Default
    @Schema(description = "생성 건수", example = "1", defaultValue = "1")
    private Integer count = 1;

    public enum ScenarioType {
        /** 제11조 ②항 위반: 주말 식대 지출 금지 — 토/일 랜덤, 적요 '팀내 주말 업무 식대' */
        WEEKEND_MEAL,
        /** 제14조 ②항 위반: 인당 한도 초과 — 1인 2만원 초과 (예: 2인 6만원), 적요 '대외 협력 미팅 식대' */
        OVER_LIMIT,
        /** 제11조 ②항 위반: 업무시간 외·비정상 업종 — 23:30~02:00, 주점/이자카야 */
        LATE_NIGHT,
        /** 정상 케이스 (규정 준수) */
        NORMAL
    }
}
