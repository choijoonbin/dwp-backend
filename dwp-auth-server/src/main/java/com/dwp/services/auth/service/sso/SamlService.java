package com.dwp.services.auth.service.sso;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PR-10C: SAML 연동 서비스 (Skeleton)
 * 
 * 실제 연동은 다음 PR로 분리 가능
 * 현재는 metadata_url 기반 skeleton만 제공
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SamlService {
    
    private final IdentityProviderRepository identityProviderRepository;
    
    /**
     * PR-10C: SAML Metadata URL 조회
     * 
     * @param tenantId 테넌트 ID
     * @param providerKey Provider Key (예: SAML_SKT)
     * @return SAML Metadata URL
     */
    @Transactional(readOnly = true)
    public String getMetadataUrl(Long tenantId, String providerKey) {
        IdentityProvider idp = identityProviderRepository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                        "Identity Provider를 찾을 수 없습니다: " + providerKey));
        
        if (!idp.getEnabled()) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "Identity Provider가 비활성화되어 있습니다: " + providerKey);
        }
        
        if (!"SAML".equals(idp.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "SAML 타입이 아닙니다: " + idp.getProviderType());
        }
        
        if (idp.getMetadataUrl() == null || idp.getMetadataUrl().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "SAML Metadata URL이 설정되지 않았습니다.");
        }
        
        return idp.getMetadataUrl();
    }
    
    /**
     * PR-10C: SAML 로그인 시작 (SAML AuthnRequest 생성)
     * 
     * 실제 구현은 다음 PR로 분리
     * 현재는 skeleton만 제공
     */
    @Transactional(readOnly = true)
    public String initiateSamlLogin(Long tenantId, String providerKey) {
        IdentityProvider idp = identityProviderRepository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                        "Identity Provider를 찾을 수 없습니다: " + providerKey));
        
        if (!idp.getEnabled() || !"SAML".equals(idp.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "SAML Identity Provider가 활성화되지 않았습니다.");
        }
        
        // TODO: SAML AuthnRequest 생성 및 SSO URL 반환
        // 실제 구현은 다음 PR로 분리
        log.info("SAML login initiated: tenantId={}, providerKey={} (skeleton)", tenantId, providerKey);
        
        if (idp.getAuthUrl() == null || idp.getAuthUrl().isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "SAML Auth URL이 설정되지 않았습니다.");
        }
        
        return idp.getAuthUrl(); // 임시로 Auth URL 반환
    }
    
    /**
     * PR-10C: SAML Assertion 처리 (SAML Response 파싱)
     * 
     * 실제 구현은 다음 PR로 분리
     * 현재는 skeleton만 제공
     */
    @Transactional(readOnly = true)
    public SamlUserInfo processSamlResponse(Long tenantId, String providerKey, String samlResponse) {
        IdentityProvider idp = identityProviderRepository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                        "Identity Provider를 찾을 수 없습니다: " + providerKey));
        
        if (!idp.getEnabled() || !"SAML".equals(idp.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "SAML Identity Provider가 활성화되지 않았습니다.");
        }
        
        // TODO: SAML Response 파싱 및 사용자 정보 추출
        // 실제 구현은 다음 PR로 분리
        log.info("SAML response processing: tenantId={}, providerKey={} (skeleton)", tenantId, providerKey);
        
        // Skeleton: 빈 사용자 정보 반환
        return SamlUserInfo.builder()
                .nameId(null)
                .email(null)
                .name(null)
                .build();
    }
}
