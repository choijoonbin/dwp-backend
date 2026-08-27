package com.dwp.services.messaging.appearance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class MessagingDisplayPreferenceService {

    private static final Set<String> GLOBAL_LAYOUTS =
            Set.of("AUTO", "CONVERSATIONAL", "COLLABORATIVE");
    private static final Set<String> CONVERSATION_LAYOUTS =
            Set.of("INHERIT", "AUTO", "CONVERSATIONAL", "COLLABORATIVE");
    private static final Set<String> GLOBAL_DENSITIES = Set.of("COMFORTABLE", "COMPACT");
    private static final Set<String> CONVERSATION_DENSITIES =
            Set.of("INHERIT", "COMFORTABLE", "COMPACT");
    private static final Set<String> TIMESTAMP_MODES = Set.of("SMART", "ALWAYS");
    private static final Set<String> STRUCTURED_TYPES =
            Set.of("ANNOUNCEMENT", "INCIDENT", "MEETING");

    private final MessagingDisplayPreferenceRepository repository;

    public MessagingDisplayPreferenceService(MessagingDisplayPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MessagingDisplayDtos.DisplayPreference displayPreference() {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return response(global(subject), repository.policy(subject.tenantId()));
    }

    @Transactional
    public MessagingDisplayDtos.DisplayPreference updateDisplayPreference(
            MessagingDisplayDtos.UpdateDisplayPreferenceRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MessagingDisplayDtos.AppearancePolicy policy = repository.policy(subject.tenantId());
        MessagingDisplayPreferenceRepository.GlobalRow candidate = new MessagingDisplayPreferenceRepository.GlobalRow(
                normalized(request.layoutMode(), GLOBAL_LAYOUTS, "layout mode"),
                normalized(request.density(), GLOBAL_DENSITIES, "density"),
                allowedTheme(request.theme(), policy, false),
                request.showAvatars(),
                normalized(request.timestampMode(), TIMESTAMP_MODES, "timestamp mode"),
                request.messagePreview(),
                request.version());
        int changed = request.version() == 0
                ? repository.insertGlobal(subject.tenantId(), subject.userId(), candidate)
                : repository.updateGlobal(
                        subject.tenantId(), subject.userId(), candidate, request.version());
        requireChanged(changed);
        MessagingDisplayPreferenceRepository.GlobalRow saved = repository.global(
                subject.tenantId(), subject.userId()).orElseThrow();
        repository.auditGlobal(subject.tenantId(), subject.userId(), saved.version());
        return response(saved, policy);
    }

    @Transactional(readOnly = true)
    public MessagingDisplayDtos.ConversationDisplayPreference conversationPreference(
            UUID conversationId) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        return conversationResponse(subject, conversationId);
    }

    @Transactional
    public MessagingDisplayDtos.ConversationDisplayPreference updateConversationPreference(
            UUID conversationId,
            MessagingDisplayDtos.UpdateConversationDisplayPreferenceRequest request) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        MessagingDisplayDtos.AppearancePolicy policy = repository.policy(subject.tenantId());
        requireConversation(subject, conversationId);
        MessagingDisplayPreferenceRepository.ConversationRow candidate =
                new MessagingDisplayPreferenceRepository.ConversationRow(
                        normalized(request.layoutMode(), CONVERSATION_LAYOUTS, "layout mode"),
                        normalized(request.density(), CONVERSATION_DENSITIES, "density"),
                        allowedTheme(request.theme(), policy, true),
                        request.version());
        int changed = request.version() == 0
                ? repository.insertConversation(
                        subject.tenantId(), subject.userId(), conversationId, candidate)
                : repository.updateConversation(
                        subject.tenantId(), subject.userId(), conversationId,
                        candidate, request.version());
        requireChanged(changed);
        MessagingDisplayPreferenceRepository.ConversationRow saved = repository.conversation(
                subject.tenantId(), subject.userId(), conversationId).orElseThrow();
        repository.auditConversation(
                subject.tenantId(), subject.userId(), conversationId, saved.version());
        return conversationResponse(subject, conversationId);
    }

    @Transactional
    public MessagingDisplayDtos.ConversationDisplayPreference resetConversationPreference(
            UUID conversationId,
            long version) {
        MessagingRequestContext.Subject subject = MessagingRequestContext.get();
        requireConversation(subject, conversationId);
        if (version > 0 && repository.deleteConversation(
                subject.tenantId(), subject.userId(), conversationId, version) == 0) {
            requireChanged(0);
        }
        repository.auditConversation(subject.tenantId(), subject.userId(), conversationId, 0);
        return conversationResponse(subject, conversationId);
    }

    private MessagingDisplayDtos.ConversationDisplayPreference conversationResponse(
            MessagingRequestContext.Subject subject,
            UUID conversationId) {
        MessagingDisplayPreferenceRepository.ConversationContext context =
                requireConversation(subject, conversationId);
        MessagingDisplayPreferenceRepository.GlobalRow global = global(subject);
        MessagingDisplayPreferenceRepository.ConversationRow override = repository.conversation(
                subject.tenantId(), subject.userId(), conversationId)
                .orElse(new MessagingDisplayPreferenceRepository.ConversationRow(
                        "INHERIT", "INHERIT", "INHERIT", 0));
        String requestedLayout = inherited(override.layoutMode(), global.layoutMode());
        boolean structured = STRUCTURED_TYPES.contains(context.conversationType());
        String effectiveLayout = structured
                ? "COLLABORATIVE"
                : resolveAutoLayout(requestedLayout, context.conversationType());
        String effectiveDensity = inherited(override.density(), global.density());
        String requestedTheme = inherited(override.theme(), global.theme());
        boolean restricted = "RESTRICTED".equals(context.classification());
        String effectiveTheme = restricted ? "DEFAULT" : requestedTheme;
        String reason = restricted
                ? "RESTRICTED_CONVERSATION"
                : structured ? "STRUCTURED_CONVERSATION" : null;
        return new MessagingDisplayDtos.ConversationDisplayPreference(
                conversationId,
                override.layoutMode(),
                override.density(),
                override.theme(),
                effectiveLayout,
                effectiveDensity,
                effectiveTheme,
                global.showAvatars(),
                global.timestampMode(),
                global.messagePreview(),
                restricted || structured,
                reason,
                override.version());
    }

    private MessagingDisplayPreferenceRepository.ConversationContext requireConversation(
            MessagingRequestContext.Subject subject,
            UUID conversationId) {
        return repository.conversationContext(
                subject.tenantId(), subject.userId(), conversationId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.ENTITY_NOT_FOUND, "The conversation was not found."));
    }

    private MessagingDisplayPreferenceRepository.GlobalRow global(
            MessagingRequestContext.Subject subject) {
        return repository.global(subject.tenantId(), subject.userId())
                .orElse(new MessagingDisplayPreferenceRepository.GlobalRow(
                        "AUTO", "COMFORTABLE", "DEFAULT", true, "SMART", true, 0));
    }

    private MessagingDisplayDtos.DisplayPreference response(
            MessagingDisplayPreferenceRepository.GlobalRow row,
            MessagingDisplayDtos.AppearancePolicy policy) {
        return new MessagingDisplayDtos.DisplayPreference(
                row.layoutMode(), row.density(), row.theme(), row.showAvatars(),
                row.timestampMode(), row.messagePreview(), row.version(), policy);
    }

    private String resolveAutoLayout(String layout, String conversationType) {
        if (!"AUTO".equals(layout)) return layout;
        return Set.of("DIRECT", "GROUP").contains(conversationType)
                ? "CONVERSATIONAL" : "COLLABORATIVE";
    }

    private String inherited(String value, String fallback) {
        return "INHERIT".equals(value) ? fallback : value;
    }

    private String allowedTheme(
            String value,
            MessagingDisplayDtos.AppearancePolicy policy,
            boolean allowInherit) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (allowInherit && "INHERIT".equals(normalized)) return normalized;
        if (!policy.allowedThemes().contains(normalized)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The selected Messaging theme is not allowed by the tenant policy.");
        }
        return normalized;
    }

    private String normalized(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "Unsupported Messaging " + label + ".");
        }
        return normalized;
    }

    private void requireChanged(int changed) {
        if (changed == 0) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Messaging display preferences changed in another session.");
        }
    }
}
