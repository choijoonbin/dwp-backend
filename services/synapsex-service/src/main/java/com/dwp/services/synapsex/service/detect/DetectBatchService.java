package com.dwp.services.synapsex.service.detect;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.config.AuraTenantContext;
import com.dwp.services.synapsex.dto.detect.DetectScreenRequest;
import com.dwp.services.synapsex.dto.detect.DetectScreenResponse;
import com.dwp.services.synapsex.dto.detect.ScreenBatchItemRequest;
import com.dwp.services.synapsex.dto.detect.ScreenBatchResponse;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.AgentCaseStatus;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiDocItem;
import com.dwp.services.synapsex.entity.FiOpenItem;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.DetectRunRepository;
import com.dwp.services.synapsex.repository.FiDocHeaderRepository;
import com.dwp.services.synapsex.repository.FiDocItemRepository;
import com.dwp.services.synapsex.repository.FiOpenItemRepository;
import com.dwp.services.synapsex.repository.AppCodeRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase B: Detect Run 배치 — window 내 전표/미결제 대상 케이스 Upsert
 * P0 규칙: case_type, severity, score, dedup_key, evidence_json 등 명확한 기준 적용
 * 참고: docs/job/PROMPT_BE_CASE_FIELD_RULES_AND_DEDUP_P0.txt
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectBatchService {

    private static final String RULE_ID_DOC = "WINDOW_DOC_ENTRY";
    private static final String RULE_ID_OPEN_ITEM = "WINDOW_OPEN_ITEM";
    /** DRIVER_TYPE 비즈니스 코드 (V68 마이그레이션). Aura 미반환/미검증 시 DEFAULT */
    private static final String DEFAULT_CASE_TYPE = "DEFAULT";
    private static final String SOURCE_TYPE_DOC = "DOC";
    private static final String SOURCE_TYPE_OPEN_ITEM = "OPEN_ITEM";
    /** Advisory lock key base (tenant별 고유 키) */
    private static final long ADVISORY_LOCK_BASE = 1_000_000_000_000L;
    /** Fallback 전용: severity → score (0~100). Aura 스크리닝 실패 시에만 사용 */
    private static final Map<String, Integer> SEVERITY_SCORE_FALLBACK = Map.of(
            "CRITICAL", 95, "HIGH", 80, "MEDIUM", 60, "LOW", 30, "INFO", 10);
    /** Fallback 전용: amount 기반 severity 임계값 (원) */
    private static final BigDecimal AMOUNT_HIGH = new BigDecimal("100000000");
    private static final BigDecimal AMOUNT_MEDIUM = new BigDecimal("10000000");

    /** 스크리닝 결과 또는 fallback 결과 (severity, score, reasonText, caseType) */
    private record ScreeningOutcome(String severity, BigDecimal score, String reasonText, String caseType) {}
    /** 청크 내 전표 컨텍스트 (배치 응답과 매핑용) */
    private record DocContext(FiDocHeader doc, String firstBuzei, BigDecimal amount, String waers) {}
    /** upsertCase 반환: created/updated 건수 + 해당 케이스 ID (브리핑 우선순위 Global Max용) */
    private record UpsertResult(int created, int updated, Long caseId) {}

    @Value("${detect.screen-batch-chunk-size:50}")
    private int screenBatchChunkSize;
    @Value("${workbench.redis.action-channel:workbench:case:action}")
    private String workbenchActionChannel;

    private final DetectRunRepository detectRunRepository;
    private final AuraCaseTabClient auraCaseTabClient;
    private final AppCodeRepository appCodeRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AuditWriter auditWriter;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.RedisTemplate<String, String>> redisTemplateProvider;

    @Transactional
    public DetectRun runDetectBatch(Long tenantId, Instant windowFrom, Instant windowTo) {
        long lockKey = ADVISORY_LOCK_BASE + tenantId;
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, lockKey);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Detect batch skipped: advisory lock not acquired tenant={} (another instance running)", tenantId);
            return null;
        }
        try {
            return runDetectBatchInternal(tenantId, windowFrom, windowTo);
        } finally {
            jdbcTemplate.execute("SELECT pg_advisory_unlock(" + lockKey + ")");
        }
    }

    public SkippedRunInfo getSkippedRunInfo(Long tenantId) {
        Optional<DetectRun> running = detectRunRepository.findTopByTenantIdAndStatusOrderByStartedAtDesc(tenantId, "STARTED");
        return running.map(r -> new SkippedRunInfo(r.getRunId(), r.getStartedAt())).orElse(null);
    }

    public record SkippedRunInfo(Long runId, Instant startedAt) {}

    private DetectRun runDetectBatchInternal(Long tenantId, Instant windowFrom, Instant windowTo) {
        Instant now = Instant.now();
        DetectRun run = DetectRun.builder()
                .tenantId(tenantId)
                .windowFrom(windowFrom)
                .windowTo(windowTo)
                .status("STARTED")
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        run = detectRunRepository.save(run);

        Map<String, Object> runTags = new HashMap<>();
        runTags.put("runId", run.getRunId());
        auditWriter.logDetectRunEvent(tenantId, AuditEventConstants.TYPE_RUN_DETECT_STARTED, run.getRunId(),
                AuditEventConstants.OUTCOME_SUCCESS,
                Map.of("runId", run.getRunId(), "windowFrom", windowFrom.toString(), "windowTo", windowTo.toString()),
                runTags);

        int caseCreated = 0;
        int caseUpdated = 0;
        BigDecimal highestScore = null;
        Long globalPriorityId = null;
        String globalBriefingInsight = null;

        try {
            List<FiDocHeader> docs = fiDocHeaderRepository.findByTenantIdAndCreatedAtBetween(tenantId, windowFrom, windowTo);
            List<FiOpenItem> openItems = fiOpenItemRepository.findByTenantIdAndLastUpdateTsBetween(tenantId, windowFrom, windowTo);

            // 전표: 50건 단위 청킹 → Aura /aura/detect/screen-batch 호출. 실패 청크만 fallback
            List<DocContext> docContexts = new ArrayList<>();
            for (FiDocHeader doc : docs) {
                String firstBuzei = firstDocBuzei(tenantId, doc.getBukrs(), doc.getBelnr(), doc.getGjahr());
                BigDecimal amount = sumDocAmount(tenantId, doc.getBukrs(), doc.getBelnr(), doc.getGjahr());
                docContexts.add(new DocContext(doc, firstBuzei, amount, doc.getWaers()));
            }
            for (List<DocContext> chunk : partition(docContexts, Math.max(1, screenBatchChunkSize))) {
                List<ScreenBatchItemRequest> batchBody = chunk.stream()
                        .map(ctx -> buildFlattenedBatchItem(tenantId, ctx))
                        .toList();
                List<DetectScreenResponse> responses;
                ScreenBatchResponse batchResponse = null;
                try {
                    AuraTenantContext.setTenantId(tenantId);
                    try {
                        batchResponse = auraCaseTabClient.screenBatch(tenantId, batchBody);
                        responses = (batchResponse != null && batchResponse.getResults() != null)
                                ? batchResponse.getResults()
                                : null;
                    } finally {
                        AuraTenantContext.clear();
                    }
                } catch (FeignException e) {
                    log.warn("Aura screen-batch failed (chunk size={}), using amount fallback: status={} message={}",
                            chunk.size(), e.status(), e.getMessage());
                    responses = null;
                } catch (Exception e) {
                    log.warn("Aura screen-batch error (chunk size={}), using amount fallback: {}", chunk.size(), e.getMessage());
                    responses = null;
                }
                BigDecimal chunkMaxScore = null;
                Long chunkPriorityCaseId = null;
                for (int i = 0; i < chunk.size(); i++) {
                    DocContext ctx = chunk.get(i);
                    FiDocHeader doc = ctx.doc();
                    ScreeningOutcome outcome;
                    if (responses != null && i < responses.size()) {
                        DetectScreenResponse res = responses.get(i);
                        outcome = (res != null && res.getSeverity() != null && !res.getSeverity().isBlank())
                                ? outcomeFromResponse(res, SOURCE_TYPE_DOC)
                                : screeningOutcomeFromAmountFallback(ctx.amount(), SOURCE_TYPE_DOC);
                    } else {
                        outcome = screeningOutcomeFromAmountFallback(ctx.amount(), SOURCE_TYPE_DOC);
                    }
                    outcome = validateCaseTypeOutcome(outcome);
                    String dedupKey = buildDedupKey(tenantId, outcome.caseType(), SOURCE_TYPE_DOC, doc.getBukrs(), doc.getBelnr(), doc.getGjahr(), null);
                    UpsertResult result = upsertCase(tenantId, run, dedupKey, outcome.caseType(), SOURCE_TYPE_DOC, RULE_ID_DOC,
                            doc.getBukrs(), doc.getBelnr(), doc.getGjahr(), ctx.firstBuzei(), ctx.amount(), ctx.waers(), null, outcome,
                            doc.getIntendedRiskType(), doc.getHrStatus(), doc.getMccCode(), doc.getBudgetExceeded());
                    caseCreated += result.created();
                    caseUpdated += result.updated();
                    if (outcome.score() != null && (chunkMaxScore == null || outcome.score().compareTo(chunkMaxScore) > 0)) {
                        chunkMaxScore = outcome.score();
                        chunkPriorityCaseId = result.caseId();
                    }
                }
                if (chunkMaxScore != null && (highestScore == null || chunkMaxScore.compareTo(highestScore) > 0)) {
                    highestScore = chunkMaxScore;
                    globalPriorityId = chunkPriorityCaseId;
                    if (batchResponse != null && batchResponse.getBriefingInsight() != null && !batchResponse.getBriefingInsight().isBlank())
                        globalBriefingInsight = batchResponse.getBriefingInsight();
                }
                if (responses != null) {
                    logHighSeverityFromBatch(tenantId, chunk, responses);
                }
            }

            for (FiOpenItem oi : openItems) {
                ScreeningOutcome outcome = validateCaseTypeOutcome(screeningOutcomeFromAmountFallback(oi.getOpenAmount(), SOURCE_TYPE_OPEN_ITEM));
                String dedupKey = buildDedupKey(tenantId, outcome.caseType(), SOURCE_TYPE_OPEN_ITEM,
                        oi.getBukrs(), oi.getBelnr(), oi.getGjahr(), oi.getBuzei());
                UpsertResult result = upsertCase(tenantId, run, dedupKey, outcome.caseType(), SOURCE_TYPE_OPEN_ITEM, RULE_ID_OPEN_ITEM,
                        oi.getBukrs(), oi.getBelnr(), oi.getGjahr(), oi.getBuzei(), oi.getOpenAmount(), oi.getCurrency(), oi.getDueDate(), outcome,
                        null, null, null, null);
                caseCreated += result.created();
                caseUpdated += result.updated();
            }

            ObjectNode counts = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            counts.put("caseCreated", caseCreated);
            counts.put("caseUpdated", caseUpdated);
            counts.put("created_count", caseCreated);
            counts.put("updated_count", caseUpdated);
            counts.put("suppressed_count", 0);
            run.setCountsJson(counts);
            run.setStatus("COMPLETED");
            run.setCompletedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            run = detectRunRepository.save(run);

            Map<String, Object> doneTags = new HashMap<>();
            doneTags.put("runId", run.getRunId());
            auditWriter.logDetectRunEvent(tenantId, AuditEventConstants.TYPE_RUN_DETECT_COMPLETED, run.getRunId(),
                    AuditEventConstants.OUTCOME_SUCCESS,
                    Map.of("runId", run.getRunId(), "caseCreated", caseCreated, "caseUpdated", caseUpdated),
                    doneTags);

            publishDetectCompleted(tenantId, run.getRunId(), caseCreated, caseUpdated, globalPriorityId, globalBriefingInsight);

        } catch (Exception e) {
            log.error("Detect batch failed tenant={} runId={}", tenantId, run.getRunId(), e);
            run.setStatus("FAILED");
            run.setErrorMessage(e.getMessage());
            run.setCompletedAt(Instant.now());
            run.setUpdatedAt(Instant.now());
            run = detectRunRepository.save(run);

            Map<String, Object> failTags = new HashMap<>();
            failTags.put("runId", run.getRunId());
            auditWriter.logDetectRunEvent(tenantId, AuditEventConstants.TYPE_RUN_DETECT_FAILED, run.getRunId(),
                    AuditEventConstants.OUTCOME_FAILED,
                    Map.of("error", e.getMessage()),
                    failTags);
        }

        return run;
    }

    /** P0: dedup_key = tenant:case_type:sourceType:bukrs-belnr-gjahr-buzei */
    private String buildDedupKey(Long tenantId, String caseType, String sourceType,
                                  String bukrs, String belnr, String gjahr, String buzei) {
        String entity = bukrs + "-" + belnr + "-" + gjahr + "-" + (buzei != null ? buzei : "_");
        return tenantId + ":" + caseType + ":" + sourceType + ":" + entity;
    }

    private BigDecimal sumDocAmount(Long tenantId, String bukrs, String belnr, String gjahr) {
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, bukrs, belnr, gjahr);
        return items.stream()
                .map(FiDocItem::getWrbtr)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 전표의 첫 라인 buzei (agent_case.buzei 및 dedup_key용). 없으면 "001". */
    private String firstDocBuzei(Long tenantId, String bukrs, String belnr, String gjahr) {
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, bukrs, belnr, gjahr);
        return items.isEmpty() ? "001" : (items.get(0).getBuzei() != null ? items.get(0).getBuzei() : "001");
    }

    /** Flatten: 전표 → Aura screen-batch 1건. merchantName은 bktxt→xblnr, 둘 다 무의미 시 item.sgtxt 보강 */
    private ScreenBatchItemRequest buildFlattenedBatchItem(Long tenantId, DocContext ctx) {
        FiDocHeader doc = ctx.doc();
        String occurredAt = null;
        if (doc.getBudat() != null) {
            LocalTime t = doc.getCputm() != null ? doc.getCputm() : LocalTime.MIDNIGHT;
            occurredAt = doc.getBudat().atTime(t).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, doc.getBukrs(), doc.getBelnr(), doc.getGjahr());
        String merchantName = resolveMerchantName(doc, items);
        return ScreenBatchItemRequest.builder()
                .amount(ctx.amount())
                .occurredAt(occurredAt)
                .expenseType(doc.getBlart())
                .merchantName(merchantName)
                .caseId(null)
                .hrStatus(doc.getHrStatus())
                .mccCode(doc.getMccCode())
                .budgetExceeded(doc.getBudgetExceeded())
                .build();
    }

    /** 가맹점명(업종명) 형태: bktxt/xblnr에 업종·적요(sgtxt)를 결합하여 "가맹점명(업종명)" 전달. mcc_name 없으면 sgtxt 사용 */
    private String resolveMerchantName(FiDocHeader doc, List<FiDocItem> items) {
        String namePart = null;
        if (!isGenericMerchantName(doc.getBktxt())) namePart = doc.getBktxt().trim();
        else if (!isGenericMerchantName(doc.getXblnr())) namePart = doc.getXblnr().trim();
        String industryPart = null;
        if (items != null) {
            String joined = items.stream()
                    .map(FiDocItem::getSgtxt)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .limit(3)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(null);
            if (joined != null && !joined.isBlank()) industryPart = joined;
        }
        if (namePart != null && !namePart.isEmpty() && industryPart != null && !industryPart.isEmpty())
            return namePart + " (" + industryPart + ")";
        if (namePart != null && !namePart.isEmpty()) return namePart;
        if (industryPart != null && !industryPart.isEmpty()) return industryPart;
        return doc.getBktxt() != null ? doc.getBktxt().trim() : (doc.getXblnr() != null ? doc.getXblnr().trim() : "");
    }

    private static boolean isGenericMerchantName(String s) {
        if (s == null || s.isBlank() || s.trim().length() < 2) return true;
        String lower = s.trim().toLowerCase();
        return lower.contains("법인카드") || lower.contains("전표");
    }

    /** 배치 응답 중 HIGH/CRITICAL 심각도 건의 reasonText를 서버 로그로 남겨 모니터링. 테넌트 ID 포함으로 고객사 식별 가능 */
    private void logHighSeverityFromBatch(Long tenantId, List<DocContext> chunk, List<DetectScreenResponse> responses) {
        for (int i = 0; i < Math.min(chunk.size(), responses.size()); i++) {
            DetectScreenResponse res = responses.get(i);
            if (res == null) continue;
            String sev = res.getSeverity();
            if (sev == null || (!"HIGH".equals(sev) && !"CRITICAL".equals(sev))) continue;
            DocContext ctx = chunk.get(i);
            FiDocHeader doc = ctx.doc();
            String reasonText = res.getReasonText() != null ? res.getReasonText() : "";
            log.info("tenantId={} [Screen batch] HIGH severity doc={}-{}-{} severity={} reasonText={}",
                    tenantId, doc.getBukrs(), doc.getBelnr(), doc.getGjahr(), sev, reasonText);
        }
    }

    private DetectScreenRequest buildScreenRequest(FiDocHeader doc, List<FiDocItem> docItems) {
        DetectScreenRequest.Header header = DetectScreenRequest.Header.builder()
                .bukrs(doc.getBukrs())
                .belnr(doc.getBelnr())
                .gjahr(doc.getGjahr())
                .docSource(doc.getDocSource())
                .budat(doc.getBudat())
                .cputm(doc.getCputm())
                .waers(doc.getWaers())
                .bktxt(doc.getBktxt())
                .xblnr(doc.getXblnr())
                .blart(doc.getBlart())
                .build();
        List<DetectScreenRequest.Item> items = new ArrayList<>();
        for (FiDocItem i : docItems) {
            items.add(DetectScreenRequest.Item.builder()
                    .buzei(i.getBuzei())
                    .hkont(i.getHkont())
                    .wrbtr(i.getWrbtr())
                    .lifnr(i.getLifnr())
                    .kunnr(i.getKunnr())
                    .sgtxt(i.getSgtxt())
                    .bschl(i.getBschl())
                    .shkzg(i.getShkzg())
                    .waers(i.getWaers())
                    .build());
        }
        return DetectScreenRequest.builder().header(header).items(items).build();
    }

    /** Fallback: Aura 미응답/에러 시 금액 기반 severity, score, 고정 reasonText. caseType은 DEFAULT(검증 통과) */
    private ScreeningOutcome screeningOutcomeFromAmountFallback(BigDecimal amount, String sourceType) {
        String severity = resolveSeverityFallback(amount);
        BigDecimal score = resolveScoreFallback(severity);
        String reasonText = defaultReasonText(sourceType);
        return new ScreeningOutcome(severity, score, reasonText, DEFAULT_CASE_TYPE);
    }

    /** Aura 응답 → ScreeningOutcome. caseType은 이후 validateCaseTypeOutcome에서 검증 */
    private ScreeningOutcome outcomeFromResponse(DetectScreenResponse res, String sourceType) {
        BigDecimal score = res.getScore() != null ? res.getScore() : resolveScoreFallback(res.getSeverity());
        String reasonText = res.getReasonText() != null && !res.getReasonText().isBlank()
                ? res.getReasonText() : defaultReasonText(sourceType);
        String caseType = res.getCaseType() != null && !res.getCaseType().isBlank() ? res.getCaseType() : DEFAULT_CASE_TYPE;
        return new ScreeningOutcome(res.getSeverity(), score, reasonText, caseType);
    }

    /** DRIVER_TYPE app_codes 검증. 없으면 DEFAULT로 저장하고 로그 */
    private ScreeningOutcome validateCaseTypeOutcome(ScreeningOutcome outcome) {
        String ct = outcome.caseType();
        if (ct == null || ct.isBlank()) return new ScreeningOutcome(outcome.severity(), outcome.score(), outcome.reasonText(), DEFAULT_CASE_TYPE);
        if (appCodeRepository.findByGroupKeyAndCodeAndIsActiveTrue("DRIVER_TYPE", ct).isEmpty()) {
            log.info("Aura caseType not in DRIVER_TYPE, storing as DEFAULT: caseType={}", ct);
            return new ScreeningOutcome(outcome.severity(), outcome.score(), outcome.reasonText(), DEFAULT_CASE_TYPE);
        }
        return outcome;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }

    /** 배치 완료 시 Redis workbench:case:action 발행 — 전체 탐지 완료 알림. briefing 필드는 Aura가 제공 시 FE 워크벤치 주목 가이드용으로 포함 */
    private void publishDetectCompleted(Long tenantId, Long runId, int caseCreated, int caseUpdated,
                                        Long briefingPriorityCaseId, String briefingInsight) {
        redisTemplateProvider.ifAvailable(template -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "detect_completed");
                payload.put("category", "CASE_ACTION");
                payload.put("run_id", runId != null ? runId.toString() : null);
                payload.put("tenant_id", tenantId);
                payload.put("case_created", caseCreated);
                payload.put("case_updated", caseUpdated);
                if (briefingPriorityCaseId != null) payload.put("briefing_priority_case_id", briefingPriorityCaseId);
                if (briefingInsight != null && !briefingInsight.isBlank()) payload.put("briefing_insight", briefingInsight);
                payload.put("title", "전체 탐지 완료");
                payload.put("message", String.format("탐지 배치 완료. 생성 %d건, 갱신 %d건", caseCreated, caseUpdated));
                payload.put("at", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                template.convertAndSend(workbenchActionChannel, json);
                if (log.isDebugEnabled()) log.debug("Published detect_completed: runId={} caseCreated={} caseUpdated={} briefingPriorityCaseId={}", runId, caseCreated, caseUpdated, briefingPriorityCaseId);
            } catch (JsonProcessingException e) {
                log.warn("Failed to publish detect_completed: runId={} {}", runId, e.getMessage());
            }
        });
    }

    private static String defaultReasonText(String sourceType) {
        return "DOC".equals(sourceType)
                ? "Detected in document window during scheduled run"
                : "Detected in open item window during scheduled run";
    }

    /** Fallback 전용: amount 기반 severity (>=1억 HIGH, >=1천만 MEDIUM, else LOW) */
    private String resolveSeverityFallback(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) return "LOW";
        if (amount.compareTo(AMOUNT_HIGH) >= 0) return "HIGH";
        if (amount.compareTo(AMOUNT_MEDIUM) >= 0) return "MEDIUM";
        return "LOW";
    }

    private BigDecimal resolveScoreFallback(String severity) {
        Integer s = SEVERITY_SCORE_FALLBACK.get(severity != null ? severity.toUpperCase() : "LOW");
        return s != null ? BigDecimal.valueOf(s).setScale(4, RoundingMode.HALF_UP) : BigDecimal.valueOf(30).setScale(4, RoundingMode.HALF_UP);
    }

    private JsonNode buildEvidenceJson(String source, String window, String bukrs, String belnr, String gjahr, String buzei,
                                       BigDecimal amount, String currency, java.time.LocalDate dueDate, String vendor, String customer,
                                       String intendedRiskType, String hrStatus, String mccCode, Boolean budgetExceeded) {
        ObjectNode root = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        root.put("source", source);
        root.put("window", window);
        ObjectNode keys = root.putObject("keys");
        keys.put("bukrs", bukrs != null ? bukrs : "");
        keys.put("belnr", belnr != null ? belnr : "");
        keys.put("gjahr", gjahr != null ? gjahr : "");
        keys.put("buzei", buzei != null ? buzei : "");
        if (amount != null) root.put("amount", amount.toString());
        if (currency != null) root.put("currency", currency);
        if (dueDate != null) root.put("due_date", dueDate.toString());
        if (vendor != null && !vendor.isBlank()) root.put("vendor", vendor);
        if (customer != null && !customer.isBlank()) root.put("customer", customer);
        if (intendedRiskType != null && !intendedRiskType.isBlank()) root.put("intended_risk_type", intendedRiskType);
        if (hrStatus != null && !hrStatus.isBlank()) root.put("hr_status", hrStatus);
        if (mccCode != null && !mccCode.isBlank()) root.put("mcc_code", mccCode);
        if (budgetExceeded != null) root.put("budget_exceeded", budgetExceeded);
        return root;
    }

    /** @return UpsertResult(created, updated, caseId). severity/score/reasonText/caseType는 Aura 스크리닝 또는 fallback으로 이미 결정된 outcome 사용. intendedRiskType 및 규정 v2.0 context(hrStatus, mccCode, budgetExceeded)는 evidence에 포함. */
    private UpsertResult upsertCase(Long tenantId, DetectRun run, String dedupKey, String caseType, String sourceType, String ruleId,
                             String bukrs, String belnr, String gjahr, String buzei,
                             BigDecimal amount, String currency, java.time.LocalDate dueDate, ScreeningOutcome outcome,
                             String intendedRiskType, String hrStatus, String mccCode, Boolean budgetExceeded) {
        AgentCase existing = agentCaseRepository.findByTenantIdAndDedupKey(tenantId, dedupKey).orElse(null);

        String severity = outcome.severity();
        BigDecimal score = outcome.score();
        String reasonText = outcome.reasonText();
        String source = "DOC".equals(sourceType) ? "fi_doc_header" : "fi_open_item";
        String window = "DOC".equals(sourceType) ? RULE_ID_DOC : RULE_ID_OPEN_ITEM;

        if (existing == null) {
            JsonNode evidence = buildEvidenceJson(source, window, bukrs, belnr, gjahr, buzei, amount, currency, dueDate, null, null, intendedRiskType, hrStatus, mccCode, budgetExceeded);
            AgentCase created = AgentCase.builder()
                    .tenantId(tenantId)
                    .detectedAt(run.getStartedAt() != null ? run.getStartedAt() : Instant.now())
                    .bukrs(bukrs)
                    .belnr(belnr)
                    .gjahr(gjahr)
                    .buzei(buzei)
                    .caseType(caseType)
                    .severity(severity)
                    .score(score)
                    .reasonText(reasonText)
                    .evidenceJson(evidence)
                    .ragRefsJson(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode())
                    .status(AgentCaseStatus.ANALYZING)
                    .dedupKey(dedupKey)
                    .lastDetectRunId(run.getRunId())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            agentCaseRepository.save(created);

            String entityKey = buzei != null ? bukrs + "-" + belnr + "-" + gjahr + "-" + buzei : bukrs + "-" + belnr + "-" + gjahr;
            Map<String, Object> createAfter = new HashMap<>();
            createAfter.put("caseId", created.getCaseId());
            createAfter.put("dedupKey", dedupKey);
            createAfter.put("ruleId", ruleId);
            createAfter.put("entityKey", entityKey);
            createAfter.put("runId", run.getRunId());
            auditWriter.logCaseEvent(tenantId, AuditEventConstants.TYPE_CASE_CREATED, created.getCaseId(),
                    createAfter, null, Map.of("runId", run.getRunId()));
            return new UpsertResult(1, 0, created.getCaseId());
        } else {
            // P0: RESOLVED/IGNORED는 재오픈하지 않음 (표준 7개 기준)
            if (existing.getStatus() == AgentCaseStatus.RESOLVED || existing.getStatus() == AgentCaseStatus.IGNORED) {
                existing.setLastDetectRunId(run.getRunId());
                existing.setUpdatedAt(Instant.now());
                agentCaseRepository.save(existing);
                return new UpsertResult(0, 1, existing.getCaseId());
            }
            // P0: detected_at 유지, updated_at만 갱신 (buzei 보강: DOC 시 첫 라인 번호 반영)
            existing.setUpdatedAt(Instant.now());
            existing.setLastDetectRunId(run.getRunId());
            if (buzei != null) existing.setBuzei(buzei);
            existing.setSeverity(severity);
            existing.setScore(score);
            existing.setReasonText(reasonText);
            existing.setEvidenceJson(buildEvidenceJson(source, window, bukrs, belnr, gjahr, buzei, amount, currency, dueDate, null, null, intendedRiskType, hrStatus, mccCode, budgetExceeded));
            agentCaseRepository.save(existing);

            String entityKey = buzei != null ? bukrs + "-" + belnr + "-" + gjahr + "-" + buzei : bukrs + "-" + belnr + "-" + gjahr;
            Map<String, Object> updateAfter = new HashMap<>();
            updateAfter.put("lastDetectRunId", run.getRunId());
            updateAfter.put("severity", severity);
            updateAfter.put("dedupKey", existing.getDedupKey());
            updateAfter.put("runId", run.getRunId());
            auditWriter.logCaseEvent(tenantId, AuditEventConstants.TYPE_CASE_UPDATED, existing.getCaseId(),
                    updateAfter,
                    Map.of("detectedAt", existing.getDetectedAt() != null ? existing.getDetectedAt().toString() : ""),
                    Map.of("runId", run.getRunId()));
            return new UpsertResult(0, 1, existing.getCaseId());
        }
    }
}
