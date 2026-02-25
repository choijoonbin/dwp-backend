package com.dwp.services.synapsex.service.demo;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 데모용 가맹점명 풀. 호출 시마다 랜덤 선택하여 매번 다른 가맹점이 나오도록 함.
 * 식당 20곳, 주점 10곳, 사적 유용 의심(유흥/골프 등), 이상 패턴(고액/원거리) 풀.
 */
public final class DemoMerchantPool {

    private static final List<String> RESTAURANTS = List.of(
            "오늘의한식당", "맛나초밥", "청담돌솥", "강남찌개마을", "역전회관",
            "삼겹살천국", "해물탕집", "김치찜명가", "파스타하우스", "중화루",
            "봉피양", "할매국밥", "평양면옥", "남포면옥", "본죽",
            "빕스", "애슐리", "스시로", "이조감자탕", "한촌설렁탕"
    );

    private static final List<String> BARS = List.of(
            "바오밥", "루프탑바", "스피킹버블", "이자카야산", "호프마루",
            "펍909", "라운지앤", "와인바쏘", "막걸리한잔", "술집달빛"
    );

    /** PRIVATE_USE_RISK: 유흥업소·골프장 등 사적 유용 의심 가맹점(MCC). */
    private static final List<String> PRIVATE_USE_MERCHANTS = List.of(
            "골프클럽A", "골프리조트B", "나이트클럽C", "KTV룸살롱", "유흥주점D",
            "사우나스파E", "마사지테라피F", "카지노바G", "호텔라운지H", "고급클럽I"
    );

    /** UNUSUAL_PATTERN: 고액 또는 원거리 결제 느낌 가맹점. */
    private static final List<String> UNUSUAL_PATTERN_MERCHANTS = List.of(
            "제주프리미엄골프", "부산해운대호텔", "강원스키리조트", "해외결제대행",
            "명품백화점", "대형유통본사", "해외출장전용", "고액연회장"
    );

    private DemoMerchantPool() {}

    /** 식당 리스트(20곳)에서 랜덤 선택 */
    public static String pickRandomRestaurant(ThreadLocalRandom rnd) {
        return RESTAURANTS.get(rnd.nextInt(RESTAURANTS.size()));
    }

    /** 주점 리스트(10곳)에서 랜덤 선택 */
    public static String pickRandomBar(ThreadLocalRandom rnd) {
        return BARS.get(rnd.nextInt(BARS.size()));
    }

    /** 시나리오에 맞는 가맹점 랜덤 선택: 심야는 주점, 그 외 식당 */
    public static String pickByScenario(boolean isLateNight, ThreadLocalRandom rnd) {
        return isLateNight ? pickRandomBar(rnd) : pickRandomRestaurant(rnd);
    }

    /** PRIVATE_USE_RISK 전용: 유흥업소·골프장 등 사적 유용 의심 가맹점 랜덤 선택 */
    public static String pickPrivateUseRisk(ThreadLocalRandom rnd) {
        return PRIVATE_USE_MERCHANTS.get(rnd.nextInt(PRIVATE_USE_MERCHANTS.size()));
    }

    /** UNUSUAL_PATTERN 전용: 고액/원거리 느낌 가맹점 랜덤 선택 */
    public static String pickUnusualPatternMerchant(ThreadLocalRandom rnd) {
        return UNUSUAL_PATTERN_MERCHANTS.get(rnd.nextInt(UNUSUAL_PATTERN_MERCHANTS.size()));
    }
}
