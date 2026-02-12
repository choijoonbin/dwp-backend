package com.dwp.services.synapsex.dto.demo;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 시연용 위반/정상 시나리오 데이터 생성 요청 (generate_demo_scenario).
 * camelCase·snake_case 모두 수용 (예: scenarioType / scenario_type).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "시연 위반 시나리오 생성 요청")
public class GenerateViolationRequest {

    @JsonAlias("scenario_type")
    @Schema(description = "시나리오 유형 (미지정 시 NORMAL)", example = "WEEKEND_MEAL")
    private ScenarioType scenarioType;

    @Schema(description = "강도: VIOLATION/WARNING=규정 150%~500% 랜덤, NORMAL=규정 50%~90% 랜덤. 미지정 시 scenarioType으로 유추.", example = "VIOLATION")
    private Intensity intensity;

    @JsonAlias("total_count")
    @Builder.Default
    @Schema(description = "생성 건수(1~10, 미지정 시 1). total_count 별칭 수용.", example = "1", defaultValue = "1")
    private Integer count = 1;

    @JsonAlias("limit_amount_krw")
    @Schema(description = "규정 한도 금액(원). 미지정·0 이하면 30000 사용.", example = "30000")
    private Integer limitAmountKrw;

    @JsonAlias("amount_range_min")
    @Schema(description = "금액 범위 하한(원). amountRangeMax와 함께 지정 시 해당 구간 랜덤.", example = "10000")
    private Integer amountRangeMin;

    @JsonAlias("amount_range_max")
    @Schema(description = "금액 범위 상한(원). amountRangeMin과 함께 지정 시 해당 구간 랜덤.", example = "60000")
    private Integer amountRangeMax;

    /** 본문 미지정/빈 객체 시 기본 시나리오. */
    public ScenarioType getScenarioType() {
        return scenarioType != null ? scenarioType : ScenarioType.NORMAL;
    }

    /** 강도. 미지정 시 위반 시나리오면 VIOLATION, 아니면 NORMAL. */
    public Intensity getIntensity() {
        if (intensity != null) return intensity;
        return getScenarioType() == ScenarioType.NORMAL ? Intensity.NORMAL : Intensity.VIOLATION;
    }

    /** 규정 한도(원). 미지정 시 30000. */
    public int getLimitAmountKrwResolved() {
        return limitAmountKrw != null && limitAmountKrw >= 1000 ? limitAmountKrw : 30_000;
    }

    /** 금액 범위 사용 여부: min·max 모두 지정된 경우에만 사용. */
    public boolean hasAmountRange() {
        return amountRangeMin != null && amountRangeMax != null && amountRangeMin <= amountRangeMax;
    }

    @AssertTrue(message = "amountRangeMin은 amountRangeMax 이하여야 합니다")
    public boolean isAmountRangeValid() {
        if (amountRangeMin == null || amountRangeMax == null) return true;
        return amountRangeMin <= amountRangeMax;
    }

    public enum Intensity {
        /** 규정 위반: 금액을 규정의 150%~500% 사이 랜덤 (끝자리까지 랜덤화) */
        VIOLATION,
        /** 정상: 금액을 규정의 50%~90% 사이 랜덤 */
        NORMAL,
        /** 경고 수준 (FE 시연 메뉴 강도 옵션). 금액 해석은 VIOLATION과 동일(150%~500%). */
        WARNING;

        @JsonCreator
        public static Intensity fromString(String value) {
            if (value == null || value.isBlank()) return null;
            String upper = value.trim().toUpperCase();
            for (Intensity i : values()) {
                if (i.name().equals(upper)) return i;
            }
            return null;
        }
    }

    public enum ScenarioType {
        /** 제11조 ②항 위반: 주말 식대 지출 금지 — 토/일 랜덤, 적요 '팀내 주말 업무 식대' */
        WEEKEND_MEAL,
        /** 제14조 ②항 위반: 인당 한도 초과 — 1인 2만원 초과 (예: 2인 6만원), 적요 '대외 협력 미팅 식대' */
        OVER_LIMIT,
        /** 제11조 ②항 위반: 업무시간 외·비정상 업종 — 23:30~02:00, 주점/이자카야 */
        LATE_NIGHT,
        /** 정상 케이스 (규정 준수) */
        NORMAL,
        /** 분할 결제 시나리오 — 평일·업무시간 (클라이언트 scenario_type: split_payment 수용) */
        SPLIT_PAYMENT;

        @JsonCreator
        public static ScenarioType fromString(String value) {
            if (value == null || value.isBlank()) return null;
            String upper = value.trim().toUpperCase().replace(' ', '_');
            for (ScenarioType t : values()) {
                if (t.name().equals(upper)) return t;
                if (t.name().replace("_", "").equals(upper.replace("_", ""))) return t;
            }
            if ("SPLITPAYMENT".equals(upper) || "SPLIT_PAYMENT".equals(upper)) return SPLIT_PAYMENT;
            return null;
        }
    }
}
