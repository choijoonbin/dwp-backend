package com.dwp.services.synapsex.service.detect;

import com.dwp.services.synapsex.audit.AuditEventConstants;
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
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
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
    /** sys_codes CASE_TYPE (auth) */
    private static final String CASE_TYPE_DOC_WINDOW = "DOC_WINDOW";
    private static final String CASE_TYPE_OPEN_ITEM_WINDOW = "OPEN_ITEM_WINDOW";
    private static final String SOURCE_TYPE_DOC = "DOC";
    private static final String SOURCE_TYPE_OPEN_ITEM = "OPEN_ITEM";
    /** Advisory lock key base (tenant별 고유 키) */
    private static final long ADVISORY_LOCK_BASE = 1_000_000_000_000L;
    /** severity → score (0~100) */
    private static final Map<String, Integer> SEVERITY_SCORE = Map.of(
            "CRITICAL", 95, "HIGH", 80, "MEDIUM", 60, "LOW", 30, "INFO", 10);
    /** amount 기반 severity 임계값 (원) */
    private static final BigDecimal AMOUNT_HIGH = new BigDecimal("100000000");
    private static final BigDecimal AMOUNT_MEDIUM = new BigDecimal("10000000");

    private final DetectRunRepository detectRunRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AuditWriter auditWriter;
    private final JdbcTemplate jdbcTemplate;

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

        try {
            List<FiDocHeader> docs = fiDocHeaderRepository.findByTenantIdAndCreatedAtBetween(tenantId, windowFrom, windowTo);
            List<FiOpenItem> openItems = fiOpenItemRepository.findByTenantIdAndLastUpdateTsBetween(tenantId, windowFrom, windowTo);

            for (FiDocHeader doc : docs) {
                String dedupKey = buildDedupKey(tenantId, CASE_TYPE_DOC_WINDOW, SOURCE_TYPE_DOC, doc.getBukrs(), doc.getBelnr(), doc.getGjahr(), null);
                BigDecimal amount = sumDocAmount(tenantId, doc.getBukrs(), doc.getBelnr(), doc.getGjahr());
                int[] c = upsertCase(tenantId, run, dedupKey, CASE_TYPE_DOC_WINDOW, SOURCE_TYPE_DOC, RULE_ID_DOC,
                        doc.getBukrs(), doc.getBelnr(), doc.getGjahr(), null, amount, doc.getWaers(), null);
                caseCreated += c[0];
                caseUpdated += c[1];
            }

            for (FiOpenItem oi : openItems) {
                String dedupKey = buildDedupKey(tenantId, CASE_TYPE_OPEN_ITEM_WINDOW, SOURCE_TYPE_OPEN_ITEM,
                        oi.getBukrs(), oi.getBelnr(), oi.getGjahr(), oi.getBuzei());
                int[] c = upsertCase(tenantId, run, dedupKey, CASE_TYPE_OPEN_ITEM_WINDOW, SOURCE_TYPE_OPEN_ITEM, RULE_ID_OPEN_ITEM,
                        oi.getBukrs(), oi.getBelnr(), oi.getGjahr(), oi.getBuzei(), oi.getOpenAmount(), oi.getCurrency(), oi.getDueDate());
                caseCreated += c[0];
                caseUpdated += c[1];
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

    /** severity: amount 기반 (>=1억 HIGH, >=1천만 MEDIUM, else LOW). sys_codes SEVERITY 일치 */
    private String resolveSeverity(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) return "LOW";
        if (amount.compareTo(AMOUNT_HIGH) >= 0) return "HIGH";
        if (amount.compareTo(AMOUNT_MEDIUM) >= 0) return "MEDIUM";
        return "LOW";
    }

    private BigDecimal resolveScore(String severity) {
        Integer s = SEVERITY_SCORE.get(severity != null ? severity.toUpperCase() : "LOW");
        return s != null ? BigDecimal.valueOf(s).setScale(4, RoundingMode.HALF_UP) : BigDecimal.valueOf(30).setScale(4, RoundingMode.HALF_UP);
    }

    private JsonNode buildEvidenceJson(String source, String window, String bukrs, String belnr, String gjahr, String buzei,
                                       BigDecimal amount, String currency, java.time.LocalDate dueDate, String vendor, String customer) {
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
        return root;
    }

    /** @return [created, updated] */
    private int[] upsertCase(Long tenantId, DetectRun run, String dedupKey, String caseType, String sourceType, String ruleId,
                             String bukrs, String belnr, String gjahr, String buzei,
                             BigDecimal amount, String currency, java.time.LocalDate dueDate) {
        AgentCase existing = agentCaseRepository.findByTenantIdAndDedupKey(tenantId, dedupKey).orElse(null);

        String severity = resolveSeverity(amount);
        BigDecimal score = resolveScore(severity);
        String reasonText = "DOC".equals(sourceType)
                ? "Detected in document window during scheduled run"
                : "Detected in open item window during scheduled run";
        String source = "DOC".equals(sourceType) ? "fi_doc_header" : "fi_open_item";
        String window = "DOC".equals(sourceType) ? RULE_ID_DOC : RULE_ID_OPEN_ITEM;

        if (existing == null) {
            JsonNode evidence = buildEvidenceJson(source, window, bukrs, belnr, gjahr, buzei, amount, currency, dueDate, null, null);
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
                    .status(AgentCaseStatus.OPEN)
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
            return new int[]{1, 0};
        } else {
            // P0: CLOSED/RESOLVED는 재오픈하지 않음 (2중작업 방지)
            if (existing.getStatus() == AgentCaseStatus.CLOSED || existing.getStatus() == AgentCaseStatus.RESOLVED) {
                existing.setLastDetectRunId(run.getRunId());
                existing.setUpdatedAt(Instant.now());
                agentCaseRepository.save(existing);
                return new int[]{0, 1};
            }
            // P0: detected_at 유지, updated_at만 갱신
            existing.setUpdatedAt(Instant.now());
            existing.setLastDetectRunId(run.getRunId());
            existing.setSeverity(severity);
            existing.setScore(score);
            existing.setReasonText(reasonText);
            existing.setEvidenceJson(buildEvidenceJson(source, window, bukrs, belnr, gjahr, buzei, amount, currency, dueDate, null, null));
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
            return new int[]{0, 1};
        }
    }
}
