package com.dwp.services.auth.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Signs role IDs resolved by Auth, with no public population or task-vote grant. */
@Component
public final class ApprovalWorkflowRoleAttestationIssuer {
    public static final String ISSUER = "dwp-auth-server:workflow-role-mapping:v1";
    public static final String AUDIENCE = "dwp-approval-server:workflow-role-mapping:v1";
    public static final String PURPOSE = "APPROVAL_WORKFLOW_ROLE_MAPPING_V1";
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RSAKey key;

    @Autowired
    public ApprovalWorkflowRoleAttestationIssuer(ObjectMapper mapper,
            @Value("${dwp.auth.approval-workflow-role-mapping-private-jwk:}") String privateJwk,
            @Value("${dwp.auth.approval-workflow-role-proof-jwks:}") String ownerJwks,
            @Value("${dwp.auth.approval-form-user-proof-jwks:}") String userJwks,
            @Value("${dwp.auth.approval-workflow-role-transport-jwks:}") String transportJwks) {
        this(mapper, privateJwk, ownerJwks, userJwks, transportJwks, Clock.systemUTC());
    }

    public ApprovalWorkflowRoleAttestationIssuer(ObjectMapper mapper, String privateJwk, String ownerJwks,
            String userJwks, String transportJwks, Clock clock) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(SerializationFeature.INDENT_OUTPUT);
        this.clock = clock; this.key = key(privateJwk, ownerJwks, userJwks, transportJwks);
    }

    public Attestation sign(ApprovalWorkflowRoleProofVerifier.VerifiedProof proof, List<ApprovalWorkflowRoleMapping> mapping,
            ApprovalWorkflowRoleCurrentAuthority.Evidence current) {
        if (key == null || proof == null || current == null || mapping == null || mapping.isEmpty()
                || !mapping.stream().map(ApprovalWorkflowRoleMapping::roleCode).toList().equals(proof.binding().roleCodes())
                || mapping.size() > 64 || mapping.stream().map(ApprovalWorkflowRoleMapping::roleId).distinct().count() != mapping.size()
                || !revision(current.authRevision()) || !revision(current.policyRevision()) || current.expiresAt() == null) {
            throw ApprovalWorkflowRoleBinding.unavailable();
        }
        long issued = clock.instant().getEpochSecond();
        long expires = Math.min(issued + 30, Math.min(proof.expiresAt().getEpochSecond(), current.expiresAt().getEpochSecond()));
        if (expires <= issued) throw ApprovalWorkflowRoleBinding.unavailable();
        var roles = mapping.stream().map(role -> new TreeMap<>(Map.of("roleCode", role.roleCode(), "roleId", role.roleId(),
                "version", role.version()))).toList();
        String mappingRevision = ApprovalWorkflowRoleBinding.sha256(ApprovalWorkflowRoleBinding.json(mapper, roles));
        UUID id = UUID.randomUUID();
        var claims = new TreeMap<String, Object>();
        claims.put("iss", ISSUER); claims.put("aud", AUDIENCE); claims.put("purpose", PURPOSE); claims.put("jti", id.toString());
        claims.put("iat", issued); claims.put("nbf", issued); claims.put("exp", expires);
        claims.put("ownerProofId", proof.proofId().toString()); claims.put("requestDigest", proof.requestDigest());
        claims.put("bindings", proof.binding().sealed()); claims.put("mapping", roles); claims.put("mappingRevision", mappingRevision);
        claims.put("authRevision", current.authRevision()); claims.put("policyRevision", current.policyRevision());
        try {
            var signed = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                    new Payload(mapper.writeValueAsString(claims)));
            signed.sign(new RSASSASigner(key));
            return new Attestation(signed.serialize(), id, mappingRevision);
        } catch (Exception exception) { throw ApprovalWorkflowRoleBinding.unavailable(); }
    }

    private RSAKey key(String privateJwk, String... prohibitedJwks) {
        if (privateJwk == null || privateJwk.isBlank() || privateJwk.length() > 16384) return null;
        try {
            mapper.readTree(privateJwk); RSAKey value = RSAKey.parse(privateJwk);
            if (!value.isPrivate() || value.size() < 2048 || !KeyUse.SIGNATURE.equals(value.getKeyUse())
                    || !JWSAlgorithm.RS256.equals(value.getAlgorithm()) || value.getKeyID() == null
                    || !value.getKeyID().matches("[A-Za-z0-9._-]{1,80}")) return null;
            var prohibited = new HashSet<String>(); var prohibitedIds = new HashSet<String>();
            for (String raw : prohibitedJwks) if (raw != null && !raw.isBlank()) {
                if (raw.length() > 65536) return null;
                mapper.readTree(raw);
                for (var other : JWKSet.parse(raw).getKeys()) {
                    prohibited.add(other.computeThumbprint().toString()); prohibitedIds.add(other.getKeyID());
                }
            }
            return prohibited.contains(value.computeThumbprint().toString()) || prohibitedIds.contains(value.getKeyID()) ? null : value;
        } catch (Exception exception) { return null; }
    }

    private boolean revision(String value) { return value != null && value.matches("[A-Za-z0-9._:-]{1,120}"); }
    public record Attestation(String attestationToken, UUID attestationId, String mappingRevision) { }
}
