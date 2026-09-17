package com.dwp.services.platform.mail;

import com.dwp.core.http.OutboundHttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Current authority check performed immediately before the destructive transaction. */
@Component
class AuthMailPurgeExecutionAuthority implements MailPurgeExecutionAuthority {

    private static final String PATH = "/internal/auth/v1/product-surface-authority/evaluate";
    private static final String TOKEN_HEADER = "X-DWP-Product-Surface-Token";

    private final RestClient auth;
    private final String token;

    AuthMailPurgeExecutionAuthority(
            RestClient.Builder builder,
            @Value("${dwp.platform.mail.purge.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.platform.mail.purge.product-surface-token:}") String token) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    @Override
    public Decision evaluate(long tenantId, long actorId, UUID jobId) {
        if (token.isBlank()) {
            return new Decision(State.UNAVAILABLE, null, null, "AUTHORITY_TOKEN_UNCONFIGURED");
        }
        try {
            AuthorityResult result = auth.post().uri(PATH)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header("X-DWP-Service-Identity", "dwp-platform-server")
                    .header(TOKEN_HEADER, token)
                    .body(new EvaluateRequest(
                            tenantId, actorId, "mail", "mail.management", "ELEVATED",
                            "route.admin.mail.retention.purge-execute.action",
                            "purge-job:" + jobId, "tenant:" + tenantId,
                            null, null, List.of()))
                    .retrieve().body(AuthorityResult.class);
            if (result == null || "AUTHORITY_UNAVAILABLE".equals(result.decision())) {
                return new Decision(State.UNAVAILABLE, null, null,
                        result == null ? "EMPTY_AUTHORITY_RESPONSE" : result.reasonCode());
            }
            boolean allowed = "ALLOWED".equals(result.decision())
                    && "ELEVATED".equals(result.accessMode())
                    && "ADMIN.MAIL".equals(result.appResourceKey())
                    && !result.effectiveReadOnly()
                    && result.routeGrantRef() != null
                    && (result.validUntil() == null
                        || result.validUntil().isAfter(OffsetDateTime.now(ZoneOffset.UTC)));
            return new Decision(
                    allowed ? State.ALLOWED : State.DENIED,
                    result.evidenceRef(), result.authRevision(), result.reasonCode());
        } catch (RestClientException failure) {
            return new Decision(State.UNAVAILABLE, null, null,
                    "AUTHORITY_SERVICE_UNAVAILABLE");
        }
    }

    private record EvaluateRequest(
            long tenantId, long actorId, String productKey, String surfaceKey,
            String activeAccessMode, String routeContractKey, String contextKey,
            String contextScopeKey, String supportSessionRef, String supportRevision,
            List<String> supportScopes) { }

    private record AuthorityResult(
            String decision, String reasonCode, String authRevision,
            String accessMode, String appResourceKey, String routeGrantRef,
            boolean effectiveReadOnly, OffsetDateTime validUntil, String evidenceRef) { }
}
