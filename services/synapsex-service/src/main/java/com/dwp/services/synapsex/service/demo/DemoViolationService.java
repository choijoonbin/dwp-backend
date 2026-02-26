package com.dwp.services.synapsex.service.demo;

import com.dwp.services.synapsex.dto.demo.GenerateViolationRequest;
import com.dwp.services.synapsex.dto.demo.GenerateViolationResponse;
import com.dwp.services.synapsex.entity.BpParty;
import com.dwp.services.synapsex.entity.FiDocHeader;
import com.dwp.services.synapsex.entity.FiDocItem;
import com.dwp.services.synapsex.entity.MccMaster;
import com.dwp.services.synapsex.repository.BpPartyRepository;
import com.dwp.services.synapsex.repository.FiDocHeaderRepository;
import com.dwp.services.synapsex.repository.FiDocItemRepository;
import com.dwp.services.synapsex.repository.MccMasterRepository;
import com.dwp.services.synapsex.repository.UserHrCalendarRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 규정집 기반 시연용 위반/정상 시나리오 데이터 생성 (POST /api/synapse/demo/generate-violation).
 * 시나리오별 생성 규칙 하드코딩: HOLIDAY_USAGE, DUPLICATE_SUSPECT, SPLIT_PAYMENT, PRIVATE_USE_RISK, LIMIT_EXCEED, UNUSUAL_PATTERN, DEFAULT.
 * 공통: shkzg='H'(대변) 고정으로 화면에서 양수(+) 표시. intended_risk_type을 Aura 엔진에 전달하여 분석 정합성 유지.
 * 규정 v2.0: hrStatus(근무/휴가), mccCode(업종), budgetExceeded(한도초과여부) 시나리오별 주입.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoViolationService {

    private static final String BUKRS = "1000";
    private static final String WAERS = "KRW";
    private static final String DOC_SOURCE = "DEMO";
    /** 비용 계정(식대 등). 데모는 화면에서 양수(+) 표기를 위해 대변(H) 사용. (차변 S 시 FE가 음수 표기) */
    private static final String HKONT = "0000601000";
    /** 비용(Expense) 탐지 데모: 대변(H)으로 설정해 금액이 화면에서 양수로 표시되도록 함. 무분별한 'S' 사용 금지. */
    private static final String SHKZG = "H";
    /** fi_doc_item PK 필수 — 라인번호(1건당 001) */
    private static final String BUZEI_FIRST_LINE = "001";
    private static final LocalDate HR_SEED_START = LocalDate.of(2026, 2, 1);
    private static final LocalDate HR_SEED_END = LocalDate.of(2026, 3, 31);

    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final BpPartyRepository bpPartyRepository;
    private final MccMasterRepository mccMasterRepository;
    private final UserHrCalendarRepository userHrCalendarRepository;
    private final DemoDetectTrigger demoDetectTrigger;

    /**
     * 시나리오·intensity·건수에 따라 전표 랜덤 생성 → fi_doc 인서트 후 생성된 ID 리스트 반환, Detect 비동기 실행 후 Aura 분석 자동 트리거.
     * authorization/userId가 있으면 케이스 생성 직후 Aura Thought Chain 자동 호출에 사용.
     */
    @Transactional
    public GenerateViolationResponse generateViolation(Long tenantId, GenerateViolationRequest request,
                                                       String authorization, Long userId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Long ownerUserId = userId != null ? userId : 1L;
        GenerateViolationRequest.ScenarioType scenarioType = request.getScenarioType();
        String gjahr = String.valueOf(LocalDate.now().getYear());
        Instant now = Instant.now();
        List<String> docKeys = new ArrayList<>();

        switch (scenarioType) {
            case HOLIDAY_USAGE, LATE_NIGHT, WEEKEND_MEAL -> generateHolidayUsage(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
            case DUPLICATE_SUSPECT -> generateDuplicateSuspect(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
            case SPLIT_PAYMENT -> generateSplitPayment(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
            case PRIVATE_USE_RISK -> generatePrivateUseRisk(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
            case LIMIT_EXCEED, OVER_LIMIT -> generateLimitExceed(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
            case UNUSUAL_PATTERN -> generateUnusualPattern(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
            default -> generateDefault(tenantId, ownerUserId, request, gjahr, now, rnd, docKeys);
        }

        Instant windowFrom = now.minusSeconds(120);
        Instant windowTo = now.plusSeconds(5);
        demoDetectTrigger.runDetectThenPublish(tenantId, windowFrom, windowTo, authorization, userId);

        String message = String.format("시연 데이터 %d건 생성 완료. 탐지(Thought Chain)가 비동기로 시작되었습니다.", docKeys.size());
        return GenerateViolationResponse.builder()
                .createdDocKeys(docKeys)
                .createdCaseIds(List.of())
                .detectRunId(null)
                .detectRunStatus("ASYNC_STARTED")
                .message(message)
                .build();
    }

    /** HOLIDAY_USAGE: 결제 일자를 주말 또는 공휴일, 심야 시간으로 설정. */
    private void generateHolidayUsage(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                      ThreadLocalRandom rnd, List<String> docKeys) {
        int totalSec = 23 * 3600 + rnd.nextInt(4 * 3600);
        if (totalSec >= 86400) totalSec -= 86400;
        LocalTime cputm = LocalTime.ofSecondOfDay(totalSec);
        String merchantName = DemoMerchantPool.pickByScenario(true, rnd);
        int count = request.getCount() == null ? 1 : Math.max(1, request.getCount());
        for (int i = 0; i < count; i++) {
            LocalDate budat = randomWeekendDate(rnd);
            String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
            FiDocHeader header = buildHeader(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.HOLIDAY_USAGE, merchantName, now, rnd);
            header.setBudat(budat);
            header.setBldat(budat);
            header.setCpudt(budat);
            header.setCputm(cputm);
            header.setBktxt(merchantName + " / 심야 식대");
            header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.HOLIDAY_USAGE.name());
            setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.HOLIDAY_USAGE, rnd);
            BigDecimal amount = resolveAmount(request, rnd);
            FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.HOLIDAY_USAGE, budat, cputm, amount, now);
            saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
        }
    }

    /** DUPLICATE_SUSPECT: count=중복 그룹 수. 각 그룹마다 동일 금액·동일 가맹점 전표를 2~4건(랜덤) 생성 → 총 건수는 count의 2배 이상. */
    private void generateDuplicateSuspect(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                          ThreadLocalRandom rnd, List<String> docKeys) {
        int groupCount = request.getCount() == null ? 1 : Math.max(1, request.getCount());
        for (int g = 0; g < groupCount; g++) {
            String merchantName = DemoMerchantPool.pickRandomRestaurant(rnd);
            LocalDate sameDate = randomWeekdayDate(rnd);
            int limit = request.getLimitAmountKrwResolved();
            int amountKr = (int) (limit * (0.5 + rnd.nextDouble()));
            if (amountKr < 1000) amountKr = 1000;
            BigDecimal sameAmount = BigDecimal.valueOf(amountKr);
            LocalTime cputm = LocalTime.of(9 + rnd.nextInt(8), rnd.nextInt(60), rnd.nextInt(60));
            int docsInGroup = 2 + rnd.nextInt(3);
            for (int i = 0; i < docsInGroup; i++) {
                String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
                FiDocHeader header = buildHeaderForScenario(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.DUPLICATE_SUSPECT, merchantName, sameDate, cputm, now, rnd);
                header.setBktxt(merchantName + " / 중복 청구 의심");
                header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.DUPLICATE_SUSPECT.name());
                setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.DUPLICATE_SUSPECT, rnd);
                FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.DUPLICATE_SUSPECT, sameDate, cputm, sameAmount, now);
                saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
            }
        }
    }

    /** SPLIT_PAYMENT: count 무시. 한도를 초과하는 금액을 2~3건으로 쪼갠 전표 세트 생성. */
    private void generateSplitPayment(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                      ThreadLocalRandom rnd, List<String> docKeys) {
        String merchantName = DemoMerchantPool.pickRandomRestaurant(rnd);
        LocalDate sameDate = randomWeekdayDate(rnd);
        LocalTime cputm = LocalTime.of(9 + rnd.nextInt(8), rnd.nextInt(60), rnd.nextInt(60));
        int limit = request.getLimitAmountKrwResolved();
        int totalKr = (int) (limit * (1.5 + rnd.nextDouble() * 1.5));
        if (totalKr < limit + 1000) totalKr = limit + 1000;
        int numParts = 2 + rnd.nextInt(2);
        List<Integer> partsKr = randomSplit(totalKr, numParts, rnd);
        for (int i = 0; i < numParts; i++) {
            String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
            BigDecimal amount = BigDecimal.valueOf(partsKr.get(i));
            FiDocHeader header = buildHeaderForScenario(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.SPLIT_PAYMENT, merchantName, sameDate, cputm, now, rnd);
            header.setBktxt(merchantName + " / 분할 결제");
            header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.SPLIT_PAYMENT.name());
            setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.SPLIT_PAYMENT, rnd);
            FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.SPLIT_PAYMENT, sameDate, cputm, amount, now);
            saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
        }
    }

    /** PRIVATE_USE_RISK: 유흥업소·골프장 등 사적 유용 의심 가맹점명(MCC) 사용. */
    private void generatePrivateUseRisk(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                         ThreadLocalRandom rnd, List<String> docKeys) {
        String merchantName = DemoMerchantPool.pickPrivateUseRisk(rnd);
        int count = request.getCount() == null ? 1 : Math.max(1, request.getCount());
        for (int i = 0; i < count; i++) {
            String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
            FiDocHeader header = buildHeader(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.PRIVATE_USE_RISK, merchantName, now, rnd);
            header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.PRIVATE_USE_RISK.name());
            setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.PRIVATE_USE_RISK, rnd);
            BigDecimal amount = resolveAmount(request, rnd);
            FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.PRIVATE_USE_RISK,
                    header.getBudat(), header.getCputm(), amount, now);
            saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
        }
    }

    /** LIMIT_EXCEED: 설정된 limitAmountKrw를 초과하는 단건 전표 생성. */
    private void generateLimitExceed(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                     ThreadLocalRandom rnd, List<String> docKeys) {
        int limit = request.getLimitAmountKrwResolved();
        int amountKr = (int) (limit * (1.01 + rnd.nextDouble() * 0.49));
        if (amountKr <= limit) amountKr = limit + 1000;
        BigDecimal amount = BigDecimal.valueOf(amountKr);
        String merchantName = DemoMerchantPool.pickRandomRestaurant(rnd);
        String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
        FiDocHeader header = buildHeader(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.LIMIT_EXCEED, merchantName, now, rnd);
        header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.LIMIT_EXCEED.name());
        setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.LIMIT_EXCEED, rnd);
        FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.LIMIT_EXCEED,
                header.getBudat(), header.getCputm(), amount, now);
        saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
    }

    /** UNUSUAL_PATTERN: 평소 거래 패턴과 동떨어진 고액 또는 원거리 결제. */
    private void generateUnusualPattern(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                         ThreadLocalRandom rnd, List<String> docKeys) {
        int limit = request.getLimitAmountKrwResolved();
        int amountKr = (int) (limit * (3 + rnd.nextDouble() * 7));
        if (amountKr < limit * 2) amountKr = limit * 2 + rnd.nextInt(10000);
        BigDecimal amount = BigDecimal.valueOf(amountKr);
        String merchantName = DemoMerchantPool.pickUnusualPatternMerchant(rnd);
        String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
        FiDocHeader header = buildHeader(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.UNUSUAL_PATTERN, merchantName, now, rnd);
        header.setBktxt(merchantName + " / 고액 또는 원거리 결제");
        header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.UNUSUAL_PATTERN.name());
        setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.UNUSUAL_PATTERN, rnd);
        FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.UNUSUAL_PATTERN,
                header.getBudat(), header.getCputm(), amount, now);
        saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
    }

    /** DEFAULT: count·intensity에 따른 일반 생성. */
    private void generateDefault(Long tenantId, Long ownerUserId, GenerateViolationRequest request, String gjahr, Instant now,
                                 ThreadLocalRandom rnd, List<String> docKeys) {
        int count = request.getCount() == null ? 1 : Math.max(1, request.getCount());
        for (int i = 0; i < count; i++) {
            String belnr = nextUniqueBelnr(tenantId, gjahr, rnd);
            String merchantName = DemoMerchantPool.pickByScenario(false, rnd);
            FiDocHeader header = buildHeader(tenantId, belnr, gjahr, GenerateViolationRequest.ScenarioType.DEFAULT, merchantName, now, rnd);
            header.setIntendedRiskType(GenerateViolationRequest.ScenarioType.DEFAULT.name());
            setContextForScenario(header, tenantId, ownerUserId, GenerateViolationRequest.ScenarioType.DEFAULT, rnd);
            BigDecimal amount = resolveAmount(request, rnd);
            FiDocItem item = buildItem(tenantId, belnr, gjahr, merchantName, GenerateViolationRequest.ScenarioType.DEFAULT,
                    header.getBudat(), header.getCputm(), amount, now);
            saveDocAndItem(tenantId, ownerUserId, header, item, gjahr, docKeys);
        }
    }

    /** totalKr을 numParts개로 나눈 양수 금액 리스트 (합 = totalKr). 각 part 최소 1000원. */
    private static List<Integer> randomSplit(int totalKr, int numParts, ThreadLocalRandom rnd) {
        if (numParts <= 1) return List.of(totalKr);
        double[] ratios = new double[numParts];
        for (int i = 0; i < numParts; i++) ratios[i] = 0.1 + rnd.nextDouble();
        double sumR = 0;
        for (double r : ratios) sumR += r;
        List<Integer> out = new ArrayList<>();
        int allocated = 0;
        for (int i = 0; i < numParts - 1; i++) {
            int part = Math.max(1000, (int) (totalKr * ratios[i] / sumR));
            out.add(part);
            allocated += part;
        }
        out.add(Math.max(1000, totalKr - allocated));
        int sum = out.stream().mapToInt(Integer::intValue).sum();
        if (sum != totalKr) out.set(0, out.get(0) + (totalKr - sum));
        return out;
    }

    /** 규정 v2.0: 시나리오별 hrStatus/mccCode/budgetExceededFlag를 마스터 기반으로 주입. */
    private void setContextForScenario(FiDocHeader header, Long tenantId, Long ownerUserId,
                                       GenerateViolationRequest.ScenarioType scenarioType, ThreadLocalRandom rnd) {
        if (header == null) return;
        String hrStatus = resolveHrStatus(tenantId, ownerUserId, header.getBudat());
        String mccCode = resolveMccCode(tenantId, scenarioType, rnd);
        String budgetExceededFlag = resolveBudgetExceededFlag(scenarioType, rnd);

        header.setHrStatus(hrStatus);
        header.setMccCode(mccCode);
        header.setBudgetExceededFlag(budgetExceededFlag);
        header.setBudgetExceeded("Y".equalsIgnoreCase(budgetExceededFlag));
    }

    private String resolveHrStatus(Long tenantId, Long userId, LocalDate eventDate) {
        if (tenantId != null && userId != null && eventDate != null) {
            return userHrCalendarRepository.findByTenantIdAndUserIdAndEventDate(tenantId, userId, eventDate)
                    .map(cal -> cal.getStatusCode() != null ? cal.getStatusCode() : "WORKING")
                    .orElseGet(() -> fallbackHrStatus(eventDate));
        }
        return fallbackHrStatus(eventDate);
    }

    private static String fallbackHrStatus(LocalDate eventDate) {
        if (eventDate == null) return "WORKING";
        return switch (eventDate.getDayOfWeek()) {
            case SATURDAY, SUNDAY -> "OFF";
            default -> "WORKING";
        };
    }

    private String resolveMccCode(Long tenantId, GenerateViolationRequest.ScenarioType scenarioType, ThreadLocalRandom rnd) {
        List<String> preferredCodes = preferredMccCodes(scenarioType);
        if (tenantId != null && !preferredCodes.isEmpty()) {
            List<MccMaster> preferred = mccMasterRepository.findByTenantIdAndMccCodeIn(tenantId, preferredCodes);
            if (!preferred.isEmpty()) {
                return preferred.get(rnd.nextInt(preferred.size())).getMccCode();
            }
        }
        if (tenantId != null) {
            List<MccMaster> all = mccMasterRepository.findByTenantId(tenantId);
            if (!all.isEmpty()) {
                return all.get(rnd.nextInt(all.size())).getMccCode();
            }
        }
        return "5812";
    }

    private static List<String> preferredMccCodes(GenerateViolationRequest.ScenarioType scenarioType) {
        return switch (scenarioType) {
            case HOLIDAY_USAGE, LATE_NIGHT, WEEKEND_MEAL -> List.of("5813", "5812", "5814");
            case DUPLICATE_SUSPECT -> List.of("5812", "5814");
            case SPLIT_PAYMENT -> List.of("5812");
            case PRIVATE_USE_RISK -> List.of("7992", "5813", "7011");
            case LIMIT_EXCEED, OVER_LIMIT -> List.of("7011", "4722", "5812");
            case UNUSUAL_PATTERN -> List.of("4722", "7011", "7992", "5813");
            case DEFAULT, NORMAL -> List.of("5812", "5814");
        };
    }

    private static String resolveBudgetExceededFlag(GenerateViolationRequest.ScenarioType scenarioType, ThreadLocalRandom rnd) {
        return switch (scenarioType) {
            case SPLIT_PAYMENT, LIMIT_EXCEED, OVER_LIMIT -> "Y";
            case UNUSUAL_PATTERN -> rnd.nextBoolean() ? "Y" : "N";
            default -> "N";
        };
    }

    private void saveDocAndItem(Long tenantId, Long ownerUserId, FiDocHeader header, FiDocItem item, String gjahr, List<String> docKeys) {
        header.setTenantId(tenantId);
        header.setUserId(ownerUserId);
        header.setUsnam(String.valueOf(ownerUserId));
        header.setCreatedBy(ownerUserId);
        header.setUpdatedBy(ownerUserId);
        fiDocHeaderRepository.saveAndFlush(header);
        try {
            fiDocItemRepository.saveAndFlush(item);
            log.info("Demo violation saved fi_doc_item: tenantId={} bukrs={} belnr={} gjahr={} buzei={}",
                    tenantId, BUKRS, item.getBelnr(), gjahr, item.getBuzei());
        } catch (Exception e) {
            log.error("Demo violation fi_doc_item save failed: tenantId={} belnr={} gjahr={} buzei={}",
                    tenantId, item.getBelnr(), gjahr, item.getBuzei(), e);
            throw e;
        }
        docKeys.add(BUKRS + "-" + item.getBelnr() + "-" + gjahr);
    }

    private static boolean isHolidayUsageScenario(GenerateViolationRequest.ScenarioType t) {
        return t == GenerateViolationRequest.ScenarioType.HOLIDAY_USAGE
                || t == GenerateViolationRequest.ScenarioType.LATE_NIGHT
                || t == GenerateViolationRequest.ScenarioType.WEEKEND_MEAL;
    }

    /**
     * intensity 기준 랜덤 금액: VIOLATION=규정 150%~500%(끝자리 랜덤), NORMAL=50%~90%.
     * amount_range 지정 시 해당 구간 내 랜덤. **항상 양수** 반환.
     */
    private BigDecimal resolveAmount(GenerateViolationRequest request, ThreadLocalRandom rnd) {
        if (request.hasAmountRange()) {
            int min = Math.abs(request.getAmountRangeMin());
            int max = Math.abs(request.getAmountRangeMax());
            if (min > max) { int t = min; min = max; max = t; }
            int amount = min >= max ? Math.max(1, min) : Math.max(1, rnd.nextInt(min, max + 1));
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
        minKr = Math.max(1, minKr);
        maxKr = Math.max(1, maxKr);
        int amountKr = minKr + rnd.nextInt(Math.max(1, maxKr - minKr + 1));
        return BigDecimal.valueOf(Math.max(1, amountKr));
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

    /** SPLIT_PAYMENT 전용: 동일일·동일 가맹점·지정 시간으로 헤더 생성. */
    private FiDocHeader buildHeaderForScenario(Long tenantId, String belnr, String gjahr,
                                               GenerateViolationRequest.ScenarioType scenarioType,
                                               String merchantName, LocalDate sameDate, LocalTime cputm,
                                               Instant createdAt, ThreadLocalRandom rnd) {
        String bktxt = merchantName + " / 분할 결제";
        return FiDocHeader.builder()
                .tenantId(tenantId)
                .bukrs(BUKRS)
                .belnr(belnr)
                .gjahr(gjahr)
                .docSource(DOC_SOURCE)
                .budat(sameDate)
                .bldat(sameDate)
                .cpudt(sameDate)
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

    /** 시나리오별 결제일·결제시간(구간 내 분·초 랜덤), 가맹점명을 bktxt에 반영. */
    private FiDocHeader buildHeader(Long tenantId, String belnr, String gjahr,
                                    GenerateViolationRequest.ScenarioType scenarioType,
                                    String merchantName, Instant createdAt, ThreadLocalRandom rnd) {
        LocalDate budat;
        LocalTime cputm;
        String bktxt = merchantName;
        switch (scenarioType) {
            case HOLIDAY_USAGE, WEEKEND_MEAL -> {
                budat = randomWeekendDate(rnd);
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
            case LATE_NIGHT -> {
                budat = randomDateInWindow(rnd, false);
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
            case LIMIT_EXCEED, OVER_LIMIT -> {
                budat = randomWeekdayDate(rnd);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(12, min, sec);
                bktxt = merchantName + " / 대외 협력 미팅 식대";
            }
            case DUPLICATE_SUSPECT, SPLIT_PAYMENT -> {
                budat = randomWeekdayDate(rnd);
                int hour = 9 + rnd.nextInt(8);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 분할 결제";
            }
            case PRIVATE_USE_RISK -> {
                budat = randomWeekdayDate(rnd);
                int hour = 9 + rnd.nextInt(9);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 업무 무관 가맹점";
            }
            case UNUSUAL_PATTERN -> {
                budat = randomWeekdayDate(rnd);
                int hour = 8 + rnd.nextInt(12);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 이상 거래 패턴";
            }
            case DEFAULT, NORMAL -> {
                budat = randomWeekdayDate(rnd);
                int hour = 9 + rnd.nextInt(9);
                int min = rnd.nextInt(60);
                int sec = rnd.nextInt(60);
                cputm = LocalTime.of(hour, min, sec);
                bktxt = merchantName + " / 팀 내부 회의 식대";
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
        BigDecimal positiveAmount = amount != null && amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount;
        if (positiveAmount == null || positiveAmount.compareTo(BigDecimal.ZERO) <= 0) {
            positiveAmount = BigDecimal.ONE;
        }
        String sgtxt = merchantName;
        if (isHolidayUsageScenario(scenarioType)) {
            sgtxt = (cputm.getHour() >= 22 || cputm.getHour() < 4) ? merchantName + " 주점" : merchantName + " 이자카야";
        } else if (scenarioType == GenerateViolationRequest.ScenarioType.SPLIT_PAYMENT || scenarioType == GenerateViolationRequest.ScenarioType.DUPLICATE_SUSPECT) {
            sgtxt = merchantName + " 분할 결제";
        } else if (scenarioType == GenerateViolationRequest.ScenarioType.PRIVATE_USE_RISK) {
            sgtxt = merchantName + " 업무 무관";
        } else if (scenarioType == GenerateViolationRequest.ScenarioType.UNUSUAL_PATTERN) {
            sgtxt = merchantName + " 이상 패턴";
        }
        FiDocItem item = FiDocItem.builder()
                .tenantId(tenantId)
                .bukrs(BUKRS)
                .belnr(belnr)
                .gjahr(gjahr)
                .buzei(BUZEI_FIRST_LINE)
                .hkont(HKONT)
                .bschl("40")
                .shkzg(SHKZG)
                .wrbtr(positiveAmount)
                .dmbtr(positiveAmount)
                .waers(WAERS)
                .sgtxt(sgtxt)
                .paymentBlock(false)
                .disputeFlag(false)
                .createdAt(createdAt)
                .build();
        assignRandomParty(item, tenantId);
        return item;
    }

    /**
     * 테스트 전표 라인을 bp_party 시드와 연결하기 위해 lifnr/kunnr를 무작위 주입한다.
     * - VENDOR면 lifnr, CUSTOMER면 kunnr를 채운다.
     * - 시드가 없으면 null 유지(기존 동작과 호환).
     */
    private void assignRandomParty(FiDocItem item, Long tenantId) {
        if (tenantId == null || item == null) return;
        List<BpParty> parties = bpPartyRepository.findByTenantIdAndPartyTypeInOrderByPartyCodeAsc(
                tenantId, List.of("VENDOR", "CUSTOMER"));
        if (parties.isEmpty()) return;
        BpParty selected = parties.get(ThreadLocalRandom.current().nextInt(parties.size()));
        if ("VENDOR".equalsIgnoreCase(selected.getPartyType())) {
            item.setLifnr(selected.getPartyCode());
            item.setKunnr(null);
        } else if ("CUSTOMER".equalsIgnoreCase(selected.getPartyType())) {
            item.setKunnr(selected.getPartyCode());
            item.setLifnr(null);
        }
    }

    private static LocalDate randomWeekendDate(ThreadLocalRandom rnd) {
        return randomDateInWindow(rnd, true);
    }

    private static LocalDate randomWeekdayDate(ThreadLocalRandom rnd) {
        return randomDateInWindow(rnd, false);
    }

    private static LocalDate randomDateInWindow(ThreadLocalRandom rnd, boolean weekend) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(HR_SEED_START, HR_SEED_END);
        for (int i = 0; i < 200; i++) {
            LocalDate date = HR_SEED_START.plusDays(rnd.nextLong(days + 1));
            boolean isWeekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (weekend == isWeekend) {
                return date;
            }
        }
        return weekend ? HR_SEED_START.with(DayOfWeek.SATURDAY) : HR_SEED_START.with(DayOfWeek.MONDAY);
    }
}
