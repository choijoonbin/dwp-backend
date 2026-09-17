package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Revision;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class NotificationAttentionGovernanceRuntime {

    private static final int HARD_MAX_ACTIVE_RULES = 500;
    private static final Pattern TOPIC = Pattern.compile("#[a-z0-9][a-z0-9._-]{1,79}");

    private final NotificationAttentionGovernanceRepository repository;
    private final Settings defaultSettings;

    @Autowired
    public NotificationAttentionGovernanceRuntime(
            NotificationAttentionGovernanceRepository repository,
            @Value("${dwp.notification.attention.max-active-rules:500}")
            int defaultMaxActiveRules,
            @Value("${dwp.notification.attention.minimum-analytics-cohort:10}")
            int defaultMinimumAnalyticsCohort) {
        this(repository, defaults(defaultMaxActiveRules, defaultMinimumAnalyticsCohort));
    }

    NotificationAttentionGovernanceRuntime(
            NotificationAttentionGovernanceRepository repository,
            Settings defaultSettings) {
        this.repository = repository;
        this.defaultSettings = validate(defaultSettings, "default");
    }

    public EffectiveGovernance resolve(long tenantId) {
        if (tenantId < 1) {
            throw new IllegalArgumentException("A positive tenant identifier is required.");
        }
        try {
            return repository.active(tenantId)
                    .map(this::published)
                    .orElseGet(() -> new EffectiveGovernance(
                            defaultSettings, false, null, 0L));
        } catch (DataAccessException | IllegalStateException exception) {
            throw unavailable(exception);
        }
    }

    public int minimumAnalyticsCohort(long tenantId) {
        return resolve(tenantId).settings().minimumAnalyticsCohort();
    }

    private EffectiveGovernance published(Revision revision) {
        if (revision == null
                || revision.governanceId() == null
                || !"PUBLISHED".equals(revision.state())
                || revision.revisionNumber() < 1) {
            throw new IllegalStateException("Published attention governance is malformed.");
        }
        Settings published = validate(revision.settings(), "published");
        Settings effective = new Settings(
                Math.min(published.maxActiveUserRules(), defaultSettings.maxActiveUserRules()),
                Math.min(published.maxVipRules(), defaultSettings.maxActiveUserRules()),
                Math.min(published.maxFollowRules(), defaultSettings.maxActiveUserRules()),
                published.approvedTopicAllowlist(),
                true,
                Math.max(
                        published.minimumAnalyticsCohort(),
                        defaultSettings.minimumAnalyticsCohort()),
                true);
        return new EffectiveGovernance(
                validate(effective, "effective"),
                true,
                revision.governanceId(),
                revision.revisionNumber());
    }

    private static Settings defaults(int maxActiveRules, int minimumAnalyticsCohort) {
        if (maxActiveRules < 1 || maxActiveRules > HARD_MAX_ACTIVE_RULES) {
            throw new IllegalArgumentException(
                    "The default attention rule limit must be between 1 and 500.");
        }
        if (minimumAnalyticsCohort < 10 || minimumAnalyticsCohort > 10000) {
            throw new IllegalArgumentException(
                    "The default analytics cohort must be between 10 and 10000.");
        }
        return new Settings(
                maxActiveRules,
                maxActiveRules,
                maxActiveRules,
                List.of(),
                true,
                minimumAnalyticsCohort,
                true);
    }

    private static Settings validate(Settings settings, String source) {
        if (settings == null
                || settings.maxActiveUserRules() < 1
                || settings.maxActiveUserRules() > HARD_MAX_ACTIVE_RULES
                || settings.maxVipRules() < 0
                || settings.maxVipRules() > settings.maxActiveUserRules()
                || settings.maxFollowRules() < 0
                || settings.maxFollowRules() > settings.maxActiveUserRules()
                || !settings.mandatoryPolicyPrecedence()
                || settings.minimumAnalyticsCohort() < 10
                || settings.minimumAnalyticsCohort() > 10000
                || !settings.independentReviewerRequired()) {
            throw new IllegalStateException(
                    "The " + source + " attention governance settings are invalid.");
        }
        List<String> topics = settings.approvedTopicAllowlist();
        if (topics == null
                || topics.size() > 100
                || new HashSet<>(topics).size() != topics.size()
                || topics.stream().anyMatch(topic -> topic == null
                        || !topic.equals(topic.trim())
                        || !TOPIC.matcher(topic).matches())) {
            throw new IllegalStateException(
                    "The " + source + " attention topic allowlist is invalid.");
        }
        return new Settings(
                settings.maxActiveUserRules(),
                settings.maxVipRules(),
                settings.maxFollowRules(),
                topics,
                true,
                settings.minimumAnalyticsCohort(),
                true);
    }

    private NotificationException unavailable(RuntimeException cause) {
        return new NotificationException(
                NotificationErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "Active attention governance could not be resolved.",
                cause);
    }

    public record EffectiveGovernance(
            Settings settings,
            boolean published,
            UUID governanceId,
            long revisionNumber) {

        public boolean topicAllowed(String canonicalScopeKey) {
            return settings.approvedTopicAllowlist().contains("#" + canonicalScopeKey);
        }

        public boolean topicHashAllowed(String canonicalScopeHash) {
            return settings.approvedTopicAllowlist().stream()
                    .map(topic -> NotificationAttentionScope.sha256(topic.substring(1)))
                    .anyMatch(canonicalScopeHash::equals);
        }
    }
}
