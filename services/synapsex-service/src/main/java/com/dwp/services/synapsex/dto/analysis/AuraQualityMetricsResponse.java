package com.dwp.services.synapsex.dto.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuraQualityMetricsResponse {
    @Schema(description = "집계 시작 시각(UTC)")
    private Instant from;
    @Schema(description = "집계 종료 시각(UTC)")
    private Instant to;
    @Schema(description = "분모. 기간 내 분석 결과 건수(케이스/run 집계 단위)")
    private Long totalCount;
    @Schema(description = "문장 근거 미연결 건수")
    private Long sentenceCitationMissingCount;
    @Schema(description = "근거 커버리지 부족 건수")
    private Long evidenceCoverageLowCount;
    @Schema(description = "정책 재검토 적용 건수")
    private Long policyReevalAppliedCount;
    @Schema(description = "RAG 근거 0건 건수")
    private Long ragZeroCount;
    @Schema(description = "문장 근거 미연결 비율 = sentenceCitationMissingCount / totalCount")
    private BigDecimal sentenceCitationMissingRatio;
    @Schema(description = "근거 커버리지 부족 비율 = evidenceCoverageLowCount / totalCount")
    private BigDecimal evidenceCoverageLowRatio;
    @Schema(description = "정책 재검토 적용 비율 = policyReevalAppliedCount / totalCount")
    private BigDecimal policyReevalAppliedRatio;
    @Schema(description = "RAG 근거 0건 비율 = ragZeroCount / totalCount")
    private BigDecimal ragZeroRatio;
}
