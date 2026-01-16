package com.dwp.core.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 도메인 이벤트 기본 클래스
 * 
 * 서비스 간 데이터 변경 사항을 전파하기 위해 사용됩니다.
 * AI 에이전트(Aura)의 벡터 DB 업데이트에도 활용됩니다.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DomainEvent {
    
    /**
     * 이벤트 고유 ID
     */
    private String eventId;
    
    /**
     * 이벤트 타입 (예: "MAIL_SENT", "APPROVAL_CREATED", "USER_UPDATED")
     */
    private String eventType;
    
    /**
     * 이벤트 발생 소스 (예: "mail-service", "approval-service")
     */
    private String source;
    
    /**
     * 테넌트 ID (멀티테넌시)
     */
    private String tenantId;
    
    /**
     * 사용자 ID (이벤트를 발생시킨 사용자)
     */
    private String userId;
    
    /**
     * 이벤트 데이터 (JSON으로 직렬화 가능한 Map)
     */
    private Map<String, Object> data;
    
    /**
     * 이벤트 발생 시각
     */
    private LocalDateTime timestamp;
    
    /**
     * 이벤트 메타데이터 (추가 정보)
     */
    private Map<String, String> metadata;
}
