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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
public class OidcService {

    private static final int MINIMUM_STEP_UP_MAX_AGE_SECONDS = 60;
    private static final int MAXIMUM_STEP_UP_MAX_AGE_SECONDS = 3600;
    private static final String VALUE_PATTERN = "[A-Za-z0-9:._/+-]{1,200}";

    private final IdentityProviderRepository identityProviderRepository;
    private final OidcStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final Set<String> allowedHosts;
    private final Set<String> allowedCallbackHosts;
    private final boolean allowUnlistedHosts;
    private final String callbackUrl;
    private final long assuranceClockSkewSeconds;
    private final Clock clock;
    private final HttpClient httpClient;
    private final Function<String, String> environmentReader;

    @Autowired
    public OidcService(
            IdentityProviderRepository identityProviderRepository,
            OidcStateStore stateStore,
            ObjectMapper objectMapper,
            @Value("${dwp.auth.oidc.allowed-hosts:}") String allowedHosts,
            @Value("${dwp.auth.oidc.allowed-callback-hosts:}") String allowedCallbackHosts,
            @Value("${dwp.auth.oidc.allow-unlisted-hosts:false}") boolean allowUnlistedHosts,
            @Value("${sso.callback-url:http://localhost:4200/auth/oidc/callback}")
            String callbackUrl,
            @Value("${dwp.auth.step-up.assurance-clock-skew-seconds:30}")
            long assuranceClockSkewSeconds) {
        this(identityProviderRepository, stateStore, objectMapper, allowedHosts,
                allowedCallbackHosts, allowUnlistedHosts, callbackUrl,
                assuranceClockSkewSeconds, Clock.systemUTC(), HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(), System::getenv);
    }

    OidcService(
            IdentityProviderRepository identityProviderRepository,
            OidcStateStore stateStore,
            ObjectMapper objectMapper,
            String allowedHosts,
            String allowedCallbackHosts,
            boolean allowUnlistedHosts,
            String callbackUrl,
            long assuranceClockSkewSeconds,
            Clock clock,
            HttpClient httpClient,
            Function<String, String> environmentReader) {
        this.identityProviderRepository = identityProviderRepository;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
        this.allowedHosts = parseHosts(allowedHosts);
        this.allowedCallbackHosts = parseHosts(allowedCallbackHosts);
        this.allowUnlistedHosts = allowUnlistedHosts;
        this.callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
        this.assuranceClockSkewSeconds = assuranceClockSkewSeconds;
        this.clock = clock;
        this.httpClient = httpClient;
        this.environmentReader = environmentReader;
    }

    @Bulkhead(name = "oidcProvider", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "oidcProvider")
    public String getAuthorizationUrl(Long tenantId, String providerKey) {
        IdentityProvider provider = requireProvider(tenantId, providerKey);
        OidcStateStore.AuthorizationRequest authorization = stateStore.create(tenantId, providerKey);
        return authorizationUrl(provider, authorization, null, null);
    }

    public StepUpAuthorization getStepUpAuthorizationUrl(
            OidcStateStore.StepUpBinding binding) {
        StepUpProviderConfiguration configuration = stepUpProvider(
                binding.tenantId(), binding.providerKey(), binding.requiredAcr());
        int maximumAge = Math.min(
                configuration.maximumAgeSeconds(), binding.maximumAgeSeconds());
        OidcStateStore.StepUpBinding resolved = new OidcStateStore.StepUpBinding(
                binding.tenantId(), configuration.provider().getProviderKey(), binding.actorId(),
                binding.sessionFamilyId(), binding.tokenId(), binding.browserBinding(),
                binding.requiredAcr(), configuration.acceptedAmrs(), maximumAge,
                binding.returnPath(), binding.commandDigest(), binding.sourceRevision());
        OidcStateStore.AuthorizationRequest authorization = stateStore.createStepUp(resolved);
        return new StepUpAuthorization(
                authorizationUrl(configuration.provider(), authorization,
                        binding.requiredAcr(), maximumAge),
                authorization.expiresAt(), configuration.provider().getProviderKey(),
                authorization.flowRef());
    }

