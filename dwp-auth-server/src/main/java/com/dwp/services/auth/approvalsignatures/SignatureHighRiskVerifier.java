package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/** Verifies the original Auth MFA proof, not an owner-supplied highRiskVerified boolean. */
public final class SignatureHighRiskVerifier {
    private final SignatureAuthorityJson json;
    private final RSAKey publicKey;
    private final String issuer, keyId, acr;
    private final Clock clock;
    private final long maximumAge, maximumTtl;
    public SignatureHighRiskVerifier(SignatureAuthorityJson json, RSAKey publicKey, String issuer, String keyId, String acr,
            Clock clock, long maximumAge, long maximumTtl) {
        this.json = json; this.publicKey = publicKey; this.issuer = issuer; this.keyId = keyId; this.acr = acr;
        this.clock = clock; this.maximumAge = maximumAge; this.maximumTtl = maximumTtl;
    }
    public Instant verify(SignatureAuthorityBindings binding) {
        if (publicKey == null || publicKey.isPrivate() || publicKey.size() < 2048 || issuer == null || issuer.isBlank()
                || keyId == null || keyId.isBlank() || acr == null || acr.isBlank() || maximumAge < 1 || maximumTtl < 1) throw unavailable();
        if (binding.operation() != SignatureAuthorityProtocol.Operation.SIGN || binding.stepUpToken() == null) throw denied();
        try {
            String[] parts = binding.stepUpToken().split("\\.", -1); if (parts.length != 3) throw denied();
            var header = json.parse(Base64.getUrlDecoder().decode(parts[0])); keys(header, ProductSurfaceStepUpChallengeContract.HEADER_FIELDS);
            if (!"RS256".equals(text(header, "alg", 8)) || !"JWT".equals(text(header, "typ", 8)) || !keyId.equals(text(header, "kid", 100))) throw denied();
            var c = json.parse(Base64.getUrlDecoder().decode(parts[1])); keys(c, ProductSurfaceStepUpChallengeContract.CLAIM_FIELDS);
            String path = "/api/approvals" + binding.operation().path(binding.objectId());
            boolean audience=c.path("aud").isTextual() && "dwp-approval-server".equals(c.path("aud").textValue())
                    || c.path("aud").isArray() && c.path("aud").size()==1 && c.path("aud").get(0).isTextual() && "dwp-approval-server".equals(c.path("aud").get(0).textValue());
            if (!issuer.equals(text(c, "iss", 100)) || !audience
                    || !Long.toString(binding.actorId()).equals(text(c, "sub", 30)) || integer(c, "tenant_id") != binding.tenantId()
                    || !acr.equals(text(c, "acr", 200)) || !c.path("amr").isArray()
                    || c.path("amr").size() > 8 || !java.util.stream.StreamSupport.stream(c.path("amr").spliterator(), false)
                        .allMatch(value -> value.isTextual() && !value.textValue().isBlank())
                    || !java.util.stream.StreamSupport.stream(c.path("amr").spliterator(), false).anyMatch(value -> "mfa".equals(value.textValue()))
                    || !"approval".equals(text(c, "owner_service_key", 30))
                    || !binding.operation().route().equals(text(c, "command_contract_key", 200))
                    || !binding.operation().capability().equals(text(c, "capability_contract_key", 100))
                    || !"STEPUP-MGMT-HIGH-V1".equals(text(c, "activation_policy", 100))
                    || !binding.contextKey().equals(text(c, "context_key", 512)) || !binding.contextScopeKey().equals(text(c, "scope_ref", 512))
                    || !"APPROVAL_SIGNATURE_REQUEST".equals(text(c, "target_type", 100))
                    || !binding.objectId().toString().equals(text(c, "target_id", 36)) || integer(c, "target_version") != binding.objectVersion()
                    || !"POST".equals(text(c, "command_method", 4)) || !path.equals(text(c, "command_path", 500))
                    || !binding.idempotencyKey().equals(text(c, "idempotency_key", 120))
                    || !binding.bodySha256().equals(hash(c, "payload_sha256")) || !binding.decisionRevision().equals(text(c, "decision_revision", 68))) throw denied();
            String command = ProductSurfaceStepUpChallengeContract.commandSha256(new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                    binding.operation().route(), "approval", "dwp-approval-server", "POST", path, binding.contextKey(), binding.contextScopeKey(),
                    "APPROVAL_SIGNATURE_REQUEST", binding.objectId().toString(), binding.objectVersion(), binding.idempotencyKey(),
                    binding.bodySha256(), binding.decisionRevision()));
            if (!command.equals(hash(c, "command_sha256"))) throw denied();
            text(c, "jti", 160); text(c, "nonce", 160);
            Instant now = clock.instant(), issued = Instant.ofEpochSecond(integer(c, "iat")), starts = Instant.ofEpochSecond(integer(c, "nbf"));
            Instant expires = Instant.ofEpochSecond(integer(c, "exp")), auth = Instant.ofEpochSecond(integer(c, "auth_time"));
            if (!starts.equals(issued) || issued.isAfter(now) || auth.isAfter(issued) || auth.isBefore(now.minusSeconds(maximumAge))
                    || !expires.isAfter(now) || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(maximumTtl))) throw denied();
            if (!SignedJWT.parse(binding.stepUpToken()).verify(new RSASSAVerifier(publicKey))) throw denied();
            return expires;
        } catch (com.dwp.core.exception.BaseException invalid) { throw invalid; }
        catch (Exception invalid) { throw denied(); }
    }
}
