package com.dwp.services.main.domain;

/**
 * AI 에이전트 작업 상태
 */
public enum TaskStatus {
    /**
     * 작업 요청됨 (초기 상태)
     */
    REQUESTED,
    
    /**
     * 작업 진행 중
     */
    IN_PROGRESS,
    
    /**
     * 작업 완료
     */
    COMPLETED,
    
    /**
     * 작업 실패
     */
    FAILED
}
