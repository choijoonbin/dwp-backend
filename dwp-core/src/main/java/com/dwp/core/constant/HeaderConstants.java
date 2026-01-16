package com.dwp.core.constant;

/**
 * HTTP 헤더 상수 정의
 * 
 * DWP 플랫폼에서 사용하는 표준 헤더 키를 정의합니다.
 */
public class HeaderConstants {
    
    /**
     * 요청 출처 식별 헤더
     * 값: AURA, FRONTEND, INTERNAL, BATCH
     */
    public static final String X_DWP_SOURCE = "X-DWP-Source";
    
    /**
     * 호출자 타입 식별 헤더
     * 값: AGENT (AI 에이전트가 호출한 경우)
     * 
     * X-DWP-Source와 유사하지만, 더 구체적으로 에이전트 호출을 식별합니다.
     */
    public static final String X_DWP_CALLER_TYPE = "X-DWP-Caller-Type";
    
    /**
     * 호출자 타입 값: AGENT
     */
    public static final String CALLER_TYPE_AGENT = "AGENT";
    
    /**
     * 테넌트 식별 헤더
     */
    public static final String X_TENANT_ID = "X-Tenant-ID";
    
    /**
     * 사용자 식별 헤더
     */
    public static final String X_USER_ID = "X-User-ID";
    
    /**
     * 인증 토큰 헤더
     */
    public static final String AUTHORIZATION = "Authorization";
    
    private HeaderConstants() {
        // 유틸리티 클래스이므로 인스턴스화 방지
    }
}
