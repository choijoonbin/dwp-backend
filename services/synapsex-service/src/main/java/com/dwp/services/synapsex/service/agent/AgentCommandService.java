package com.dwp.services.synapsex.service.agent;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.agent.AgentDetailDto;
import com.dwp.services.synapsex.dto.agent.CreateAgentRequest;
import com.dwp.services.synapsex.dto.agent.UpdateAgentRequest;
import com.dwp.services.synapsex.entity.AgentMaster;
import com.dwp.services.synapsex.entity.AgentPromptHistory;
import com.dwp.services.synapsex.entity.AgentToolMapping;
import com.dwp.services.synapsex.repository.AgentMasterRepository;
import com.dwp.services.synapsex.repository.AgentPromptHistoryRepository;
import com.dwp.services.synapsex.repository.AgentToolInventoryRepository;
import com.dwp.services.synapsex.repository.AgentToolMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 에이전트 스튜디오: 생성/수정
 * Tenant Isolation: 모든 변경은 tenantId 검증 후 수행
 */
@Service
@RequiredArgsConstructor
public class AgentCommandService {

    private final AgentMasterRepository agentMasterRepository;
    private final AgentPromptHistoryRepository agentPromptHistoryRepository;
    private final AgentToolMappingRepository agentToolMappingRepository;
    private final AgentToolInventoryRepository agentToolInventoryRepository;
    private final AgentConfigQueryService agentConfigQueryService;
    private final AgentStudioCodeValidator codeValidator;

    @Transactional
    public AgentDetailDto create(Long tenantId, CreateAgentRequest request) {
        if (agentMasterRepository.existsByTenantIdAndAgentKey(tenantId, request.getAgentKey())) {
            throw new BaseException(ErrorCode.AGENT_KEY_DUPLICATE, "agent_key가 이미 사용 중입니다: " + request.getAgentKey());
        }
        codeValidator.validateDomain(request.getDomain());
        codeValidator.validateModelName(request.getModelName());
        String systemInstruction = request.getSystemInstruction() != null ? request.getSystemInstruction() : "";
        validatePromptLength(systemInstruction);
        AgentMaster agent = AgentMaster.builder()
                .tenantId(tenantId)
                .agentKey(request.getAgentKey())
                .name(request.getName())
                .domain(request.getDomain())
                .modelName(request.getModelName())
                .temperature(request.getTemperature())
                .maxTokens(request.getMaxTokens())
                .isActive(true)
                .build();
        agent = agentMasterRepository.save(agent);
        AgentPromptHistory prompt = AgentPromptHistory.builder()
                .agentId(agent.getAgentId())
                .systemInstruction(systemInstruction)
                .version(1)
                .isCurrent(true)
                .build();
        agentPromptHistoryRepository.save(prompt);
        if (request.getToolIds() != null && !request.getToolIds().isEmpty()) {
            validateToolIds(request.getToolIds());
            for (Long toolId : request.getToolIds()) {
                agentToolMappingRepository.save(AgentToolMapping.builder()
                        .agentId(agent.getAgentId())
                        .toolId(toolId)
                        .build());
            }
        }
        return agentConfigQueryService.getAgentDetail(tenantId, agent.getAgentId())
                .orElseThrow(() -> new BaseException(ErrorCode.AGENT_CREATE_FAILED, "에이전트 생성 후 조회에 실패했습니다."));
    }

    @Transactional
    public AgentDetailDto update(Long tenantId, Long agentId, UpdateAgentRequest request) {
        AgentMaster agent = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
        if (request.getDomain() != null) codeValidator.validateDomain(request.getDomain());
        if (request.getModelName() != null) codeValidator.validateModelName(request.getModelName());
        if (request.getName() != null) agent.setName(request.getName());
        if (request.getDomain() != null) agent.setDomain(request.getDomain());
        if (request.getModelName() != null) agent.setModelName(request.getModelName());
        if (request.getTemperature() != null) agent.setTemperature(request.getTemperature());
        if (request.getMaxTokens() != null) agent.setMaxTokens(request.getMaxTokens());
        if (request.getIsActive() != null) agent.setIsActive(request.getIsActive());
        agentMasterRepository.save(agent);
        if (request.getSystemInstruction() != null) {
            validatePromptLength(request.getSystemInstruction());
            // 원자적 처리: 기존 is_current 해제 후 새 레코드 삽입 (동일 @Transactional 내)
            agentPromptHistoryRepository.clearCurrentByAgentId(agentId);
            int nextVersion = agentPromptHistoryRepository.findByAgentIdOrderByVersionDesc(agentId)
                    .stream()
                    .mapToInt(AgentPromptHistory::getVersion)
                    .max()
                    .orElse(0) + 1;
            agentPromptHistoryRepository.save(AgentPromptHistory.builder()
                    .agentId(agentId)
                    .systemInstruction(request.getSystemInstruction())
                    .version(nextVersion)
                    .isCurrent(true)
                    .build());
        }
        if (request.getToolIds() != null) {
            agentToolMappingRepository.deleteByAgentId(agentId);
            if (!request.getToolIds().isEmpty()) {
                validateToolIds(request.getToolIds());
                for (Long toolId : request.getToolIds()) {
                    agentToolMappingRepository.save(AgentToolMapping.builder()
                            .agentId(agentId)
                            .toolId(toolId)
                            .build());
                }
            }
        }
        return agentConfigQueryService.getAgentDetail(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
    }

    /** 삭제: soft delete (is_active = false). 목록/설정 조회에서 제외됨. */
    @Transactional
    public void delete(Long tenantId, Long agentId) {
        AgentMaster agent = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
        agent.setIsActive(false);
        agentMasterRepository.save(agent);
    }

    private static final int MAX_PROMPT_LENGTH = 100_000;

    private void validatePromptLength(String systemInstruction) {
        if (systemInstruction != null && systemInstruction.length() > MAX_PROMPT_LENGTH) {
            throw new BaseException(ErrorCode.PROMPT_VALIDATION_ERROR,
                    "system_instruction 길이가 " + MAX_PROMPT_LENGTH + "자를 초과합니다.");
        }
    }

    private void validateToolIds(List<Long> toolIds) {
        List<Long> existing = agentToolInventoryRepository.findAllById(toolIds).stream()
                .map(t -> t.getToolId())
                .toList();
        List<Long> missing = new ArrayList<>(toolIds);
        missing.removeAll(existing);
        if (!missing.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "존재하지 않는 도구 ID가 포함되어 있습니다: " + missing);
        }
    }
}
