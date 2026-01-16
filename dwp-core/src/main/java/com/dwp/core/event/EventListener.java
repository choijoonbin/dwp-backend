package com.dwp.core.event;

/**
 * 이벤트 리스너 인터페이스
 * 
 * 각 서비스에서 필요한 이벤트를 구독하여 처리할 수 있습니다.
 */
public interface EventListener {
    
    /**
     * 이벤트 처리
     * 
     * @param event 수신한 도메인 이벤트
     */
    void onEvent(DomainEvent event);
    
    /**
     * 이 리스너가 처리할 이벤트 타입
     * 
     * @return 이벤트 타입 배열 (예: ["MAIL_SENT", "MAIL_RECEIVED"])
     */
    String[] getSupportedEventTypes();
}
