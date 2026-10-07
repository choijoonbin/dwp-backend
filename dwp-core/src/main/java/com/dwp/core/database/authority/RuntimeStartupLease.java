package com.dwp.core.database.authority;

import java.util.Set;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Signed fresh response from the independent deployment authority; not a local caller assertion. */
public record RuntimeStartupLease(Claims claims, String keyId, String signature) {
    public RuntimeStartupLease {
        require(claims != null, "lease claims missing"); key(keyId);
        signatureEncoding(signature);
    }
    public record Claims(String schemaVersion, String phase, String service, String deploymentId,
            String applicationInstanceId, long epoch, long keyRevision, String permitId,
            String leaseId, String startupChallengeSha256, String sealSha256,
            String fenceState, String issuedAt, String expiresAt) {
        public Claims {
            require(RuntimeStreamStartupSeal.VERSION.equals(schemaVersion), "unsupported lease version");
            require(Set.of("RESERVED", "ACTIVE").contains(phase), "unsupported lease phase");
            identifier(service); uuid(deploymentId); uuid(applicationInstanceId);
            require(epoch > 0 && keyRevision > 0, "positive lease/key epoch required");
            uuid(permitId); uuid(leaseId); digest(startupChallengeSha256); digest(sealSha256);
            require("SERVING".equals(fenceState), "deployment fence is not serving");
            require(instant(issuedAt).isBefore(instant(expiresAt)), "lease time window invalid");
        }
    }
}
