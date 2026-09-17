package com.dwp.services.platform.widgetregistry.internal.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Evidence-bound authority interlock. A configuration request alone never grants authority.
 */
@Component
public final class WidgetRegistryActivationInterlock implements InitializingBean {

    public static final int REQUIRED_BINDING_COUNT = 19;
    private static final String ACTIVATION_BLOCKED =
            "Widget Registry activation is blocked: evidence is incomplete, malformed, or unsealed";

    private final WidgetRegistryActivationProperties properties;

    public WidgetRegistryActivationInterlock(WidgetRegistryActivationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.enabled() && !configuredEvidenceValid()) {
            throw new IllegalStateException(ACTIVATION_BLOCKED);
        }
    }

    boolean permitsRequest() {
        return false;
    }

    public boolean permitsAuthority(AuthorityEvidence evidence, String rolloutRevision) {
        if (!properties.enabled() || !configuredEvidenceValid() || evidence == null) return false;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return properties.expiresAt().isAfter(now)
                && "AUTHORITATIVE".equals(evidence.migrationMode())
                && evidence.runtimeActivationReady()
                && evidence.activeBindingCount() == REQUIRED_BINDING_COUNT
                && evidence.activeBindingCount() == properties.bindingCount()
                && properties.bindingRevision().equals(evidence.bindingRevision())
                && properties.safetyRevision() == evidence.safetyRevision()
                && properties.rolloutRevision().equals(rolloutRevision);
    }

    boolean configuredEvidenceValid() {
        if (properties.bindingCount() != REQUIRED_BINDING_COUNT
                || properties.safetyRevision() < 0
                || !properties.bindingRevision().matches("[A-Za-z0-9_-]{20,160}")
                || !properties.rolloutRevision().matches("[A-Za-z0-9._:-]{1,160}")
                || properties.expiresAt() == null
                || !properties.evidenceSha256().matches("[0-9a-f]{64}")) {
            return false;
        }
        return properties.evidenceSha256().equals(evidenceDigest(
                properties.bindingCount(),
                properties.bindingRevision(),
                properties.safetyRevision(),
                properties.rolloutRevision(),
                properties.expiresAt()));
    }

    static String evidenceDigest(
            int bindingCount,
            String bindingRevision,
            long safetyRevision,
            String rolloutRevision,
            OffsetDateTime expiresAt) {
        String material = "HOME_WIDGET_REGISTRY_AUTHORITY_V1\n" + bindingCount + "\n"
                + bindingRevision + "\n" + safetyRevision + "\n" + rolloutRevision + "\n"
                + expiresAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record AuthorityEvidence(
            String migrationMode,
            boolean runtimeActivationReady,
            int activeBindingCount,
            String bindingRevision,
            long safetyRevision) {
    }
}
