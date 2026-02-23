package com.dwp.services.synapsex.service.agent;

import com.dwp.services.synapsex.dto.agent.AgentCatalogResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentConfigResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentDetailDto;
import com.dwp.services.synapsex.dto.agent.AgentDiscoveryDto;
import com.dwp.services.synapsex.dto.agent.AgentDiscoveryResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentToolCatalogItemDto;
import com.dwp.services.synapsex.dto.agent.KnowledgeCatalogItemDto;
import com.dwp.services.synapsex.entity.AgentMaster;
import com.dwp.services.synapsex.entity.AppCode;
import com.dwp.services.synapsex.repository.AppCodeRepository;
import com.dwp.services.synapsex.entity.AgentPromptHistory;
import com.dwp.services.synapsex.entity.AgentToolInventory;
import com.dwp.services.synapsex.entity.RagDocument;
import com.dwp.services.synapsex.repository.AgentMasterRepository;
import com.dwp.services.synapsex.repository.AgentPromptHistoryRepository;
import com.dwp.services.synapsex.repository.AgentToolMappingRepository;
import com.dwp.services.synapsex.repository.AgentToolInventoryRepository;
import com.dwp.services.synapsex.repository.AgentDocumentMappingRepository;
import com.dwp.services.synapsex.repository.RagDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConfigQueryService {

    private final AgentMasterRepository agentMasterRepository;
    private final AgentPromptHistoryRepository agentPromptHistoryRepository;
    private final AgentToolMappingRepository agentToolMappingRepository;
    private final AgentToolInventoryRepository agentToolInventoryRepository;
    private final AgentDocumentMappingRepository agentDocumentMappingRepository;
    private final RagDocumentRepository ragDocumentRepository;
    private final AppCodeRepository appCodeRepository;

    /**
     * GET /api/v1/agents/{id}/config — 모델 설정, 최신 프롬프트, 활성 도구 리스트 일괄 반환
     */
    @Transactional(readOnly = true)
    public Optional<AgentConfigResponseDto> getAgentConfig(Long tenantId, Long agentId) {
        Optional<AgentMaster> agentOpt = agentMasterRepository.findByTenantIdAndAgentId(tenantId, agentId);
        if (agentOpt.isEmpty()) {
            log.debug("Agent not found: tenantId={}, agentId={}", tenantId, agentId);
            return Optional.empty();
        }
        AgentMaster agent = agentOpt.get();
        if (!Boolean.TRUE.equals(agent.getIsActive())) {
            log.debug("Agent is inactive: tenantId={}, agentId={}, agentKey={}, isActive={}", 
                    tenantId, agentId, agent.getAgentKey(), agent.getIsActive());
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

        log.info("[getAgentConfig] Querying docIds: tenantId={} agentId={} agentKey={}", tenantId, agentId, agent.getAgentKey());
        List<Long> docIds = Optional.ofNullable(
                agentDocumentMappingRepository.findDocIdsByTenantIdAndAgentId(tenantId, agentId))
                .orElseGet(List::of);
        log.info("[getAgentConfig] DocIds query result: tenantId={} agentId={} docIds={} (count={})", 
                tenantId, agentId, docIds, docIds.size());
        if (docIds.isEmpty()) {
            log.warn("[getAgentConfig] EMPTY docIds! Check agent_document_mapping table: tenantId={} agentId={} agentKey={}",
                    tenantId, agentId, agent.getAgentKey());
        }

        AgentConfigResponseDto dto = AgentConfigResponseDto.builder()
                .agentId(agent.getAgentId())
                .agentKey(agent.getAgentKey())
                .name(agent.getName())
                .domain(agent.getDomain())
                .tenantId(agent.getTenantId())
                .model(model)
                .systemInstruction(systemInstruction)
                .version(version)
                .tools(toolDtos)
                .docIds(docIds)
                .build();

        return Optional.of(dto);
    }

    /**
     * agent_key로 설정 조회 (Aura용). tenantId + agent_key로 활성 에이전트 찾은 뒤 getAgentConfig와 동일 응답.
     */
    @Transactional(readOnly = true)
    public Optional<AgentConfigResponseDto> getAgentConfigByKey(Long tenantId, String agentKey) {
        log.info("[getAgentConfigByKey] START: tenantId={} agentKey={}", tenantId, agentKey);
        Optional<AgentMaster> agentOpt = agentMasterRepository.findByTenantIdAndAgentKey(tenantId, agentKey);
        if (agentOpt.isEmpty()) {
            log.warn("[getAgentConfigByKey] Agent NOT FOUND in DB: tenantId={}, agentKey={}", tenantId, agentKey);
            return Optional.empty();
        }
        AgentMaster agent = agentOpt.get();
        log.info("[getAgentConfigByKey] Agent found: agentId={} agentKey={} tenantId={} isActive={}", 
                agent.getAgentId(), agent.getAgentKey(), agent.getTenantId(), agent.getIsActive());
        if (!Boolean.TRUE.equals(agent.getIsActive())) {
            log.warn("[getAgentConfigByKey] Agent is INACTIVE: tenantId={}, agentKey={}, agentId={}", 
                    tenantId, agentKey, agent.getAgentId());
            return Optional.empty();
        }
        return getAgentConfig(tenantId, agent.getAgentId());
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

    /**
     * GET /api/synapse/agents?discovery=true — Aura 엔진용 에이전트 디스커버리 목록
     * 활성 에이전트의 메타데이터만 반환 (agentKey, domain, description)
     */
    @Transactional(readOnly = true)
    public AgentDiscoveryResponseDto getAgentDiscovery(Long tenantId) {
        List<AgentMaster> agents = agentMasterRepository.findByTenantIdAndIsActiveTrueOrderByAgentIdAsc(tenantId);
        log.debug("Agent discovery query: tenantId={} found {} active agents", tenantId, agents.size());
        List<AgentDiscoveryDto> discoveryList = agents.stream()
                .map(a -> AgentDiscoveryDto.builder()
                        .agentKey(a.getAgentKey())
                        .domain(a.getDomain())
                        .description(a.getName()) // description 필드가 없으므로 name을 description으로 사용
                        .build())
                .collect(Collectors.toList());
        if (discoveryList.isEmpty()) {
            log.warn("Agent discovery returned empty list for tenantId={}. Check if any agents exist with is_active=true", tenantId);
        }
        return AgentDiscoveryResponseDto.builder()
                .agents(discoveryList)
                .build();
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
                .tenantId(agent.getTenantId())
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
        List<AgentCatalogResponseDto.KeyValueItem> domains = codes.stream().filter(c -> "AGENT_DOMAIN".equals(c.getGroupKey())).map(c -> AgentCatalogResponseDto.KeyValueItem.builder().key(c.getCode()).value(c.getName()).description(c.getDescription()).build()).collect(Collectors.toList());
        List<AgentCatalogResponseDto.KeyValueItem> docTypes = codes.stream().filter(c -> "DOC_TYPE".equals(c.getGroupKey())).map(c -> AgentCatalogResponseDto.KeyValueItem.builder().key(c.getCode()).value(c.getName()).description(c.getDescription()).build()).collect(Collectors.toList());
        List<AgentCatalogResponseDto.KeyValueItem> models = codes.stream().filter(c -> "LLM_MODEL".equals(c.getGroupKey())).map(c -> AgentCatalogResponseDto.KeyValueItem.builder().key(c.getCode()).value(c.getName()).description(c.getDescription()).build()).collect(Collectors.toList());
        List<AgentToolCatalogItemDto> tools = getToolCatalog();
        return AgentCatalogResponseDto.builder().domains(domains).docTypes(docTypes).models(models).tools(tools).build();
    }

    /** GET /api/synapse/agents/knowledge — 지식 베이스 카탈로그 (agent_id가 있으면 해당 에이전트에 바인딩된 문서만 반환) */
    @Transactional(readOnly = true)
    public List<KnowledgeCatalogItemDto> getKnowledgeCatalog(Long tenantId, Long agentId, int page, int size) {
        List<RagDocument> documents;
        
        if (agentId != null) {
            // agent_id가 있으면 해당 에이전트에 바인딩된 문서만 조회
            if (!agentMasterRepository.existsByTenantIdAndAgentId(tenantId, agentId)) {
                return List.of(); // 에이전트가 존재하지 않으면 빈 리스트 반환
            }
            List<Long> boundDocIds = agentDocumentMappingRepository.findDocIdsByTenantIdAndAgentId(tenantId, agentId);
            if (boundDocIds.isEmpty()) {
                return List.of(); // 바인딩된 문서가 없으면 빈 리스트 반환
            }
            documents = ragDocumentRepository.findAllById(boundDocIds).stream()
                    .filter(doc -> doc.getTenantId().equals(tenantId))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                    .collect(Collectors.toList());
        } else {
            // agent_id가 없으면 전체 문서 조회
            documents = ragDocumentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        
        // 페이징 처리
        int from = Math.max(0, page * size);
        int to = Math.min(from + size, documents.size());
        List<RagDocument> paged = documents.subList(from, to);
        
        return paged.stream()
                .map(doc -> KnowledgeCatalogItemDto.builder()
                        .docId(doc.getDocId())
                        .title(doc.getTitle())
                        .sourceType(doc.getSourceType())
                        .docType(doc.getDocType())
                        .status(doc.getStatus())
                        .isBound(agentId != null) // agent_id가 있으면 모두 바인딩된 문서
                        .createdAt(doc.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }
}
