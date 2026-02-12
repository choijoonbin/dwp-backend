package com.dwp.services.synapsex.service.demo;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 데모용 가맹점명 풀. 호출 시마다 랜덤 선택하여 매번 다른 가맹점이 나오도록 함.
 * 식당 20곳, 주점 10곳 등 업종별 리스트에서 Random 라이브러리로 선택.
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
}
