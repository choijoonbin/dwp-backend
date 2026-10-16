package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static com.dwp.services.payroll.home.PayrollHomePorts.ResolvedPayrollHomeAuthority;

final class PayrollHomePayloadCodec {

    private final byte[] secret;

    PayrollHomePayloadCodec(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("Payroll Home reference secret is too short.");
        }
    }

    String digest(HrisHomeContributionMaterializationV1 materialization) {
        return HrisHomeMaterializationDigestV1.digest(materialization);
    }

    String reference(
            ResolvedPayrollHomeAuthority authority,
            long sourceVersion,
            String payloadDigest) {
        String binding = authority.tenantId() + "|PAY|" + authority.widgetKey() + "|"
                + authority.ownerPopulationScope() + "|" + authority.scopeRevision() + "|"
                + authority.populationScopeDigest() + "|" + authority.fieldPolicyRevision() + "|"
                + sourceVersion + "|" + payloadDigest;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return "href_" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(binding.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Payroll Home reference signing failed.", exception);
        }
    }

    boolean matches(
            String candidate,
            ResolvedPayrollHomeAuthority authority,
            long sourceVersion,
            String payloadDigest) {
        if (candidate == null || !candidate.matches("^href_[A-Za-z0-9_-]{16,128}$")) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.US_ASCII),
                reference(authority, sourceVersion, payloadDigest)
                        .getBytes(StandardCharsets.US_ASCII));
    }
}
