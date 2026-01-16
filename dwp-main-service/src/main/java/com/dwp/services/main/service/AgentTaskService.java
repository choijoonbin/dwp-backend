package com.dwp.services.main.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.main.domain.AgentTask;
import com.dwp.services.main.domain.TaskStatus;
import com.dwp.services.main.dto.AgentTaskRequest;
import com.dwp.services.main.dto.AgentTaskResponse;
import com.dwp.services.main.dto.TaskProgressUpdate;
import com.dwp.services.main.repository.AgentTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * AI 에이전트 작업 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskService {
    
    private final AgentTaskRepository taskRepository;
    
    /**
     * 새로운 AI 에이전트 작업 생성
     */
    @Transactional
    public AgentTaskResponse createTask(AgentTaskRequest request) {
        String taskId = UUID.randomUUID().toString();
        
        AgentTask task = AgentTask.builder()
                .taskId(taskId)
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .taskType(request.getTaskType())
                .status(TaskStatus.REQUESTED)
                .progress(0)
                .description(request.getDescription())
                .inputData(request.getInputData())
                .build();
        
        @SuppressWarnings("null")
        AgentTask savedTask = taskRepository.save(task);
        log.info("Created new agent task: taskId={}, type={}, userId={}", 
                 taskId, request.getTaskType(), request.getUserId());
        
        return toResponse(savedTask);
    }
    
    /**
     * Task ID로 작업 조회
     */
    @Transactional(readOnly = true)
    public AgentTaskResponse getTask(String taskId) {
        AgentTask task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다: " + taskId));
        
        return toResponse(task);
    }
    
    /**
     * 사용자별 작업 목록 조회 (페이징)
     */
    @Transactional(readOnly = true)
    public Page<AgentTaskResponse> getUserTasks(String userId, String tenantId, Pageable pageable) {
        return taskRepository.findByUserIdAndTenantIdOrderByCreatedAtDesc(userId, tenantId, pageable)
                .map(this::toResponse);
    }
    
    /**
     * 작업 시작
     */
    @Transactional
    public AgentTaskResponse startTask(String taskId) {
        AgentTask task = findTaskByIdOrThrow(taskId);
        task.start();
        taskRepository.save(task);
        
        log.info("Started agent task: taskId={}", taskId);
        return toResponse(task);
    }
    
    /**
     * 작업 진척도 업데이트
     */
    @Transactional
    public AgentTaskResponse updateProgress(String taskId, TaskProgressUpdate update) {
        AgentTask task = findTaskByIdOrThrow(taskId);
        task.updateProgress(update.getProgress(), update.getDescription());
        taskRepository.save(task);
        
        log.debug("Updated task progress: taskId={}, progress={}%, description={}", 
                  taskId, update.getProgress(), update.getDescription());
        return toResponse(task);
    }
    
    /**
     * 작업 완료
     */
    @Transactional
    public AgentTaskResponse completeTask(String taskId, String resultData) {
        AgentTask task = findTaskByIdOrThrow(taskId);
        task.complete(resultData);
        taskRepository.save(task);
        
        log.info("Completed agent task: taskId={}", taskId);
        return toResponse(task);
    }
    
    /**
     * 작업 실패 처리
     */
    @Transactional
    public AgentTaskResponse failTask(String taskId, String errorMessage) {
        AgentTask task = findTaskByIdOrThrow(taskId);
        task.fail(errorMessage);
        taskRepository.save(task);
        
        log.warn("Failed agent task: taskId={}, error={}", taskId, errorMessage);
        return toResponse(task);
    }
    
    /**
     * 비동기 작업 실행 예제
     * 실제 AI 에이전트 연동 시 이 메서드를 참고하여 구현
     */
    @Async
    public CompletableFuture<AgentTaskResponse> executeTaskAsync(String taskId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. 작업 시작
                startTask(taskId);
                
                // 2. 진척도 업데이트 (예시)
                updateProgress(taskId, TaskProgressUpdate.builder()
                        .progress(30)
                        .description("데이터 분석 중...")
                        .build());
                
                // 3. 실제 작업 수행 (여기에 AI 에이전트 호출 로직 추가)
                // String result = auraClient.analyze(taskId);
                
                // 4. 작업 완료
                updateProgress(taskId, TaskProgressUpdate.builder()
                        .progress(100)
                        .description("작업 완료")
                        .build());
                
                return completeTask(taskId, "작업 결과 데이터");
                
            } catch (Exception e) {
                log.error("Task execution failed: taskId={}", taskId, e);
                return failTask(taskId, e.getMessage());
            }
        });
    }
    
    /**
     * Task ID로 작업 찾기 (없으면 예외 발생)
     */
    private AgentTask findTaskByIdOrThrow(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다: " + taskId));
    }
    
    /**
     * Entity를 Response DTO로 변환
     */
    private AgentTaskResponse toResponse(AgentTask task) {
        return AgentTaskResponse.builder()
                .taskId(task.getTaskId())
                .userId(task.getUserId())
                .tenantId(task.getTenantId())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .progress(task.getProgress())
                .description(task.getDescription())
                .resultData(task.getResultData())
                .planSteps(task.getPlanSteps())
                .errorMessage(task.getErrorMessage())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
