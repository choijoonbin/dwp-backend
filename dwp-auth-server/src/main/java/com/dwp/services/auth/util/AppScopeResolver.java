package com.dwp.services.auth.util;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 앱 코드별 역할 범위 정의.
 * SynapseX Admin Users 탭 등 "앱 사용 등록 사용자" 조회 시 해당 앱의 역할만 필터링할 때 사용.
 */
public final class AppScopeResolver {

    private static final Map<String, List<String>> APP_ROLE_CODES = Map.of(
            "SYNAPSEX", List.of("SYNAPSEX_ADMIN", "SYNAPSEX_OPERATOR", "SYNAPSEX_VIEWER")
            // 향후: "MAIL", List.of(...), "OTHER_APP", List.of(...)
    );

    private AppScopeResolver() {}

    /**
     * 앱 코드에 해당하는 역할 코드 목록 반환.
     * @param appCode 앱 코드 (예: SYNAPSEX). 대소문자 무시.
     * @return 해당 앱의 역할 코드 목록. 미정의 앱이면 빈 리스트.
     */
    public static List<String> getRoleCodesByAppCode(String appCode) {
        if (appCode == null || appCode.isBlank()) {
            return Collections.emptyList();
        }
        return APP_ROLE_CODES.getOrDefault(appCode.toUpperCase().trim(), Collections.emptyList());
    }

    /**
     * 지원 앱 코드 여부
     */
    public static boolean isSupportedAppCode(String appCode) {
        return appCode != null && !appCode.isBlank() && APP_ROLE_CODES.containsKey(appCode.toUpperCase().trim());
    }
}
