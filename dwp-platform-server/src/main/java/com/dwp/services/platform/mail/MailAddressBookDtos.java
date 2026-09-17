package com.dwp.services.platform.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.Classification;

public final class MailAddressBookDtos {

    private MailAddressBookDtos() {
    }

    public enum GroupRecipientMode { TO, BCC }

    public record Contact(
            UUID contactId,
            String displayName,
            String emailAddress,
            String organizationName,
            String jobTitle,
            String phoneNumber,
            String sourceKind,
            UUID sourcePersonPublicId,
            boolean favorite,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record GroupMember(
            UUID contactId,
            String displayName,
            String emailAddress,
            String organizationName,
            int sortOrder) {
    }

    public record ContactGroup(
            UUID groupId,
            String displayName,
            String description,
            List<GroupMember> members,
            long version,
            OffsetDateTime updatedAt) {
    }

    public record AddressBookSummary(
            long contactCount,
            long favoriteCount,
            long groupCount) {
    }

    public record ContactPage(
            List<Contact> items,
            long total,
            int page,
            int pageSize) {
    }

    public record AddressBook(
            ContactPage contacts,
            List<ContactGroup> groups,
            AddressBookSummary summary,
            OffsetDateTime generatedAt) {
    }

    public record ContactCreateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Email @Size(max = 255) String emailAddress,
            @Size(max = 200) String organizationName,
            @Size(max = 160) String jobTitle,
            @Pattern(regexp = "^[+()0-9 .-]{0,40}$") String phoneNumber,
            @NotNull @Pattern(regexp = "MANUAL|DIRECTORY") String sourceKind,
            UUID sourcePersonPublicId,
            boolean favorite,
            @NotNull UUID idempotencyKey) {
    }

    public record ContactUpdateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Email @Size(max = 255) String emailAddress,
            @Size(max = 200) String organizationName,
            @Size(max = 160) String jobTitle,
            @Pattern(regexp = "^[+()0-9 .-]{0,40}$") String phoneNumber,
            boolean favorite,
            @NotNull @Min(0) Long version) {
    }

    public record ContactGroupCreateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 500) String description,
            @NotNull UUID idempotencyKey) {
    }

    public record ContactGroupUpdateRequest(
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 500) String description,
            @NotNull @Min(0) Long version) {
    }

    public record GroupMembersReplaceRequest(
            @NotNull @Size(max = 100) List<UUID> contactIds,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long version) {
    }

    public record GroupMessageRequest(
            @NotBlank @Size(max = 500) String subject,
            @NotBlank @Size(max = 100_000) String body,
            @NotNull Classification classification,
            @NotNull GroupRecipientMode recipientMode,
            @NotNull UUID idempotencyKey,
            @NotNull @Min(0) Long groupVersion) {

        public GroupMessageRequest(
                String subject,
                String body,
                Classification classification,
                UUID idempotencyKey,
                Long groupVersion) {
            this(subject, body, classification, GroupRecipientMode.TO, idempotencyKey, groupVersion);
        }
    }

    public record GroupSendReceipt(
            UUID receiptId,
            UUID groupId,
            long groupVersion,
            GroupRecipientMode recipientMode,
            int recipientCount,
            UUID threadId,
            OffsetDateTime acceptedAt,
            String state) {
    }

    public record GroupSendResult(
            MailDtos.ThreadDetail thread,
            GroupSendReceipt receipt) {
    }
}
