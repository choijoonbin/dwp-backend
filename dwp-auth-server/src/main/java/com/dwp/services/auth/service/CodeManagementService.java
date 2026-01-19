package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.CodeGroupResponse;
import com.dwp.services.auth.dto.CodeResponse;
import com.dwp.services.auth.dto.admin.CreateCodeGroupRequest;
import com.dwp.services.auth.dto.admin.CreateCodeRequest;
import com.dwp.services.auth.dto.admin.UpdateCodeGroupRequest;
import com.dwp.services.auth.dto.admin.UpdateCodeRequest;
import com.dwp.services.auth.entity.Code;
import com.dwp.services.auth.entity.CodeGroup;
import com.dwp.services.auth.repository.CodeGroupRepository;
import com.dwp.services.auth.repository.CodeRepository;
import com.dwp.services.auth.service.audit.AuditLogService;
import com.dwp.services.auth.util.CodeResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 코드 관리 서비스
 */
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CodeManagementService {
    
    private final CodeGroupRepository codeGroupRepository;
    private final CodeRepository codeRepository;
    private final CodeResolver codeResolver;
    private final AuditLogService auditLogService;
    
    /**
     * 모든 코드 그룹 조회
     */
    @Transactional(readOnly = true)
    public List<CodeGroupResponse> getAllGroups() {
        return codeGroupRepository.findByIsActiveTrueOrderByGroupKey()
                .stream()
                .map(this::toCodeGroupResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * 그룹별 코드 목록 조회
     */
    @Transactional(readOnly = true)
    public List<CodeResponse> getCodesByGroup(String groupKey) {
        return codeRepository.findByGroupKeyAndIsActiveTrueOrderBySortOrderAsc(groupKey)
                .stream()
                .map(this::toCodeResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * 모든 그룹의 코드를 맵으로 조회
     */
    @Transactional(readOnly = true)
    public Map<String, List<CodeResponse>> getAllCodesByGroup() {
        List<String> groupKeys = codeGroupRepository.findByIsActiveTrueOrderByGroupKey()
                .stream()
                .map(CodeGroup::getGroupKey)
                .collect(Collectors.toList());
        
        return codeRepository.findByGroupKeyInAndIsActiveTrueOrderByGroupKeyAscSortOrderAsc(groupKeys)
                .stream()
                .map(this::toCodeResponse)
                .collect(Collectors.groupingBy(CodeResponse::getGroupKey));
    }
    
    private CodeGroupResponse toCodeGroupResponse(CodeGroup group) {
        return CodeGroupResponse.builder()
                .sysCodeGroupId(group.getSysCodeGroupId())
                .groupKey(group.getGroupKey())
                .groupName(group.getGroupName())
                .description(group.getDescription())
                .isActive(group.getIsActive())
                .build();
    }
    
    private CodeResponse toCodeResponse(Code code) {
        return CodeResponse.builder()
                .sysCodeId(code.getSysCodeId())
                .groupKey(code.getGroupKey())
                .code(code.getCode())
                .name(code.getName())
                .description(code.getDescription())
                .sortOrder(code.getSortOrder())
                .isActive(code.getIsActive())
                .ext1(code.getExt1())
                .ext2(code.getExt2())
                .ext3(code.getExt3())
                .build();
    }
    
    /**
     * 코드 그룹 생성
     */
    @Transactional
    public CodeGroupResponse createCodeGroup(Long tenantId, Long actorUserId, CreateCodeGroupRequest request,
                                             HttpServletRequest httpRequest) {
        // 그룹 키 중복 체크
        codeGroupRepository.findByGroupKey(request.getGroupKey())
                .ifPresent(g -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 그룹 키입니다.");
                });
        
        CodeGroup group = CodeGroup.builder()
                .groupKey(request.getGroupKey())
                .groupName(request.getGroupName())
                .description(request.getDescription())
                .isActive(request.getIsActive() != null ? request.getIsActive() : true)
                .build();
        group = codeGroupRepository.save(group);
        
        // 캐시 초기화
        codeResolver.clearCache(request.getGroupKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_GROUP_CREATE", "CODE_GROUP", group.getSysCodeGroupId(),
                null, group, httpRequest);
        
        return toCodeGroupResponse(group);
    }
    
    /**
     * 코드 그룹 수정
     */
    @Transactional
    public CodeGroupResponse updateCodeGroup(Long tenantId, Long actorUserId, Long groupId,
                                             UpdateCodeGroupRequest request, HttpServletRequest httpRequest) {
        CodeGroup group = codeGroupRepository.findById(groupId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 그룹을 찾을 수 없습니다."));
        
        CodeGroup before = copyCodeGroup(group);
        
        if (request.getGroupName() != null) {
            group.setGroupName(request.getGroupName());
        }
        if (request.getDescription() != null) {
            group.setDescription(request.getDescription());
        }
        if (request.getIsActive() != null) {
            group.setIsActive(request.getIsActive());
        }
        
        CodeGroup updatedGroup = codeGroupRepository.save(group);
        
        // 캐시 초기화
        codeResolver.clearCache(updatedGroup.getGroupKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_GROUP_UPDATE", "CODE_GROUP", groupId,
                before, updatedGroup, httpRequest);
        
        return toCodeGroupResponse(updatedGroup);
    }
    
    /**
     * 코드 그룹 삭제
     */
    @Transactional
    public void deleteCodeGroup(Long tenantId, Long actorUserId, Long groupId, HttpServletRequest httpRequest) {
        CodeGroup group = codeGroupRepository.findById(groupId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 그룹을 찾을 수 없습니다."));
        
        // 코드 존재 시 삭제 금지
        long codeCount = codeRepository.findByGroupKeyOrderBySortOrderAsc(group.getGroupKey()).size();
        if (codeCount > 0) {
            throw new BaseException(ErrorCode.INVALID_STATE, "코드가 존재하는 그룹은 삭제할 수 없습니다.");
        }
        
        CodeGroup before = copyCodeGroup(group);
        
        codeGroupRepository.delete(group);
        
        // 캐시 초기화
        codeResolver.clearCache(group.getGroupKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_GROUP_DELETE", "CODE_GROUP", groupId,
                before, null, httpRequest);
    }
    
    /**
     * 코드 생성
     */
    @Transactional
    public CodeResponse createCode(Long tenantId, Long actorUserId, CreateCodeRequest request,
                                   HttpServletRequest httpRequest) {
        // 그룹 존재 확인
        codeGroupRepository.findByGroupKey(request.getGroupKey())
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드 그룹을 찾을 수 없습니다."));
        
        // 코드 중복 체크
        codeRepository.findByGroupKeyAndCode(request.getGroupKey(), request.getCodeKey())
                .ifPresent(c -> {
                    throw new BaseException(ErrorCode.DUPLICATE_ENTITY, "이미 존재하는 코드 키입니다.");
                });
        
        Code code = Code.builder()
                .groupKey(request.getGroupKey())
                .code(request.getCodeKey())
                .name(request.getCodeName())
                .description(request.getDescription())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isActive(request.getEnabled() != null ? request.getEnabled() : true)
                .ext1(request.getExt1())
                .ext2(request.getExt2())
                .ext3(request.getExt3())
                .build();
        code = codeRepository.save(code);
        
        // 캐시 초기화
        codeResolver.clearCache(request.getGroupKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_CREATE", "CODE", code.getSysCodeId(),
                null, code, httpRequest);
        
        return toCodeResponse(code);
    }
    
    /**
     * 코드 수정
     */
    @Transactional
    public CodeResponse updateCode(Long tenantId, Long actorUserId, Long codeId,
                                   UpdateCodeRequest request, HttpServletRequest httpRequest) {
        Code code = codeRepository.findById(codeId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드를 찾을 수 없습니다."));
        
        Code before = copyCode(code);
        
        if (request.getCodeName() != null) {
            code.setName(request.getCodeName());
        }
        if (request.getDescription() != null) {
            code.setDescription(request.getDescription());
        }
        if (request.getSortOrder() != null) {
            code.setSortOrder(request.getSortOrder());
        }
        if (request.getEnabled() != null) {
            code.setIsActive(request.getEnabled());
        }
        if (request.getExt1() != null) {
            code.setExt1(request.getExt1());
        }
        if (request.getExt2() != null) {
            code.setExt2(request.getExt2());
        }
        if (request.getExt3() != null) {
            code.setExt3(request.getExt3());
        }
        
        code = codeRepository.save(code);
        
        // 캐시 초기화
        codeResolver.clearCache(code.getGroupKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_UPDATE", "CODE", codeId,
                before, code, httpRequest);
        
        return toCodeResponse(code);
    }
    
    /**
     * 코드 삭제
     */
    @Transactional
    public void deleteCode(Long tenantId, Long actorUserId, Long codeId, HttpServletRequest httpRequest) {
        Code code = codeRepository.findById(codeId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "코드를 찾을 수 없습니다."));
        
        Code before = copyCode(code);
        
        // Soft delete (isActive = false)
        code.setIsActive(false);
        codeRepository.save(code);
        
        // 캐시 초기화
        codeResolver.clearCache(code.getGroupKey());
        
        // 감사 로그
        auditLogService.recordAuditLog(tenantId, actorUserId, "CODE_DELETE", "CODE", codeId,
                before, code, httpRequest);
    }
    
    private CodeGroup copyCodeGroup(CodeGroup group) {
        return CodeGroup.builder()
                .sysCodeGroupId(group.getSysCodeGroupId())
                .groupKey(group.getGroupKey())
                .groupName(group.getGroupName())
                .description(group.getDescription())
                .isActive(group.getIsActive())
                .build();
    }
    
    private Code copyCode(Code code) {
        return Code.builder()
                .sysCodeId(code.getSysCodeId())
                .groupKey(code.getGroupKey())
                .code(code.getCode())
                .name(code.getName())
                .description(code.getDescription())
                .sortOrder(code.getSortOrder())
                .isActive(code.getIsActive())
                .ext1(code.getExt1())
                .ext2(code.getExt2())
                .ext3(code.getExt3())
                .build();
    }
}
