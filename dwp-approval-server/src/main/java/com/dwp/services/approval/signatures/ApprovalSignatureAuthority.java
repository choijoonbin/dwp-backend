package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Only a trusted, exact-purpose signed authority response can manufacture Verified. */
public final class ApprovalSignatureAuthority {
    public enum Operation {
        CONTEXT("GET", "requests", "/signature-context", "signature-context.data"),
        CREATE("POST", "requests", "/signature-requests", "signature-request-create.action"),
        GET("GET", "signature-requests", "", "signature-request.data"),
        CONSENT("POST", "signature-requests", "/consents", "signature-consent.action"),
        SIGN("POST", "signature-requests", "/sign", "signature-sign.action"),
        CANCEL("POST", "signature-requests", "/cancel", "signature-cancel.action"),
        AUDIT("GET", "signature-requests", "/audit", "signature-audit.data");
        final String method, root, suffix, route;
        Operation(String method, String root, String suffix, String route) { this.method=method; this.root=root; this.suffix=suffix; this.route=route; }
        public String path(UUID id) { return "/v1/" + root + "/" + id + suffix; }
        public String route() { return "route.approvals.work." + route; }
        public String method() { return method; }
    }
    public record Binding(Operation operation, UUID objectId, Long expectedVersion, String bodySha256, String idempotencyKey,
            com.fasterxml.jackson.databind.JsonNode commandBody) {
        public Binding(Operation operation, UUID objectId, Long expectedVersion, String bodySha256, String idempotencyKey) {
            this(operation, objectId, expectedVersion, bodySha256, idempotencyKey, null);
        }
        public Binding {
            if (operation==null || objectId==null || bodySha256==null || !bodySha256.matches("[a-f0-9]{64}")
                    || (expectedVersion!=null && (expectedVersion<0 || expectedVersion>ApprovalSignatureDtos.MAX_VERSION))
                    || (operation.method.equals("POST") && (expectedVersion==null || idempotencyKey==null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,120}")))
                    || (!operation.method.equals("POST") && (expectedVersion!=null || idempotencyKey!=null))) throw denied();
            commandBody = commandBody == null ? null : commandBody.deepCopy();
        }
        @Override public com.fasterxml.jackson.databind.JsonNode commandBody() { return commandBody == null ? null : commandBody.deepCopy(); }
    }
    public interface Source { String currentSignedAssertion(Binding binding,UUID exchangeNonce); }
    public static final class Verified {
        private final long tenantId, actorId;
        private final String resourceSetKey, contextScopeKey, stableDigest, contextKey, revision, registrySha256;
        private final boolean highRiskVerified;
        private final UUID personPublicId;
        private final Binding binding;
        private final Instant expiresAt;
        private Verified(long tenantId, long actorId, UUID personPublicId, String resourceSetKey, String contextScopeKey,
                String stableDigest, Binding binding, Instant expiresAt, String contextKey, String revision, String registrySha256, boolean highRiskVerified) {
            this.tenantId=tenantId; this.actorId=actorId; this.personPublicId=personPublicId; this.resourceSetKey=resourceSetKey;
            this.contextScopeKey=contextScopeKey; this.stableDigest=stableDigest; this.binding=binding; this.expiresAt=expiresAt;
            this.contextKey=contextKey; this.revision=revision; this.registrySha256=registrySha256; this.highRiskVerified=highRiskVerified;
        }
        public long tenantId() { return tenantId; }
        public long actorId() { return actorId; }
        public UUID personPublicId() { return personPublicId; }
        public String resourceSetKey() { return resourceSetKey; }
        public String contextScopeKey() { return contextScopeKey; }
        public String digest() { return stableDigest; }
        public UUID objectId() { return binding.objectId(); }
        public Operation operation() { return binding.operation(); }
        Binding binding() { return binding; }
        String contextKey() { return contextKey; }
        String revision() { return revision; }
        String registrySha256() { return registrySha256; }
        boolean highRiskVerified() { return highRiskVerified; }
    }
    private static final Set<String> CLAIMS=Set.of("iss","aud","iat","exp","jti","nonce","contract","purpose","method","path",
            "tenantId","actorId","personPublicId","resourceSetKey","contextKey","contextScopeKey","decisionRevision",
            "registrySha256","routeContractKey","objectId","objectVersion","bodySha256","idempotencyKey","grantSha256",
            "installed","signerKind","accessMode","highRiskVerified","sourceSha256","sourceKind");
    private final Source source;
    private final JWKSet trust;
    private final Clock clock;
    private final ApprovalSignatureCanonical canonical;
    public ApprovalSignatureAuthority(Source source, JWKSet trust, Clock clock, ApprovalSignatureCanonical canonical) {
        this.source=source; this.trust=trust; this.clock=clock; this.canonical=canonical;
    }
    public Verified require(Binding binding) {
        if (source==null || trust==null || trust.getKeys().isEmpty()) throw unavailable();
        final ApprovalRequestContext.Actor actor;
        try { actor=ApprovalRequestContext.require(); } catch (IllegalStateException absent) { throw unavailable(); }
        if (actor.tenantId()==null || actor.tenantId()<=0 || actor.userId()==null || actor.userId()<=0 || actor.personPublicId()==null
                || actor.roles().stream().anyMatch(role -> role.startsWith("PROVIDER_"))) throw denied();
        try {
            UUID nonce=UUID.randomUUID(); String raw=source.currentSignedAssertion(binding,nonce);
            if (raw==null || raw.length()>16384) throw unavailable();
            String[] parts = raw.split("\\.", -1); if (parts.length != 3) throw denied();
            canonical.read(new String(java.util.Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8),
                    com.fasterxml.jackson.databind.JsonNode.class);
            canonical.read(new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8),
                    com.fasterxml.jackson.databind.JsonNode.class);
            SignedJWT jwt=SignedJWT.parse(raw); var header=jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm()) || header.getKeyID()==null
                    || !header.getKeyID().startsWith("approval-signature-authority:")
                    || !Set.of("alg","kid","typ").equals(header.toJSONObject().keySet()) || !"JWT".equals(String.valueOf(header.getType()))) throw denied();
            var key=trust.getKeyByKeyId(header.getKeyID());
            if (!(key instanceof RSAKey rsa) || rsa.isPrivate() || rsa.size()<2048 || !KeyUse.SIGNATURE.equals(rsa.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(rsa.getAlgorithm()) || !jwt.verify(new RSASSAVerifier(rsa))) throw denied();
            Map<String,Object> c=jwt.getJWTClaimsSet().getClaims();
            var expectedClaims=new java.util.HashSet<>(CLAIMS);
            if (!binding.operation.method.equals("POST")) { expectedClaims.remove("objectVersion"); expectedClaims.remove("idempotencyKey"); }
            if (!c.keySet().equals(expectedClaims) || !"DWP_APPROVAL_SIGNATURE_AUTHORITY".equals(c.get("iss"))
                    || !jwt.getJWTClaimsSet().getAudience().equals(java.util.List.of("dwp-approval-server"))
                    || !"DWP_APPROVAL_SIGNATURE_AUTHORITY_V1".equals(c.get("contract")) || !Boolean.TRUE.equals(c.get("installed"))
                    || !"SELF_ATTESTATION".equals(c.get("signerKind")) || !Set.of("NORMAL","ELEVATED").contains(c.get("accessMode"))
                    || !binding.operation.name().equals(c.get("purpose")) || !binding.operation.method.equals(c.get("method"))
                    || !binding.operation.path(binding.objectId).equals(c.get("path")) || !binding.operation.route().equals(c.get("routeContractKey"))
                    || !nonce.toString().equals(c.get("nonce"))
                    || !binding.objectId.toString().equals(c.get("objectId")) || !binding.bodySha256.equals(c.get("bodySha256"))
                    || !java.util.Objects.equals(binding.idempotencyKey,c.get("idempotencyKey"))
                    || !java.util.Objects.equals(binding.expectedVersion, c.get("objectVersion")==null?null:integer(c.get("objectVersion")))
                    || integer(c.get("tenantId"))!=actor.tenantId() || integer(c.get("actorId"))!=actor.userId()
                    || !actor.personPublicId().toString().equals(c.get("personPublicId"))
                    || !text(c,"resourceSetKey",80).matches("RS_[A-Z0-9_]{1,76}")
                    || !text(c,"decisionRevision",68).matches("psr-[a-f0-9]{64}")
                    || !text(c,"registrySha256",64).matches("[a-f0-9]{64}") || !text(c,"grantSha256",64).matches("[a-f0-9]{64}")
                    || !"ARTIFACT".equals(c.get("sourceKind")) || !text(c,"sourceSha256",64).matches("[a-f0-9]{64}")
                    || !(c.get("highRiskVerified") instanceof Boolean) || (binding.operation==Operation.SIGN && !Boolean.TRUE.equals(c.get("highRiskVerified")))) throw denied();
            text(c,"contextKey",512); String scope=text(c,"contextScopeKey",512); UUID.fromString(text(c,"jti",36));
            Instant now=clock.instant(), issued=jwt.getJWTClaimsSet().getIssueTime().toInstant(), expires=jwt.getJWTClaimsSet().getExpirationTime().toInstant();
            if (!expires.isAfter(now) || expires.isAfter(now.plusSeconds(60)) || issued.isAfter(now.plusSeconds(5))
                    || issued.isBefore(now.minusSeconds(60)) || !expires.isAfter(issued)) throw denied();
            var stable=new HashMap<>(c); stable.remove("iat"); stable.remove("exp"); stable.remove("jti"); stable.remove("nonce");
            return new Verified(actor.tenantId(),actor.userId(),actor.personPublicId(),c.get("resourceSetKey").toString(),scope,
                    canonical.digest(stable),binding,expires,c.get("contextKey").toString(),c.get("decisionRevision").toString(),
                    c.get("registrySha256").toString(),Boolean.TRUE.equals(c.get("highRiskVerified")));
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (Exception error) { throw denied(); }
    }
    public void unchanged(Verified original) {
        if (original==null || !original.expiresAt.isAfter(clock.instant())) throw denied();
        Verified latest=require(original.binding);
        if (!original.stableDigest.equals(latest.stableDigest)) throw denied();
    }
    private static long integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) throw denied();
        long number=((Number)value).longValue(); if (number<0 || number>ApprovalSignatureDtos.MAX_VERSION) throw denied(); return number;
    }
    private static String text(Map<String,Object> c, String key, int max) {
        if (!(c.get(key) instanceof String value) || value.isBlank() || value.length()>max) throw denied(); return value;
    }
}
