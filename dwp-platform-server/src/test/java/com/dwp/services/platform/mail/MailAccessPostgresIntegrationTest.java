package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dwp.services.platform.mail.MailTypes.ThreadAction;
import static com.dwp.services.platform.mail.MailTypes.WorkflowState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class MailAccessPostgresIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void sharedMailboxAccessIsRecheckedForEveryReadAndWrite() {
        String schema = "mail_shared_access_fence";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);
        MailLifecycleRepository lifecycle = new MailLifecycleRepository(jdbc);
        SharedFixture fixture = sharedFixture(jdbc);

        MailDtos.ThreadSummary visible = queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).orElseThrow();
        MailLifecycleRepository.LifecycleThread lifecycleVisible = lifecycle.visibleThread(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).orElseThrow();
        assertThat(queries.messages(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isNotEmpty();
        assertThat(queries.comments(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isNotEmpty();

        jdbc.update("""
                UPDATE mail_tenant_policies SET allow_shared_inboxes = FALSE
                 WHERE tenant_id = ?
                """, fixture.tenantId());
        assertDenied(queries, commands, lifecycle, fixture, visible, lifecycleVisible);

        jdbc.update("""
                UPDATE mail_tenant_policies SET allow_shared_inboxes = TRUE
                 WHERE tenant_id = ?
                """, fixture.tenantId());
        jdbc.update("""
                UPDATE mail_shared_inboxes SET lifecycle_state = 'ARCHIVED'
                 WHERE tenant_id = ? AND shared_inbox_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId());
        assertDenied(queries, commands, lifecycle, fixture, visible, lifecycleVisible);

        jdbc.update("""
                UPDATE mail_shared_inboxes SET lifecycle_state = 'ACTIVE'
                 WHERE tenant_id = ? AND shared_inbox_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId());
        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isPresent();
        jdbc.update("""
                UPDATE mail_shared_inbox_members SET lifecycle_state = 'RETIRED'
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId(), fixture.userId());
        assertDenied(queries, commands, lifecycle, fixture, visible, lifecycleVisible);

        UUID otherInboxId = jdbc.queryForObject("""
                SELECT shared_inbox_id FROM mail_shared_inboxes
                 WHERE tenant_id = ? AND account_id <> ?
                 ORDER BY shared_inbox_id LIMIT 1
                """, UUID.class, fixture.tenantId(), fixture.accountId());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE mail_threads SET shared_inbox_id = ?
                 WHERE tenant_id = ? AND thread_id = ?
                """, otherInboxId, fixture.tenantId(), fixture.threadId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sendOnBehalfCanSendButCannotManageOrRevealAnotherActorsBcc() {
        String schema = "mail_shared_read_only_bcc";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);
        MailLifecycleRepository lifecycle = new MailLifecycleRepository(jdbc);
        MailWorkspaceRepository workspace = new MailWorkspaceRepository(jdbc, json);
        SharedFixture fixture = sharedFixture(jdbc);

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET can_read = TRUE, can_send_as = FALSE,
                       can_send_on_behalf = TRUE, can_assign = FALSE, can_manage = FALSE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId(), fixture.userId());
        long otherActor = fixture.userId() + 1_000_000L;
        jdbc.update("""
                UPDATE mail_threads
                   SET participants = '[
                       {"type":"TO","name":"Visible","email":"visible@example.com"},
                       {"type":"BCC","name":"Hidden","email":"hidden@example.com"}
                   ]'::jsonb,
                       created_by = ?
                 WHERE tenant_id = ? AND thread_id = ?
                """, otherActor, fixture.tenantId(), fixture.threadId());
        UUID messageId = jdbc.queryForObject("""
                UPDATE mail_messages
                   SET recipients = '[
                       {"type":"TO","name":"Visible","email":"visible@example.com"},
                       {"type":"BCC","name":"Hidden","email":"hidden@example.com"}
                   ]'::jsonb,
                       message_direction = 'OUTBOUND', created_by = ?
                 WHERE message_id = (
                       SELECT message_id FROM mail_messages
                        WHERE tenant_id = ? AND thread_id = ?
                        ORDER BY sent_at, message_id LIMIT 1)
                RETURNING message_id
                """, UUID.class, otherActor, fixture.tenantId(), fixture.threadId());
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_delivery_outbox (
                    delivery_id, tenant_id, thread_id, message_id, idempotency_key,
                    request_fingerprint, delivery_status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """, deliveryId, fixture.tenantId(), fixture.threadId(), messageId,
                UUID.randomUUID(), "b".repeat(64), otherActor);

        MailDtos.ThreadSummary summary = queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).orElseThrow();
        assertThat(summary.participants())
                .extracting(MailDtos.Participant::email)
                .containsExactly("visible@example.com");
        assertThat(queries.messages(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).getFirst().recipients())
                .extracting(value -> String.valueOf(value.get("email")))
                .containsExactly("visible@example.com");
        assertThat(queries.threads(
                fixture.tenantId(), fixture.userId(), "", "", "", true,
                "hidden@example.com", 0, 100)).isEmpty();
        assertThat(workspace.delivery(
                fixture.tenantId(), fixture.userId(), deliveryId).orElseThrow().recipients())
                .extracting(MailWorkspaceDtos.Recipient::email)
                .containsExactly("visible@example.com");

        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.sharedInboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.SEND)).isTrue();
        assertThat(commands.insertReply(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                "Send on behalf of the mailbox", UUID.randomUUID())).isTrue();
        MailDeliveryRepository deliveryRepository = new MailDeliveryRepository(jdbc, json);
        MailDeliveryRepository.DeliveryJob authorizationProbe =
                new MailDeliveryRepository.DeliveryJob(
                        UUID.randomUUID(), fixture.tenantId(), fixture.threadId(), messageId,
                        UUID.randomUUID(), 1, "corr-on-behalf", fixture.userId(),
                        UUID.randomUUID(), MailTypes.ProviderType.DWP_SANDBOX, null,
                        "example.com", fixture.accountId(), "shared:mailbox",
                        "shared@example.com", "Shared", "Subject", "Body",
                        List.of("recipient@example.com"), null);
        assertThat(deliveryRepository.authorizedSenderMode(authorizationProbe))
                .contains(MailConnectorPort.SenderMode.SEND_ON_BEHALF);
        MailLifecycleRepository.LifecycleThread before = lifecycle.visibleThread(
                fixture.tenantId(), fixture.userId(), fixture.threadId()).orElseThrow();
        MailLifecycleRepository.FolderTarget archive = lifecycle.systemTarget(
                fixture.tenantId(), fixture.userId(), fixture.accountId(), "ARCHIVE")
                .orElseThrow();
        assertThat(lifecycle.move(
                fixture.tenantId(), fixture.userId(), before, archive, "ARCHIVED",
                before.folderId(), before.version())).isZero();
        assertThat(workspace.cancelDelivery(
                fixture.tenantId(), fixture.userId(), deliveryId, 0L)).isTrue();
        assertThat(workspace.reconcileSandboxDelivery(
                fixture.tenantId(), fixture.userId(), deliveryId, 0L)).isFalse();

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET can_send_on_behalf = FALSE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.sharedInboxId(), fixture.userId());
        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.sharedInboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.SEND)).isFalse();
        assertThat(commands.insertReply(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                "Read access alone must not send", UUID.randomUUID())).isFalse();
    }

    @Test
    void personalOwnerRemainsAuthorizedAndDueSnoozeReturnsToTheInbox() {
        String schema = "mail_personal_access_and_snooze";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);

        PersonalFixture fixture = personalFixture(jdbc);
        MailDtos.ThreadSummary before = queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadIds().get(0)).orElseThrow();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), before.threadId(),
                ThreadAction.STAR, before.version())).isOne();

        UUID futureThreadId = fixture.threadIds().get(0);
        UUID dueThreadId = fixture.threadIds().get(1);
        jdbc.update("""
                UPDATE mail_threads
                   SET workflow_state = 'SNOOZED', snoozed_until = CURRENT_TIMESTAMP + INTERVAL '2 hours'
                 WHERE tenant_id = ? AND thread_id = ?
                """, fixture.tenantId(), futureThreadId);
        jdbc.update("""
                UPDATE mail_threads
                   SET workflow_state = 'SNOOZED', snoozed_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE tenant_id = ? AND thread_id = ?
                """, fixture.tenantId(), dueThreadId);

        List<MailDtos.ThreadSummary> inbox = queries.threads(
                fixture.tenantId(), fixture.userId(), "", "", "INBOX",
                false, "", 0, 100);
        assertThat(inbox).extracting(MailDtos.ThreadSummary::threadId)
                .doesNotContain(futureThreadId)
                .contains(dueThreadId);
        MailDtos.ThreadSummary due = inbox.stream()
                .filter(thread -> thread.threadId().equals(dueThreadId))
                .findFirst().orElseThrow();
        assertThat(due.workflowState()).isEqualTo(WorkflowState.OPEN);
        assertThat(due.snoozedUntil()).isNull();

        List<MailDtos.ThreadSummary> snoozed = queries.threads(
                fixture.tenantId(), fixture.userId(), "", "SNOOZED", "INBOX",
                false, "", 0, 100);
        assertThat(snoozed).extracting(MailDtos.ThreadSummary::threadId)
                .contains(futureThreadId)
                .doesNotContain(dueThreadId);
        Integer expectedFuture = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE thread.tenant_id = ? AND account.owner_user_id = ?
                   AND thread.workflow_state = 'SNOOZED'
                   AND thread.snoozed_until > CURRENT_TIMESTAMP
                """, Integer.class, fixture.tenantId(), fixture.userId());
        assertThat(queries.metrics(fixture.tenantId(), fixture.userId()).snoozed())
                .isEqualTo(expectedFuture);
    }

    @Test
    void deliveryCommandPersistsItsPayloadFingerprint() {
        String schema = "mail_delivery_payload_binding";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailCommandRepository commands = new MailCommandRepository(jdbc, json);
        PersonalFixture fixture = personalFixture(jdbc);
        UUID idempotencyKey = UUID.randomUUID();
        MailDtos.ComposeRequest request = new MailDtos.ComposeRequest(
                "recipient@example.com", "Recipient", "Bound command", "Body",
                MailTypes.DeliveryMode.SEND, idempotencyKey);
        String composeFingerprint = new MailSendCommandFingerprint().compose(fixture.userId(), request);

        MailCommandRepository.ComposeResult result = commands.compose(
                fixture.tenantId(), fixture.userId(), request, composeFingerprint);
        assertThat(result).isNotNull();
        assertThat(result.created()).isTrue();
        assertThat(result.requestFingerprint()).isEqualTo(composeFingerprint);

        assertThatThrownBy(() -> commands.enqueueDelivery(
                fixture.tenantId(), fixture.userId(), result.threadId(),
                idempotencyKey, "corr-unbound", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request fingerprint");

        commands.enqueueDelivery(
                fixture.tenantId(), fixture.userId(), result.threadId(),
                idempotencyKey, "corr-bound", composeFingerprint);

        assertThat(commands.deliveryCommand(
                fixture.tenantId(), fixture.userId(), idempotencyKey))
                .isEqualTo(new MailCommandRepository.DeliveryCommand(
                        result.threadId(), fixture.userId(), composeFingerprint));

        assertThat(commands.enqueueDelivery(
                fixture.tenantId(), fixture.userId(), result.threadId(),
                idempotencyKey, "corr-bound-replay", composeFingerprint))
                .isEqualTo(new MailCommandRepository.DeliveryCommand(
                        result.threadId(), fixture.userId(), composeFingerprint));

        UUID secondCreationKey = UUID.randomUUID();
        MailDtos.ComposeRequest secondRequest = new MailDtos.ComposeRequest(
                "second@example.com", "Second", "Second command", "Second body",
                MailTypes.DeliveryMode.SEND, secondCreationKey);
        String secondFingerprint = new MailSendCommandFingerprint()
                .compose(fixture.userId(), secondRequest);
        MailCommandRepository.ComposeResult second = commands.compose(
                fixture.tenantId(), fixture.userId(), secondRequest, secondFingerprint);
        assertThat(commands.enqueueDelivery(
                fixture.tenantId(), fixture.userId(), second.threadId(),
                idempotencyKey, "corr-bound-drift", secondFingerprint))
                .isEqualTo(new MailCommandRepository.DeliveryCommand(
                        result.threadId(), fixture.userId(), composeFingerprint));

        Actor other = anotherPersonalActor(jdbc, fixture.tenantId(), fixture.userId());
        String otherFingerprint = new MailSendCommandFingerprint()
                .compose(other.userId(), request);
        MailCommandRepository.ComposeResult otherResult = commands.compose(
                other.tenantId(), other.userId(), request, otherFingerprint);
        assertThat(commands.enqueueDelivery(
                other.tenantId(), other.userId(), otherResult.threadId(),
                idempotencyKey, "corr-other-actor", otherFingerprint))
                .isEqualTo(new MailCommandRepository.DeliveryCommand(
                        otherResult.threadId(), other.userId(), otherFingerprint));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_delivery_outbox
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, Integer.class, fixture.tenantId(), idempotencyKey)).isEqualTo(2);

        MailCommandRepository.ComposeResult replay = commands.compose(
                fixture.tenantId(), fixture.userId(), request, composeFingerprint);
        assertThat(replay.created()).isFalse();
        assertThat(replay.requestFingerprint()).isEqualTo(composeFingerprint);

        jdbc.update("""
                UPDATE mail_delivery_outbox SET request_fingerprint = NULL
                 WHERE tenant_id = ? AND created_by = ? AND idempotency_key = ?
                """, fixture.tenantId(), fixture.userId(), idempotencyKey);
        assertThat(commands.deliveryCommand(
                fixture.tenantId(), fixture.userId(), idempotencyKey))
                .isEqualTo(new MailCommandRepository.DeliveryCommand(
                        result.threadId(), fixture.userId(), null));
    }

    @Test
    void concurrentSameActorEnqueueReturnsTheWinningCommand() throws Exception {
        String schema = "mail_delivery_concurrent_idempotency";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailCommandRepository commands = new MailCommandRepository(
                jdbc, new MailJsonCodec(new ObjectMapper().findAndRegisterModules()));
        PersonalFixture fixture = personalFixture(jdbc);
        UUID firstCreateKey = UUID.randomUUID();
        UUID secondCreateKey = UUID.randomUUID();
        UUID sharedDeliveryKey = UUID.randomUUID();
        MailDtos.ComposeRequest firstRequest = new MailDtos.ComposeRequest(
                "first@example.com", "First", "First", "First body",
                MailTypes.DeliveryMode.SEND, firstCreateKey);
        MailDtos.ComposeRequest secondRequest = new MailDtos.ComposeRequest(
                "second@example.com", "Second", "Second", "Second body",
                MailTypes.DeliveryMode.SEND, secondCreateKey);
        MailSendCommandFingerprint fingerprints = new MailSendCommandFingerprint();
        String firstFingerprint = fingerprints.compose(fixture.userId(), firstRequest);
        String secondFingerprint = fingerprints.compose(fixture.userId(), secondRequest);
        MailCommandRepository.ComposeResult first = commands.compose(
                fixture.tenantId(), fixture.userId(), firstRequest, firstFingerprint);
        MailCommandRepository.ComposeResult second = commands.compose(
                fixture.tenantId(), fixture.userId(), secondRequest, secondFingerprint);
        CyclicBarrier start = new CyclicBarrier(2);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var firstResult = workers.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return commands.enqueueDelivery(
                        fixture.tenantId(), fixture.userId(), first.threadId(),
                        sharedDeliveryKey, "corr-first", firstFingerprint);
            });
            var secondResult = workers.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return commands.enqueueDelivery(
                        fixture.tenantId(), fixture.userId(), second.threadId(),
                        sharedDeliveryKey, "corr-second", secondFingerprint);
            });

            MailCommandRepository.DeliveryCommand winner = firstResult.get(10, TimeUnit.SECONDS);
            assertThat(secondResult.get(10, TimeUnit.SECONDS)).isEqualTo(winner);
            assertThat(commands.deliveryCommand(
                    fixture.tenantId(), fixture.userId(), sharedDeliveryKey))
                    .isEqualTo(winner);
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_delivery_outbox
                 WHERE tenant_id = ? AND created_by = ? AND idempotency_key = ?
                """, Integer.class, fixture.tenantId(), fixture.userId(), sharedDeliveryKey))
                .isOne();
    }

    @Test
    void htmlAndAttachmentsSurviveClaimSandboxMirrorAndRecipientAcl() {
        String schema = "mail_delivery_html_attachments";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailJsonCodec json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
        MailWorkspaceRepository workspace = new MailWorkspaceRepository(jdbc, json);
        MailDeliveryRepository deliveries = new MailDeliveryRepository(jdbc, json);
        MailDeliveryCompletionService completion = new MailDeliveryCompletionService(
                deliveries, new MailCommandRepository(jdbc, json),
                new MailInboundMessageService(
                        deliveries, mock(MailNotificationEvents.class)));
        PersonalFixture sender = personalFixture(jdbc);
        Actor recipient = anotherPersonalActor(jdbc, sender.tenantId(), sender.userId());
        AccountAddress senderAccount = sandboxAccount(jdbc, sender.tenantId(), sender.userId());
        AccountAddress recipientAccount = sandboxAccount(
                jdbc, recipient.tenantId(), recipient.userId());
        UUID firstAttachment = UUID.randomUUID();
        UUID secondAttachment = UUID.randomUUID();
        workspace.createAttachment(
                sender.tenantId(), sender.userId(), firstAttachment,
                sender.tenantId() + "/mail/compose/first.txt", "first.txt", "text/plain",
                5, "a".repeat(64), "TEST_READY");
        workspace.createAttachment(
                sender.tenantId(), sender.userId(), secondAttachment,
                sender.tenantId() + "/mail/compose/second.txt", "second.txt", "text/plain",
                6, "b".repeat(64), "TEST_READY");
        UUID idempotencyKey = UUID.randomUUID();
        MailWorkspaceRepository.AdvancedComposeCreated created = workspace.createAdvancedCompose(
                sender.tenantId(), sender.userId(), senderAccount.accountId(),
                List.of(new MailWorkspaceDtos.Recipient(
                        MailWorkspaceDtos.RecipientType.TO,
                        "Recipient", recipientAccount.email())),
                "HTML with attachments", "<p>Preserved body</p>",
                MailWorkspaceDtos.BodyFormat.HTML,
                List.of(firstAttachment, secondAttachment), null,
                idempotencyKey, "f".repeat(64), "corr-html-attachments");
        assertThat(created).isNotNull();

        MailDeliveryRepository.DeliveryJob initiallyClaimed = deliveries
                .claim("html-attachment-test", 500, 30).stream()
                .filter(job -> job.threadId().equals(created.threadId()))
                .findFirst().orElseThrow();
        assertThat(initiallyClaimed.bodyFormat()).isEqualTo("HTML");
        assertThat(initiallyClaimed.body()).isEqualTo("<p>Preserved body</p>");
        assertThat(initiallyClaimed.expectedAttachmentCount()).isEqualTo(2);
        assertThat(initiallyClaimed.attachments())
                .extracting(MailDeliveryRepository.DeliveryAttachment::fileName)
                .containsExactly("first.txt", "second.txt");
        jdbc.update("""
                UPDATE mail_delivery_outbox
                   SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE delivery_id = ?
                """, created.deliveryId());
        MailWorkspaceDtos.DeliveryReceipt unknown = workspace.delivery(
                sender.tenantId(), sender.userId(), created.deliveryId()).orElseThrow();
        assertThat(unknown.state()).isEqualTo("UNKNOWN");
        assertThat(unknown.canReconcile()).isTrue();
        assertThat(workspace.reconcileSandboxDelivery(
                sender.tenantId(), sender.userId(), created.deliveryId(), unknown.version()))
                .isTrue();
        MailWorkspaceDtos.DeliveryReceipt requeued = workspace.delivery(
                sender.tenantId(), sender.userId(), created.deliveryId()).orElseThrow();
        assertThat(requeued.state()).isEqualTo("QUEUED");
        assertThat(requeued.canReconcile()).isFalse();
        MailDeliveryRepository.DeliveryJob claimed = deliveries
                .claim("html-attachment-test", 500, 30).stream()
                .filter(job -> job.threadId().equals(created.threadId()))
                .findFirst().orElseThrow();

        MailConnectorPort.DeliveryReceipt receipt = new MailConnectorPort.DeliveryReceipt(
                "sandbox:message:" + UUID.randomUUID(),
                "sandbox:thread:" + UUID.randomUUID(), java.time.Instant.now());
        completion.complete(
                claimed, "html-attachment-test", receipt,
                MailConnectorPort.SenderMode.ACCOUNT);
        Map<String, Object> mirrored = jdbc.queryForMap("""
                SELECT thread.thread_id, message.message_id, message.body_format,
                       message.body_content, message.attachments::text AS attachments
                  FROM mail_threads thread
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                 WHERE thread.tenant_id = ? AND thread.account_id = ?
                   AND thread.provider_thread_ref = ?
                   AND message.provider_message_ref = ?
                """, sender.tenantId(), recipientAccount.accountId(),
                receipt.providerThreadReference(), receipt.providerMessageReference());
        assertThat(mirrored.get("body_format")).isEqualTo("HTML");
        assertThat(mirrored.get("body_content")).isEqualTo("<p>Preserved body</p>");
        List<Map<String, Object>> mirroredAttachments = json.mapList(
                String.valueOf(mirrored.get("attachments")));
        assertThat(mirroredAttachments)
                .extracting(value -> String.valueOf(value.get("fileName")))
                .containsExactly("first.txt", "second.txt");
        UUID mirroredAttachmentId = UUID.fromString(
                String.valueOf(mirroredAttachments.getFirst().get("attachmentId")));
        UUID mirroredThreadId = (UUID) mirrored.get("thread_id");
        UUID mirroredMessageId = (UUID) mirrored.get("message_id");
        assertThat(workspace.visibleAttachment(
                sender.tenantId(), recipient.userId(), mirroredThreadId,
                mirroredMessageId, mirroredAttachmentId)).isPresent();
        assertThat(workspace.visibleAttachment(
                sender.tenantId(), sender.userId(), mirroredThreadId,
                mirroredMessageId, mirroredAttachmentId)).isEmpty();

        MailWorkspaceDtos.FollowUp followUp = workspace.createFollowUp(
                sender.tenantId(), sender.userId(), created.threadId(),
                new MailWorkspaceDtos.FollowUpRequest(
                        OffsetDateTime.now().plusDays(1), "UTC", "Awaiting reply", null))
                .orElseThrow();
        assertThat(followUp.status()).isEqualTo("WAITING");
        assertThat(followUp.version()).isZero();
        UUID replyMessageId = UUID.randomUUID();
        UUID replyDeliveryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_messages (
                    message_id, tenant_id, thread_id, provider_message_ref,
                    sender_email, sender_name, recipients, message_direction,
                    body_format, body_content, attachments, sent_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'OUTBOUND', 'TEXT', ?,
                        '[]'::jsonb, CURRENT_TIMESTAMP, ?)
                """, replyMessageId, sender.tenantId(), mirroredThreadId,
                "dwp:reply:" + replyDeliveryId, recipientAccount.email(), "Recipient",
                json.write(List.of(Map.of(
                        "type", "TO", "name", "Sender", "email", senderAccount.email()))),
                "Reply received", recipient.userId());
        jdbc.update("""
                INSERT INTO mail_delivery_outbox (
                    delivery_id, tenant_id, thread_id, message_id, idempotency_key,
                    request_fingerprint, delivery_status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """, replyDeliveryId, sender.tenantId(), mirroredThreadId,
                replyMessageId, UUID.randomUUID(), "e".repeat(64), recipient.userId());
        MailDeliveryRepository.DeliveryJob reply = deliveries
                .claim("follow-up-reply-test", 500, 30).stream()
                .filter(job -> job.deliveryId().equals(replyDeliveryId))
                .findFirst().orElseThrow();
        assertThat(reply.replyToProviderMessageReference())
                .isEqualTo(receipt.providerMessageReference());
        MailConnectorPort.DeliveryReceipt replyReceipt = new MailConnectorPort.DeliveryReceipt(
                "sandbox:message:" + UUID.randomUUID(),
                receipt.providerThreadReference(), java.time.Instant.now());
        completion.complete(
                reply, "follow-up-reply-test", replyReceipt,
                MailConnectorPort.SenderMode.ACCOUNT);

        Map<String, Object> tracker = jdbc.queryForMap("""
                SELECT tracker_status, last_checked_at, version
                  FROM mail_follow_up_trackers
                 WHERE tenant_id = ? AND follow_up_id = ?
                """, sender.tenantId(), followUp.followUpId());
        assertThat(tracker.get("tracker_status")).isEqualTo("REPLIED");
        assertThat(java.time.Duration.between(
                replyReceipt.acceptedAt(),
                ((java.sql.Timestamp) tracker.get("last_checked_at")).toInstant()).abs())
                .isLessThan(java.time.Duration.ofMillis(1));
        assertThat(tracker.get("version")).isEqualTo(1L);
    }

    private void assertDenied(
            MailQueryRepository queries,
            MailCommandRepository commands,
            MailLifecycleRepository lifecycle,
            SharedFixture fixture,
            MailDtos.ThreadSummary visible,
            MailLifecycleRepository.LifecycleThread lifecycleVisible) {
        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(queries.messages(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(queries.comments(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(lifecycle.visibleThread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
        assertThat(lifecycle.target(
                fixture.tenantId(), fixture.userId(), fixture.accountId(),
                lifecycleVisible.folderId())).isEmpty();
        assertThat(queries.isActiveSharedInboxMember(
                fixture.tenantId(), fixture.sharedInboxId(), fixture.userId())).isFalse();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                ThreadAction.MARK_READ, visible.version())).isZero();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                ThreadAction.ARCHIVE, visible.version())).isZero();
        assertThat(commands.applyAction(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                ThreadAction.RESTORE, visible.version())).isZero();
        assertThat(commands.snooze(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                OffsetDateTime.now().plusHours(1), visible.version())).isZero();
        assertThat(commands.assign(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                fixture.userId(), "Revoked member", visible.version())).isZero();
        assertThat(commands.insertComment(
                fixture.tenantId(), fixture.userId(), "Revoked member",
                fixture.threadId(), "Must not persist", List.of())).isNull();
        assertThat(commands.insertReply(
                fixture.tenantId(), fixture.userId(), fixture.threadId(),
                "Must not persist", UUID.randomUUID())).isFalse();
    }

    private SharedFixture sharedFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT thread.tenant_id, membership.user_id, thread.thread_id,
                       thread.account_id, thread.shared_inbox_id
                  FROM mail_threads thread
                  JOIN mail_shared_inboxes inbox
                    ON inbox.tenant_id = thread.tenant_id
                   AND inbox.account_id = thread.account_id
                   AND inbox.shared_inbox_id = thread.shared_inbox_id
                  JOIN mail_shared_inbox_members membership
                    ON membership.tenant_id = inbox.tenant_id
                   AND membership.shared_inbox_id = inbox.shared_inbox_id
                   AND membership.lifecycle_state = 'ACTIVE'
                 WHERE inbox.lifecycle_state = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM mail_messages message
                                WHERE message.tenant_id = thread.tenant_id
                                  AND message.thread_id = thread.thread_id)
                   AND EXISTS (SELECT 1 FROM mail_internal_comments comment
                                WHERE comment.tenant_id = thread.tenant_id
                                  AND comment.thread_id = thread.thread_id)
                 ORDER BY thread.thread_id, membership.user_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Shared mail fixture is missing.");
            return new SharedFixture(
                    result.getLong("tenant_id"), result.getLong("user_id"),
                    result.getObject("thread_id", UUID.class),
                    result.getObject("account_id", UUID.class),
                    result.getObject("shared_inbox_id", UUID.class));
        });
    }

    private PersonalFixture personalFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT account.tenant_id, account.owner_user_id, thread.thread_id
                  FROM mail_accounts account
                  JOIN mail_threads thread
                    ON thread.tenant_id = account.tenant_id
                   AND thread.account_id = account.account_id
                  JOIN mail_folders folder
                    ON folder.tenant_id = thread.tenant_id
                   AND folder.account_id = thread.account_id
                   AND folder.folder_id = thread.folder_id
                 WHERE account.account_kind = 'PERSONAL'
                   AND folder.folder_type = 'INBOX'
                 ORDER BY account.tenant_id, account.owner_user_id, thread.thread_id
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Personal mail fixture is missing.");
            long tenantId = result.getLong("tenant_id");
            long userId = result.getLong("owner_user_id");
            java.util.ArrayList<UUID> threadIds = new java.util.ArrayList<>();
            threadIds.add(result.getObject("thread_id", UUID.class));
            while (result.next() && result.getLong("tenant_id") == tenantId
                    && result.getLong("owner_user_id") == userId && threadIds.size() < 2) {
                threadIds.add(result.getObject("thread_id", UUID.class));
            }
            if (threadIds.size() < 2) throw new IllegalStateException("Two personal threads are required.");
            return new PersonalFixture(tenantId, userId, List.copyOf(threadIds));
        });
    }

    private Actor anotherPersonalActor(
            JdbcTemplate jdbc,
            Long tenantId,
            Long excludedUserId) {
        return jdbc.queryForObject("""
                SELECT account.tenant_id, account.owner_user_id
                  FROM mail_accounts account
                 WHERE account.tenant_id = ? AND account.account_kind = 'PERSONAL'
                   AND account.is_default = TRUE
                   AND account.connection_state = 'ACTIVE'
                   AND account.owner_user_id <> ?
                 ORDER BY account.owner_user_id
                 LIMIT 1
                """, (result, ignored) -> new Actor(
                result.getLong("tenant_id"), result.getLong("owner_user_id")),
                tenantId, excludedUserId);
    }

    private AccountAddress sandboxAccount(
            JdbcTemplate jdbc, Long tenantId, Long userId) {
        return jdbc.queryForObject("""
                SELECT account.account_id, account.email_address
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE account.tenant_id = ? AND account.owner_user_id = ?
                   AND account.account_kind = 'PERSONAL'
                   AND account.connection_state = 'ACTIVE'
                   AND connection.connection_state = 'ACTIVE'
                   AND connection.provider_type = 'DWP_SANDBOX'
                 ORDER BY account.is_default DESC, account.account_id
                 LIMIT 1
                """, (result, ignored) -> new AccountAddress(
                        result.getObject("account_id", UUID.class),
                        result.getString("email_address")), tenantId, userId);
    }

    private void migrate(String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("282")
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

    private record SharedFixture(
            Long tenantId, Long userId, UUID threadId, UUID accountId, UUID sharedInboxId) {
    }

    private record PersonalFixture(Long tenantId, Long userId, List<UUID> threadIds) {
    }

    private record Actor(Long tenantId, Long userId) {
    }

    private record AccountAddress(UUID accountId, String email) {
    }
}
