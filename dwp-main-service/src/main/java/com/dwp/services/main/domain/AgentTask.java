package com.dwp.services.main.domain;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 에이전트가 수행하는 장기 실행 작업의 상태를 저장하는 엔티티
 *
 * 시스템 컬럼(created_at, created_by, updated_at, updated_by)은 BaseEntity 상속.
 *
 * 프론트엔드는 이 테이블을 조회하여 "AI가 현재 분석 중입니다(30%)..."와 같은
 * 진행 바를 보여줄 수 있습니다.
 */
@Entity
@Table(name = "agent_tasks", indexes = {
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_tenant_id", columnList = "tenant_id"),
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentTask extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 작업 고유 식별자 (UUID)
     */
    @Column(name = "task_id", unique = true, nullable = false, length = 36)
    private String taskId;
    
    /**
     * 작업을 요청한 사용자 ID
     */
    @Column(name = "user_id", nullable = false, length = 50)
    private String userId;
    
    /**
     * 테넌트 ID (멀티테넌시 환경)
     */
    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;
    
    /**
     * 작업 유형 (예: "data_analysis", "report_generation", "email_summarization")
     */
    @Column(name = "task_type", nullable = false, length = 100)
    private String taskType;
    
    /**
     * 작업 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;
    
    /**
     * 작업 진척도 (0 ~ 100)
     */
    @Column(name = "progress", nullable = false)
    @Builder.Default
    private Integer progress = 0;
    
    /**
     * 작업 설명 또는 현재 단계 설명
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    
    /**
     * 작업 입력 데이터 (JSON 형식)
     */
    @Column(name = "input_data", columnDefinition = "TEXT")
    private String inputData;
    
    /**
     * 작업 결과 데이터 (JSON 형식)
     */
    @Column(name = "result_data", columnDefinition = "TEXT")
    private String resultData;
    
    /**
     * AI 에이전트의 실행 계획 단계 (JSON 형식)
     * 프론트엔드에서 표시할 plan_steps 배열을 저장합니다.
     * 
     * 예시:
     * [
     *   {"step": 1, "action": "analyze", "status": "completed"},
     *   {"step": 2, "action": "generate_report", "status": "in_progress"}
     * ]
     */
    @Column(name = "plan_steps", columnDefinition = "TEXT")
    private String planSteps;
    
    /**
     * HITL 승인 요청 ID
     * 이 작업과 연관된 HITL 승인 요청의 ID를 저장합니다.
     * 
     * HITL 요청이 생성되면 이 필드에 requestId가 저장되고,
     * 승인/거절 시 Redis에서 해당 요청을 조회할 수 있습니다.
     * 
     * 관계:
     * - AgentTask.taskId (작업 ID)
     * - HitlManager의 hitl:request:{requestId} (Redis 키)
     * 
     * 이 맵핑을 통해 에이전트 재시작 시에도 승인 대기 상태를 복구할 수 있습니다.
     */
    @Column(name = "hitl_request_id", length = 100)
    private String hitlRequestId;
    
    /**
     * 에러 메시지 (작업 실패 시)
     */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    /**
     * 작업 시작 시각
     */
    @Column(name = "started_at")
    private LocalDateTime startedAt;
    
    /**
     * 작업 완료 시각
     */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * 작업 시작
     */
    public void start() {
        this.status = TaskStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
        this.progress = 0;
    }
    
    /**
     * 작업 진척도 업데이트
     */
    public void updateProgress(int progress, String description) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("Progress must be between 0 and 100");
        }
        this.progress = progress;
        this.description = description;
    }
    
    /**
     * 작업 완료
     */
    public void complete(String resultData) {
        this.status = TaskStatus.COMPLETED;
        this.progress = 100;
        this.completedAt = LocalDateTime.now();
        this.resultData = resultData;
    }
    
    /**
     * 작업 실패
     */
    public void fail(String errorMessage) {
        this.status = TaskStatus.FAILED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = errorMessage;
    }
}
