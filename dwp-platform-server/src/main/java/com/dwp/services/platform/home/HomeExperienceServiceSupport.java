package com.dwp.services.platform.home;

import com.dwp.services.platform.experience.ExperienceRevisionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

abstract class HomeExperienceServiceSupport {
    private static final Logger log = LoggerFactory.getLogger(HomeExperienceServiceSupport.class);
    private static final List<String> ROLLBACK_AFFECTED_SCOPES = List.of(
            "PRESENTATION",
            "BACKGROUND_ASSET",
            "LAUNCHPAD",
            "COMPOSITION");

    protected final ObjectMapper objectMapper;
    protected final HomeLaunchpadPolicy launchpadPolicy;
    protected final HomeCompositionPolicyRegistry compositionPolicyRegistry;

    HomeExperienceServiceSupport(
            ObjectMapper objectMapper,
            HomeLaunchpadPolicy launchpadPolicy,
            HomeCompositionPolicyRegistry compositionPolicyRegistry) {
        this.objectMapper = objectMapper;
        this.launchpadPolicy = launchpadPolicy;
        this.compositionPolicyRegistry = compositionPolicyRegistry;
    }

    protected HomeExperienceDtos.HomeExperienceRevisionResponse revisionResponse(
            ExperienceRevisionStore.ExperienceRevision revision,
            long currentVersion) {
        JsonNode value = revision.snapshot();
        JsonNode localized = value.get("localizedContent");
        return new HomeExperienceDtos.HomeExperienceRevisionResponse(
                revision.revisionId(),
                revision.sourceVersion(),
                revision.changeType(),
                text(value, "headline"),
                text(value, "backgroundOriginalName"),
                integer(value, "backgroundWidth"),
                integer(value, "backgroundHeight"),
                localized != null && localized.isObject() ? localized.size() : 0,
                ROLLBACK_AFFECTED_SCOPES,
                revision.sourceVersion() == currentVersion && !"BASELINE".equals(revision.changeType()),
                revision.createdAt(),
                revision.createdBy());
    }

    protected long versionOf(HomeExperience experience) {
        return experience.getVersion() == null ? 0L : experience.getVersion();
    }

    protected String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    protected Integer integer(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || !node.isNumber() ? null : node.intValue();
    }

    protected Long longValue(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || !node.isNumber() ? null : node.longValue();
    }

    protected HomeExperienceDtos.HomeLaunchpadConfiguration launchpadConfiguration(JsonNode value) {
        if (value == null || !value.isObject() || value.isEmpty()) {
            return launchpadPolicy.defaultConfiguration();
        }
        try {
            HomeExperienceDtos.HomeLaunchpadConfiguration configuration =
                    objectMapper.treeToValue(
                            value,
                            HomeExperienceDtos.HomeLaunchpadConfiguration.class);
            return launchpadPolicy.normalize(configuration);
        } catch (Exception exception) {
            log.warn(
                    "Invalid persisted home launchpad configuration; using the governed default.",
                    exception);
            return launchpadPolicy.defaultConfiguration();
        }
    }

    protected HomeExperienceDtos.HomeCompositionPolicy compositionPolicy(JsonNode value) {
        if (value == null || !value.isObject() || value.isEmpty()) {
            return compositionPolicyRegistry.failClosedPolicy();
        }
        try {
            HomeExperienceDtos.HomeCompositionPolicy policy = objectMapper.treeToValue(
                    value,
                    HomeExperienceDtos.HomeCompositionPolicy.class);
            return compositionPolicyRegistry.normalize(policy);
        } catch (Exception exception) {
            log.warn(
                    "Invalid persisted home composition policy; disabling personal customization.",
                    exception);
            return compositionPolicyRegistry.failClosedPolicy();
        }
    }
}
