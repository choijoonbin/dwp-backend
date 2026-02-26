package com.dwp.services.mcp.service;

import com.dwp.services.mcp.dto.mcp.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BigDecimal DEFAULT_ZERO_RATE_THRESHOLD = new BigDecimal("0.2000");
    private static final BigDecimal DEFAULT_HIT_AT_K_THRESHOLD = new BigDecimal("0.7000");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PolicyLookupResult policyLookup(Long tenantId, PolicyLookupRequest request) {
        // 정책 시점판정은 KST(Asia/Seoul) 기준으로 고정
        LocalDate effectiveAt = request.getEffectiveAt() != null
                ? request.getEffectiveAt().atZone(KST).toLocalDate()
                : LocalDate.now(KST);

        String sql = """
                SELECT c.doc_id, c.chunk_id, c.regulation_article, c.regulation_clause, c.version,
                       c.effective_from, c.effective_to, c.is_active, c.chunk_text
                FROM dwp_aura.rag_chunk c
                JOIN dwp_aura.rag_document d ON d.doc_id = c.doc_id AND d.tenant_id = c.tenant_id
                WHERE c.tenant_id = ?
                  AND c.is_active = true
                  AND d.lifecycle_status = 'ACTIVE'
                  AND (? IS NULL OR c.regulation_article ILIKE CONCAT('%%', ?, '%%'))
                  AND (? IS NULL OR c.regulation_clause ILIKE CONCAT('%%', ?, '%%'))
                  AND (d.effective_from IS NULL OR d.effective_from <= ?)
                  AND (d.effective_to IS NULL OR d.effective_to >= ?)
                  AND (c.effective_from IS NULL OR c.effective_from <= ?)
                  AND (c.effective_to IS NULL OR c.effective_to >= ?)
                ORDER BY c.regulation_article, c.regulation_clause, c.chunk_index, c.chunk_id
                LIMIT 100
                """;

        List<PolicyLookupResult.PolicyItem> items = jdbcTemplate.query(sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setString(2, trimToNull(request.getArticle()));
            ps.setString(3, trimToNull(request.getArticle()));
            ps.setString(4, trimToNull(request.getClause()));
            ps.setString(5, trimToNull(request.getClause()));
            ps.setDate(6, Date.valueOf(effectiveAt));
            ps.setDate(7, Date.valueOf(effectiveAt));
            ps.setDate(8, Date.valueOf(effectiveAt));
            ps.setDate(9, Date.valueOf(effectiveAt));
        }, (rs, rowNum) -> PolicyLookupResult.PolicyItem.builder()
                .docId(rs.getLong("doc_id"))
                .chunkId(rs.getLong("chunk_id"))
                .article(rs.getString("regulation_article"))
                .clause(rs.getString("regulation_clause"))
                .version(rs.getString("version"))
                .effectiveFrom(rs.getDate("effective_from") != null ? rs.getDate("effective_from").toLocalDate() : null)
                .effectiveTo(rs.getDate("effective_to") != null ? rs.getDate("effective_to").toLocalDate() : null)
                .isActive(rs.getBoolean("is_active"))
                .text(rs.getString("chunk_text"))
                .build());

        return PolicyLookupResult.builder()
                .article(request.getArticle())
                .clause(request.getClause())
                .effectiveAt(effectiveAt)
                .count(items.size())
                .items(items)
                .build();
    }

    public BusinessCalendarResult businessCalendar(Long tenantId, BusinessCalendarRequest request) {
        LocalDate eventDate = request.getOccurredAt() != null
                ? request.getOccurredAt().atZone(KST).toLocalDate()
                : LocalDate.now(KST);
        Map<String, Object> row = jdbcTemplate.query("""
                        SELECT status_code FROM dwp_aura.user_hr_calendar
                        WHERE tenant_id = ? AND user_id = ? AND event_date = ?
                        LIMIT 1
                        """,
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, request.getUserId());
                    ps.setDate(3, Date.valueOf(eventDate));
                },
                rs -> rs.next() ? Map.of("status_code", rs.getString("status_code")) : null);

        String hrRaw = row != null ? (String) row.get("status_code") : null;
        String hr = normalizeHrStatus(hrRaw);
        boolean weekend = eventDate.getDayOfWeek() == DayOfWeek.SATURDAY || eventDate.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean isHoliday;
        String holidayType;
        String decisionSource;
        if (hrRaw != null) {
            String v = hrRaw.toUpperCase(Locale.ROOT);
            isHoliday = "OFF".equals(v);
            holidayType = isHoliday ? (weekend ? "WEEKEND" : "PUBLIC_HOLIDAY") : "NONE";
            decisionSource = "HR_CALENDAR";
        } else {
            isHoliday = weekend;
            holidayType = weekend ? "WEEKEND" : "NONE";
            decisionSource = "WEEKEND_FALLBACK";
        }

        return BusinessCalendarResult.builder()
                .eventDate(eventDate)
                .hrStatusRaw(hrRaw)
                .hrStatus(hr)
                .isHoliday(isHoliday)
                .holidayType(holidayType)
                .decisionSource(decisionSource)
                .build();
    }

    public MasterDataNormalizeResult masterData(Long tenantId, MasterDataNormalizeRequest request) {
        MasterDataNormalizeResult.Mapping mcc = normalizeMcc(tenantId, request.getMccCode());
        MasterDataNormalizeResult.Mapping expenseType = normalizeExpenseType(request.getExpenseType());
        MasterDataNormalizeResult.Mapping hrStatus = normalizeHr(request.getHrStatus());
        return MasterDataNormalizeResult.builder()
                .mcc(mcc)
                .expenseType(expenseType)
                .hrStatus(hrStatus)
                .build();
    }

    public CaseContextResult caseContext(Long tenantId, CaseContextRequest request) {
        // 현재 SoT는 fi_doc_header.created_at 이며, occurred_at 컬럼이 도입되면 이 기준으로 전환한다.
        Instant occurredAt = request.getOccurredAt() != null ? request.getOccurredAt() : Instant.now();
        Instant from10m = occurredAt.minusSeconds(10 * 60L);
        Instant from24h = occurredAt.minusSeconds(24 * 60 * 60L);
        Instant from30d = occurredAt.minusSeconds(30L * 24 * 60 * 60);
        Long userId = request.getUserId();
        String merchant = trimToNull(request.getMerchantName());
        String source = "INPUT";

        if ((userId == null || merchant == null) && request.getCaseId() != null) {
            Map<String, Object> row = jdbcTemplate.query("""
                            SELECT c.user_id, h.bktxt, h.xblnr
                            FROM dwp_aura.agent_case c
                            LEFT JOIN dwp_aura.fi_doc_header h
                              ON h.tenant_id = c.tenant_id
                             AND h.bukrs = c.bukrs
                             AND h.belnr = c.belnr
                             AND h.gjahr = c.gjahr
                            WHERE c.tenant_id = ? AND c.case_id = ?
                            LIMIT 1
                            """,
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setLong(2, request.getCaseId());
                    },
                    rs -> rs.next() ? Map.of(
                            "user_id", rs.getObject("user_id"),
                            "bktxt", rs.getString("bktxt"),
                            "xblnr", rs.getString("xblnr")) : null);
            if (row != null) {
                if (userId == null && row.get("user_id") != null) userId = ((Number) row.get("user_id")).longValue();
                if (merchant == null) merchant = trimToNull((String) row.get("bktxt")) != null ? (String) row.get("bktxt") : trimToNull((String) row.get("xblnr"));
                source = "CASE_CONTEXT";
            }
        }

        Integer c10 = 0;
        Integer c24 = 0;
        BigDecimal baseline = BigDecimal.ZERO;
        if (userId != null) {
            c10 = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dwp_aura.fi_doc_header
                    WHERE tenant_id = ? AND user_id = ? AND created_at >= ? AND created_at <= ?
                    """, Integer.class, tenantId, userId, Timestamp.from(from10m), Timestamp.from(occurredAt));
            baseline = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(AVG(t.doc_amount), 0)
                    FROM (
                      SELECT h.bukrs, h.belnr, h.gjahr, COALESCE(SUM(ABS(i.wrbtr)),0) AS doc_amount
                      FROM dwp_aura.fi_doc_header h
                      LEFT JOIN dwp_aura.fi_doc_item i
                        ON i.tenant_id = h.tenant_id AND i.bukrs = h.bukrs AND i.belnr = h.belnr AND i.gjahr = h.gjahr
                      WHERE h.tenant_id = ? AND h.user_id = ? AND h.created_at >= ? AND h.created_at < ?
                      GROUP BY h.bukrs, h.belnr, h.gjahr
                    ) t
                    """, BigDecimal.class, tenantId, userId, Timestamp.from(from30d), Timestamp.from(occurredAt));
        }
        if (userId != null && merchant != null) {
            c24 = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dwp_aura.fi_doc_header
                    WHERE tenant_id = ? AND user_id = ? AND created_at >= ? AND created_at <= ?
                      AND LOWER(COALESCE(NULLIF(TRIM(bktxt), ''), NULLIF(TRIM(xblnr), ''), '')) = LOWER(?)
                    """, Integer.class, tenantId, userId, Timestamp.from(from24h), Timestamp.from(occurredAt), merchant);
        }
        return CaseContextResult.builder()
                .window10mTxnCount(c10 != null ? c10 : 0)
                .window24hSameMerchantCount(c24 != null ? c24 : 0)
                .user30dBaselineAmount(baseline != null ? baseline : BigDecimal.ZERO)
                .decisionSource(source)
                .build();
    }

    public EvidenceVerificationResult evidenceVerification(Long tenantId, EvidenceVerificationRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> reqIds = request.getCitationIds() != null ? request.getCitationIds() : List.of();
        if (reqIds.isEmpty()) reasons.add("EMPTY_CITATIONS");

        UUID runId = resolveRunId(tenantId, request.getCaseId(), request.getRunId());
        if (runId == null) reasons.add("NO_ANALYSIS_RESULT");
        JsonNode ragRefs = runId != null ? queryResultJsonByRunId(tenantId, runId, "rag_refs_json") : null;

        Set<String> availableIds = collectCitationIds(ragRefs);
        int matched = 0;
        for (String id : reqIds) {
            Set<String> normalizedRequestIds = normalizeCitationIds(id);
            if (normalizedRequestIds.stream().anyMatch(availableIds::contains)) matched++;
        }
        if (matched < reqIds.size()) reasons.add("CITATION_NOT_FOUND");

        boolean articleMatched = isArticleMatched(request.getArticle(), ragRefs);
        if (trimToNull(request.getArticle()) != null && !articleMatched) reasons.add("ARTICLE_MISMATCH");

        boolean valid = reasons.isEmpty();
        return EvidenceVerificationResult.builder()
                .isValid(valid)
                .mismatchReasons(reasons)
                .resolvedRunId(runId)
                .matchedCitationCount(matched)
                .requestedCitationCount(reqIds.size())
                .articleMatched(articleMatched)
                .decisionCode(valid ? "OK" : (reasons.contains("CITATION_NOT_FOUND") ? "POLICY_CONFLICT" : "EVIDENCE_MISSING"))
                .evidenceHash(buildHash(request))
                .build();
    }

    public RagConflictDiagnosticsResult ragConflictDiagnostics(Long tenantId, RagConflictDiagnosticsRequest request) {
        UUID runId = resolveRunId(tenantId, request.getCaseId(), request.getRunId());
        JsonNode ragRefs = runId != null ? queryResultJsonByRunId(tenantId, runId, "rag_refs_json") : null;
        JsonNode qualityGateCodesNode = runId != null ? queryResultJsonByRunId(tenantId, runId, "quality_gate_codes") : null;
        String reasonText = runId != null ? jdbcTemplate.query("""
                        SELECT reason_text
                        FROM dwp_aura.case_analysis_result
                        WHERE run_id = ? AND tenant_id = ?
                        LIMIT 1
                        """,
                ps -> {
                    ps.setObject(1, runId);
                    ps.setLong(2, tenantId);
                },
                rs -> rs.next() ? rs.getString("reason_text") : null) : null;
        String caseType = request.getCaseId() != null ? jdbcTemplate.query("""
                        SELECT case_type FROM dwp_aura.agent_case WHERE tenant_id = ? AND case_id = ? LIMIT 1
                        """,
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, request.getCaseId());
                },
                rs -> rs.next() ? rs.getString("case_type") : null) : null;

        List<String> qualityGateCodes = readStringList(qualityGateCodesNode);
        boolean ragZero = qualityGateCodes.contains("RAG_ZERO");
        boolean sentenceMissing = qualityGateCodes.contains("SENTENCE_CITATION_MISSING");
        boolean reeval = qualityGateCodes.contains("POLICY_REEVAL_APPLIED");
        boolean ragHasRefs = ragRefs != null && ragRefs.isArray() && ragRefs.size() > 0;

        List<String> reasons = new ArrayList<>();
        if (caseType != null && !"DEFAULT".equalsIgnoreCase(caseType) && !ragHasRefs) reasons.add("NO_RAG_REFERENCE_FOR_POLICY_CASE");
        if (ragZero) reasons.add("RAG_ZERO");
        if (sentenceMissing) reasons.add("SENTENCE_CITATION_MISSING");

        boolean conflict = !reasons.isEmpty();
        return RagConflictDiagnosticsResult.builder()
                .policyRagConflict(conflict)
                .conflictReasons(reasons)
                .caseType(caseType)
                .reasonText(reasonText)
                .resolvedRunId(runId)
                .qualityGateCodes(qualityGateCodes)
                .ragHasReferences(ragHasRefs)
                .policyReevalApplied(reeval)
                .decisionCode(conflict ? "POLICY_CONFLICT" : "OK")
                .build();
    }

    public EvalGateLatestResult latestEvalGate(Long tenantId) {
        return jdbcTemplate.query("""
                        SELECT id, run_key, zero_rate, hit_at_k, strict_hit_top1, total_cases, gate_passed, created_at
                        FROM dwp_aura.rag_eval_run
                        WHERE tenant_id = ?
                        ORDER BY created_at DESC
                        LIMIT 1
                        """,
                ps -> ps.setLong(1, tenantId),
                rs -> {
                    if (!rs.next()) return null;
                    BigDecimal zero = rs.getBigDecimal("zero_rate");
                    BigDecimal hit = rs.getBigDecimal("hit_at_k");
                    boolean computed = zero != null && hit != null
                            && zero.compareTo(DEFAULT_ZERO_RATE_THRESHOLD) <= 0
                            && hit.compareTo(DEFAULT_HIT_AT_K_THRESHOLD) >= 0;
                    return EvalGateLatestResult.builder()
                            .evalRunId(rs.getLong("id"))
                            .runKey(rs.getString("run_key"))
                            .zeroRate(zero)
                            .hitAtK(hit)
                            .strictHitTop1(rs.getBigDecimal("strict_hit_top1"))
                            .totalCases(rs.getInt("total_cases"))
                            .persistedGatePassed(rs.getBoolean("gate_passed"))
                            .computedGatePassed(computed)
                            .thresholdZeroRate(DEFAULT_ZERO_RATE_THRESHOLD)
                            .thresholdHitAtK(DEFAULT_HIT_AT_K_THRESHOLD)
                            .createdAt(rs.getTimestamp("created_at").toInstant())
                            .build();
                });
    }

    private UUID resolveRunId(Long tenantId, Long caseId, UUID inputRunId) {
        if (inputRunId != null) {
            return jdbcTemplate.query("""
                            SELECT ar.run_id
                            FROM dwp_aura.case_analysis_result ar
                            JOIN dwp_aura.case_analysis_run r ON r.run_id = ar.run_id
                            WHERE ar.tenant_id = ?
                              AND r.tenant_id = ?
                              AND ar.run_id = ?
                              AND (? IS NULL OR r.case_id = ?)
                            LIMIT 1
                            """,
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setLong(2, tenantId);
                        ps.setObject(3, inputRunId);
                        if (caseId == null) {
                            ps.setObject(4, null);
                            ps.setObject(5, null);
                        } else {
                            ps.setLong(4, caseId);
                            ps.setLong(5, caseId);
                        }
                    },
                    rs -> rs.next() ? UUID.fromString(rs.getString("run_id")) : null);
        }
        if (caseId == null) return null;
        return jdbcTemplate.query("""
                        SELECT r.run_id
                        FROM dwp_aura.case_analysis_run r
                        JOIN dwp_aura.case_analysis_result ar ON ar.run_id = r.run_id
                        WHERE r.tenant_id = ? AND r.case_id = ?
                        ORDER BY r.started_at DESC
                        LIMIT 1
                        """,
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setLong(2, caseId);
                },
                rs -> rs.next() ? UUID.fromString(rs.getString("run_id")) : null);
    }

    private JsonNode queryResultJsonByRunId(Long tenantId, UUID runId, String columnName) {
        String sql = "SELECT " + columnName + " FROM dwp_aura.case_analysis_result WHERE tenant_id = ? AND run_id = ? LIMIT 1";
        String raw = jdbcTemplate.query(sql, ps -> {
            ps.setLong(1, tenantId);
            ps.setObject(2, runId);
        }, rs -> rs.next() ? rs.getString(1) : null);
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Failed to parse JSON field: {}", e.getMessage());
            return null;
        }
    }

    private MasterDataNormalizeResult.Mapping normalizeMcc(Long tenantId, String raw) {
        String normalized = normalizeMccCode(raw);
        String name = null;
        String source = "RULE";
        String confidence = "LOW";
        if (normalized != null && !normalized.isBlank()) {
            name = jdbcTemplate.query("""
                            SELECT mcc_name FROM dwp_aura.mcc_master
                            WHERE tenant_id = ? AND mcc_code = ?
                            LIMIT 1
                            """,
                    ps -> {
                        ps.setLong(1, tenantId);
                        ps.setString(2, normalized);
                    },
                    rs -> rs.next() ? rs.getString("mcc_name") : null);
            if (name != null) {
                source = "MCC_MASTER";
                confidence = "HIGH";
            } else if (normalized.matches("\\d{4}")) {
                confidence = "MEDIUM";
            }
        }
        return MasterDataNormalizeResult.Mapping.builder()
                .raw(raw)
                .normalized(normalized)
                .normalizedName(name)
                .mappingConfidence(confidence)
                .source(source)
                .build();
    }

    private MasterDataNormalizeResult.Mapping normalizeExpenseType(String raw) {
        String normalized = raw != null ? raw.trim().toUpperCase(Locale.ROOT) : null;
        String source = "RULE";
        String confidence = "LOW";
        String name = null;
        if (normalized != null && !normalized.isBlank()) {
            name = jdbcTemplate.query("""
                            SELECT name FROM dwp_aura.app_codes
                            WHERE group_key IN ('EXPENSE_TYPE','DOC_TYPE','BLART_TYPE')
                              AND code = ?
                              AND is_active = true
                            ORDER BY CASE group_key WHEN 'EXPENSE_TYPE' THEN 1 WHEN 'DOC_TYPE' THEN 2 ELSE 3 END
                            LIMIT 1
                            """,
                    ps -> ps.setString(1, normalized),
                    rs -> rs.next() ? rs.getString("name") : null);
            if (name != null) {
                source = "APP_CODES";
                confidence = "HIGH";
            } else {
                name = switch (normalized) {
                    case "SA" -> "G/L Account Document";
                    case "KR" -> "Vendor Invoice";
                    case "DR" -> "Customer Invoice";
                    default -> normalized;
                };
                confidence = "MEDIUM";
            }
        }
        return MasterDataNormalizeResult.Mapping.builder()
                .raw(raw)
                .normalized(normalized)
                .normalizedName(name)
                .mappingConfidence(confidence)
                .source(source)
                .build();
    }

    private MasterDataNormalizeResult.Mapping normalizeHr(String raw) {
        String normalized = normalizeHrStatus(raw);
        String confidence = normalized == null ? "LOW" : ("WORK".equals(normalized) || "LEAVE".equals(normalized) ? "HIGH" : "MEDIUM");
        return MasterDataNormalizeResult.Mapping.builder()
                .raw(raw)
                .normalized(normalized)
                .normalizedName(normalized)
                .mappingConfidence(confidence)
                .source("RULE")
                .build();
    }

    private String normalizeHrStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "WORKING", "BUSINESS_TRIP", "WORK" -> "WORK";
            case "VACATION", "OFF", "LEAVE" -> "LEAVE";
            default -> v;
        };
    }

    private String normalizeMccCode(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (v.matches("\\d{4}")) return v;
        return switch (v) {
            case "BAR", "PUB" -> "5813";
            case "GOLF", "GOLF_CLUB", "GOLFCOURSE" -> "7992";
            case "RESTAURANT", "DINING" -> "5812";
            case "FASTFOOD", "FAST_FOOD" -> "5814";
            default -> v;
        };
    }

    private Set<String> collectCitationIds(JsonNode ragRefs) {
        Set<String> ids = new HashSet<>();
        if (ragRefs == null || !ragRefs.isArray()) return ids;
        for (JsonNode n : ragRefs) {
            String citationId = textAny(n, "citation_id", "citationId");
            if (citationId != null) ids.addAll(normalizeCitationIds(citationId));
            String chunkId = textAny(n, "chunk_id", "chunkId");
            if (chunkId != null) ids.addAll(normalizeCitationIds(chunkId));
        }
        return ids;
    }

    private Set<String> normalizeCitationIds(String raw) {
        Set<String> normalized = new HashSet<>();
        String id = trimToNull(raw);
        if (id == null) return normalized;
        String upper = id.toUpperCase(Locale.ROOT);
        normalized.add(upper);
        if (upper.startsWith("C") && upper.length() > 1) {
            String numeric = upper.substring(1);
            if (numeric.matches("\\d+")) normalized.add(numeric);
        }
        if (upper.matches("\\d+")) normalized.add("C" + upper);
        return normalized;
    }

    private boolean isArticleMatched(String article, JsonNode ragRefs) {
        String expected = trimToNull(article);
        if (expected == null) return true;
        if (ragRefs == null || !ragRefs.isArray()) return false;
        for (JsonNode n : ragRefs) {
            String actual = textAny(n, "article", "regulation_article", "regulationArticle");
            if (actual != null && (actual.contains(expected) || expected.contains(actual))) return true;
        }
        return false;
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            List<String> out = new ArrayList<>();
            node.forEach(v -> {
                if (!v.isNull()) out.add(v.asText());
            });
            return out;
        }
        if (node.isTextual()) return List.of(node.asText());
        return List.of();
    }

    private String textAny(JsonNode node, String... keys) {
        if (node == null || node.isNull()) return null;
        for (String key : keys) {
            JsonNode child = node.get(key);
            if (child != null && !child.isNull()) {
                String v = trimToNull(child.asText());
                if (v != null) return v;
            }
        }
        return null;
    }

    private String buildHash(Object obj) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(objectMapper.writeValueAsBytes(obj));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(String.valueOf(obj).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
