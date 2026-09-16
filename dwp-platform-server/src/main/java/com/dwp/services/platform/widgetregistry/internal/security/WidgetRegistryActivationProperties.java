package com.dwp.services.platform.widgetregistry.internal.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Immutable, default-deny activation request for the Widget Registry plane. */
@Component
public final class WidgetRegistryActivationProperties {

    private final boolean enabled;
    private final int bindingCount;
    private final String bindingRevision;
    private final long safetyRevision;
    private final String rolloutRevision;
    private final OffsetDateTime expiresAt;
    private final String evidenceSha256;

    @Autowired
    public WidgetRegistryActivationProperties(
            @Value("${dwp.platform.widget-registry-enabled:false}") boolean enabled,
            @Value("${dwp.platform.widget-registry-authority.binding-count:0}") int bindingCount,
            @Value("${dwp.platform.widget-registry-authority.binding-revision:}")
            String bindingRevision,
            @Value("${dwp.platform.widget-registry-authority.safety-revision:-1}")
            long safetyRevision,
            @Value("${dwp.platform.widget-registry-authority.rollout-revision:}")
            String rolloutRevision,
            @Value("${dwp.platform.widget-registry-authority.expires-at:}") String expiresAt,
            @Value("${dwp.platform.widget-registry-authority.evidence-sha256:}")
            String evidenceSha256) {
        this.enabled = enabled;
        this.bindingCount = bindingCount;
        this.bindingRevision = clean(bindingRevision);
        this.safetyRevision = safetyRevision;
        this.rolloutRevision = clean(rolloutRevision);
        this.expiresAt = parse(expiresAt);
        this.evidenceSha256 = clean(evidenceSha256);
    }

    public WidgetRegistryActivationProperties(boolean enabled) {
        this(enabled, 0, "", -1, "", "", "");
    }

    boolean enabled() {
        return enabled;
    }

    int bindingCount() { return bindingCount; }
    String bindingRevision() { return bindingRevision; }
    long safetyRevision() { return safetyRevision; }
    String rolloutRevision() { return rolloutRevision; }
    OffsetDateTime expiresAt() { return expiresAt; }
    String evidenceSha256() { return evidenceSha256; }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static OffsetDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
