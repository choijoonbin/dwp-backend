package com.dwp.services.synapsex.service.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisQualitySignalMapperTest {

    private final AnalysisQualitySignalMapper mapper = new AnalysisQualitySignalMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("analysis_quality_signals 직접 수신 시 distinct+우선순위 정렬+정상 제거 규칙 적용")
    void directSignalsNormalized() {
        ArrayNode directSignals = objectMapper.createArrayNode()
                .add("정상")
                .add("근거 커버리지 낮음")
                .add("근거 커버리지 낮음")
                .add("규정 판단 불일치");

        ArrayNode result = mapper.buildSignals(null, directSignals);

        assertThat(result).extracting(n -> n.asText())
                .containsExactly("근거 커버리지 낮음", "규정 판단 불일치");
    }

    @Test
    @DisplayName("quality_gate_codes만 존재 시 fallback 매핑 + 신규코드 기타(<코드>) 보존")
    void fallbackMappingWithUnknownCode() {
        ArrayNode qualityGateCodes = objectMapper.createArrayNode()
                .add("OK")
                .add("EVIDENCE_COVERAGE_LOW")
                .add("NEW_UNSEEN_CODE")
                .add("RAG_ZERO")
                .add("RAG_ZERO");

        ArrayNode result = mapper.buildSignals(qualityGateCodes, null);

        assertThat(result).extracting(n -> n.asText())
                .containsExactly("규정 검색 실패", "근거 커버리지 낮음", "기타(NEW_UNSEEN_CODE)");
    }

    @Test
    @DisplayName("과거 데이터(backfill) 케이스: 코드/신호 모두 없으면 정상 1개")
    void emptyInputDefaultsToNormal() {
        ArrayNode result = mapper.buildSignals(objectMapper.createArrayNode(), null);
        assertThat(result).extracting(n -> n.asText()).containsExactly("정상");
    }
}
