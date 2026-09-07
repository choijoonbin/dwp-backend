package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dwp.services.platform.mail.MailAddressBookCommandReceiptRepository.CommandType.GROUP_MESSAGE_SEND;
import static com.dwp.services.platform.mail.MailTypes.Classification.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailAddressBookPostgresIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void personalContactsGroupsAndGroupDeliveryStayTenantAndVersionBound() {
        String schema = "mail_address_book";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        MailAddressBookRepository addressBook = new MailAddressBookRepository(jdbc);
        Owner owner = owner(jdbc);

        UUID contactId = addressBook.createContact(
                owner.tenantId(), owner.userId(), contactRequest(
                        "Kim", "kim.external@example.com", UUID.randomUUID()));
        UUID secondContactId = addressBook.createContact(
                owner.tenantId(), owner.userId(), contactRequest(
                        "Lee", "lee.external@example.com", UUID.randomUUID()));
        UUID groupId = addressBook.createGroup(
                owner.tenantId(), owner.userId(), groupRequest("Launch team"));

        MailAddressBookDtos.ContactGroup saved = transaction.execute(status -> {
            assertThat(addressBook.lockGroup(owner.tenantId(), owner.userId(), groupId, 0L))
                    .isTrue();
            assertThat(addressBook.replaceMembers(
                    owner.tenantId(), owner.userId(), groupId,
                    List.of(secondContactId, contactId), 0L)).isOne();
            return addressBook.group(owner.tenantId(), owner.userId(), groupId).orElseThrow();
        });
        assertThat(saved.version()).isOne();
        assertThat(saved.members()).extracting(MailAddressBookDtos.GroupMember::emailAddress)
                .containsExactlyInAnyOrder(
                        "kim.external@example.com", "lee.external@example.com");

        var update = new MailAddressBookDtos.ContactUpdateRequest(
                "Kim", "kim.updated@example.com", null, null, null, true, 0L);
        transaction.executeWithoutResult(status -> {
            assertThat(addressBook.updateContact(
                    owner.tenantId(), owner.userId(), contactId, update)).isOne();
            assertThat(addressBook.advanceContainingGroupVersions(
                    owner.tenantId(), owner.userId(), contactId)).isOne();
        });
        MailAddressBookDtos.ContactGroup revised =
                addressBook.group(owner.tenantId(), owner.userId(), groupId).orElseThrow();
        assertThat(revised.version()).isEqualTo(2L);
        assertThat(revised.members()).extracting(MailAddressBookDtos.GroupMember::emailAddress)
                .containsExactlyInAnyOrder(
                        "kim.updated@example.com", "lee.external@example.com");

        List<MailAddressBookRepository.Recipient> recipients =
                addressBook.recipients(owner.tenantId(), owner.userId(), groupId);
        var send = new MailAddressBookDtos.GroupMessageRequest(
                "Launch", "Please review the launch plan.", INTERNAL,
                UUID.randomUUID(), revised.version());
        MailGroupComposeRepository compose = new MailGroupComposeRepository(
                jdbc, new MailJsonCodec(new ObjectMapper()));
        MailGroupComposeRepository.ComposeResult delivery = transaction.execute(status -> {
            assertThat(addressBook.lockGroup(
                    owner.tenantId(), owner.userId(), groupId, send.groupVersion())).isTrue();
            return compose.compose(
                    owner.tenantId(), owner.userId(), groupId, send, recipients, "corr-group");
        });
        assertThat(delivery).isNotNull();
        Map<String, Object> persisted = jdbc.queryForMap("""
                SELECT jsonb_array_length(thread.participants) AS participant_count,
                       jsonb_array_length(message.recipients) AS recipient_count,
                       delivery.delivery_status
                  FROM mail_threads thread
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                  JOIN mail_delivery_outbox delivery
                    ON delivery.tenant_id = thread.tenant_id
                   AND delivery.thread_id = thread.thread_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """, owner.tenantId(), delivery.threadId());
        assertThat(persisted)
                .containsEntry("participant_count", 2)
                .containsEntry("recipient_count", 2)
                .containsEntry("delivery_status", "QUEUED");

        jdbc.update("""
                UPDATE mail_messages
                   SET recipients = '[{"name":"Tampered","email":"tampered@example.com","type":"TO"}]'::jsonb
                 WHERE tenant_id = ? AND message_id = (
                       SELECT message_id FROM mail_delivery_outbox
                        WHERE tenant_id = ? AND thread_id = ?)
                """, owner.tenantId(), owner.tenantId(), delivery.threadId());
        MailDeliveryRepository deliveryRepository = new MailDeliveryRepository(
                jdbc, new MailJsonCodec(new ObjectMapper()));
        MailDeliveryRepository.DeliveryJob claimed = deliveryRepository
                .claim("address-book-test", 500, 30)
                .stream()
                .filter(job -> job.threadId().equals(delivery.threadId()))
                .findFirst()
                .orElseThrow();
        assertThat(claimed.recipients()).containsExactly(
                "kim.updated@example.com", "lee.external@example.com");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE mail_group_recipient_snapshots
                   SET recipient_count = 1
                 WHERE tenant_id = ? AND thread_id = ?
                """, owner.tenantId(), delivery.threadId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(addressBook.contact(owner.tenantId() + 1, owner.userId(), contactId))
                .isEmpty();
        UUID foreignContact = addressBook.createContact(
                owner.tenantId() + 1, owner.userId(), contactRequest(
                        "Foreign", "foreign@example.com", UUID.randomUUID()));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO mail_contact_group_members (
                    tenant_id, owner_user_id, group_id, contact_id, sort_order, created_by)
                VALUES (?, ?, ?, ?, 0, ?)
                """, owner.tenantId(), owner.userId(), groupId, foreignContact, owner.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void receiptSerializesConcurrentGroupSendReplayAndRejectsFingerprintDrift()
            throws Exception {
        String schema = "mail_address_receipt";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        MailAddressBookCommandReceiptRepository receipts =
                new MailAddressBookCommandReceiptRepository(jdbc);
        Owner owner = owner(jdbc);
        UUID requestId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.execute(status -> {
                var receipt = receipts.reserve(
                        owner.tenantId(), owner.userId(), GROUP_MESSAGE_SEND,
                        requestId, fingerprint);
                reserved.countDown();
                await(release);
                receipts.complete(
                        owner.tenantId(), owner.userId(), GROUP_MESSAGE_SEND,
                        requestId, fingerprint, targetId, 0L);
                return receipt;
            }));
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
            var replay = workers.submit(() -> transaction.execute(status -> receipts.reserve(
                    owner.tenantId(), owner.userId(), GROUP_MESSAGE_SEND,
                    requestId, fingerprint)));
            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).inserted()).isTrue();
            var replayed = replay.get(10, TimeUnit.SECONDS);
            assertThat(replayed.inserted()).isFalse();
            assertThat(replayed.completed()).isTrue();
            assertThat(replayed.targetId()).isEqualTo(targetId);
        }

        var drift = transaction.execute(status -> receipts.reserve(
                owner.tenantId(), owner.userId(), GROUP_MESSAGE_SEND,
                requestId, "b".repeat(64)));
        assertThat(drift).isNotNull();
        assertThat(drift.requestFingerprint()).isEqualTo(fingerprint);
        assertThat(drift.completed()).isTrue();
    }

    private MailAddressBookDtos.ContactCreateRequest contactRequest(
            String name, String email, UUID requestId) {
        return new MailAddressBookDtos.ContactCreateRequest(
                name, email, null, null, null, "MANUAL", null, false, requestId);
    }

    private MailAddressBookDtos.ContactGroupCreateRequest groupRequest(String name) {
        return new MailAddressBookDtos.ContactGroupCreateRequest(
                name, "Launch recipients", UUID.randomUUID());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the address-book receipt.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the receipt.", exception);
        }
    }

    private Owner owner(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT tenant_id, owner_user_id
                  FROM mail_accounts
                 WHERE account_kind = 'PERSONAL' AND owner_user_id IS NOT NULL
                   AND is_default = TRUE AND connection_state = 'ACTIVE'
                 ORDER BY tenant_id, owner_user_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Mail owner fixture is missing.");
            return new Owner(result.getLong("tenant_id"), result.getLong("owner_user_id"));
        });
    }

    private void migrate(String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }

    private record Owner(Long tenantId, Long userId) {
    }
}
