package com.dwp.core.event;

/**
 * 이벤트 채널 상수
 * 
 * Redis Pub/Sub 채널명을 표준화하여 관리합니다.
 */
public final class EventChannels {
    
    private EventChannels() {
        // 유틸리티 클래스 - 인스턴스화 방지
    }
    
    /**
     * 모든 이벤트가 발행되는 기본 채널
     * Aura-Platform(AI)이 이 채널을 구독하여 벡터 DB를 업데이트합니다.
     */
    public static final String ALL_EVENTS = "dwp:events:all";
    
    /**
     * 메일 서비스 이벤트 채널
     */
    public static final String MAIL_EVENTS = "dwp:events:mail";
    
    /**
     * 채팅 서비스 이벤트 채널
     */
    public static final String CHAT_EVENTS = "dwp:events:chat";
    
    /**
     * 결재 서비스 이벤트 채널
     */
    public static final String APPROVAL_EVENTS = "dwp:events:approval";
    
    /**
     * 메인 서비스 이벤트 채널
     */
    public static final String MAIN_EVENTS = "dwp:events:main";
    
    /**
     * AI 에이전트 작업 이벤트 채널
     */
    public static final String AGENT_TASK_EVENTS = "dwp:events:agent-task";
    
    /**
     * 테넌트별 이벤트 채널 생성
     * 
     * @param tenantId 테넌트 ID
     * @return 테넌트별 채널명 (예: "dwp:events:tenant:acme-corp")
     */
    public static String forTenant(String tenantId) {
        return "dwp:events:tenant:" + tenantId;
    }
    
    /**
     * 사용자별 이벤트 채널 생성
     * 
     * @param userId 사용자 ID
     * @return 사용자별 채널명 (예: "dwp:events:user:user123")
     */
    public static String forUser(String userId) {
        return "dwp:events:user:" + userId;
    }
}
