package com.dwp.services.main.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.main.dto.AgentTaskRequest;
import com.dwp.services.main.dto.AgentTaskResponse;
import com.dwp.services.main.dto.TaskProgressUpdate;
import com.dwp.services.main.service.AgentTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * AI 에이전트 작업 관리 API
 * 
 * Aura-Platform(AI)이 장기 실행 작업을 등록하고,
 * 프론트엔드가 작업 상태를 조회할 수 있는 엔드포인트를 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/main/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskController {
    
    private final AgentTaskService taskService;
    
    /**
     * 새로운 AI 에이전트 작업 생성
     * 
     * POST /api/main/agent/tasks
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AgentTaskResponse> createTask(@Valid @RequestBody AgentTaskRequest request) {
        log.info("Creating new agent task: type={}, userId={}", request.getTaskType(), request.getUserId());
        AgentTaskResponse response = taskService.createTask(request);
        return ApiResponse.success("작업이 생성되었습니다", response);
    }
    
    /**
     * Task ID로 작업 조회
     * 
     * GET /api/main/agent/tasks/{taskId}
     */
    @GetMapping("/{taskId}")
    public ApiResponse<AgentTaskResponse> getTask(@PathVariable String taskId) {
        AgentTaskResponse response = taskService.getTask(taskId);
        return ApiResponse.success(response);
    }
    
    /**
     * 사용자별 작업 목록 조회 (페이징)
     * 
     * GET /api/main/agent/tasks?userId={userId}&tenantId={tenantId}&page=0&size=20
     */
    @GetMapping
    public ApiResponse<Page<AgentTaskResponse>> getUserTasks(
            @RequestParam String userId,
            @RequestParam String tenantId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        
        Page<AgentTaskResponse> response = taskService.getUserTasks(userId, tenantId, pageable);
        return ApiResponse.success(response);
    }
    
    /**
     * 작업 시작
     * 
     * POST /api/main/agent/tasks/{taskId}/start
     */
    @PostMapping("/{taskId}/start")
    public ApiResponse<AgentTaskResponse> startTask(@PathVariable String taskId) {
        log.info("Starting agent task: taskId={}", taskId);
        AgentTaskResponse response = taskService.startTask(taskId);
        return ApiResponse.success("작업이 시작되었습니다", response);
    }
    
    /**
     * 작업 진척도 업데이트
     * 
     * PATCH /api/main/agent/tasks/{taskId}/progress
     */
    @PatchMapping("/{taskId}/progress")
    public ApiResponse<AgentTaskResponse> updateProgress(
            @PathVariable String taskId,
            @Valid @RequestBody TaskProgressUpdate update) {
        
        AgentTaskResponse response = taskService.updateProgress(taskId, update);
        return ApiResponse.success("진척도가 업데이트되었습니다", response);
    }
    
    /**
     * 작업 완료
     * 
     * POST /api/main/agent/tasks/{taskId}/complete
     */
    @PostMapping("/{taskId}/complete")
    public ApiResponse<AgentTaskResponse> completeTask(
            @PathVariable String taskId,
            @RequestBody(required = false) String resultData) {
        
        log.info("Completing agent task: taskId={}", taskId);
        AgentTaskResponse response = taskService.completeTask(taskId, resultData);
        return ApiResponse.success("작업이 완료되었습니다", response);
    }
    
    /**
     * 작업 실패 처리
     * 
     * POST /api/main/agent/tasks/{taskId}/fail
     */
    @PostMapping("/{taskId}/fail")
    public ApiResponse<AgentTaskResponse> failTask(
            @PathVariable String taskId,
            @RequestParam String errorMessage) {
        
        log.warn("Failing agent task: taskId={}, error={}", taskId, errorMessage);
        AgentTaskResponse response = taskService.failTask(taskId, errorMessage);
        return ApiResponse.success("작업 실패가 기록되었습니다", response);
    }
    
    /**
     * 비동기 작업 실행
     * 
     * POST /api/main/agent/tasks/{taskId}/execute
     */
    @PostMapping("/{taskId}/execute")
    public ApiResponse<String> executeTask(@PathVariable String taskId) {
        log.info("Executing agent task asynchronously: taskId={}", taskId);
        taskService.executeTaskAsync(taskId);
        return ApiResponse.success("작업 실행이 시작되었습니다. 진행 상태는 GET /{taskId}로 확인하세요.", taskId);
    }
}
