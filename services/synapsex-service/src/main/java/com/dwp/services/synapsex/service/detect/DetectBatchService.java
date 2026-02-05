package com.dwp.services.synapsex.service.detect;

import com.dwp.services.synapsex.audit.AuditEventConstants;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.AgentCaseStatus;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiOpenItem;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.DetectRunRepository;
import com.dwp.services.synapsex.repository.FiDocHeaderRepository;
import com.dwp.services.synapsex.repository.FiOpenItemRepository;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase B: Detect Run 배치 — window 내 전표 대상 룰 평가, Case Upsert
 * 클러스터 중복 방지: PostgreSQL advisory lock (tenant별)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectBatchService {

    private static final String RULE_ID_DOC = "WINDOW_DOC_ENTRY";
    private static final String RULE_ID_OPEN_ITEM = "WINDOW_OPEN_ITEM";
    /** Advisory lock key base (tenant별 고유 키: BASE + tenantId) */
    private static final long ADVISORY_LOCK_BASE = 1_000_000_000_000L;

    private final DetectRunRepository detectRunRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final AuditWriter auditWriter;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 단일 tenant에 대해 detect 배치 실행.
     * advisory lock 미획득 시 null 반환 (다른 인스턴스가 실행 중).
     */
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

    /**
     * SKIPPED 시 현재 실행 중인 run 정보 (락 미획득 원인 파악용).
     */
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
                String entityKey = doc.getBukrs() + "-" + doc.getBelnr() + "-" + doc.getGjahr();
                String dedupKey = tenantId + ":" + RULE_ID_DOC + ":" + entityKey;
                int[] c = upsertCase(tenantId, run, dedupKey, RULE_ID_DOC, doc.getBukrs(), doc.getBelnr(), doc.getGjahr(), null);
                caseCreated += c[0];
                caseUpdated += c[1];
            }

            for (FiOpenItem oi : openItems) {
                String entityKey = oi.getBukrs() + "-" + oi.getBelnr() + "-" + oi.getGjahr() + "-" + oi.getBuzei();
                String dedupKey = tenantId + ":" + RULE_ID_OPEN_ITEM + ":" + entityKey;
                int[] c = upsertCase(tenantId, run, dedupKey, RULE_ID_OPEN_ITEM, oi.getBukrs(), oi.getBelnr(), oi.getGjahr(), oi.getBuzei());
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

    /** @return [created, updated] */
    private int[] upsertCase(Long tenantId, DetectRun run, String dedupKey, String ruleId,
                             String bukrs, String belnr, String gjahr, String buzei) {
        AgentCase existing = agentCaseRepository.findByTenantIdAndDedupKey(tenantId, dedupKey).orElse(null);

        if (existing == null) {
            AgentCase created = AgentCase.builder()
                    .tenantId(tenantId)
                    .detectedAt(Instant.now())
                    .bukrs(bukrs)
                    .belnr(belnr)
                    .gjahr(gjahr)
                    .buzei(buzei)
                    .caseType(ruleId)
                    .severity("INFO")
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
            Map<String, Object> createTags = new HashMap<>();
            createTags.put("runId", run.getRunId());
            auditWriter.logCaseEvent(tenantId, AuditEventConstants.TYPE_CASE_CREATED, created.getCaseId(),
                    createAfter, null, createTags);
            return new int[]{1, 0};
        } else {
            existing.setUpdatedAt(Instant.now());
            existing.setDetectedAt(Instant.now());
            existing.setLastDetectRunId(run.getRunId());
            agentCaseRepository.save(existing);

            String entityKey = buzei != null ? bukrs + "-" + belnr + "-" + gjahr + "-" + buzei : bukrs + "-" + belnr + "-" + gjahr;
            Map<String, Object> updateAfter = new HashMap<>();
            updateAfter.put("detectedAt", Instant.now().toString());
            updateAfter.put("dedupKey", existing.getDedupKey());
            updateAfter.put("ruleId", ruleId);
            updateAfter.put("entityKey", entityKey);
            updateAfter.put("runId", run.getRunId());
            Map<String, Object> updateTags = new HashMap<>();
            updateTags.put("runId", run.getRunId());
            auditWriter.logCaseEvent(tenantId, AuditEventConstants.TYPE_CASE_UPDATED, existing.getCaseId(),
                    updateAfter,
                    Map.of("detectedAt", existing.getDetectedAt() != null ? existing.getDetectedAt().toString() : ""),
                    updateTags);
            return new int[]{0, 1};
        }
    }
}
