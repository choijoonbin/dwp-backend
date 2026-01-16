package com.dwp.services.main.repository;

import com.dwp.services.main.domain.AgentTask;
import com.dwp.services.main.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AgentTask Repository
 */
@Repository
public interface AgentTaskRepository extends JpaRepository<AgentTask, Long> {
    
    /**
     * Task ID로 조회
     */
    Optional<AgentTask> findByTaskId(String taskId);
    
    /**
     * 사용자별 작업 목록 조회 (페이징)
     */
    Page<AgentTask> findByUserIdAndTenantIdOrderByCreatedAtDesc(
        String userId, 
        String tenantId, 
        Pageable pageable
    );
    
    /**
     * 사용자별 특정 상태의 작업 목록 조회
     */
    List<AgentTask> findByUserIdAndTenantIdAndStatusOrderByCreatedAtDesc(
        String userId, 
        String tenantId, 
        TaskStatus status
    );
    
    /**
     * 특정 기간 동안 완료되지 않은 작업 조회 (타임아웃 감지용)
     */
    @Query("SELECT t FROM AgentTask t WHERE t.status IN :statuses AND t.createdAt < :timeout")
    List<AgentTask> findTimedOutTasks(
        @Param("statuses") List<TaskStatus> statuses,
        @Param("timeout") LocalDateTime timeout
    );
    
    /**
     * 테넌트별 작업 통계
     */
    @Query("SELECT t.status, COUNT(t) FROM AgentTask t WHERE t.tenantId = :tenantId GROUP BY t.status")
    List<Object[]> countByStatusAndTenantId(@Param("tenantId") String tenantId);
}
