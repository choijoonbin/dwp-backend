package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.OidcUserInfo;
import com.dwp.services.auth.entity.IdentityProvider;
import com.dwp.services.auth.repository.IdentityProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OidcService {

    private final IdentityProviderRepository identityProviderRepository;
    private final OidcStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedHosts;
    private final boolean allowUnlistedHosts;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${sso.callback-url:http://localhost:4200/auth/oidc/callback}")
    private String callbackUrl;

    public OidcService(
            IdentityProviderRepository identityProviderRepository,
            OidcStateStore stateStore,
            ObjectMapper objectMapper,
            @Value("${dwp.auth.oidc.allowed-hosts:}") String allowedHosts,
            @Value("${dwp.auth.oidc.allow-unlisted-hosts:false}") boolean allowUnlistedHosts) {
        this.identityProviderRepository = identityProviderRepository;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
        this.allowedHosts = parseHosts(allowedHosts);
        this.allowUnlistedHosts = allowUnlistedHosts;
    }

    @Bulkhead(name = "oidcProvider", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "oidcProvider")
    public String getAuthorizationUrl(Long tenantId, String providerKey) {
        IdentityProvider provider = requireProvider(tenantId, providerKey);
        OidcStateStore.AuthorizationRequest authorization = stateStore.create(tenantId, providerKey);
        return UriComponentsBuilder.fromUriString(provider.getAuthUrl())
                .queryParam("client_id", provider.getClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", "openid profile email")
                .queryParam("state", authorization.state())
                .queryParam("nonce", authorization.nonce())
                .queryParam("code_challenge", sha256Base64Url(authorization.codeVerifier()))
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
    }

    @Bulkhead(name = "oidcProvider", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "oidcProvider")
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
        form.put("code_verifier", context.codeVerifier());

        JsonNode tokenPayload = postForm(provider.getTokenUrl(), form);
        Jwt idToken = decodeIdToken(provider, requiredText(tokenPayload, "id_token"));
        requireClaim(idToken.getClaimAsString("nonce"), context.nonce());

        String subject = idToken.getSubject();
        JsonNode userPayload = null;
        String accessToken = optionalText(tokenPayload, "access_token");
        if (!isBlank(accessToken) && !isBlank(provider.getUserInfoUrl())) {
            userPayload = getJson(provider.getUserInfoUrl(), accessToken);
            requireClaim(requiredText(userPayload, "sub"), subject);
        }
        String email = firstNonBlank(optionalText(userPayload, "email"), idToken.getClaimAsString("email"));
        boolean emailVerified = optionalBoolean(userPayload, "email_verified")
                || Boolean.TRUE.equals(idToken.getClaimAsBoolean("email_verified"));
        String name = firstNonBlank(optionalText(userPayload, "name"), idToken.getClaimAsString("name"));
        OidcUserInfo userInfo = new OidcUserInfo(
                idToken.getIssuer().toString(), subject, email, emailVerified, name);
        return new OidcExchangeResult(context.tenantId(), context.providerKey(), userInfo);
    }

    private Jwt decodeIdToken(IdentityProvider provider, String idToken) {
        try {
            JwtDecoder decoder = JwtDecoders.fromIssuerLocation(provider.getIssuerUri());
            Jwt jwt = decoder.decode(idToken);
            List<String> audience = jwt.getAudience();
            if (audience == null || !audience.contains(provider.getClientId())) {
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
            }
            return jwt;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
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
                || isBlank(provider.getIssuerUri())
                || isBlank(provider.getClientId())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC 공급자 설정이 완전하지 않습니다.");
        }
        requireAllowed(provider.getIssuerUri());
        requireAllowed(provider.getAuthUrl());
        requireAllowed(provider.getTokenUrl());
        if (!isBlank(provider.getUserInfoUrl())) requireAllowed(provider.getUserInfoUrl());
        return provider;
    }

    private void requireAllowed(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || (!allowUnlistedHosts && !allowedHosts.contains(host.toLowerCase(Locale.ROOT)))) {
                throw new BaseException(ErrorCode.INVALID_STATE, "OIDC endpoint host is not allowed.");
            }
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC endpoint is invalid.");
        }
    }

    private static Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(host -> host.toLowerCase(Locale.ROOT))
                .filter(host -> host.matches("[a-z0-9.-]+"))
                .collect(Collectors.toUnmodifiableSet());
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
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean optionalBoolean(JsonNode node, String field) {
        if (node == null) return false;
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private static void requireClaim(String actual, String expected) {
        if (isBlank(actual) || !actual.equals(expected)) {
            throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
