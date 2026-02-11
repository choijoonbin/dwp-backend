package com.dwp.services.synapsex.service.demo;

import com.dwp.services.synapsex.dto.demo.GenerateViolationRequest;
import com.dwp.services.synapsex.dto.demo.GenerateViolationResponse;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiDocItem;
import com.dwp.services.synapsex.entity.DetectRun;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.FiDocHeaderRepository;
import com.dwp.services.synapsex.repository.FiDocItemRepository;
import com.dwp.services.synapsex.service.detect.DetectBatchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 규정집 기반 시연용 위반/정상 시나리오 데이터 생성.
 * fi_doc_header + fi_doc_item 생성 후 즉시 Detect 배치 실행 → agent_case 생성 및 WebSocket 알림.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoViolationService {

    private static final String BUKRS = "1000";
    private static final String WAERS = "KRW";
    private static final String DOC_SOURCE = "DEMO";
    private static final String HKONT = "0000601000";
    private static final String SHKZG = "S";

    @Value("${workbench.redis.action-channel:workbench:case:action}")
    private String caseActionChannel;

    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final DetectBatchService detectBatchService;
    private final AgentCaseRepository agentCaseRepository;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;

    /**
     * 시나리오 유형·건수에 따라 전표 생성 → 즉시 탐지 Run → 생성된 케이스 WebSocket 알림.
     */
    @Transactional
    public GenerateViolationResponse generateViolation(Long tenantId, GenerateViolationRequest request) {
        int count = request.getCount() == null ? 1 : Math.min(10, Math.max(1, request.getCount()));
        String gjahr = String.valueOf(LocalDate.now().getYear());
        Instant now = Instant.now();
        List<String> docKeys = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String belnr = nextUniqueBelnr(tenantId, gjahr);
            FiDocHeader header = buildHeader(tenantId, belnr, gjahr, request.getScenarioType(), now);
            FiDocItem item = buildItem(tenantId, belnr, gjahr, request.getScenarioType(), header.getBudat(), header.getCputm(), now);
            fiDocHeaderRepository.save(header);
            fiDocItemRepository.save(item);
            docKeys.add(BUKRS + "-" + belnr + "-" + gjahr);
        }

        Instant windowFrom = now.minusSeconds(120);
        Instant windowTo = now.plusSeconds(5);
        DetectRun run = detectBatchService.runDetectBatch(tenantId, windowFrom, windowTo);

        List<Long> caseIds = new ArrayList<>();
        String runStatus = "SKIPPED";
        Long runId = null;
        if (run != null) {
            runStatus = run.getStatus();
            runId = run.getRunId();
            List<AgentCase> cases = agentCaseRepository.findByTenantIdAndLastDetectRunId(tenantId, run.getRunId());
            for (AgentCase c : cases) {
                caseIds.add(c.getCaseId());
                publishCaseCreated(tenantId, c.getCaseId());
            }
        }

        String message = String.format("시연 데이터 %d건 생성 완료. 탐지 Run: %s. 케이스 %d건 알림 발송.", count, runStatus, caseIds.size());
        return GenerateViolationResponse.builder()
                .createdDocKeys(docKeys)
                .createdCaseIds(caseIds)
                .detectRunId(runId)
                .detectRunStatus(runStatus)
                .message(message)
                .build();
    }

    private String nextUniqueBelnr(Long tenantId, String gjahr) {
        String belnr;
        int attempts = 0;
        do {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            belnr = "DEMO" + suffix; // 4 + 6 = 10 chars
            attempts++;
            if (attempts > 20) belnr = "DEMO" + String.format("%06d", (int)(System.nanoTime() % 1000000));
        } while (fiDocHeaderRepository.existsByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, BUKRS, belnr, gjahr));
        return belnr;
    }

    private FiDocHeader buildHeader(Long tenantId, String belnr, String gjahr,
                                    GenerateViolationRequest.ScenarioType scenarioType, Instant createdAt) {
        LocalDate budat;
        LocalTime cputm;
        String bktxt;
        switch (scenarioType) {
            case WEEKEND_MEAL -> {
                budat = randomWeekendDate();
                cputm = LocalTime.of(10 + (int)(Math.random() * 8), (int)(Math.random() * 60));
                bktxt = "팀내 주말 업무 식대";
            }
            case OVER_LIMIT -> {
                budat = LocalDate.now().minusDays((long)(Math.random() * 30));
                cputm = LocalTime.of(12, 30);
                bktxt = "대외 협력 미팅 식대";
            }
            case LATE_NIGHT -> {
                budat = LocalDate.now();
                int minutesAfter2330 = (int)(Math.random() * 151); // 23:30 ~ 02:00
                cputm = LocalTime.of(23, 30).plusMinutes(minutesAfter2330);
                if (cputm.getHour() < 6) budat = budat.minusDays(1);
                bktxt = Math.random() < 0.5 ? "주점 식대" : "이자카야 식대";
            }
            default -> { // NORMAL
                budat = randomWeekdayDate();
                cputm = LocalTime.of(9 + (int)(Math.random() * 9), (int)(Math.random() * 60));
                bktxt = "팀 내부 회의 식대";
            }
        }
        return FiDocHeader.builder()
                .tenantId(tenantId)
                .bukrs(BUKRS)
                .belnr(belnr)
                .gjahr(gjahr)
                .docSource(DOC_SOURCE)
                .budat(budat)
                .bldat(budat)
                .cpudt(budat)
                .cputm(cputm)
                .usnam("DEMO_USER")
                .tcode("DEMO")
                .blart("SA")
                .waers(WAERS)
                .bktxt(bktxt)
                .statusCode("POSTED")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private FiDocItem buildItem(Long tenantId, String belnr, String gjahr,
                                GenerateViolationRequest.ScenarioType scenarioType,
                                LocalDate budat, LocalTime cputm, Instant createdAt) {
        BigDecimal amount;
        String sgtxt;
        switch (scenarioType) {
            case WEEKEND_MEAL -> {
                amount = new BigDecimal("55000");
                sgtxt = "팀내 주말 업무 식대";
            }
            case OVER_LIMIT -> {
                amount = new BigDecimal("60000"); // 2인 6만원 → 인당 3만원 > 2만원 한도
                sgtxt = "대외 협력 미팅 식대";
            }
            case LATE_NIGHT -> {
                amount = new BigDecimal("80000");
                sgtxt = cputm.getHour() >= 22 || cputm.getHour() < 4 ? "주점" : "이자카야";
            }
            default -> {
                amount = new BigDecimal("35000"); // 2인 1.75만원/인 < 2만원
                sgtxt = "팀 내부 회의 식대";
            }
        }
        return FiDocItem.builder()
                .tenantId(tenantId)
                .bukrs(BUKRS)
                .belnr(belnr)
                .gjahr(gjahr)
                .buzei("001")
                .hkont(HKONT)
                .bschl("40")
                .shkzg(SHKZG)
                .wrbtr(amount)
                .dmbtr(amount)
                .waers(WAERS)
                .sgtxt(sgtxt)
                .paymentBlock(false)
                .disputeFlag(false)
                .createdAt(createdAt)
                .build();
    }

    private static LocalDate randomWeekendDate() {
        LocalDate base = LocalDate.now().minusDays(ThreadLocalRandom.current().nextInt(1, 60));
        while (base.getDayOfWeek() != DayOfWeek.SATURDAY && base.getDayOfWeek() != DayOfWeek.SUNDAY) {
            base = base.minusDays(1);
        }
        return base;
    }

    private static LocalDate randomWeekdayDate() {
        LocalDate base = LocalDate.now().minusDays(ThreadLocalRandom.current().nextInt(1, 60));
        while (base.getDayOfWeek() == DayOfWeek.SATURDAY || base.getDayOfWeek() == DayOfWeek.SUNDAY) {
            base = base.minusDays(1);
        }
        return base;
    }

    private void publishCaseCreated(Long tenantId, Long caseId) {
        redisTemplateProvider.ifAvailable(template -> {
            try {
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "case_created");
                payload.put("category", "CASE_ACTION");
                payload.put("case_id", String.valueOf(caseId));
                payload.put("tenant_id", tenantId);
                payload.put("title", "신규 케이스");
                payload.put("message", "시연 데이터로 케이스가 생성되었습니다. 케이스 ID: " + caseId);
                payload.put("at", Instant.now().toString());
                String json = objectMapper.writeValueAsString(payload);
                template.convertAndSend(caseActionChannel, json);
                log.debug("Published case_created: caseId={} channel={}", caseId, caseActionChannel);
            } catch (JsonProcessingException e) {
                log.warn("Failed to publish case_created: caseId={} {}", caseId, e.getMessage());
            }
        });
    }
}
