package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.agent.AgentCatalogResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentConfigResponseDto;
import com.dwp.services.synapsex.dto.agent.AgentDetailDto;
import com.dwp.services.synapsex.dto.agent.AgentToolCatalogItemDto;
import com.dwp.services.synapsex.dto.agent.CreateAgentRequest;
import com.dwp.services.synapsex.dto.agent.KnowledgeCatalogItemDto;
import com.dwp.services.synapsex.dto.agent.UpdateAgentRequest;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.rag.RagDocumentListDto;
import com.dwp.services.synapsex.service.agent.AgentCommandService;
import com.dwp.services.synapsex.service.agent.AgentConfigQueryService;
import com.dwp.services.synapsex.service.rag.RagQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 에이전트 스튜디오 API — 생성/배포/설정 조회 및 카탈로그
 * Tenant Isolation: X-Tenant-ID 필수
 */
@Tag(name = "Agent Studio", description = "에이전트 스튜디오 — CRUD, 설정 조회, 도구/지식 카탈로그")
@RestController
@RequestMapping("/synapse/agents")
@RequiredArgsConstructor
public class AgentStudioController {

    private final AgentConfigQueryService agentConfigQueryService;
    private final AgentCommandService agentCommandService;
    private final RagQueryService ragQueryService;

    @Operation(summary = "에이전트 목록 조회", description = "활성 에이전트 목록. 사이드바 카드 등 FE 목록용.")
    @GetMapping
    public ApiResponse<List<AgentDetailDto>> listAgents(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId) {
        List<AgentDetailDto> list = agentConfigQueryService.listAgents(tenantId);
        return ApiResponse.success(list);
    }

    @Operation(summary = "에이전트 상세 조회", description = "단건 상세. 4탭 로드 시 사용.")
    @GetMapping("/{id}")
    public ApiResponse<AgentDetailDto> getAgentById(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("id") Long agentId) {
        AgentDetailDto detail = agentConfigQueryService.getAgentDetail(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없습니다."));
        return ApiResponse.success(detail);
    }

    @Operation(summary = "에이전트 설정 조회(agent_key)", description = "Aura용. agent_key만으로 config 조회. Query: agent_key 필수.")
    @GetMapping("/config")
    public ApiResponse<AgentConfigResponseDto> getAgentConfigByKey(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(value = "agent_key", required = false) String agentKey) {
        if (agentKey == null || agentKey.isBlank()) {
            throw new BaseException(ErrorCode.VALIDATION_ERROR, "agent_key 쿼리 파라미터가 필요합니다.");
        }
        AgentConfigResponseDto config = agentConfigQueryService.getAgentConfigByKey(tenantId, agentKey.trim())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없거나 비활성 상태입니다."));
        return ApiResponse.success(config);
    }

    @Operation(summary = "에이전트 설정 조회(agentId)", description = "모델 설정, 최신 시스템 프롬프트, 활성 도구 리스트. Aura 런타임 조립용.")
    @GetMapping("/{id}/config")
    public ApiResponse<AgentConfigResponseDto> getAgentConfig(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("id") Long agentId) {
        AgentConfigResponseDto config = agentConfigQueryService.getAgentConfig(tenantId, agentId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "에이전트를 찾을 수 없거나 비활성 상태입니다."));
        return ApiResponse.success(config);
    }

    @Operation(summary = "에이전트 생성", description = "agent_master 및 초기 prompt_history 저장. agent_key는 Snake Case 권장(finance_aura, hr_aura).")
    @PostMapping
    public ApiResponse<AgentDetailDto> createAgent(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody CreateAgentRequest request) {
        AgentDetailDto created = agentCommandService.create(tenantId, request);
        return ApiResponse.success("에이전트가 생성되었습니다.", created);
    }

    @Operation(summary = "에이전트 수정", description = "모델/파라미터/프롬프트/도구 매핑 업데이트. 프롬프트 변경 시 버전 이력 유지(is_current 전환).")
    @PutMapping("/{id}")
    public ApiResponse<AgentDetailDto> updateAgent(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("id") Long agentId,
            @Valid @RequestBody UpdateAgentRequest request) {
        AgentDetailDto updated = agentCommandService.update(tenantId, agentId, request);
        return ApiResponse.success("에이전트가 수정되었습니다.", updated);
    }

    @Operation(summary = "에이전트 삭제", description = "Soft delete (is_active=false). 목록/설정 조회에서 제외됨.")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteAgent(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable("id") Long agentId) {
        agentCommandService.delete(tenantId, agentId);
        return ApiResponse.success("에이전트가 삭제되었습니다.", null);
    }

    @Operation(summary = "통합 카탈로그", description = "domains, docTypes, models(app_codes), tools 한꺼번에. FE 옵션 동적 렌더링용.")
    @GetMapping("/catalog")
    public ApiResponse<AgentCatalogResponseDto> getCatalog() {
        return ApiResponse.success(agentConfigQueryService.getCatalog());
    }

    @Operation(summary = "도구 카탈로그", description = "Aura에 등록 가능한 전체 도구 인벤토리. FE '도구' 탭용.")
    @GetMapping("/tools")
    public ApiResponse<List<AgentToolCatalogItemDto>> getToolCatalog() {
        List<AgentToolCatalogItemDto> list = agentConfigQueryService.getToolCatalog();
        return ApiResponse.success(list);
    }

    @Operation(summary = "지식 베이스 카탈로그", description = "업로드된 RAG 문서 리스트 및 상태. FE '지식' 탭용. X-Tenant-ID 필수.")
    @GetMapping("/knowledge")
    public ApiResponse<List<KnowledgeCatalogItemDto>> getKnowledgeCatalog(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        PageResponse<RagDocumentListDto> paged = ragQueryService.listDocuments(tenantId, null, page, size);
        List<KnowledgeCatalogItemDto> list = paged.getItems().stream()
                .map(d -> KnowledgeCatalogItemDto.builder()
                        .docId(d.getDocId())
                        .title(d.getTitle())
                        .sourceType(d.getSourceType())
                        .docType(d.getDocType())
                        .status(d.getStatus())
                        .createdAt(d.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
        return ApiResponse.success(list);
    }

}
