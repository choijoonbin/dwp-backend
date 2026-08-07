package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.OidcUserInfo;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OidcService {

    private final IdentityProviderRepository identityProviderRepository;
    private final OidcStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${sso.callback-url:http://localhost:4200/auth/oidc/callback}")
    private String callbackUrl;

    public OidcService(
            IdentityProviderRepository identityProviderRepository,
            OidcStateStore stateStore,
            ObjectMapper objectMapper) {
        this.identityProviderRepository = identityProviderRepository;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
    }

    public String getAuthorizationUrl(Long tenantId, String providerKey) {
        IdentityProvider provider = requireProvider(tenantId, providerKey);
        String state = stateStore.create(tenantId, providerKey);
        return UriComponentsBuilder.fromUriString(provider.getAuthUrl())
                .queryParam("client_id", provider.getClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", "openid profile email")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    public OidcExchangeResult exchange(String state, String code) {
        OidcStateStore.StateContext context = stateStore.consume(state);
        IdentityProvider provider = requireProvider(context.tenantId(), context.providerKey());
        String clientSecret = resolveClientSecret(provider);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", callbackUrl);
        form.put("client_id", provider.getClientId());
        form.put("client_secret", clientSecret);

        JsonNode tokenPayload = postForm(provider.getTokenUrl(), form);
        String accessToken = requiredText(tokenPayload, "access_token");
        JsonNode userPayload = getJson(provider.getUserInfoUrl(), accessToken);
        OidcUserInfo userInfo = new OidcUserInfo(
                requiredText(userPayload, "sub"),
                optionalText(userPayload, "email"),
                optionalText(userPayload, "name"));
        return new OidcExchangeResult(context.tenantId(), context.providerKey(), userInfo);
    }

    private IdentityProvider requireProvider(Long tenantId, String providerKey) {
        IdentityProvider provider = identityProviderRepository
                .findByTenantIdAndProviderKey(tenantId, providerKey)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND));
        if (!Boolean.TRUE.equals(provider.getEnabled()) || !"OIDC".equals(provider.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "활성화된 OIDC 공급자가 아닙니다.");
        }
        if (isBlank(provider.getAuthUrl())
                || isBlank(provider.getTokenUrl())
                || isBlank(provider.getUserInfoUrl())
                || isBlank(provider.getClientId())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC 공급자 설정이 완전하지 않습니다.");
        }
        return provider;
    }

    private String resolveClientSecret(IdentityProvider provider) {
        if (isBlank(provider.getClientSecretEnv())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC client secret 환경 변수명이 없습니다.");
        }
        String secret = System.getenv(provider.getClientSecretEnv());
        if (isBlank(secret)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC client secret이 설정되지 않았습니다.");
        }
        return secret;
    }

    private JsonNode postForm(String url, Map<String, String> values) {
        String body = values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private JsonNode getJson(String url, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR);
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR);
        } catch (IOException exception) {
            throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR);
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (isBlank(value)) throw new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR);
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record OidcExchangeResult(Long tenantId, String providerKey, OidcUserInfo userInfo) {
    }
}
