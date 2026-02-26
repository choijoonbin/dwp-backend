package com.dwp.services.synapsex.service.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * quality_gate_codes(내부 코드) <-> analysis_quality_signals(표시 신호) 변환/정규화 유틸.
 */
@Component
public class AnalysisQualitySignalMapper {

    private static final String NORMAL_LABEL = "정상";

    private static final Map<String, String> CODE_TO_LABEL = Map.ofEntries(
            Map.entry("OK", NORMAL_LABEL),
            Map.entry("EVIDENCE_MISSING", "근거 데이터 없음"),
            Map.entry("RAG_ZERO", "규정 검색 실패"),
            Map.entry("INPUT_PARTIAL", "입력 데이터 일부 누락"),
            Map.entry("POLICY_CONFLICT", "규정 판단 불일치"),
            Map.entry("POLICY_CONFLICT_DETECTED", "규정 판단 불일치"),
            Map.entry("POLICY_REEVAL_APPLIED", "정책 재검토 적용"),
            Map.entry("RISK_ARTICLE_MISMATCH", "위험유형-조항 불일치"),
            Map.entry("SENTENCE_CITATION_MISSING", "문장 근거 미연결"),
            Map.entry("EVIDENCE_COVERAGE_LOW", "근거 커버리지 낮음"),
            Map.entry("FACT_CONTEXT_PARTIAL", "사실 컨텍스트 일부 누락")
    );

    // 우선순위(낮을수록 먼저)
    private static final Map<String, Integer> LABEL_PRIORITY = Map.ofEntries(
            Map.entry("근거 데이터 없음", 1),
            Map.entry("규정 검색 실패", 2),
            Map.entry("입력 데이터 일부 누락", 3),
            Map.entry("문장 근거 미연결", 4),
            Map.entry("근거 커버리지 낮음", 5),
            Map.entry("위험유형-조항 불일치", 6),
            Map.entry("규정 판단 불일치", 7),
            Map.entry("정책 재검토 적용", 8),
            Map.entry("사실 컨텍스트 일부 누락", 9),
            Map.entry(NORMAL_LABEL, 10)
    );

    public ArrayNode normalizeCodes(JsonNode qualityGateCodes) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (qualityGateCodes == null || qualityGateCodes.isNull() || !qualityGateCodes.isArray()) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonNode n : qualityGateCodes) {
            if (n == null || n.isNull() || !n.isTextual()) continue;
            String v = n.asText().trim();
            if (v.isEmpty() || !seen.add(v)) continue;
            out.add(v);
        }
        return out;
    }

    public ArrayNode buildSignals(JsonNode qualityGateCodes, JsonNode analysisQualitySignals) {
        List<String> rawSignals = new ArrayList<>();

        if (analysisQualitySignals != null && analysisQualitySignals.isArray()) {
            for (JsonNode n : analysisQualitySignals) {
                if (n == null || n.isNull() || !n.isTextual()) continue;
                String v = n.asText().trim();
                if (!v.isEmpty()) rawSignals.add(v);
            }
        } else {
            ArrayNode normalizedCodes = normalizeCodes(qualityGateCodes);
            for (JsonNode n : normalizedCodes) {
                String code = n.asText();
                rawSignals.add(CODE_TO_LABEL.getOrDefault(code, "기타(" + code + ")"));
            }
        }
        return normalizeSignals(rawSignals);
    }

    private ArrayNode normalizeSignals(List<String> rawSignals) {
        // distinct
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String s : rawSignals) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) dedup.add(t);
        }

        // OK(정상) 처리: 다른 신호가 있으면 제거. 비어있으면 정상 1개 보장.
        if (dedup.isEmpty()) {
            dedup.add(NORMAL_LABEL);
        } else if (dedup.size() > 1) {
            dedup.remove(NORMAL_LABEL);
        }

        List<String> sorted = new ArrayList<>(dedup);
        sorted.sort((a, b) -> {
            int pa = LABEL_PRIORITY.getOrDefault(a, 99);
            int pb = LABEL_PRIORITY.getOrDefault(b, 99);
            if (pa != pb) return Integer.compare(pa, pb);
            return a.compareTo(b);
        });

        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        sorted.forEach(out::add);
        return out;
    }
}
