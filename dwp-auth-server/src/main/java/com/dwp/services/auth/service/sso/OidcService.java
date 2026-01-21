package com.dwp.services.auth.service.sso;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.entity.UserAccount;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import com.dwp.services.auth.repository.UserAccountRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.util.CodeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * PR-10B: OIDC 연동 서비스
 * 
 * Azure AD 예시로 시작하는 OIDC authorization_code flow 구현
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class OidcService {
    
    private final IdentityProviderRepository identityProviderRepository;
    private final UserAccountRepository userAccountRepository;
    private final UserRepository userRepository;
    private final CodeResolver codeResolver;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    
    @Value("${sso.redirect-base-url:http://localhost:3000}")
    private String redirectBaseUrl;
    
    /**
     * PR-10B: OIDC Authorization URL 생성
     * 
     * @param tenantId 테넌트 ID
     * @param providerKey Provider Key (예: AZURE_AD)
     * @param state CSRF 방지를 위한 state 파라미터
     * @return Authorization URL
     */
    @Transactional(readOnly = true)
    public String getAuthorizationUrl(Long tenantId, String providerKey, String state) {
        IdentityProvider idp = identityProviderRepository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                        "Identity Provider를 찾을 수 없습니다: " + providerKey));
        
        if (!idp.getEnabled()) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "Identity Provider가 비활성화되어 있습니다: " + providerKey);
        }
        
        if (!"OIDC".equals(idp.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "OIDC 타입이 아닙니다: " + idp.getProviderType());
        }
        
        if (idp.getAuthUrl() == null || idp.getClientId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "Identity Provider 설정이 불완전합니다: authUrl 또는 clientId가 없습니다.");
        }
        
        // Redirect URI 생성
        String redirectUri = redirectBaseUrl + "/auth/oidc/callback";
        
        // Authorization URL 생성 (authorization_code flow)
        return UriComponentsBuilder.fromUriString(idp.getAuthUrl())
                .queryParam("client_id", idp.getClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "openid profile email")
                .queryParam("state", state)
                .queryParam("response_mode", "query")
                .build()
                .toUriString();
    }
    
    /**
     * PR-10B: OIDC Authorization Code를 Access Token으로 교환
     * 
     * @param tenantId 테넌트 ID
     * @param providerKey Provider Key
     * @param code Authorization Code
     * @param state State 파라미터 (검증)
     * @return 사용자 정보 (email, name 등)
     */
    @Transactional
    public OidcUserInfo exchangeCodeForUserInfo(Long tenantId, String providerKey, String code, String state) {
        IdentityProvider idp = identityProviderRepository.findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, 
                        "Identity Provider를 찾을 수 없습니다: " + providerKey));
        
        if (!idp.getEnabled() || !"OIDC".equals(idp.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, 
                    "Identity Provider가 활성화되지 않았거나 OIDC 타입이 아닙니다.");
        }
        
        // Redirect URI
        String redirectUri = redirectBaseUrl + "/auth/oidc/callback";
        
        // Client Secret (config_json에서 추출 또는 별도 필드)
        String clientSecret = extractClientSecret(idp);
        
        // Token 요청
        Map<String, String> tokenRequest = new HashMap<>();
        tokenRequest.put("grant_type", "authorization_code");
        tokenRequest.put("code", code);
        tokenRequest.put("redirect_uri", redirectUri);
        tokenRequest.put("client_id", idp.getClientId());
        tokenRequest.put("client_secret", clientSecret);
        
        try {
            // Token Endpoint 호출
            HttpRequest tokenRequestHttp = HttpRequest.newBuilder()
                    .uri(URI.create(idp.getTokenUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(buildFormData(tokenRequest)))
                    .timeout(Duration.ofSeconds(10))
                    .build();
            
            HttpResponse<String> tokenResponse = httpClient.send(tokenRequestHttp, HttpResponse.BodyHandlers.ofString());
            
            if (tokenResponse.statusCode() != 200) {
                log.error("Token exchange failed: status={}, body={}", tokenResponse.statusCode(), tokenResponse.body());
                throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "OIDC 토큰 교환 실패");
            }
            
            JsonNode tokenJson = objectMapper.readTree(tokenResponse.body());
            String accessToken = tokenJson.get("access_token").asText();
            String idToken = tokenJson.has("id_token") ? tokenJson.get("id_token").asText() : null;
            
            // UserInfo Endpoint 호출 (또는 ID Token 디코딩)
            return getUserInfoFromIdToken(idToken, accessToken, idp);
            
        } catch (IOException | InterruptedException e) {
            log.error("Failed to exchange code for token", e);
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "OIDC 토큰 교환 중 오류 발생");
        }
    }
    
    /**
     * ID Token에서 사용자 정보 추출 (간단한 구현)
     * 실제로는 JWT 디코딩 및 검증이 필요하지만, 여기서는 간단히 구현
     */
    private OidcUserInfo getUserInfoFromIdToken(String idToken, String accessToken, IdentityProvider idp) {
        // 실제 구현에서는 JWT 디코딩 및 검증 필요
        // 여기서는 간단히 accessToken을 사용하여 UserInfo Endpoint 호출
        try {
            // UserInfo Endpoint URL (metadata_url에서 추출 또는 config_json)
            String userInfoUrl = extractUserInfoUrl(idp);
            
            if (userInfoUrl != null) {
                HttpRequest userInfoRequest = HttpRequest.newBuilder()
                        .uri(URI.create(userInfoUrl))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();
                
                HttpResponse<String> userInfoResponse = httpClient.send(userInfoRequest, HttpResponse.BodyHandlers.ofString());
                
                if (userInfoResponse.statusCode() == 200) {
                    JsonNode userInfoJson = objectMapper.readTree(userInfoResponse.body());
                    return OidcUserInfo.builder()
                            .email(userInfoJson.has("email") ? userInfoJson.get("email").asText() : null)
                            .name(userInfoJson.has("name") ? userInfoJson.get("name").asText() : null)
                            .sub(userInfoJson.has("sub") ? userInfoJson.get("sub").asText() : null)
                            .build();
                }
            }
            
            // Fallback: ID Token 디코딩 (간단한 구현)
            // 실제로는 JWT 라이브러리로 디코딩 및 검증 필요
            log.warn("UserInfo endpoint not available, using fallback");
            return OidcUserInfo.builder()
                    .email(null)
                    .name(null)
                    .sub(null)
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to get user info", e);
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, "사용자 정보 조회 실패");
        }
    }
    
    /**
     * 사용자 계정 생성 또는 조회 (SSO 로그인 후)
     */
    @Transactional
    public UserAccount findOrCreateUserAccount(Long tenantId, String providerKey, OidcUserInfo userInfo) {
        String providerType = "SSO";
        codeResolver.require("IDP_PROVIDER_TYPE", providerType);
        
        // Principal은 email 또는 sub 사용
        String principal = userInfo.getEmail() != null ? userInfo.getEmail() : userInfo.getSub();
        
        // 기존 계정 조회
        return userAccountRepository
                .findByTenantIdAndProviderTypeAndProviderIdAndPrincipal(
                        tenantId, providerType, providerKey, principal)
                .orElseGet(() -> {
                    // 새 계정 생성 (User도 함께 생성 필요)
                    log.info("Creating new SSO user account: tenantId={}, providerKey={}, principal={}", 
                            tenantId, providerKey, principal);
                    
                    // User 생성
                    User user = User.builder()
                            .tenantId(tenantId)
                            .displayName(userInfo.getName() != null ? userInfo.getName() : principal)
                            .email(userInfo.getEmail())
                            .status("ACTIVE")
                            .build();
                    user = userRepository.save(user);
                    
                    // UserAccount 생성
                    UserAccount account = UserAccount.builder()
                            .tenantId(tenantId)
                            .userId(user.getUserId())
                            .providerType(providerType)
                            .providerId(providerKey)
                            .principal(principal)
                            .status("ACTIVE")
                            .build();
                    return userAccountRepository.save(account);
                });
    }
    
    private String extractClientSecret(IdentityProvider idp) {
        // config_json에서 client_secret 추출 또는 ext1 필드 사용
        if (idp.getExt1() != null && !idp.getExt1().isEmpty()) {
            return idp.getExt1(); // 간단히 ext1에 저장
        }
        throw new BaseException(ErrorCode.INVALID_STATE, "Client Secret이 설정되지 않았습니다.");
    }
    
    private String extractUserInfoUrl(IdentityProvider idp) {
        // metadata_url에서 UserInfo Endpoint 추출 또는 config_json에서 추출
        // 실제로는 metadata_url을 호출하여 userinfo_endpoint를 가져와야 함
        return null; // TODO: 구현 필요
    }
    
    private String buildFormData(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }
}
