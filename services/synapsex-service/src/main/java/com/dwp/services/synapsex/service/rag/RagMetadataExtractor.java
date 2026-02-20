package com.dwp.services.synapsex.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 청크 메타데이터 추출기
 * 정규식 기반으로 regulation_article(조), regulation_clause(항) 추출
 */
@Slf4j
@Component
public class RagMetadataExtractor {

    private static final Pattern ARTICLE_PATTERN = Pattern.compile("제\\s*(\\d+)\\s*조");
    private static final Pattern CLAUSE_PATTERN = Pattern.compile("제?\\s*(\\d+)\\s*항");
    private static final Pattern SUBCLAUSE_PATTERN = Pattern.compile("제?\\s*(\\d+)\\s*호");

    /**
     * 청크 텍스트에서 regulation_article 추출
     * @param text 청크 텍스트
     * @return "제n조" 형식 (없으면 null)
     */
    public String extractArticle(String text) {
        if (text == null || text.isBlank()) return null;
        
        Matcher matcher = ARTICLE_PATTERN.matcher(text);
        if (matcher.find()) {
            return "제" + matcher.group(1) + "조";
        }
        return null;
    }

    /**
     * 청크 텍스트에서 regulation_clause 추출
     * @param text 청크 텍스트
     * @return "n항" 또는 "제n호" 형식 (없으면 null)
     */
    public String extractClause(String text) {
        if (text == null || text.isBlank()) return null;
        
        Matcher clauseMatcher = CLAUSE_PATTERN.matcher(text);
        if (clauseMatcher.find()) {
            return clauseMatcher.group(1) + "항";
        }
        
        Matcher subclauseMatcher = SUBCLAUSE_PATTERN.matcher(text);
        if (subclauseMatcher.find()) {
            return "제" + subclauseMatcher.group(1) + "호";
        }
        
        return null;
    }

    /**
     * 노드 타입 추론 (ARTICLE, CLAUSE, PARAGRAPH)
     * @param text 청크 텍스트
     * @return 노드 타입
     */
    public String inferNodeType(String text) {
        if (text == null || text.isBlank()) return "PARAGRAPH";
        
        String trimmed = text.trim();
        
        if (ARTICLE_PATTERN.matcher(trimmed.substring(0, Math.min(20, trimmed.length()))).find()) {
            return "ARTICLE";
        }
        
        if (CLAUSE_PATTERN.matcher(trimmed.substring(0, Math.min(20, trimmed.length()))).find() ||
            SUBCLAUSE_PATTERN.matcher(trimmed.substring(0, Math.min(20, trimmed.length()))).find()) {
            return "CLAUSE";
        }
        
        return "PARAGRAPH";
    }

    /**
     * 검색용 정제 텍스트 생성 (prefix 제거)
     * @param text 원본 청크 텍스트
     * @return 정제된 텍스트
     */
    public String cleanSearchText(String text) {
        if (text == null || text.isBlank()) return null;
        
        return text
                .replaceAll("^\\s*제\\s*\\d+\\s*조\\s*", "")
                .replaceAll("^\\s*제?\\s*\\d+\\s*항\\s*", "")
                .replaceAll("^\\s*제?\\s*\\d+\\s*호\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * 조항 문자열에서 숫자만 추출 (예: "제11조" → "11", "2항" → "2")
     */
    public static String extractNumber(String regulationStr) {
        if (regulationStr == null || regulationStr.isBlank()) return null;
        Matcher m = Pattern.compile("(\\d+)").matcher(regulationStr);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 청크 텍스트에서 조항 제목 추출 (조항 번호 이후 첫 줄)
     * 예: "제11조 AI 에이전트의 역할\n..." → "AI 에이전트의 역할"
     */
    public String extractTitle(String text) {
        if (text == null || text.isBlank()) return null;
        
        String cleaned = text.trim();
        cleaned = cleaned.replaceFirst("^제\\s*\\d+\\s*조\\s*", "");
        cleaned = cleaned.replaceFirst("^제?\\s*\\d+\\s*항\\s*", "");
        cleaned = cleaned.replaceFirst("^제?\\s*\\d+\\s*호\\s*", "");
        
        String[] lines = cleaned.split("\n", 2);
        String firstLine = lines[0].trim();
        
        if (firstLine.length() > 50) {
            firstLine = firstLine.substring(0, 50);
        }
        
        return firstLine.isBlank() ? null : firstLine;
    }

    /**
     * 청크 텍스트에서 모든 메타데이터 추출
     * @param text 청크 텍스트
     * @return 추출 결과
     */
    public ExtractionResult extract(String text) {
        return new ExtractionResult(
                extractArticle(text),
                extractClause(text),
                inferNodeType(text),
                cleanSearchText(text)
        );
    }

    public record ExtractionResult(
            String regulationArticle,
            String regulationClause,
            String nodeType,
            String searchText
    ) {}
}
