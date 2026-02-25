package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Aura 배치 스크리닝 응답 (객체 형식).
 * 기존 배열 응답에서 변경: results + briefing_priority_case_id, briefing_insight.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScreenBatchResponse {

    /** 요청 순서와 동일한 스크리닝 결과 목록 */
    private List<DetectScreenResponse> results;

    /** FE 워크벤치에서 주목할 우선 케이스 ID (Aura가 지정). 없으면 null */
    @JsonProperty("briefing_priority_case_id")
    @JsonAlias("briefingPriorityCaseId")
    private Long briefingPriorityCaseId;

    /** 우선 검토 인사이트 요약 (Aura가 제공). 없으면 null */
    @JsonProperty("briefing_insight")
    @JsonAlias("briefingInsight")
    private String briefingInsight;
}
