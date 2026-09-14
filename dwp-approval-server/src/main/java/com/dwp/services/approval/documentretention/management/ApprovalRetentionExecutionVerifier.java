package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Purpose-separated executor attestation. No runtime bean is installed without the owner port. */
public final class ApprovalRetentionExecutionVerifier {
    public record Claims(ApprovalRetentionExecutionAuthorityPort.Target target,String issuer,String audience,
            String purpose,String keyId,UUID nonce,Instant issuedAt,Instant expiresAt,boolean allowed) {
        @JsonAnySetter public void unknown(String key,JsonNode value) {throw new IllegalArgumentException("Unknown execution proof field: "+key);}
    }
    private final ObjectMapper mapper;private final Clock clock;private final PublicKey key;private final String issuer,keyId;
    public ApprovalRetentionExecutionVerifier(ObjectMapper mapper,Clock clock,PublicKey key,String issuer,String keyId) {
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock=clock;this.key=Objects.requireNonNull(key);this.issuer=Objects.requireNonNull(issuer);this.keyId=Objects.requireNonNull(keyId);
    }
    public String verify(ApprovalRetentionExecutionAuthorityPort.Target target,ApprovalRetentionExecutionAuthorityPort.SignedAuthorization signed) {
        try {
            if(signed==null || signed.payloadBase64Url()==null || !signed.payloadBase64Url().matches("[A-Za-z0-9_-]{1,12000}")
                    || signed.signatureBase64Url()==null || !signed.signatureBase64Url().matches("[A-Za-z0-9_-]{86}")) throw ApprovalRetentionErrors.forbidden();
            byte[] bytes=Base64.getUrlDecoder().decode(signed.payloadBase64Url());if(bytes.length>8192) throw ApprovalRetentionErrors.forbidden();
            var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(bytes);
            if(!verifier.verify(Base64.getUrlDecoder().decode(signed.signatureBase64Url()))) throw ApprovalRetentionErrors.forbidden();
            var c=mapper.readValue(bytes,Claims.class);Instant now=clock.instant();
            if(!target.equals(c.target()) || !issuer.equals(c.issuer()) || !keyId.equals(c.keyId()) || !"dwp-approval-server".equals(c.audience())
                    || !"APPROVAL_RETENTION_EXECUTE_V1".equals(c.purpose()) || !c.allowed() || c.nonce()==null || c.issuedAt()==null || c.expiresAt()==null
                    || c.issuedAt().isAfter(now) || !c.expiresAt().isAfter(now) || !c.expiresAt().isAfter(c.issuedAt())
                    || Duration.between(c.issuedAt(),c.expiresAt()).getSeconds()>300) throw ApprovalRetentionErrors.forbidden();
            return ApprovalDocumentCanonical.sha(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)+":"+signed.signatureBase64Url());
        } catch(com.dwp.core.exception.BaseException denied) {throw denied;}
        catch(Exception invalid) {throw ApprovalRetentionErrors.forbidden();}
    }
}
