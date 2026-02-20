package com.dwp.services.synapsex.service.demo;

import com.dwp.services.synapsex.dto.demo.GenerateViolationRequest;
import com.dwp.services.synapsex.dto.demo.GenerateViolationResponse;
import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiDocItem;
import com.dwp.services.synapsex.repository.FiDocHeaderRepository;
import com.dwp.services.synapsex.repository.FiDocItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 규정집 기반 시연용 위반/정상 시나리오 데이터 생성 (generate_demo_scenario).
 * 호출 시마다 랜덤: 금액(intensity 150%~500% / 50%~90%), 가맹점명(식당20/주점10 풀), 결제시간(시나리오 구간 내 분·초), 전표번호(UUID).
 * fi_doc_header + fi_doc_item 저장 후 Detect 비동기 호출 → 생성된 데이터 ID 리스트 반환.
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
    /** fi_doc_item PK 필수 — 라인번호(1건당 001) */
    private static final String BUZEI_FIRST_LINE = "001";

    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final DemoDetectTrigger demoDetectTrigger;

    /**
     * 시나리오·intensity·건수에 따라 전표 랜덤 생성 → fi_doc 인서트 후 생성된 ID 리스트 반환, Detect 비동기 실행 후 Aura 분석 자동 트리거.
     * authorization/userId가 있으면 케이스 생성 직후 Aura Thought Chain 자동 호출에 사용.
     */
    @Transactional
    public GenerateViolationResponse generateViolation(Long tenantId, GenerateViolationRequest request,
                                                       String authorization, Long userId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int count = request.getCount() == null ? 1 : Math.min(10, Math.max(1, request.getCount()));
        String gjahr = String.valueOf(LocalDate.now().getYear());
        Instant now = Instant.now();
        List<String> docKeys = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
            String merchantName = DemoMerchantPool.pickByScenario(
                    request.getScenarioType() == GenerateViolationRequest.ScenarioType.LATE_NIGHT, rnd);
            FiDocHeader header = buildHeader(tenantId, belnr, gjahr, request.getScenarioType(), merchantName, now, rnd);
            BigDecimal amount = resolveAmount(request, rnd);
            FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, request.getScenarioType(),
                    header.getBudat(), header.getCputm(), amount, now);
            // FK(fi_doc_item -> fi_doc_header) 보장: header를 먼저 flush 후 item 저장
            fiDocHeaderRepository.saveAndFlush(header);
            fiDocItemRepository.saveAndFlush(item);
            docKeys.add(BUKRS + "-" + belnr + "-" + gjahr);
        }

        Instant windowFrom = now.minusSeconds(120);
        Instant windowTo = now.plusSeconds(5);
        demoDetectTrigger.runDetectThenPublish(tenantId, windowFrom, windowTo, authorization, userId);

        String message = String.format("시연 데이터 %d건 생성 완료. 탐지(Thought Chain)가 비동기로 시작되었습니다.", count);
        return GenerateViolationResponse.builder()
                .createdDocKeys(docKeys)
                .createdCaseIds(List.of())
                .detectRunId(null)
                .detectRunStatus("ASYNC_STARTED")
                .message(message)
                .build();
    }

    /**
     * intensity 기준 랜덤 금액: VIOLATION=규정 150%~500%(끝자리 랜덤), NORMAL=50%~90%.
     * amount_range 지정 시 해당 구간 내 랜덤.
     */
    private BigDecimal resolveAmount(GenerateViolationRequest request, ThreadLocalRandom rnd) {
        if (request.hasAmountRange()) {
            int min = request.getAmountRangeMin();
            int max = request.getAmountRangeMax();
            int amount = min >= max ? min : rnd.nextInt(min, max + 1);
            return BigDecimal.valueOf(amount);
        }
        int limit = request.getLimitAmountKrwResolved();
        boolean violation = request.getIntensity() == GenerateViolationRequest.Intensity.VIOLATION
                || request.getIntensity() == GenerateViolationRequest.Intensity.WARNING;
        int minKr;
        int maxKr;
        if (violation) {
            minKr = (int) (limit * 1.5);
            maxKr = (int) (limit * 5.0);
        } else {
            minKr = (int) (limit * 0.5);
            maxKr = (int) (limit * 0.9);
        }
        if (maxKr <= minKr) maxKr = minKr + 1;
        int amountKr = minKr + rnd.nextInt(maxKr - minKr + 1);
        return BigDecimal.valueOf(amountKr);
    }

    /** UUID 기반 전표번호 생성, 중복 시 랜덤 시퀀스로 재시도. */
    private String nextUniqueBelnr(Long tenantId, String gjahr, ThreadLocalRandom rnd) {
        String belnr;
        int attempts = 0;
        do {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
            belnr = "D" + suffix;
            if (belnr.length() > 10) belnr = belnr.substring(0, 10);
            attempts++;
            if (attempts > 25) {
                belnr = "D" + String.format("%09d", rnd.nextInt(1_000_000_000));
            }
        } while (fiDocHeaderRepository.existsByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, BUKRS, belnr, gjahr));
        return belnr;
    }

    /** 시나리오별 결제일·결제시간(구간 내 분·초 랜덤), 가맹점명을 bktxt에 반영. */
    private FiDocHeader buildHeader(Long tenantId, String belnr, String gjahr,
                                    GenerateViolationRequest.ScenarioType scenarioType,
                                    String merchantName, Instant createdAt, ThreadLocalRandom rnd) {
        LocalDate budat;
        LocalTime cputm;
        String bktxt = merchantName;
        switch (scenarioType) {
            case WEEKEND_MEAL -> {
                budat = randomWeekendDate(rnd);
                int hour = 10 + rnd.nextInt(8);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 팀내 주말 업무 식대";
            }
            case OVER_LIMIT -> {
                long daysAgo = rnd.nextLong(1, 31);
                budat = LocalDate.now().minusDays(daysAgo);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(12, min, sec);
                bktxt = merchantName + " / 대외 협력 미팅 식대";
            }
            case LATE_NIGHT -> {
                budat = LocalDate.now();
                int secFrom2300 = rnd.nextInt(5 * 3600);
                int totalSec = 23 * 3600 + secFrom2300;
                if (totalSec >= 86400) {
                    budat = budat.plusDays(1);
                    cputm = LocalTime.ofSecondOfDay(totalSec - 86400);
                } else {
                    cputm = LocalTime.ofSecondOfDay(totalSec);
                }
                bktxt = merchantName + " / 심야 식대";
            }
            case SPLIT_PAYMENT -> {
                budat = randomWeekdayDate(rnd);
                int hour = 9 + rnd.nextInt(8);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 분할 결제";
            }
            default -> {
                budat = randomWeekdayDate(rnd);
                int hour = 9 + rnd.nextInt(9);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 팀 내부 회의 식대";
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

    private FiDocItem buildItem(Long tenantId, String belnr, String gjahr, String merchantName,
                                GenerateViolationRequest.ScenarioType scenarioType,
                                LocalDate budat, LocalTime cputm, BigDecimal amount, Instant createdAt) {
        String sgtxt = merchantName;
        if (scenarioType == GenerateViolationRequest.ScenarioType.LATE_NIGHT) {
            sgtxt = (cputm.getHour() >= 22 || cputm.getHour() < 4) ? merchantName + " 주점" : merchantName + " 이자카야";
        } else if (scenarioType == GenerateViolationRequest.ScenarioType.SPLIT_PAYMENT) {
            sgtxt = merchantName + " 분할 결제";
        }
        return FiDocItem.builder()
                .tenantId(tenantId)
                .bukrs(BUKRS)
                .belnr(belnr)
                .gjahr(gjahr)
                .buzei(BUZEI_FIRST_LINE)
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

    private static LocalDate randomWeekendDate(ThreadLocalRandom rnd) {
        LocalDate base = LocalDate.now().minusDays(rnd.nextInt(1, 60));
        while (base.getDayOfWeek() != DayOfWeek.SATURDAY && base.getDayOfWeek() != DayOfWeek.SUNDAY) {
            base = base.minusDays(1);
        }
        return base;
    }

    private static LocalDate randomWeekdayDate(ThreadLocalRandom rnd) {
        LocalDate base = LocalDate.now().minusDays(rnd.nextInt(1, 60));
        while (base.getDayOfWeek() == DayOfWeek.SATURDAY || base.getDayOfWeek() == DayOfWeek.SUNDAY) {
            base = base.minusDays(1);
        }
        return base;
    }
}
