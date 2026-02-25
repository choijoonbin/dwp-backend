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
    @Schema(description = "생성 건수(1 이상, 미지정 시 1). total_count 별칭 수용.", example = "1", defaultValue = "1")
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
        return scenarioType != null ? scenarioType : ScenarioType.DEFAULT;
    }

    /** 강도. 미지정 시 위반 시나리오면 VIOLATION, 아니면 NORMAL. */
    public Intensity getIntensity() {
        if (intensity != null) return intensity;
        return getScenarioType() == ScenarioType.DEFAULT ? Intensity.NORMAL : Intensity.VIOLATION;
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
        /** 휴일/심야 사적 유용 의심 (기존 LATE_NIGHT, WEEKEND_MEAL 매핑) */
        HOLIDAY_USAGE,
        /** 중복 청구 및 분할 결제 의심 */
        DUPLICATE_SUSPECT,
        /** 한도 우회 분할 결제 의심 — 동일일·동일 가맹점·유사 금액 2~3건 쌍 생성 */
        SPLIT_PAYMENT,
        /** 가맹점 성격 업무 무관 */
        PRIVATE_USE_RISK,
        /** 지출 한도·가이드라인 초과 (기존 OVER_LIMIT 매핑) */
        LIMIT_EXCEED,
        /** 이상 거래 패턴 */
        UNUSUAL_PATTERN,
        /** 정상 케이스 (규정 준수) */
        DEFAULT,
        // 하위 호환 별칭 (API 수신 시 위 enum으로 매핑)
        @Deprecated LATE_NIGHT,
        @Deprecated WEEKEND_MEAL,
        @Deprecated OVER_LIMIT,
        @Deprecated NORMAL;

        @JsonCreator
        public static ScenarioType fromString(String value) {
            if (value == null || value.isBlank()) return null;
            String upper = value.trim().toUpperCase().replace(' ', '_');
            for (ScenarioType t : values()) {
                if (t.name().equals(upper)) return t;
                if (t.name().replace("_", "").equals(upper.replace("_", ""))) return t;
            }
            if ("SPLITPAYMENT".equals(upper) || "SPLIT_PAYMENT".equals(upper)) return SPLIT_PAYMENT;
            if ("LATENIGHT".equals(upper) || "LATE_NIGHT".equals(upper)) return HOLIDAY_USAGE;
            if ("WEEKENDMEAL".equals(upper) || "WEEKEND_MEAL".equals(upper)) return HOLIDAY_USAGE;
            if ("OVERLIMIT".equals(upper) || "OVER_LIMIT".equals(upper)) return LIMIT_EXCEED;
            if ("NORMAL".equals(upper)) return DEFAULT;
            return null;
        }

        /** 7개 표준 코드만 반환 (프론트 드롭다운용). DEPRECATED 제외 */
        public static ScenarioType[] standardCodes() {
            return new ScenarioType[]{
                    HOLIDAY_USAGE, DUPLICATE_SUSPECT, SPLIT_PAYMENT, PRIVATE_USE_RISK,
                    LIMIT_EXCEED, UNUSUAL_PATTERN, DEFAULT
            };
        }

        /** 표시용 한글 라벨 (프론트 드롭다운). */
        public String getLabel() {
            return switch (this) {
                case HOLIDAY_USAGE -> "휴일/심야 사적 유용 의심";
                case DUPLICATE_SUSPECT -> "중복 청구 및 분할 결제 의심";
                case SPLIT_PAYMENT -> "한도 우회 분할 결제 의심";
                case PRIVATE_USE_RISK -> "가맹점 성격 업무 무관";
                case LIMIT_EXCEED -> "지출 한도·가이드라인 초과";
                case UNUSUAL_PATTERN -> "이상 거래 패턴";
                case DEFAULT -> "기타";
                default -> name();
            };
        }
    }
}
