package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class MailAddressBookRepository {

    record Recipient(UUID contactId, String displayName, String emailAddress) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    MailAddressBookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    MailAddressBookDtos.ContactPage contacts(
            Long tenantId,
            Long userId,
            String query,
            int page,
            int pageSize) {
        String pattern = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
        long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_contacts
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND (? = '%%' OR lower(display_name) LIKE ? ESCAPE '\\'
                        OR lower(email_address) LIKE ? ESCAPE '\\'
                        OR lower(COALESCE(organization_name, '')) LIKE ? ESCAPE '\\')
                """, Long.class, tenantId, userId, pattern, pattern, pattern, pattern);
        List<MailAddressBookDtos.Contact> items = jdbc.query("""
                SELECT contact_id, display_name, email_address, organization_name,
                       job_title, phone_number, source_kind, source_person_public_id,
                       favorite, version, updated_at
                  FROM mail_contacts
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                   AND (? = '%%' OR lower(display_name) LIKE ? ESCAPE '\\'
                        OR lower(email_address) LIKE ? ESCAPE '\\'
                        OR lower(COALESCE(organization_name, '')) LIKE ? ESCAPE '\\')
                 ORDER BY favorite DESC, lower(display_name), contact_id
                 LIMIT ? OFFSET ?
                """, (result, ignored) -> contact(result), tenantId, userId,
                pattern, pattern, pattern, pattern, pageSize, page * pageSize);
        return new MailAddressBookDtos.ContactPage(items, total, page, pageSize);
    }

    MailAddressBookDtos.AddressBookSummary summary(Long tenantId, Long userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE contact.lifecycle_state = 'ACTIVE') AS contact_count,
                       COUNT(*) FILTER (WHERE contact.lifecycle_state = 'ACTIVE'
                                           AND contact.favorite) AS favorite_count,
                       (SELECT COUNT(*) FROM mail_contact_groups group_record
                         WHERE group_record.tenant_id = ?
                           AND group_record.owner_user_id = ?
                           AND group_record.lifecycle_state = 'ACTIVE') AS group_count
                  FROM mail_contacts contact
                 WHERE contact.tenant_id = ? AND contact.owner_user_id = ?
                """, (result, ignored) -> new MailAddressBookDtos.AddressBookSummary(
                result.getLong("contact_count"),
                result.getLong("favorite_count"),
                result.getLong("group_count")), tenantId, userId, tenantId, userId);
    }

    List<MailAddressBookDtos.ContactGroup> groups(Long tenantId, Long userId) {
        List<GroupRow> rows = jdbc.query("""
                SELECT group_id, display_name, description, version, updated_at
                  FROM mail_contact_groups
                 WHERE tenant_id = ? AND owner_user_id = ?
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY lower(display_name), group_id
                """, (result, ignored) -> new GroupRow(
                result.getObject("group_id", UUID.class),
                result.getString("display_name"),
                result.getString("description"),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class)), tenantId, userId);
        Map<UUID, List<MailAddressBookDtos.GroupMember>> members = new LinkedHashMap<>();
        jdbc.query("""
                SELECT membership.group_id, contact.contact_id, contact.display_name,
                       contact.email_address, contact.organization_name, membership.sort_order
                  FROM mail_contact_group_members membership
                  JOIN mail_contact_groups group_record
                    ON group_record.tenant_id = membership.tenant_id
                   AND group_record.owner_user_id = membership.owner_user_id
                   AND group_record.group_id = membership.group_id
                   AND group_record.lifecycle_state = 'ACTIVE'
                  JOIN mail_contacts contact
                    ON contact.tenant_id = membership.tenant_id
                   AND contact.owner_user_id = membership.owner_user_id
                   AND contact.contact_id = membership.contact_id
                   AND contact.lifecycle_state = 'ACTIVE'
                 WHERE membership.tenant_id = ? AND membership.owner_user_id = ?
                 ORDER BY membership.group_id, membership.sort_order,
                          lower(contact.display_name), contact.contact_id
                """, result -> {
            UUID groupId = result.getObject("group_id", UUID.class);
            members.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(
                    new MailAddressBookDtos.GroupMember(
                            result.getObject("contact_id", UUID.class),
                            result.getString("display_name"),
                            result.getString("email_address"),
                            result.getString("organization_name"),
                            result.getInt("sort_order")));
        }, tenantId, userId);
        return rows.stream().map(row -> new MailAddressBookDtos.ContactGroup(
                row.groupId(), row.displayName(), row.description(),
                List.copyOf(members.getOrDefault(row.groupId(), List.of())),
                row.version(), row.updatedAt())).toList();
    }

    Optional<MailAddressBookDtos.Contact> contact(Long tenantId, Long userId, UUID contactId) {
        return jdbc.query("""
                SELECT contact_id, display_name, email_address, organization_name,
                       job_title, phone_number, source_kind, source_person_public_id,
                       favorite, version, updated_at
                  FROM mail_contacts
                 WHERE tenant_id = ? AND owner_user_id = ? AND contact_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> contact(result), tenantId, userId, contactId)
                .stream().findFirst();
    }

    Optional<MailAddressBookDtos.ContactGroup> group(Long tenantId, Long userId, UUID groupId) {
        return groups(tenantId, userId).stream()
                .filter(group -> group.groupId().equals(groupId))
                .findFirst();
    }

    UUID createContact(
            Long tenantId,
            Long userId,
            MailAddressBookDtos.ContactCreateRequest request) {
        UUID contactId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_contacts (
                    contact_id, tenant_id, owner_user_id, create_request_id,
                    display_name, email_address, organization_name, job_title,
                    phone_number, source_kind, source_person_public_id, favorite,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, lower(?), ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, owner_user_id, create_request_id) DO NOTHING
                """, contactId, tenantId, userId, request.idempotencyKey(),
                request.displayName().trim(), request.emailAddress().trim(),
                value(request.organizationName()), value(request.jobTitle()),
                value(request.phoneNumber()), request.sourceKind(),
                request.sourcePersonPublicId(), request.favorite(), userId, userId);
        return jdbc.queryForObject("""
                SELECT contact_id
                  FROM mail_contacts
                 WHERE tenant_id = ? AND owner_user_id = ? AND create_request_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, UUID.class, tenantId, userId, request.idempotencyKey());
    }

    int updateContact(
            Long tenantId,
            Long userId,
            UUID contactId,
            MailAddressBookDtos.ContactUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_contacts
                   SET display_name = ?, email_address = lower(?), organization_name = ?,
                       job_title = ?, phone_number = ?, favorite = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND contact_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, request.displayName().trim(), request.emailAddress().trim(),
                value(request.organizationName()), value(request.jobTitle()),
                value(request.phoneNumber()), request.favorite(), userId,
                tenantId, userId, contactId, request.version());
    }

    int advanceContainingGroupVersions(Long tenantId, Long userId, UUID contactId) {
        return jdbc.update("""
                UPDATE mail_contact_groups group_record
                   SET version = group_record.version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE group_record.tenant_id = ?
                   AND group_record.owner_user_id = ?
                   AND group_record.lifecycle_state = 'ACTIVE'
                   AND EXISTS (
                       SELECT 1
                         FROM mail_contact_group_members membership
                        WHERE membership.tenant_id = group_record.tenant_id
                          AND membership.owner_user_id = group_record.owner_user_id
                          AND membership.group_id = group_record.group_id
                          AND membership.contact_id = ?)
                """, userId, tenantId, userId, contactId);
    }

    int deleteContact(Long tenantId, Long userId, UUID contactId, long version) {
        return jdbc.update("""
                UPDATE mail_contacts
                   SET lifecycle_state = 'ARCHIVED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND contact_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, userId, tenantId, userId, contactId, version);
    }

    UUID createGroup(
            Long tenantId,
            Long userId,
            MailAddressBookDtos.ContactGroupCreateRequest request) {
        UUID groupId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_contact_groups (
                    group_id, tenant_id, owner_user_id, create_request_id,
                    display_name, description, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, owner_user_id, create_request_id) DO NOTHING
                """, groupId, tenantId, userId, request.idempotencyKey(),
                request.displayName().trim(), value(request.description()), userId, userId);
        return jdbc.queryForObject("""
                SELECT group_id
                  FROM mail_contact_groups
                 WHERE tenant_id = ? AND owner_user_id = ? AND create_request_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, UUID.class, tenantId, userId, request.idempotencyKey());
    }

    int updateGroup(
            Long tenantId,
            Long userId,
            UUID groupId,
            MailAddressBookDtos.ContactGroupUpdateRequest request) {
        return jdbc.update("""
                UPDATE mail_contact_groups
                   SET display_name = ?, description = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND group_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, request.displayName().trim(), value(request.description()), userId,
                tenantId, userId, groupId, request.version());
    }

    int deleteGroup(Long tenantId, Long userId, UUID groupId, long version) {
        return jdbc.update("""
                UPDATE mail_contact_groups
                   SET lifecycle_state = 'ARCHIVED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND group_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, userId, tenantId, userId, groupId, version);
    }

    boolean lockGroup(Long tenantId, Long userId, UUID groupId, long version) {
        return !jdbc.query("""
                SELECT group_id
                  FROM mail_contact_groups
                 WHERE tenant_id = ? AND owner_user_id = ? AND group_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                 FOR UPDATE
                """, (result, ignored) -> result.getObject("group_id", UUID.class),
                tenantId, userId, groupId, version).isEmpty();
    }

    int replaceMembers(
            Long tenantId,
            Long userId,
            UUID groupId,
            List<UUID> requestedContactIds,
            long version) {
        List<UUID> contactIds = requestedContactIds.stream().distinct().sorted().toList();
        if (!contactIds.isEmpty()) {
            var parameters = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("userId", userId)
                    .addValue("contactIds", contactIds);
            List<UUID> authorized = namedJdbc.query("""
                    SELECT contact_id
                      FROM mail_contacts
                     WHERE tenant_id = :tenantId AND owner_user_id = :userId
                       AND lifecycle_state = 'ACTIVE' AND contact_id IN (:contactIds)
                    """, parameters,
                    (result, ignored) -> result.getObject("contact_id", UUID.class));
            if (authorized.size() != contactIds.size()) return -1;
        }
        jdbc.update("""
                DELETE FROM mail_contact_group_members
                 WHERE tenant_id = ? AND owner_user_id = ? AND group_id = ?
                """, tenantId, userId, groupId);
        jdbc.batchUpdate("""
                INSERT INTO mail_contact_group_members (
                    tenant_id, owner_user_id, group_id, contact_id, sort_order, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setLong(1, tenantId);
                statement.setLong(2, userId);
                statement.setObject(3, groupId);
                statement.setObject(4, contactIds.get(index));
                statement.setInt(5, index);
                statement.setLong(6, userId);
            }

            @Override
            public int getBatchSize() {
                return contactIds.size();
            }
        });
        return jdbc.update("""
                UPDATE mail_contact_groups
                   SET version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND group_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, userId, tenantId, userId, groupId, version);
    }

    List<Recipient> recipients(Long tenantId, Long userId, UUID groupId) {
        return jdbc.query("""
                SELECT contact.contact_id, contact.display_name, contact.email_address
                  FROM mail_contact_group_members membership
                  JOIN mail_contact_groups group_record
                    ON group_record.tenant_id = membership.tenant_id
                   AND group_record.owner_user_id = membership.owner_user_id
                   AND group_record.group_id = membership.group_id
                   AND group_record.lifecycle_state = 'ACTIVE'
                  JOIN mail_contacts contact
                    ON contact.tenant_id = membership.tenant_id
                   AND contact.owner_user_id = membership.owner_user_id
                   AND contact.contact_id = membership.contact_id
                   AND contact.lifecycle_state = 'ACTIVE'
                 WHERE membership.tenant_id = ? AND membership.owner_user_id = ?
                   AND membership.group_id = ?
                 ORDER BY membership.sort_order, lower(contact.email_address), contact.contact_id
                """, (result, ignored) -> new Recipient(
                result.getObject("contact_id", UUID.class),
                result.getString("display_name"),
                result.getString("email_address")), tenantId, userId, groupId);
    }

    private MailAddressBookDtos.Contact contact(java.sql.ResultSet result) throws SQLException {
        return new MailAddressBookDtos.Contact(
                result.getObject("contact_id", UUID.class),
                result.getString("display_name"),
                result.getString("email_address"),
                result.getString("organization_name"),
                result.getString("job_title"),
                result.getString("phone_number"),
                result.getString("source_kind"),
                result.getObject("source_person_public_id", UUID.class),
                result.getBoolean("favorite"),
                result.getLong("version"),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private String value(String input) {
        String normalized = input == null ? "" : input.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record GroupRow(
            UUID groupId,
            String displayName,
            String description,
            long version,
            OffsetDateTime updatedAt) {
    }
}
