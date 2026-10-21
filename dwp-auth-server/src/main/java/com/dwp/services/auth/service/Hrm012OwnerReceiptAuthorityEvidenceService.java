package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Auth-only issuer for the short-lived HRM-012 owner receipt boundary marker. */
@Service
public final class Hrm012OwnerReceiptAuthorityEvidenceService {

    private final ObjectMapper mapper;
    private final Clock clock;
    private final String keyId;
    private final PrivateKey privateKey;

    @Autowired
    public Hrm012OwnerReceiptAuthorityEvidenceService(
            ObjectMapper mapper,
            @Value("${dwp.auth.hrm012-owner-receipt.active-key-id:}") String keyId,
            @Value("${dwp.auth.hrm012-owner-receipt.active-private-key-pem:}") String privateKeyPem) {
        this(mapper, Clock.systemUTC(), keyId, parsePrivateKey(privateKeyPem));
    }

    Hrm012OwnerReceiptAuthorityEvidenceService(
            ObjectMapper mapper, Clock clock, String keyId, PrivateKey privateKey) {
        this.mapper = mapper;
        this.clock = clock;
        this.keyId = keyId == null ? "" : keyId.strip();
        this.privateKey = privateKey;
    }

    public String issue(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductSurfaceAuthorityDtos.AuthorityResult result) {
        requireConfigured();
        if (!request.ownerReceiptRequest()
                || result.decision()
                != ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED
                || request.tenantId() == null || request.actorId() == null
                || !Hrm012OwnerReceiptAuthorityContract.APP_ENTITLEMENTS.contains(
                        result.appResourceKey() + ":VIEW")
                || request.correlationId() == null || request.correlationId().isBlank()
                || request.idempotencyKeySha256() == null
                || !request.idempotencyKeySha256().matches("[a-f0-9]{64}")) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HRM-012 owner-receipt evidence input is invalid.");
        }
        Instant now = clock.instant();
        ObjectNode header = mapper.createObjectNode();
        header.put("typ", Hrm012OwnerReceiptAuthorityContract.TYPE);
        header.put("alg", Hrm012OwnerReceiptAuthorityContract.ALGORITHM);
        header.put("kid", keyId);
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", Hrm012OwnerReceiptAuthorityContract.ISSUER);
        claims.put("aud", Hrm012OwnerReceiptAuthorityContract.AUDIENCE);
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", now.plusSeconds(30).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("state", Hrm012OwnerReceiptAuthorityContract.STATE);
        claims.put("tenant_id", request.tenantId());
        claims.put("on_behalf_of_subject", Long.toString(request.actorId()));
        claims.put("route_contract_key", Hrm012OwnerReceiptAuthorityContract.ROUTE);
        claims.put("command_method", Hrm012OwnerReceiptAuthorityContract.METHOD);
        claims.put("command_path", Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH);
        claims.put("accepted_app_entitlement", result.appResourceKey() + ":VIEW");
        claims.put("feature_key", Hrm012OwnerReceiptAuthorityContract.FEATURE);
        claims.put("feature_decision_revision", result.tenantControlRevision());
        claims.put("correlation_id", request.correlationId());
        claims.put("idempotency_key_sha256", request.idempotencyKeySha256());
        if (request.opaqueStepUpChallengeRef() == null) {
            claims.putNull("opaque_step_up_challenge_ref");
        } else {
            claims.put("opaque_step_up_challenge_ref", request.opaqueStepUpChallengeRef());
        }
        return sign(header, claims);
    }

    private String sign(ObjectNode header, ObjectNode claims) {
        try {
            String input = encode(mapper.writeValueAsBytes(header)) + "."
                    + encode(mapper.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + encode(signer.sign());
        } catch (Exception exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HRM-012 owner-receipt signing failed.", exception);
        }
    }

    private void requireConfigured() {
        if (privateKey == null || keyId.isBlank()) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "HRM-012 owner-receipt signing is not configured.");
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static PrivateKey parsePrivateKey(String pem) {
        if (pem == null || pem.isBlank()) return null;
        try {
            String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw new IllegalStateException("Configured HRM-012 private key is invalid.", exception);
        }
    }
}
