package com.dwp.core.constant;

import java.util.Arrays;
import java.util.List;

/**
 * HTTP 헤더 상수 정의
 * 
 * DWP 플랫폼에서 사용하는 표준 헤더 키를 정의합니다.
 * 
 * Feign Header Propagation:
 * - REQUIRED_PROPAGATION_HEADERS: 모든 downstream 호출 시 필수 전파 헤더
 * - FeignHeaderInterceptor에서 자동으로 전파
 */
public class HeaderConstants {
    
    // ========================================
    // 표준 헤더 정의
    // ========================================
    
    /**
     * 인증 토큰 헤더
     */
    public static final String AUTHORIZATION = "Authorization";
    
    /**
     * 테넌트 식별 헤더
     */
    public static final String X_TENANT_ID = "X-Tenant-ID";
    
    /**
     * 사용자 식별 헤더
     */
    public static final String X_USER_ID = "X-User-ID";
    
    /**
     * AI 에이전트 세션/클라이언트 식별 헤더
     */
    public static final String X_AGENT_ID = "X-Agent-ID";
    
    /**
     * 요청 출처 식별 헤더
     * 값: AURA, FRONTEND, INTERNAL, BATCH
     */
    public static final String X_DWP_SOURCE = "X-DWP-Source";
    
    /**
     * 호출자 타입 식별 헤더
     * 값: USER, AGENT, SYSTEM
     * 
     * X-DWP-Source와 유사하지만, 더 구체적으로 에이전트 호출을 식별합니다.
     */
    public static final String X_DWP_CALLER_TYPE = "X-DWP-Caller-Type";

    /**
     * 추적 ID (Gateway 생성 또는 클라이언트 전달)
     * 에러 응답에 traceId 포함 시 클라이언트/Sentry 연동용
     */
    public static final String X_TRACE_ID = "X-Trace-Id";

    /**
     * Gateway 요청 ID (멱등성 키로 사용 가능)
     * simulate/execute 중복 실행 차단용
     */
    public static final String X_GATEWAY_REQUEST_ID = "X-Gateway-Request-Id";

    /**
     * Accept-Language: 다국어(ko/en) 지원
     * FE가 ko|en을 보내면 해당 언어로 코드/메뉴 라벨 반환
     */
    public static final String ACCEPT_LANGUAGE = "Accept-Language";
    
    // ========================================
    // 호출자 타입 값
    // ========================================
    
    /**
     * 호출자 타입: 사용자
     */
    public static final String CALLER_TYPE_USER = "USER";
    
    /**
     * 호출자 타입: AI 에이전트
     */
    public static final String CALLER_TYPE_AGENT = "AGENT";
    
    /**
     * 호출자 타입: 시스템 (배치, 스케줄러 등)
     */
    public static final String CALLER_TYPE_SYSTEM = "SYSTEM";
    
    // ========================================
    // Feign Header Propagation 설정
    // ========================================
    
    /**
     * Feign 호출 시 필수 전파 헤더 목록
     * 
     * 이 헤더들은 FeignHeaderInterceptor에서 자동으로 downstream 서비스로 전파됩니다.
     * 헤더가 없는 경우 누락되며, null 값은 주입하지 않습니다.
     */
    public static final List<String> REQUIRED_PROPAGATION_HEADERS = Arrays.asList(
        AUTHORIZATION,
        X_TENANT_ID,
        X_USER_ID,
        X_AGENT_ID,
        X_DWP_SOURCE,
        X_DWP_CALLER_TYPE,
        ACCEPT_LANGUAGE
    );
    
    private HeaderConstants() {
        // 유틸리티 클래스이므로 인스턴스화 방지
    }
}
