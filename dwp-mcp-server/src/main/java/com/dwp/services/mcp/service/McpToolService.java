package com.dwp.services.mcp.service;

import com.dwp.services.mcp.dto.mcp.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    @Value("${mcp.shadow.default-agent-mode:agentic_shadow}")
    private String defaultAgentMode;
    @Value("${mcp.shadow.default-model-version:unknown}")
    private String defaultModelVersion;
    @Value("${mcp.shadow.default-policy-version:unknown}")
    private String defaultPolicyVersion;

    @PostConstruct
    public void ensureShadowMetaTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dwp_aura.mcp_shadow_run_meta (
                  id BIGSERIAL PRIMARY KEY,
                  tenant_id BIGINT NOT NULL,
                  run_id UUID NOT NULL,
                  case_id BIGINT,
                  requested_agent_mode VARCHAR(30),
                  resolved_agent_mode VARCHAR(30) NOT NULL,
                  trace_id VARCHAR(120),
                  requested_model_version VARCHAR(120),
                  resolved_model_version VARCHAR(120) NOT NULL,
                  requested_policy_version VARCHAR(120),
                  resolved_policy_version VARCHAR(120) NOT NULL,
                  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ix_mcp_shadow_meta_tenant_created ON dwp_aura.mcp_shadow_run_meta(tenant_id, created_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS ix_mcp_shadow_meta_run ON dwp_aura.mcp_shadow_run_meta(run_id)");
    }

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
        Instant occurredAt = request.getOccurredAt() != null ? request.getOccurredAt() : Instant.now();
        Instant from10m = occurredAt.minusSeconds(10 * 60L);
        Instant from24h = occurredAt.minusSeconds(24 * 60 * 60L);
        Instant from30d = occurredAt.minusSeconds(30L * 24 * 60 * 60);
        Long userId = request.getUserId();
        String mccCode = normalizeMccCode(request.getMccCode());
        BigDecimal currentAmount = request.getAmount();
        String merchant = trimToNull(request.getMerchantName());
        String source = "INPUT";

        if ((userId == null || merchant == null) && request.getCaseId() != null) {
            Map<String, Object> row = jdbcTemplate.query("""
                            SELECT c.user_id, h.bktxt, h.xblnr, h.mcc_code
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
                    rs -> {
                        if (!rs.next()) return null;
                        Map<String, Object> out = new HashMap<>();
                        out.put("user_id", rs.getObject("user_id"));
                        out.put("bktxt", rs.getString("bktxt"));
                        out.put("xblnr", rs.getString("xblnr"));
                        out.put("mcc_code", rs.getString("mcc_code"));
                        return out;
                    });
            if (row != null) {
                if (userId == null && row.get("user_id") != null) userId = ((Number) row.get("user_id")).longValue();
                if (merchant == null) merchant = trimToNull((String) row.get("bktxt")) != null ? (String) row.get("bktxt") : trimToNull((String) row.get("xblnr"));
                if (mccCode == null) mccCode = normalizeMccCode((String) row.get("mcc_code"));
                source = "CASE_CONTEXT";
            }
        }

        if ((userId == null || mccCode == null || currentAmount == null) && request.getDocKey() != null) {
            String[] parts = request.getDocKey().split("-");
            if (parts.length >= 3) {
                String bukrs = parts[0];
                String belnr = parts[1];
                String gjahr = parts[2];
                Map<String, Object> row = jdbcTemplate.query("""
                                SELECT h.user_id, h.mcc_code, COALESCE(SUM(ABS(i.wrbtr)),0) AS doc_amount
                                FROM dwp_aura.fi_doc_header h
                                LEFT JOIN dwp_aura.fi_doc_item i
                                  ON i.tenant_id=h.tenant_id AND i.bukrs=h.bukrs AND i.belnr=h.belnr AND i.gjahr=h.gjahr
                                WHERE h.tenant_id=? AND h.bukrs=? AND h.belnr=? AND h.gjahr=?
                                GROUP BY h.user_id, h.mcc_code
                                LIMIT 1
                                """,
                        ps -> {
                            ps.setLong(1, tenantId);
                            ps.setString(2, bukrs);
                            ps.setString(3, belnr);
                            ps.setString(4, gjahr);
                        },
                        rs -> {
                            if (!rs.next()) return null;
                            Map<String, Object> out = new HashMap<>();
                            out.put("user_id", rs.getObject("user_id"));
                            out.put("mcc_code", rs.getString("mcc_code"));
                            out.put("doc_amount", rs.getBigDecimal("doc_amount"));
                            return out;
                        });
                if (row != null) {
                    if (userId == null && row.get("user_id") instanceof Number n) userId = n.longValue();
                    if (mccCode == null) mccCode = normalizeMccCode((String) row.get("mcc_code"));
                    if (currentAmount == null && row.get("doc_amount") instanceof BigDecimal b) currentAmount = b;
                    source = "DOC_KEY";
                }
            }
        }

        Integer c10 = 0;
        Integer c24Txn = 0;
        Integer c30Txn = 0;
        Integer c24 = 0;
        BigDecimal baseline = BigDecimal.ZERO;
        BigDecimal peerPercentile = null;
        String decisionCode = "OK";
        List<String> evidenceRefs = new ArrayList<>();
        if (userId != null) {
            c10 = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dwp_aura.fi_doc_header
                    WHERE tenant_id = ? AND user_id = ? AND created_at >= ? AND created_at <= ?
                    """, Integer.class, tenantId, userId, Timestamp.from(from10m), Timestamp.from(occurredAt));
            c24Txn = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dwp_aura.fi_doc_header
                    WHERE tenant_id = ? AND user_id = ? AND created_at >= ? AND created_at <= ?
                    """, Integer.class, tenantId, userId, Timestamp.from(from24h), Timestamp.from(occurredAt));
            c30Txn = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dwp_aura.fi_doc_header
                    WHERE tenant_id = ? AND user_id = ? AND created_at >= ? AND created_at <= ?
                    """, Integer.class, tenantId, userId, Timestamp.from(from30d), Timestamp.from(occurredAt));
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
            evidenceRefs.add("fi_doc_header:user_windows");
            evidenceRefs.add("fi_doc_item:user_avg_30d");
        }
        if (userId != null && merchant != null) {
            c24 = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM dwp_aura.fi_doc_header
                    WHERE tenant_id = ? AND user_id = ? AND created_at >= ? AND created_at <= ?
                      AND LOWER(COALESCE(NULLIF(TRIM(bktxt), ''), NULLIF(TRIM(xblnr), ''), '')) = LOWER(?)
                    """, Integer.class, tenantId, userId, Timestamp.from(from24h), Timestamp.from(occurredAt), merchant);
            evidenceRefs.add("fi_doc_header:user_same_merchant_24h");
        }
        if (mccCode != null && currentAmount != null) {
            Integer sample = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM (
                      SELECT h.bukrs, h.belnr, h.gjahr, COALESCE(SUM(ABS(i.wrbtr)),0) AS doc_amount
                      FROM dwp_aura.fi_doc_header h
                      LEFT JOIN dwp_aura.fi_doc_item i
                        ON i.tenant_id=h.tenant_id AND i.bukrs=h.bukrs AND i.belnr=h.belnr AND i.gjahr=h.gjahr
                      WHERE h.tenant_id=? AND UPPER(COALESCE(h.mcc_code,''))=UPPER(?)
                        AND h.created_at>=? AND h.created_at<=?
                      GROUP BY h.bukrs,h.belnr,h.gjahr
                    ) t
                    """, Integer.class, tenantId, mccCode, Timestamp.from(from30d), Timestamp.from(occurredAt));
            if (sample != null && sample >= 30) {
                Double pct = jdbcTemplate.queryForObject("""
                        SELECT 100.0 * AVG(CASE WHEN t.doc_amount <= ? THEN 1.0 ELSE 0.0 END)
                        FROM (
                          SELECT h.bukrs, h.belnr, h.gjahr, COALESCE(SUM(ABS(i.wrbtr)),0) AS doc_amount
                          FROM dwp_aura.fi_doc_header h
                          LEFT JOIN dwp_aura.fi_doc_item i
                            ON i.tenant_id=h.tenant_id AND i.bukrs=h.bukrs AND i.belnr=h.belnr AND i.gjahr=h.gjahr
                          WHERE h.tenant_id=? AND UPPER(COALESCE(h.mcc_code,''))=UPPER(?)
                            AND h.created_at>=? AND h.created_at<=?
                          GROUP BY h.bukrs,h.belnr,h.gjahr
                        ) t
                        """, Double.class, currentAmount, tenantId, mccCode, Timestamp.from(from30d), Timestamp.from(occurredAt));
                if (pct != null) {
                    peerPercentile = BigDecimal.valueOf(pct).setScale(1, RoundingMode.HALF_UP);
                }
            } else {
                decisionCode = "NO_DATA";
            }
            evidenceRefs.add("fi_doc_header+fi_doc_item:peer_group_30d");
        } else {
            decisionCode = "NO_DATA";
        }
        return CaseContextResult.builder()
                .window10mTxnCount(c10 != null ? c10 : 0)
                .window24hSameMerchantCount(c24 != null ? c24 : 0)
                .user30dBaselineAmount(baseline != null ? baseline : BigDecimal.ZERO)
                .window24hTxnCount(c24Txn != null ? c24Txn : 0)
                .window30dTxnCount(c30Txn != null ? c30Txn : 0)
                .avgAmount30d(baseline != null ? baseline : BigDecimal.ZERO)
                .peerGroupPercentile(peerPercentile)
                .decisionCode(decisionCode)
                .evidenceRefs(evidenceRefs)
                .decisionSource(source)
                .build();
    }

    public EvidenceVerificationResult evidenceVerification(Long tenantId, EvidenceVerificationRequest request) {
        return evidenceVerification(tenantId, null, request);
    }

    public EvidenceVerificationResult evidenceVerification(Long tenantId, String traceId, EvidenceVerificationRequest request) {
        List<String> reasons = new ArrayList<>();
        List<String> ungroundedSentences = new ArrayList<>();
        List<String> reqIds = new ArrayList<>();

        if (request.getCitationIds() != null) reqIds.addAll(request.getCitationIds());
        if ((request.getCitations() != null && request.getCitations().isArray()) && reqIds.isEmpty()) {
            for (JsonNode c : request.getCitations()) {
                String id = textAny(c, "citation_id", "citationId", "id");
                if (id != null) reqIds.add(id);
            }
        }
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

        int totalSentences = 0;
        int groundedSentences = 0;
        JsonNode scm = request.getSentenceCitationMap();
        if (scm != null && scm.isArray()) {
            totalSentences = scm.size();
            for (JsonNode s : scm) {
                JsonNode ids = s.get("citation_ids");
                boolean grounded = ids != null && ids.isArray() && ids.size() > 0;
                if (!grounded) {
                    String txt = textAny(s, "sentence", "text");
                    if (txt != null) ungroundedSentences.add(txt);
                } else groundedSentences++;
            }
            if (!ungroundedSentences.isEmpty()) reasons.add("SENTENCE_CITATION_MISSING");
        } else if (request.getSentence() != null && !request.getSentence().isBlank()) {
            totalSentences = 1;
            groundedSentences = matched > 0 ? 1 : 0;
            if (groundedSentences == 0) ungroundedSentences.add(request.getSentence());
        }

        BigDecimal coverage = totalSentences > 0
                ? BigDecimal.valueOf((double) groundedSentences / (double) totalSentences).setScale(4, RoundingMode.HALF_UP)
                : null;

        String decisionCode;
        if ((request.getRiskType() == null || request.getRegulationVersion() == null)
                && (request.getSentenceCitationMap() == null && (request.getSentence() == null || request.getSentence().isBlank()))) {
            decisionCode = "PARTIAL";
        } else if (reasons.isEmpty()) {
            decisionCode = "OK";
        } else {
            decisionCode = "MISMATCH";
        }
        boolean valid = "OK".equals(decisionCode);
        log.info("MCP verification summary: traceId={} groundedCoverageRatio={} mismatchCount={}",
                traceId != null ? traceId : (request.getRunId() != null ? request.getRunId() : request.getCaseId()),
                coverage, reasons.size());
        return EvidenceVerificationResult.builder()
                .verified(valid)
                .isValid(valid)
                .mismatchReasons(reasons)
                .groundedCoverageRatio(coverage)
                .ungroundedSentences(ungroundedSentences)
                .resolvedRunId(runId)
                .matchedCitationCount(matched)
                .requestedCitationCount(reqIds.size())
                .articleMatched(articleMatched)
                .decisionCode(decisionCode)
                .evidenceHash(buildHash(request))
                .evidenceRefs(List.of("case_analysis_result.rag_refs_json", "request.sentenceCitationMap"))
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
        String conflictType = "NONE";
        String recommendedAction = "PASS";
        if (qualityGateCodes.contains("POLICY_CONFLICT") || qualityGateCodes.contains("POLICY_CONFLICT_DETECTED")) {
            conflictType = "POLICY_CONFLICT";
            recommendedAction = "HOLD";
        } else if (qualityGateCodes.contains("RISK_ARTICLE_MISMATCH")) {
            conflictType = "RISK_ARTICLE_MISMATCH";
            recommendedAction = "REEVAL";
        } else if (conflict) {
            conflictType = "POLICY_CONFLICT";
            recommendedAction = "HOLD";
        }
        return RagConflictDiagnosticsResult.builder()
                .policyRagConflict(conflict)
                .conflictReasons(reasons)
                .conflictType(conflictType)
                .recommendedAction(recommendedAction)
                .caseType(caseType)
                .reasonText(reasonText)
                .resolvedRunId(runId)
                .qualityGateCodes(qualityGateCodes)
                .ragHasReferences(ragHasRefs)
                .policyReevalApplied(reeval)
                .decisionCode(conflict ? "POLICY_CONFLICT" : "OK")
                .evidenceRefs(List.of("case_analysis_result.quality_gate_codes", "case_analysis_result.rag_refs_json"))
                .build();
    }

    public ShadowRunMetadataResult saveShadowRunMetadata(Long tenantId, ShadowRunMetadataRequest request) {
        String requestedAgentMode = trimToNull(request.getAgentMode());
        String requestedModelVersion = trimToNull(request.getModelVersion());
        String requestedPolicyVersion = trimToNull(request.getPolicyVersion());
        String resolvedAgentMode = requestedAgentMode != null ? requestedAgentMode : defaultAgentMode;
        String resolvedModelVersion = requestedModelVersion != null ? requestedModelVersion : defaultModelVersion;
        String resolvedPolicyVersion = requestedPolicyVersion != null ? requestedPolicyVersion : defaultPolicyVersion;

        jdbcTemplate.update("""
                        INSERT INTO dwp_aura.mcp_shadow_run_meta (
                          tenant_id, run_id, case_id,
                          requested_agent_mode, resolved_agent_mode,
                          trace_id,
                          requested_model_version, resolved_model_version,
                          requested_policy_version, resolved_policy_version
                        ) VALUES (?,?,?,?,?,?,?,?,?,?)
                        """,
                tenantId, request.getRunId(), request.getCaseId(),
                requestedAgentMode, resolvedAgentMode,
                trimToNull(request.getTraceId()),
                requestedModelVersion, resolvedModelVersion,
                requestedPolicyVersion, resolvedPolicyVersion);

        return ShadowRunMetadataResult.builder()
                .runId(request.getRunId())
                .caseId(request.getCaseId())
                .traceId(trimToNull(request.getTraceId()))
                .requestedAgentMode(requestedAgentMode)
                .resolvedAgentMode(resolvedAgentMode)
                .requestedModelVersion(requestedModelVersion)
                .resolvedModelVersion(resolvedModelVersion)
                .requestedPolicyVersion(requestedPolicyVersion)
                .resolvedPolicyVersion(resolvedPolicyVersion)
                .decisionCode("OK")
                .evidenceRefs(List.of("mcp_shadow_run_meta"))
                .savedAt(Instant.now())
                .build();
    }

    public ShadowCompareResult shadowCompare(Long tenantId, Instant from, Instant to) {
        Instant fromTs = from != null ? from : Instant.now().minusSeconds(30L * 24 * 60 * 60);
        Instant toTs = to != null ? to : Instant.now();

        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dwp_aura.mcp_shadow_run_meta
                WHERE tenant_id=? AND created_at>=? AND created_at<=?
                """, Long.class, tenantId, Timestamp.from(fromTs), Timestamp.from(toTs));

        if (total == null || total == 0L) {
            return ShadowCompareResult.builder()
                    .from(fromTs)
                    .to(toTs)
                    .total(0L)
                    .sameVerdictRate(BigDecimal.ZERO)
                    .citationMismatchRate(BigDecimal.ZERO)
                    .ragZeroRate(BigDecimal.ZERO)
                    .holdRate(BigDecimal.ZERO)
                    .decisionCode("NO_DATA")
                    .evidenceRefs(List.of("mcp_shadow_run_meta"))
                    .build();
        }

        Map<String, Object> row = jdbcTemplate.query("""
                WITH shadow AS (
                  SELECT m.run_id, m.case_id
                  FROM dwp_aura.mcp_shadow_run_meta m
                  WHERE m.tenant_id=? AND m.created_at>=? AND m.created_at<=?
                ),
                shadow_res AS (
                  SELECT s.run_id, s.case_id, ar.severity, ar.quality_gate_codes, ar.reason_text
                  FROM shadow s
                  JOIN dwp_aura.case_analysis_result ar
                    ON ar.run_id = s.run_id AND ar.tenant_id=?
                ),
                legacy_res AS (
                  SELECT DISTINCT ON (r.case_id) r.case_id, ar.severity
                  FROM dwp_aura.case_analysis_run r
                  JOIN dwp_aura.case_analysis_result ar
                    ON ar.run_id=r.run_id AND ar.tenant_id=r.tenant_id
                  WHERE r.tenant_id=? AND r.mode='LIVE'
                  ORDER BY r.case_id, r.started_at DESC
                )
                SELECT
                  SUM(CASE WHEN l.severity IS NOT NULL AND l.severity = s.severity THEN 1 ELSE 0 END) AS same_verdict_count,
                  SUM(CASE WHEN s.quality_gate_codes IS NOT NULL AND jsonb_exists(s.quality_gate_codes,'SENTENCE_CITATION_MISSING') THEN 1 ELSE 0 END) AS citation_mismatch_count,
                  SUM(CASE WHEN s.quality_gate_codes IS NOT NULL AND jsonb_exists(s.quality_gate_codes,'RAG_ZERO') THEN 1 ELSE 0 END) AS rag_zero_count,
                  SUM(CASE WHEN (s.quality_gate_codes IS NOT NULL AND (
                                jsonb_exists(s.quality_gate_codes,'POLICY_CONFLICT')
                             OR jsonb_exists(s.quality_gate_codes,'POLICY_CONFLICT_DETECTED')
                             OR jsonb_exists(s.quality_gate_codes,'EVIDENCE_COVERAGE_LOW')
                             OR jsonb_exists(s.quality_gate_codes,'SENTENCE_CITATION_MISSING')
                           ))
                           OR LOWER(COALESCE(s.reason_text,'')) LIKE '%보류%'
                           OR LOWER(COALESCE(s.reason_text,'')) LIKE '%재검토%'
                           THEN 1 ELSE 0 END) AS hold_count
                FROM shadow_res s
                LEFT JOIN legacy_res l ON l.case_id = s.case_id
                """,
                ps -> {
                    ps.setLong(1, tenantId);
                    ps.setTimestamp(2, Timestamp.from(fromTs));
                    ps.setTimestamp(3, Timestamp.from(toTs));
                    ps.setLong(4, tenantId);
                    ps.setLong(5, tenantId);
                },
                rs -> rs.next() ? Map.of(
                        "same", rs.getLong("same_verdict_count"),
                        "citation", rs.getLong("citation_mismatch_count"),
                        "ragZero", rs.getLong("rag_zero_count"),
                        "hold", rs.getLong("hold_count")) : Map.of());

        long same = ((Number) row.getOrDefault("same", 0L)).longValue();
        long citation = ((Number) row.getOrDefault("citation", 0L)).longValue();
        long ragZero = ((Number) row.getOrDefault("ragZero", 0L)).longValue();
        long hold = ((Number) row.getOrDefault("hold", 0L)).longValue();

        return ShadowCompareResult.builder()
                .from(fromTs)
                .to(toTs)
                .total(total)
                .sameVerdictRate(ratio(same, total))
                .citationMismatchRate(ratio(citation, total))
                .ragZeroRate(ratio(ragZero, total))
                .holdRate(ratio(hold, total))
                .decisionCode("OK")
                .evidenceRefs(List.of("mcp_shadow_run_meta", "case_analysis_result", "case_analysis_run"))
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

    private BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0L) return BigDecimal.ZERO;
        return BigDecimal.valueOf((double) numerator / (double) denominator).setScale(4, RoundingMode.HALF_UP);
    }
}
