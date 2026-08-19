package com.dwp.services.messaging.collaboration;

import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CollaborationDtos {

    public static final String SQL_FALLBACK = "SQL_FALLBACK";

    private CollaborationDtos() {
    }

    public enum ConversationType {
        GROUP,
        CHANNEL;

        @JsonCreator
        public static ConversationType from(String value) {
            if (value == null) return null;
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum SearchType {
        CONVERSATION,
        MESSAGE,
        PERSON
    }

    public enum MemberRole {
        VIEWER,
        MEMBER,
        MODERATOR,
        OWNER;

        @JsonCreator
        public static MemberRole from(String value) {
            if (value == null) return null;
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public record CreateConversationRequest(
            @NotBlank @Size(max = 220) String name,
            @Size(max = 1200) String topic,
            @NotNull ConversationType type,
            @NotNull @Size(max = 500) List<@Positive Long> memberUserIds,
            @NotBlank @Size(min = 8, max = 120) String idempotencyKey) {
    }

    public record ConversationCreationResponse(
            ConversationSummary conversation,
            boolean idempotentReplay) {
    }

    public record ConversationSummary(
            UUID conversationId,
            ConversationType type,
            String name,
            String topic,
            String visibility,
            String lifecycleState,
            OffsetDateTime createdAt,
            List<MemberSummary> members) {
    }

    public record MemberSummary(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String role) {
    }

    public record AddConversationMemberRequest(
            @Positive long userId,
            @NotNull MemberRole role,
            @PositiveOrZero long conversationVersion) {
    }

    public record UpdateConversationMemberRoleRequest(
            @NotNull MemberRole role,
            @PositiveOrZero long version) {
    }

    public record LeaveConversationRequest(@PositiveOrZero long version) {
    }

    public record ManagedMemberSummary(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String jobTitle,
            String organizationName,
            String role,
            String membershipSource,
            long historyStartSequence,
            OffsetDateTime membershipStartedAt,
            long version) {
    }

    public record ConversationMembersResponse(
            UUID conversationId,
            String conversationType,
            long conversationVersion,
            List<ManagedMemberSummary> members) {
    }

    public record MembershipMutationResponse(
            ConversationMembersResponse membership,
            boolean idempotentReplay) {
    }

    public record SearchResponse(
            String backend,
            String query,
            int limit,
            int total,
            SearchGroups results) {
    }

    public record SearchGroups(
            List<ConversationSearchResult> conversations,
            List<MessageSearchResult> messages,
            List<PersonSearchResult> people) {
    }

    public record ConversationSearchResult(
            String resultType,
            UUID conversationId,
            String conversationType,
            String name,
            String snippet) {
    }

    public record MessageSearchResult(
            String resultType,
            UUID messageId,
            UUID conversationId,
            String conversationName,
            String senderName,
            String snippet,
            OffsetDateTime createdAt) {
    }

    public record PersonSearchResult(
            String resultType,
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String jobTitle,
            String organizationName,
            String presenceState) {
    }
}
