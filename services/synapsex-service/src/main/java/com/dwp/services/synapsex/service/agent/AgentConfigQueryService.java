package com.dwp.services.synapsex.service.agent;

import com.dwp.services.synapsex.dto.agent.AgentCatalogResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentConfigResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentDetailDto;
import com.dwp.services.synapsex.dto.agent.AgentToolCatalogItemDto;
import com.dwp.services.synapsex.entity.AgentMaster;
import com.dwp.services.synapsex.entity.AppCode;
import com.dwp.services.synapsex.repository.AppCodeRepository;
import com.dwp.services.synapsex.entity.AgentPromptHistory;
import com.dwp.services.synapsex.entity.AgentToolInventory;
import com.dwp.services.synapsex.repository.AgentMasterRepository;
import com.dwp.services.synapsex.repository.AgentPromptHistoryRepository;
import com.dwp.services.synapsex.repository.AgentToolMappingRepository;
import com.dwp.services.synapsex.repository.AgentToolInventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 에이전트 스튜디오: Aura 엔진 런타임 조립용 설정 조회
 * Tenant Isolation: tenantId + agentId로 에이전트 소유 검증 후 반환
 */
@Service
@RequiredArgsConstructor
public class AgentConfigQueryService {

    private final AgentMasterRepository agentMasterRepository;
    private final AgentPromptHistoryRepository agentPromptHistoryRepository;
    private final AgentToolMappingRepository agentToolMappingRepository;
    private final AgentToolInventoryRepository agentToolInventoryRepository;
    private final AppCodeRepository appCodeRepository;

    /**
     * GET /api/v1/agents/{id}/config — 모델 설정, 최신 프롬프트, 활성 도구 리스트 일괄 반환
     */
    @Transactional(readOnly = true)
    public Optional<AgentConfigResponseDto> getAgentConfig(Long tenantId, Long agentId) {
        Optional<AgentMaster> agentOpt = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId);
        if (agentOpt.isEmpty()) {
            return Optional.empty();
        }
        AgentMaster agent = agentOpt.get();
        if (!Boolean.TRUE.equals(agent.getIsActive())) {
            return Optional.empty();
        }

        Optional<AgentPromptHistory> currentPrompt = agentPromptHistoryRepository.findCurrentByAgentId(agentId);
        String systemInstruction = currentPrompt.map(AgentPromptHistory::getSystemInstruction).orElse(null);
        Integer version = currentPrompt.map(AgentPromptHistory::getVersion).orElse(null);

        List<Long> toolIds = agentToolMappingRepository.findToolIdsByAgentId(agentId);
        Map<Long, AgentToolInventory> toolMap = toolIds.isEmpty()
                ? Map.of()
                : agentToolInventoryRepository.findAllById(toolIds).stream()
                        .collect(Collectors.toMap(AgentToolInventory::getToolId, t -> t));
        List<AgentConfigResponseDto.AgentToolItemDto> toolDtos = toolIds.stream()
                .map(toolMap::get)
                .filter(t -> t != null)
                .map(t -> AgentConfigResponseDto.AgentToolItemDto.builder()
                        .toolName(t.getToolName())
                        .description(t.getDescription())
                        .schemaJson(t.getSchemaJson())
                        .build())
                .collect(Collectors.toList());

        AgentConfigResponseDto.ModelConfigDto model = AgentConfigResponseDto.ModelConfigDto.builder()
                .modelName(agent.getModelName())
                .temperature(agent.getTemperature())
                .maxTokens(agent.getMaxTokens())
                .build();

        AgentConfigResponseDto dto = AgentConfigResponseDto.builder()
                .agentId(agent.getAgentId())
                .agentKey(agent.getAgentKey())
                .name(agent.getName())
                .domain(agent.getDomain())
                .model(model)
                .systemInstruction(systemInstruction)
                .version(version)
                .tools(toolDtos)
                .build();

