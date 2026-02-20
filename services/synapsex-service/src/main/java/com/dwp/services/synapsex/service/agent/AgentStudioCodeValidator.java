package com.dwp.services.synapsex.service.agent;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.repository.AppCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 에이전트 스튜디오: agent_master 입력값을 dwp_aura.app_codes 기준으로 검증.
 * 하드코딩 제거, 코드 테이블 무결성 가드레일.
 */
@Component
@RequiredArgsConstructor
public class AgentStudioCodeValidator {

    public static final String AGENT_DOMAIN = "AGENT_DOMAIN";
    public static final String DOC_TYPE = "DOC_TYPE";
    public static final String LLM_MODEL = "LLM_MODEL";

    private final AppCodeRepository appCodeRepository;

    /** domain이 null이 아니면 AGENT_DOMAIN에 존재하는 활성(is_active=true) 코드인지 검증. 미존재 시 400 + "정의되지 않은 시스템 코드입니다." */
    public void validateDomain(String domain) {
        if (domain == null || domain.isBlank()) return;
        if (appCodeRepository.findByGroupKeyAndCodeAndIsActiveTrue(AGENT_DOMAIN, domain.trim()).isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_CODE, "정의되지 않은 시스템 코드입니다.");
        }
    }

    /** modelName이 null이 아니면 LLM_MODEL에 존재하는 활성 코드인지 검증. 미존재 시 400 + "정의되지 않은 시스템 코드입니다." */
    public void validateModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) return;
        if (appCodeRepository.findByGroupKeyAndCodeAndIsActiveTrue(LLM_MODEL, modelName.trim()).isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_CODE, "정의되지 않은 시스템 코드입니다.");
        }
    }

    /** docType이 null이 아니면 DOC_TYPE에 존재하는 활성 코드인지 검증. */
    public void validateDocType(String docType) {
        if (docType == null || docType.isBlank()) return;
        if (appCodeRepository.findByGroupKeyAndCodeAndIsActiveTrue(DOC_TYPE, docType.trim()).isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_CODE, "정의되지 않은 시스템 코드입니다.");
        }
    }
}
