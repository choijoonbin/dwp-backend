package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.ApprovalFormUserSourceProofVerifier.VerifiedSourceProof;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Separate resolve-only purpose; no candidate DATA route or candidate profile is borrowed. */
@Component
public final class ApprovalFormReferenceProofVerifier {
    public static final String PURPOSE = "APPROVAL_FORM_REFERENCE_RESOLVE_V1";
    public static final String ISSUER = "dwp-approval-server:form-reference-resolve:v1";
    public static final String AUDIENCE = "dwp-auth-server:approval-form-reference-resolve:v1";
    private static final String PREFIX = "route.approvals.work.";
    private static final Map<String, Action> ACTIONS = Map.of(
            PREFIX + "request-create.action", new Action("POST", "", "CREATE"),
            PREFIX + "request-draft-update.action", new Action("PUT", "/draft", "UPDATE"),
            PREFIX + "request-submit.action", new Action("POST", "/submit", "UPDATE"),
            PREFIX + "request-information-response.action", new Action("POST", "/information-response", "UPDATE"),
            PREFIX + "request-draft-recover.action", new Action("POST", "/draft/recover", "UPDATE"));
    private static final Set<String> CLAIMS = Set.of("iss", "aud", "purpose", "jti", "iat", "nbf", "exp",
            "tenantId", "actorId", "formId", "formVersionId", "schemaSha256", "contextKey", "contextScopeKey",
            "decisionRevision", "routeContractKey", "accessMode", "referencePurpose", "operation", "requestDigest",
            "targetRequestId", "targetRequestVersion", "mutationPayloadSha256", "idempotencyKey", "mutationMethod", "mutationPath");
    private final ApprovalFormUserSourceProofVerifier signatures;

    public ApprovalFormReferenceProofVerifier(ApprovalFormUserSourceProofVerifier signatures) { this.signatures = signatures; }

    public VerifiedSourceProof verify(String token, String digest) {
        JsonNode claims = signatures.verifySignedClaims(token, CLAIMS);
        try {
            match(claims, "purpose", PURPOSE); match(claims, "iss", ISSUER); match(claims, "aud", AUDIENCE);
            match(claims, "operation", "RESOLVE"); match(claims, "requestDigest", digest);
            match(claims, "referencePurpose", "MUTATION_REFERENCE");
            long issued = number(claims, "iat", 1), before = number(claims, "nbf", 1), expires = number(claims, "exp", 1);
            if (issued > signatures.now().getEpochSecond() || before != issued || expires <= issued
                    || expires <= signatures.now().getEpochSecond() || expires - issued > 30) throw denied();
            String route = text(claims, "routeContractKey"), mode = text(claims, "accessMode");
            Action action = ACTIONS.get(route);
            if (action == null || !Set.of("NORMAL", "ELEVATED").contains(mode)) throw denied();
            UUID request = uuid(claims, "targetRequestId");
            long version = number(claims, "targetRequestVersion", 0);
            boolean create = "CREATE".equals(action.permission());
            if (version > 9007199254740991L || (create && version != 0)) throw denied();
            match(claims, "mutationMethod", action.method());
            match(claims, "mutationPath", create ? "/v1/requests" : "/v1/requests/" + request + action.suffix());
            String payload = hex(claims, "mutationPayloadSha256"), key = text(claims, "idempotencyKey");
            if (!key.matches("[A-Za-z0-9._:-]{1,120}")) throw denied();
            String revision = text(claims, "decisionRevision");
            if (!revision.matches("psr-[a-f0-9]{64}") || !digest.matches("[a-f0-9]{64}")) throw denied();
            return new VerifiedSourceProof(number(claims, "tenantId", 1), number(claims, "actorId", 1),
                    uuid(claims, "formId"), uuid(claims, "formVersionId"), hex(claims, "schemaSha256"),
                    text(claims, "contextKey"), text(claims, "contextScopeKey"), revision, route, mode,
                    "MUTATION_REFERENCE", "RESOLVE", digest, uuid(claims, "jti"), Instant.ofEpochSecond(expires),
                    new ApprovalFormReferenceBinding(request, version, payload, key, action.method(), text(claims, "mutationPath"),
                            "ACTION.APPROVAL_REQUEST:" + action.permission()));
        } catch (BaseException exception) { throw exception;
        } catch (RuntimeException exception) { throw denied(); }
    }

    private String text(JsonNode claims, String key) {
        JsonNode value = claims.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 512
                || !value.textValue().equals(value.textValue().strip())
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) throw denied();
        return value.textValue();
    }

    private String hex(JsonNode claims, String key) {
        String value = text(claims, key); if (!value.matches("[a-f0-9]{64}")) throw denied(); return value;
    }

    private void match(JsonNode claims, String key, String value) {
        if (!value.equals(text(claims, key))) throw denied();
    }

    private long number(JsonNode claims, String key, long minimum) {
        JsonNode value = claims.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) throw denied();
        return value.longValue();
    }

    private UUID uuid(JsonNode claims, String key) {
        String value = text(claims, key); UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw denied(); return id;
    }

    private BaseException denied() {
        return new BaseException(ErrorCode.FORBIDDEN, "The exact mutation-bound approval form reference proof was rejected.");
    }

    private record Action(String method, String suffix, String permission) { }
}
