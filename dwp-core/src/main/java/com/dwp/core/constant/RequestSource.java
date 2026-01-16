package com.dwp.core.constant;

/**
 * API 요청의 출처를 나타내는 상수
 * 
 * X-DWP-Source 헤더 값으로 사용됩니다.
 */
public enum RequestSource {
    /**
     * AI 에이전트(Aura)가 사용자를 대신해 보낸 요청
     */
    AURA("AURA"),
    
    /**
     * 프론트엔드(사용자)가 직접 보낸 요청
     */
    FRONTEND("FRONTEND"),
    
    /**
     * 내부 서비스 간 통신 요청
     */
    INTERNAL("INTERNAL"),
    
    /**
     * 시스템 배치 작업 요청
     */
    BATCH("BATCH");
    
    private final String value;
    
    RequestSource(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    @Override
    public String toString() {
        return value;
    }
}
