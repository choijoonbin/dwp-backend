package com.dwp.services.synapsex.service.agent;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.agent.AgentDetailDto;
import com.dwp.services.synapsex.dto.agent.CreateAgentRequest;
import com.dwp.services.synapsex.dto.agent.UpdateAgentRequest;
import com.dwp.services.synapsex.entity.AgentMaster;
import com.dwp.services.synapsex.entity.AgentPromptHistory;
import com.dwp.services.synapsex.entity.AgentToolMapping;
import com.dwp.services.synapsex.entity.AgentDocumentMapping;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.AgentMasterRepository;
import com.dwp.services.synapsex.repository.AgentPromptHistoryRepository;
import com.dwp.services.synapsex.repository.AgentToolInventoryRepository;
import com.dwp.services.synapsex.repository.AgentToolMappingRepository;
import com.dwp.services.synapsex.repository.AgentDocumentMappingRepository;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import com.dwp.services.synapsex.client.AuraCaseTabClient;
import com.dwp.services.synapsex.config.AuraTenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * 에이전트 스튜디오: 생성/수정
 * Tenant Isolation: 모든 변경은 tenantId 검증 후 수행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCommandService {

    private final AgentMasterRepository agentMasterRepository;
    private final AgentPromptHistoryRepository agentPromptHistoryRepository;
    private final AgentToolMappingRepository agentToolMappingRepository;
    private final AgentToolInventoryRepository agentToolInventoryRepository;
    private final AgentDocumentMappingRepository agentDocumentMappingRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final AgentConfigQueryService agentConfigQueryService;
    private final AgentStudioCodeValidator codeValidator;
    private final AuraCaseTabClient auraCaseTabClient;

    @Value("${aura.refresh.enabled:true}")
    private boolean auraRefreshEnabled;

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
        // TenantId 재검증: URL의 agentId와 헤더의 tenantId가 DB 레코드와 일치하는지 확인
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
            // 빈 배열은 "변경 없음"으로 처리 (FE가 기본값으로 [] 보내는 케이스 보호)
            if (!request.getToolIds().isEmpty()) {
                agentToolMappingRepository.deleteByAgentId(agentId);
                validateToolIds(request.getToolIds());
                for (Long toolId : request.getToolIds()) {
                    agentToolMappingRepository.save(AgentToolMapping.builder()
                            .agentId(agentId)
                            .toolId(toolId)
                            .build());
                }
            }
        }
        if (request.getDocIds() != null) {
            // 빈 배열은 "변경 없음"으로 처리 (FE가 기본값으로 [] 보내는 케이스 보호)
            if (!request.getDocIds().isEmpty()) {
                agentDocumentMappingRepository.deleteByTenantIdAndAgentId(tenantId, agentId);
                validateDocIds(tenantId, request.getDocIds());
                for (Long docId : request.getDocIds()) {
                    agentDocumentMappingRepository.save(AgentDocumentMapping.builder()
                            .agentId(agentId)
                            .docId(docId)
                            .tenantId(tenantId)
                            .build());
                }
            }
        }
        
        // Aura 엔진 캐시 무효화 (에이전트 설정 변경 시)
        String authorization = getAuthorizationHeader();
        notifyAuraRefresh(tenantId, agentId, authorization);
        
        return agentConfigQueryService.getAgentDetail(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
    }

    /** 삭제: soft delete (is_active = false). 목록/설정 조회에서 제외됨. */
    @Transactional
    public void delete(Long tenantId, Long agentId) {
        // TenantId 재검증: URL의 agentId와 헤더의 tenantId가 DB 레코드와 일치하는지 확인
        AgentMaster agent = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
        agent.setIsActive(false);
        agentMasterRepository.save(agent);
        
        // Aura 엔진 캐시 무효화 (에이전트 비활성화 시)
        String authorization = getAuthorizationHeader();
        notifyAuraRefresh(tenantId, agentId, authorization);
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

    private void validateDocIds(Long tenantId, List<Long> docIds) {
        List<Long> existing = ragDocumentRepository.findAllById(docIds).stream()
                .filter(d -> tenantId.equals(d.getTenantId()))
                .map(RagDocument::getDocId)
                .toList();
        List<Long> missing = new ArrayList<>(docIds);
        missing.removeAll(existing);
        if (!missing.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "존재하지 않거나 접근 권한이 없는 문서 ID가 포함되어 있습니다: " + missing);
        }
    }

    /** 문서를 에이전트에 연결 */
    @Transactional
    public void bindDocument(Long tenantId, Long agentId, Long docId) {
        // 에이전트 소유권 검증
        AgentMaster agent = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
        
        // 문서 소유권 검증
        RagDocument document = ragDocumentRepository.findById(docId)
                .filter(doc -> doc.getTenantId().equals(tenantId))
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없거나 접근 권한이 없습니다."));
        
        // 이미 연결되어 있는지 확인
        if (agentDocumentMappingRepository.existsByTenantIdAndAgentIdAndDocId(tenantId, agentId, docId)) {
            return; // 이미 연결되어 있으면 무시
        }
        
        // 연결 생성
        agentDocumentMappingRepository.save(AgentDocumentMapping.builder()
                .agentId(agentId)
                .docId(docId)
                .tenantId(tenantId)
                .build());
        
        // Aura 엔진 캐시 무효화 (지식 바인딩 시)
        String authorization = getAuthorizationHeader();
        notifyAuraRefresh(tenantId, agentId, authorization);
    }

    /** 문서 연결 해제 */
    @Transactional
    public void unbindDocument(Long tenantId, Long agentId, Long docId) {
        // 에이전트 소유권 검증
        agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
        
        // 연결 삭제
        agentDocumentMappingRepository.deleteByTenantIdAndAgentIdAndDocId(tenantId, agentId, docId);
        
        // Aura 엔진 캐시 무효화 (지식 연결 해제 시)
        String authorization = getAuthorizationHeader();
        notifyAuraRefresh(tenantId, agentId, authorization);
    }

    /**
     * 현재 요청의 Authorization 헤더 추출 (비동기 호출 전에 가져와야 함)
     */
    private String getAuthorizationHeader() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && !authHeader.isBlank()) {
                    return authHeader;
                }
            }
        } catch (IllegalStateException e) {
            log.debug("No request context available for Authorization header extraction");
        }
        return null;
    }

    /**
     * Aura 엔진에 에이전트 설정 캐시 무효화 신호 전송 (비동기)
     * 실패 시 로그만 남기고 예외 전파하지 않음 (비즈니스 로직 롤백 방지)
     */
    @Async
    public void notifyAuraRefresh(Long tenantId, Long agentId, String authorization) {
        if (!auraRefreshEnabled) {
            log.debug("Aura refresh disabled, skipping: agentId={}", agentId);
            return;
        }
        try {
            AuraTenantContext.setTenantId(tenantId);
            try {
                auraCaseTabClient.refreshAgentConfig(agentId, tenantId, authorization);
                log.debug("Aura refresh signal sent: agentId={} tenantId={}", agentId, tenantId);
            } finally {
                AuraTenantContext.clear();
            }
        } catch (Exception e) {
            // 401 Unauthorized는 Aura Platform에 토큰이 필요하지만 현재 없을 때 발생
            // 비즈니스 로직에는 영향 없으므로 로그만 남김
            if (e.getMessage() != null && e.getMessage().contains("401")) {
                log.debug("Aura refresh signal returned 401 (token may be required by Aura Platform): agentId={} tenantId={}", 
                        agentId, tenantId);
            } else {
                log.warn("Aura refresh signal failed (non-blocking): agentId={} tenantId={} error={}", 
                        agentId, tenantId, e.getMessage());
            }
        }
    }
}
