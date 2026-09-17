package com.dwp.services.platform.mail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class MailAddressBookCommandFingerprint {

    String contactCreate(MailAddressBookDtos.ContactCreateRequest request) {
        return digest(List.of(
                "CONTACT_CREATE",
                value(request.displayName()),
                email(request.emailAddress()),
                value(request.organizationName()),
                value(request.jobTitle()),
                value(request.phoneNumber()),
                request.sourceKind(),
                string(request.sourcePersonPublicId()),
                Boolean.toString(request.favorite())));
    }

    String groupCreate(MailAddressBookDtos.ContactGroupCreateRequest request) {
        return digest(List.of(
                "GROUP_CREATE", value(request.displayName()), value(request.description())));
    }

    String membersReplace(UUID groupId, MailAddressBookDtos.GroupMembersReplaceRequest request) {
        List<String> values = new java.util.ArrayList<>();
        values.add("GROUP_MEMBERS_REPLACE");
        values.add(groupId.toString());
        values.add(request.version().toString());
        request.contactIds().stream().distinct().sorted().map(UUID::toString).forEach(values::add);
        return digest(values);
    }

    String groupMessage(UUID groupId, MailAddressBookDtos.GroupMessageRequest request) {
        return digest(List.of(
                "GROUP_MESSAGE_SEND",
                groupId.toString(),
                request.groupVersion().toString(),
                value(request.subject()),
                value(request.body()),
                request.classification().name(),
                request.recipientMode().name(),
                string(request.accountId())));
    }

    private String digest(List<String> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream canonical = new DataOutputStream(bytes)) {
                for (String value : values) write(canonical, value);
            }
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint the address-book command.", exception);
        }
    }

    private void write(DataOutputStream canonical, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        canonical.writeInt(encoded.length);
        canonical.write(encoded);
    }

    private String email(String input) {
        return value(input).toLowerCase(Locale.ROOT);
    }

    private String value(String input) {
        return input == null ? "" : input.trim();
    }

    private String string(UUID input) {
        return input == null ? "" : input.toString();
    }
}
