package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.function.Function;

public final class SignatureAuthorityProofVerifier {
    private final SignatureAuthorityJson json;
    private final SignatureAuthorityKeys keys;
    private final Clock clock;
    public SignatureAuthorityProofVerifier(SignatureAuthorityJson json, SignatureAuthorityKeys keys, Clock clock) {
        this.json = json; this.keys = keys; this.clock = clock;
    }
    public Verified verify(byte[] rawBody, String transport) {
        JsonNode body = json.parse(rawBody); keys(body, Set.of("sourceProof", "bindings"));
        JsonNode bindingsNode = body.get("bindings"); var binding = SignatureAuthorityBindings.parse(bindingsNode, json);
        String bindingHash = json.digest(bindingsNode);
        Token owner = token(text(body, "sourceProof", SignatureAuthorityProtocol.TOKEN_LIMIT), keys::owner,
                "dwp-approval-signature-owner", "dwp-auth-signature-source", SignatureAuthorityProtocol.OWNER,
                Set.of("purpose", "bindings", "bindingsSha256"));
        if (!owner.claims().get("bindings").equals(bindingsNode)
                || !hash(owner.claims(), "bindingsSha256").equals(bindingHash)) throw denied();
        Token wire = token(transport, keys::transport, "dwp-approval-signature-transport", "dwp-auth-signature-authority",
                SignatureAuthorityProtocol.TRANSPORT, Set.of("purpose", "method", "path", "bodySha256", "bindingsSha256", "sourceProofJti"));
        if (!"POST".equals(text(wire.claims(), "method", 4)) || !SignatureAuthorityProtocol.PATH.equals(text(wire.claims(), "path", 100))
                || !owner.jti().equals(text(wire.claims(), "sourceProofJti", 36)) || owner.jti().equals(wire.jti())
                || !sha(rawBody).equals(hash(wire.claims(), "bodySha256")) || !bindingHash.equals(hash(wire.claims(), "bindingsSha256"))
                || owner.expiresAt().isAfter(binding.authorityValidUntil()) || wire.expiresAt().isAfter(owner.expiresAt())
                || !binding.authorityValidUntil().isAfter(clock.instant())) throw denied();
        return binding.operation()==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT
                ? new MetadataProof(binding,bindingsNode,bindingHash,wire.expiresAt()) : new ArtifactProof(binding,bindingsNode,bindingHash,wire.expiresAt());
    }
    private Token token(String raw, Function<String, RSAKey> key, String issuer, String audience, String purpose, Set<String> extras) {
        if (raw == null || raw.length() > SignatureAuthorityProtocol.TOKEN_LIMIT
                || !raw.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        try {
            String[] parts = raw.split("\\.");
            JsonNode header = json.parse(Base64.getUrlDecoder().decode(parts[0])); keys(header, Set.of("alg", "typ", "kid"));
            if (!"RS256".equals(text(header, "alg", 8)) || !"JWT".equals(text(header, "typ", 8))) throw denied();
            JsonNode claims = json.parse(Base64.getUrlDecoder().decode(parts[1]));
            var fields = new java.util.HashSet<>(SignatureAuthorityProtocol.STANDARD); fields.addAll(extras); keys(claims, fields);
            if (!issuer.equals(text(claims, "iss", 100)) || !audience.equals(text(claims, "aud", 100))
                    || !"dwp-approval-server".equals(text(claims, "sub", 40)) || !purpose.equals(text(claims, "purpose", 100))) throw denied();
            Instant issued = Instant.ofEpochSecond(integer(claims, "iat")), start = Instant.ofEpochSecond(integer(claims, "nbf"));
            Instant expiry = Instant.ofEpochSecond(integer(claims, "exp"));
            if (!issued.equals(start) || issued.isAfter(clock.instant()) || !expiry.isAfter(clock.instant())
                    || !expiry.isAfter(issued) || expiry.isAfter(issued.plusSeconds(30))) throw denied();
            var jwt = SignedJWT.parse(raw);
            if (!jwt.verify(new RSASSAVerifier(key.apply(text(header, "kid", 100))))) throw denied();
            return new Token(claims, uuid(claims, "jti").toString(), expiry);
        } catch (Exception invalid) { throw denied(); }
    }
    private record Token(JsonNode claims, String jti, Instant expiresAt) { }
    public static abstract sealed class Verified permits ArtifactProof,MetadataProof {
        private final SignatureAuthorityBindings binding;
        private final JsonNode bindings;
        private final String digest;
        private final Instant expiry;
        private Verified(SignatureAuthorityBindings binding, JsonNode bindings, String digest, Instant expiry) {
            this.binding = binding; this.bindings = bindings.deepCopy(); this.digest = digest; this.expiry = expiry;
        }
        public SignatureAuthorityBindings binding() { return binding; }
        public JsonNode bindings() { return bindings.deepCopy(); }
        public String digest() { return digest; }
        public Instant expiresAt() { return expiry; }
        public abstract String sourceKind();
    }
    private static final class ArtifactProof extends Verified {
        private ArtifactProof(SignatureAuthorityBindings b,JsonNode n,String d,Instant e){super(b,n,d,e);}
        @Override public String sourceKind(){return "ARTIFACT";}
    }
    private static final class MetadataProof extends Verified {
        private MetadataProof(SignatureAuthorityBindings b,JsonNode n,String d,Instant e){super(b,n,d,e);}
        @Override public String sourceKind(){return "COMMAND_RECEIPT";}
    }
}