    public List<String> enabledStepUpProviderKeys(Long tenantId, String requiredAcr) {
        return compatibleStepUpProviders(tenantId, requiredAcr).stream()
                .map(value -> value.provider().getProviderKey())
                .toList();
    }

    public List<String> incompleteConfiguredStepUpProviderKeys(String requiredAcr) {
        return identityProviderRepository.findAll().stream()
                .filter(provider -> Boolean.TRUE.equals(provider.getEnabled()))
                .filter(provider -> "OIDC".equals(provider.getProviderType()))
                .filter(this::hasStepUpConfiguration)
                .filter(provider -> compatibleStepUpConfiguration(provider, requiredAcr).isEmpty())
                .map(provider -> provider.getTenantId() + ":" + provider.getProviderKey())
                .sorted()
                .toList();
    }

    private List<StepUpProviderConfiguration> compatibleStepUpProviders(
            Long tenantId,
            String requiredAcr) {
        return identityProviderRepository.findByTenantIdAndEnabledTrueOrderByProviderKey(tenantId)
                .stream()
                .map(provider -> compatibleStepUpConfiguration(provider, requiredAcr))
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    private String authorizationUrl(
            IdentityProvider provider,
            OidcStateStore.AuthorizationRequest authorization,
            String requiredAcr,
            Integer maximumAge) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(provider.getAuthUrl())
                .queryParam("client_id", provider.getClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", "openid profile email")
                .queryParam("state", authorization.state())
                .queryParam("nonce", authorization.nonce())
                .queryParam("code_challenge", sha256Base64Url(authorization.codeVerifier()))
                .queryParam("code_challenge_method", "S256");
        if (requiredAcr != null) {
            builder.queryParam("prompt", "login")
                    .queryParam("acr_values", requiredAcr)
                    .queryParam("max_age", maximumAge);
        }
        return builder.build().encode().toUriString();
    }

    @Bulkhead(name = "oidcProvider", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "oidcProvider")
    public OidcExchangeResult exchange(String state, String code) {
        OidcStateStore.StateContext context = stateStore.consume(state);
        IdentityProvider provider = requireProvider(context.tenantId(), context.providerKey());
        if (context.purpose() == OidcStateStore.Purpose.STEP_UP) {
            StepUpProviderConfiguration current = requireStepUpConfiguration(
                    provider, context.requiredAcr());
            if (!Set.copyOf(current.acceptedAmrs()).equals(Set.copyOf(context.acceptedAmrs()))) {
                throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
            }
        }
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
        List<String> verifiedAmr = verifyStepUpAssurance(context, idToken);

        String subject = idToken.getSubject();
        JsonNode userPayload = null;
        String accessToken = optionalText(tokenPayload, "access_token");
        if (!isBlank(accessToken) && !isBlank(provider.getUserInfoUrl())) {
            userPayload = getJson(provider.getUserInfoUrl(), accessToken);
            requireClaim(requiredText(userPayload, "sub"), subject);
        }
        String email = firstNonBlank(
                optionalText(userPayload, "email"), idToken.getClaimAsString("email"));
        boolean emailVerified = optionalBoolean(userPayload, "email_verified")
                || Boolean.TRUE.equals(idToken.getClaimAsBoolean("email_verified"));
        String name = firstNonBlank(
                optionalText(userPayload, "name"), idToken.getClaimAsString("name"));
        OidcUserInfo userInfo = new OidcUserInfo(
                idToken.getIssuer().toString(), subject, email, emailVerified, name,
                idToken.getClaimAsInstant("auth_time"), idToken.getClaimAsString("acr"),
                verifiedAmr);
        return new OidcExchangeResult(
                context.tenantId(), context.providerKey(), userInfo, context);
    }

    private Jwt decodeIdToken(IdentityProvider provider, String idToken) {
        try {
            JsonNode metadata = getJson(provider.getMetadataUrl(), null);
            requireClaim(requiredText(metadata, "issuer"), provider.getIssuerUri());
            requireClaim(requiredText(metadata, "authorization_endpoint"), provider.getAuthUrl());
            requireClaim(requiredText(metadata, "token_endpoint"), provider.getTokenUrl());
            if (!isBlank(provider.getUserInfoUrl())) {
                requireClaim(
                        requiredText(metadata, "userinfo_endpoint"), provider.getUserInfoUrl());
            }
            String jwkSetUri = requiredText(metadata, "jwks_uri");
            requireAllowed(jwkSetUri);
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                    .restOperations(nonRedirectingJwkClient())
                    .build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(provider.getIssuerUri()));
            Jwt jwt = decoder.decode(idToken);
            List<String> audience = jwt.getAudience();
            if (audience == null || !audience.contains(provider.getClientId())) {
                throw new BaseException(ErrorCode.AUTH_INVALID_CREDENTIALS);
            }
            if (audience.size() > 1
                    && !provider.getClientId().equals(jwt.getClaimAsString("azp"))) {
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
        requireProviderConfiguration(provider);
        return provider;
    }

    private void requireProviderConfiguration(IdentityProvider provider) {
        if (!Boolean.TRUE.equals(provider.getEnabled())
                || !"OIDC".equals(provider.getProviderType())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The OIDC provider is not enabled.");
        }
        if (isBlank(provider.getProviderKey()) || isBlank(provider.getAuthUrl())
                || isBlank(provider.getTokenUrl()) || isBlank(provider.getIssuerUri())
                || isBlank(provider.getMetadataUrl()) || isBlank(provider.getClientId())
                || isBlank(provider.getClientSecretEnv())
                || isBlank(environmentReader.apply(provider.getClientSecretEnv()))) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The OIDC provider is incomplete.");
        }
        requireAllowed(provider.getIssuerUri());
        requireAllowed(provider.getMetadataUrl());
        requireAllowed(provider.getAuthUrl());
        requireAllowed(provider.getTokenUrl());
        if (!isBlank(provider.getUserInfoUrl())) requireAllowed(provider.getUserInfoUrl());
        requireCallbackAllowed(callbackUrl);
    }

    private StepUpProviderConfiguration stepUpProvider(
            Long tenantId,
            String providerKey,
            String requiredAcr) {
        if (!isBlank(providerKey)) {
            return requireStepUpConfiguration(requireProvider(tenantId, providerKey), requiredAcr);
        }
        List<StepUpProviderConfiguration> compatible =
                compatibleStepUpProviders(tenantId, requiredAcr);
        if (compatible.size() != 1) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "Exactly one compatible identity provider must be selected for step-up.");
        }
        return compatible.getFirst();
    }

    private java.util.Optional<StepUpProviderConfiguration> compatibleStepUpConfiguration(
            IdentityProvider provider,
            String requiredAcr) {
        try {
            requireProviderConfiguration(provider);
            return java.util.Optional.of(requireStepUpConfiguration(provider, requiredAcr));
        } catch (BaseException | IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }

    private StepUpProviderConfiguration requireStepUpConfiguration(
            IdentityProvider provider,
            String requiredAcr) {
        List<String> supportedAcrs = closedValues(provider.getStepUpAcrValues());
        List<String> acceptedAmrs = OidcStepUpAmrPolicy.parseProviderPolicy(
                provider.getStepUpAcceptedAmrValues());
        Integer maximumAge = provider.getStepUpMaxAgeSeconds();
        if (isBlank(requiredAcr) || !requiredAcr.matches(VALUE_PATTERN)
                || supportedAcrs == null || !supportedAcrs.contains(requiredAcr)
                || acceptedAmrs.isEmpty()
                || maximumAge == null || maximumAge < MINIMUM_STEP_UP_MAX_AGE_SECONDS
                || maximumAge > MAXIMUM_STEP_UP_MAX_AGE_SECONDS) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "The configured identity provider cannot satisfy the required assurance.");
        }
        return new StepUpProviderConfiguration(provider, maximumAge, acceptedAmrs);
    }

    List<String> verifyStepUpAssurance(OidcStateStore.StateContext context, Jwt idToken) {
        List<String> originalAmr = textList(idToken.getClaims().get("amr"));
        if (context.purpose() != OidcStateStore.Purpose.STEP_UP) return originalAmr;
        Instant authenticatedAt = idToken.getClaimAsInstant("auth_time");
        String acr = idToken.getClaimAsString("acr");
        List<String> canonicalAmr = OidcStepUpAmrPolicy.canonicalize(
                context.acceptedAmrs(), originalAmr);
        Instant now = clock.instant();
        if (authenticatedAt == null || !context.requiredAcr().equals(acr)
                || canonicalAmr.isEmpty()
                || authenticatedAt.isBefore(
                        context.startedAt().minusSeconds(assuranceClockSkewSeconds))
                || authenticatedAt.isAfter(now.plusSeconds(assuranceClockSkewSeconds))
                || !authenticatedAt.plusSeconds(context.maximumAgeSeconds()).isAfter(now)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED);
        }
        return canonicalAmr;
    }

    private boolean hasStepUpConfiguration(IdentityProvider provider) {
        return !isBlank(provider.getStepUpAcrValues())
                || !isBlank(provider.getStepUpAcceptedAmrValues());
    }

    private void requireAllowed(String value) {
        URI uri = requireHttpsUri(value, "OIDC endpoint");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowUnlistedHosts && !allowedHosts.contains(host)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC endpoint host is not allowed.");
        }
    }

    private void requireCallbackAllowed(String value) {
        URI uri = requireHttpsUri(value, "OIDC callback");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedCallbackHosts.contains(host)
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"/auth/oidc/callback".equals(uri.getPath())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC callback is not allowed.");
        }
    }

    private URI requireHttpsUri(String value, String label) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawFragment() != null) {
                throw new BaseException(ErrorCode.INVALID_STATE, label + " is invalid.");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, label + " is invalid.");
        }
    }

    static Set<String> parseHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String[] raw = value.split(",", -1);
        Set<String> result = new LinkedHashSet<>();
        for (String item : raw) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")
                    || host.contains("..") || !result.add(host)) {
                throw new IllegalArgumentException("OIDC host allowlist is invalid.");
            }
        }
        return Set.copyOf(result);
    }

    private static List<String> closedValues(String value) {
        if (isBlank(value)) return null;
        String[] raw = value.trim().split("\\s+", -1);
        Set<String> result = new LinkedHashSet<>();
        for (String item : raw) {
            if (!item.matches(VALUE_PATTERN) || !result.add(item)) return null;
        }
        return List.copyOf(result);
    }

    private static List<String> textList(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank() || !text.matches(VALUE_PATTERN)) {
                return List.of();
            }
            if (result.contains(text)) return List.of();
            result.add(text);
        }
        return List.copyOf(result);
    }

    private String resolveClientSecret(IdentityProvider provider) {
        String secret = environmentReader.apply(provider.getClientSecretEnv());
        if (isBlank(secret)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "OIDC client secret is unavailable.");
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (!isBlank(accessToken)) builder.header("Authorization", "Bearer " + accessToken);
        return send(builder.build());
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

    private RestOperations nonRedirectingJwkClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method)
                    throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return new RestTemplate(factory);
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

    private record StepUpProviderConfiguration(
            IdentityProvider provider,
            int maximumAgeSeconds,
            List<String> acceptedAmrs) {
    }

    public record OidcExchangeResult(
            Long tenantId,
            String providerKey,
            OidcUserInfo userInfo,
            OidcStateStore.StateContext context) {
    }

    public record StepUpAuthorization(
            String authorizationUrl,
            Instant expiresAt,
            String providerKey,
            String flowRef) {
    }
}
