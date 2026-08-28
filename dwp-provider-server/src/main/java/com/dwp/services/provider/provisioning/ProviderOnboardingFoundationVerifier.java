package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Component
public class ProviderOnboardingFoundationVerifier {

    private final ProviderOnboardingFoundationRepository repository;

    public ProviderOnboardingFoundationVerifier(ProviderOnboardingFoundationRepository repository) {
        this.repository = repository;
    }

    public void requireExact(ProviderTenant tenant, JsonNode plan) {
        String primaryDomain = textOrNull(plan.path("primaryDomain"));
        String challenge = primaryDomain == null
                ? null
                : "dwp-verification=" + tenant.getProviderTenantId();
        List<String> entitlementKeys = new ArrayList<>();
        plan.path("entitlements").forEach(value -> entitlementKeys.add(value.asText()));
        ProviderOnboardingFoundationRepository.ControlFoundation expected =
                new ProviderOnboardingFoundationRepository.ControlFoundation(
                        tenant.getProviderTenantId(), tenant.getOrganizationId(),
                        plan.path("organizationKey").asText(), plan.path("organizationName").asText(),
                        textOrNull(plan.path("legalName")), textOrNull(plan.path("customerReference")),
                        plan.path("tenantKey").asText(), plan.path("displayName").asText(),
                        plan.path("environmentKey").asText(), plan.path("serviceTier").asText(),
                        plan.path("dataRegion").asText(), plan.path("isolationModel").asText(),
                        plan.path("defaultLocale").asText(), plan.path("timeZone").asText(),
                        "{}", plan.path("initialAdministrator").path("email").asText(),
                        plan.path("initialAdministrator").path("displayName").asText(),
                        entitlementKeys,
                        primaryDomain == null ? null : primaryDomain.toLowerCase(Locale.ROOT),
                        challenge, challenge == null ? null : verificationTokenHash(challenge));
        if (!repository.matches(expected)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The provider onboarding foundation does not exactly match the approved plan.");
        }
    }

    public String verificationTokenHash(String challenge) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(challenge.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String textOrNull(JsonNode value) {
        return value == null || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText().trim();
    }
}
