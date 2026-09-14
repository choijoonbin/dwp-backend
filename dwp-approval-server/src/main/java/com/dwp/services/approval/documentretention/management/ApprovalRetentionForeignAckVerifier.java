package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionForeignDtos.*;

@Component
public final class ApprovalRetentionForeignAckVerifier {
    public static final String PURPOSE="APPROVAL_RECORD_COPY_DELETION_V1";
    public record TrustedKey(String consumer,String issuer,String keyId,PublicKey key) {}
    public static final class Verified {
        private final AckClaims claims;private final String proofSha;
        private Verified(AckClaims claims,String proofSha) {this.claims=claims;this.proofSha=proofSha;}
        public AckClaims claims(){return claims;} public String proofSha(){return proofSha;}
    }
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<String,TrustedKey> keys;
    @Autowired
    public ApprovalRetentionForeignAckVerifier(ObjectMapper mapper,
            @Value("${approval.retention.ack.audit-public-key:}") String auditKey,
            @Value("${approval.retention.ack.audit-issuer:}") String auditIssuer,
            @Value("${approval.retention.ack.audit-key-id:}") String auditKeyId,
            @Value("${approval.retention.ack.notification-public-key:}") String notificationKey,
            @Value("${approval.retention.ack.notification-issuer:}") String notificationIssuer,
            @Value("${approval.retention.ack.notification-key-id:}") String notificationKeyId) {
        this(mapper,Clock.systemUTC(),configured(auditKey,auditIssuer,auditKeyId,notificationKey,notificationIssuer,notificationKeyId));
    }
    public ApprovalRetentionForeignAckVerifier(ObjectMapper mapper,Clock clock,Map<String,TrustedKey> keys) {
        this.mapper=mapper.copy();this.clock=clock;this.keys=Map.copyOf(keys);
    }
    public Verified verify(String consumer,SignedAck signed) {
        TrustedKey trusted=keys.get(consumer);if(trusted==null) throw ApprovalRetentionErrors.unavailable();
        try {
            if(signed==null || signed.payloadBase64Url()==null || !signed.payloadBase64Url().matches("[A-Za-z0-9_-]{1,12000}")
                    || signed.signatureBase64Url()==null || !signed.signatureBase64Url().matches("[A-Za-z0-9_-]{86}")) throw ApprovalRetentionErrors.forbidden();
            byte[] bytes=Base64.getUrlDecoder().decode(signed.payloadBase64Url());
            if(bytes.length>8192) throw ApprovalRetentionErrors.forbidden();
            var signature=Signature.getInstance("Ed25519");signature.initVerify(trusted.key());signature.update(bytes);
            if(!signature.verify(Base64.getUrlDecoder().decode(signed.signatureBase64Url()))) throw ApprovalRetentionErrors.forbidden();
            AckClaims claims=mapper.readValue(bytes,AckClaims.class);Instant now=clock.instant();
            if(!consumer.equals(trusted.consumer()) || !consumer.equals(claims.consumerService()) || !trusted.issuer().equals(claims.issuer())
                    || !trusted.keyId().equals(claims.keyId()) || !"dwp-approval-server".equals(claims.audience())
                    || !PURPOSE.equals(claims.purpose()) || claims.tenantId()<=0 || claims.deletionRequestId()==null || claims.intentId()==null || claims.nonce()==null
                    || !"DECLARED_COPIES_DELETED".equals(claims.outcome()) || claims.requestSha256()==null || !claims.requestSha256().matches("[a-f0-9]{64}")
                    || claims.consumerInventorySha256()==null || !claims.consumerInventorySha256().matches("[a-f0-9]{64}")
                    || claims.issuedAt()==null || claims.expiresAt()==null || claims.issuedAt().isAfter(now)
                    || !claims.expiresAt().isAfter(now) || !claims.expiresAt().isAfter(claims.issuedAt())
                    || Duration.between(claims.issuedAt(),claims.expiresAt()).getSeconds()>300) throw ApprovalRetentionErrors.forbidden();
            return new Verified(claims,ApprovalDocumentCanonical.sha(new String(bytes,StandardCharsets.UTF_8)+":"+signed.signatureBase64Url()));
        } catch(com.dwp.core.exception.BaseException denied) {throw denied;}
        catch(Exception invalid) {throw ApprovalRetentionErrors.forbidden();}
    }
    private static Map<String,TrustedKey> configured(String a,String ai,String ak,String n,String ni,String nk) {
        var result=new HashMap<String,TrustedKey>();add(result,"AUDIT",a,ai,ak);add(result,"NOTIFICATION",n,ni,nk);return result;
    }
    private static void add(Map<String,TrustedKey> keys,String consumer,String value,String issuer,String id) {
        if(value.isBlank() && issuer.isBlank() && id.isBlank()) return;
        try {if(issuer.isBlank() || id.isBlank()) throw new IllegalArgumentException("Pinned issuer and key id required");
            var key=KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(value)));
            keys.put(consumer,new TrustedKey(consumer,issuer,id,key));
        } catch(Exception invalid) {throw new IllegalArgumentException("Invalid retention ACK key configuration",invalid);}
    }
}