        return Optional.of(dto);
    }

    /**
     * agent_key로 설정 조회 (Aura용). tenantId + agent_key로 활성 에이전트 찾은 뒤 getAgentConfig와 동일 응답.
     */
    @Transactional(readOnly = true)
    public Optional<AgentConfigResponseDto> getAgentConfigByKey(Long tenantId, String agentKey) {
        return agentMasterRepository.findByTenantIdAndAgentKey(tenantId, agentKey)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .flatMap(a -> getAgentConfig(tenantId, a.getAgentId()));
    }

    /** GET /api/synapse/agents — 활성 에이전트 목록 (사이드바 카드 등) */
    @Transactional(readOnly = true)
    public List<AgentDetailDto> listAgents(Long tenantId) {
        List<AgentMaster> agents = agentMasterRepository.findByTenantIdAndIsActiveTrueOrderByAgentIdAsc(tenantId);
        return agents.stream()
                .map(a -> getAgentDetail(tenantId, a.getAgentId()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    /** 에이전트 상세 (생성/수정 응답용, GET /api/synapse/agents/{id}) */
    @Transactional(readOnly = true)
    public Optional<AgentDetailDto> getAgentDetail(Long tenantId, Long agentId) {
        Optional<AgentMaster> agentOpt = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId);
        if (agentOpt.isEmpty()) return Optional.empty();
        AgentMaster agent = agentOpt.get();
        String systemInstruction = agentPromptHistoryRepository.findCurrentByAgentId(agentId)
                .map(AgentPromptHistory::getSystemInstruction)
                .orElse(null);
        Integer promptVersion = agentPromptHistoryRepository.findCurrentByAgentId(agentId)
                .map(AgentPromptHistory::getVersion)
                .orElse(null);
        List<Long> toolIds = agentToolMappingRepository.findToolIdsByAgentId(agentId);
        AgentDetailDto dto = AgentDetailDto.builder()
                .agentId(agent.getAgentId())
                .agentKey(agent.getAgentKey())
                .name(agent.getName())
                .domain(agent.getDomain())
                .modelName(agent.getModelName())
                .temperature(agent.getTemperature())
                .maxTokens(agent.getMaxTokens())
                .isActive(agent.getIsActive())
                .systemInstruction(systemInstruction)
                .promptVersion(promptVersion)
                .toolIds(toolIds)
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
        return Optional.of(dto);
    }

    /** GET /api/synapse/agents/tools — Aura에 등록 가능한 전체 도구 인벤토리 (FE '도구' 탭) */
    @Transactional(readOnly = true)
    public List<AgentToolCatalogItemDto> getToolCatalog() {
        return agentToolInventoryRepository.findAllByOrderByToolNameAsc().stream()
                .map(t -> AgentToolCatalogItemDto.builder()
                        .toolId(t.getToolId())
                        .toolName(t.getToolName())
                        .description(t.getDescription())
                        .schemaJson(t.getSchemaJson())
                        .build())
                .collect(Collectors.toList());
    }

    private static final List<String> CATALOG_GROUP_KEYS = List.of("AGENT_DOMAIN", "DOC_TYPE", "LLM_MODEL");

    /** GET /api/synapse/agents/catalog — domains, docTypes, models from app_codes; tools from inventory */
    @Transactional(readOnly = true)
    public AgentCatalogResponseDto getCatalog() {
        List<AppCode> codes = appCodeRepository.findByGroupKeyInAndIsActiveTrueOrderByGroupKeyAscSortOrderAsc(CATALOG_GROUP_KEYS);
        List<AgentCatalogResponseDto.KeyValueItem> domains = codes.stream().filter(c -> "AGENT_DOMAIN".equals(c.getGroupKey())).map(c -> AgentCatalogResponseDto.KeyValueItem.builder().key(c.getCode()).value(c.getName()).build()).collect(Collectors.toList());
        List<AgentCatalogResponseDto.KeyValueItem> docTypes = codes.stream().filter(c -> "DOC_TYPE".equals(c.getGroupKey())).map(c -> AgentCatalogResponseDto.KeyValueItem.builder().key(c.getCode()).value(c.getName()).build()).collect(Collectors.toList());
        List<AgentCatalogResponseDto.KeyValueItem> models = codes.stream().filter(c -> "LLM_MODEL".equals(c.getGroupKey())).map(c -> AgentCatalogResponseDto.KeyValueItem.builder().key(c.getCode()).value(c.getName()).build()).collect(Collectors.toList());
        List<AgentToolCatalogItemDto> tools = getToolCatalog();
        return AgentCatalogResponseDto.builder().domains(domains).docTypes(docTypes).models(models).tools(tools).build();
    }
}
