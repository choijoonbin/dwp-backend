package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static com.dwp.services.payroll.home.PayrollHomePorts.ResolvedPayrollHomeAuthority;

final class PayrollHomePayloadCodec {

    private final byte[] secret;
    private final ObjectMapper canonicalMapper;

    PayrollHomePayloadCodec(String secret, ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8).clone();
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("Payroll Home reference secret is too short.");
        }
        this.canonicalMapper = mapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalMapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalStateException("Payroll Home canonical digest failed.", exception);
        }
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

    record DigestSeed(
            String profile,
            HrisHomeContributionMaterializationV1.Module module,
            String widgetKey,
            HrisHomeContributionMaterializationV1.WidgetType widgetType,
            HrisHomeContributionMaterializationV1.BrowserAudience browserAudience,
            HrisHomeContributionMaterializationV1.OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            HrisHomeContributionMaterializationV1.State state,
            java.time.Instant generatedAt,
            java.time.Instant freshUntil,
            java.time.Instant staleAfter,
            String deepLink,
            long sourceVersion,
            java.time.Instant asOf,
            HrisHomeContributionMaterializationV1.PayrollHomeData data,
            java.util.List<HrisHomeContributionMaterializationV1.FieldDecision> fieldDecisions,
            java.util.List<HrisHomeContributionMaterializationV1.PartialFailure> partialFailures) {
    }
}
