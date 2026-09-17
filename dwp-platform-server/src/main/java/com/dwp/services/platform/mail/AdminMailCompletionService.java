package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

@Service
public class AdminMailCompletionService {

    private static final Duration SOURCE_FRESH = Duration.ofMinutes(5);
    private static final Duration DELIVERY_EVIDENCE_FRESH = Duration.ofMinutes(2);
    private static final int MAX_SYNC_PAGES_PER_ACCOUNT = 20;
    private static final int DELIVERY_EXPORT_ITEM_LIMIT = 10_000;
    private static final Set<String> PURGE_RESOURCE_TYPES = Set.of("THREADS", "MESSAGES");

    private final AdminMailCompletionRepository repository;
    private final MailConnectorRegistry connectorRegistry;
    private final AdminMailCommandFingerprint fingerprints;
    private final ObjectMapper objectMapper;
    private final MailExternalSyncMaterializer syncMaterializer;
    private final AdminMailOperationDurability operationDurability;
    private final AdminMailPurgeTransactions purgeTransactions;

    @Autowired
    public AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability,
            AdminMailPurgeTransactions purgeTransactions) {
        this.repository = repository;
        this.connectorRegistry = connectorRegistry;
        this.fingerprints = new AdminMailCommandFingerprint(objectMapper);
        this.objectMapper = objectMapper;
        this.syncMaterializer = syncMaterializer;
        this.operationDurability = operationDurability;
        this.purgeTransactions = purgeTransactions;
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability) {
        this(repository, connectorRegistry, objectMapper, syncMaterializer,
                operationDurability, null);
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer) {
        this(repository, connectorRegistry, objectMapper, syncMaterializer, null, null);
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper) {
        this(repository, connectorRegistry, objectMapper, null, null, null);
    }

    @Transactional(readOnly = true)
    public AdminOperationsSnapshot operations(long tenantId) {
        requireIdentity(tenantId, 1);
        OffsetDateTime now = now();
        List<AdminSourceEvidence> sources = repository.sourceStamps(tenantId).stream()
                .map(source -> new AdminSourceEvidence(
                        source.sourceId(), sourceState(source.observedAt(), now),
                        source.observedAt(), source.observedAt() == null ? "NO_EVIDENCE" : null))
                .toList();
        List<AdminException> exceptions = repository.exceptions(tenantId, 100).stream()
                .map(row -> new AdminException(
                        row.kind() + ':' + row.resourceRef(), row.kind(), row.severity(),
                        safeResourceReference(row.kind(), row.resourceRef()),
                        row.impactCount(), row.observedAt(),
                        row.correlationId(), row.nextAction()))
                .toList();
        List<AdminCommandAudit> commands = repository.commandAudit(tenantId, 100).stream()
                .map(row -> new AdminCommandAudit(
                        row.auditId(), row.commandType(),
                        safeResourceReference("resource", row.resourceRef()),
                        "Mail administrator", row.result(), row.occurredAt(),
                        nullToEmpty(row.correlationId())))
                .toList();
        return new AdminOperationsSnapshot(now, sources, exceptions, commands);
    }

    @Transactional
    public ConnectionOperation connectionOperation(
            long tenantId, long actorId, UUID connectionId, String operationKind,
            String correlationId, ConnectionOperationRequest request) {
        requireIdentity(tenantId, actorId);
        String kind = requiredEnum(operationKind, Set.of("DIAGNOSTIC", "SYNC", "TEST_SEND"));
        validateConnectionRequest(kind, request);
        String fingerprint = fingerprints.digest(
                "CONNECTION_OPERATION", actorId, connectionId, kind,
                normalized(request.capability()), normalized(request.scope()),
                normalizedEmail(request.recipient()), request.confirmedExternalImpact(),
                request.version());
        var existing = repository.connectionOperation(tenantId, actorId, request.idempotencyKey());
        if (existing.isPresent()) {
            requireFingerprint(existing.get().fingerprint(), fingerprint);
            if (!existing.get().connectionId().equals(connectionId)
                    || !existing.get().kind().equals(kind)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return connectionOperation(existing.get(), true);
        }
        AdminMailCompletionRepository.ConnectionRow connection = repository
                .connection(tenantId, connectionId).orElseThrow(this::notFound);
        if (connection.version() != request.version()) conflict("CONNECTION_VERSION_CONFLICT");

        Map<String, Object> payload = Map.of(
                "capability", nullToEmpty(request.capability()),
                "scope", nullToEmpty(request.scope()),
                "recipient", nullToEmpty(request.recipient()),
                "confirmedExternalImpact", Boolean.TRUE.equals(request.confirmedExternalImpact()),
                "version", request.version());
        if ("TEST_SEND".equals(kind)) {
            return durableTestSend(
                    tenantId, actorId, connectionId, correlationId, request,
                    fingerprint, payload, connection);
        }

        UUID operationId = repository.insertConnectionOperation(
                tenantId, actorId, connectionId, kind, normalized(request.scope()),
                payload,
                request.idempotencyKey(), fingerprint, correlationId).orElse(null);
        if (operationId == null) {
            AdminMailCompletionRepository.ConnectionOperationRow winner = repository
                    .connectionOperation(tenantId, actorId, request.idempotencyKey())
                    .orElseThrow(this::conflict);
            requireFingerprint(winner.fingerprint(), fingerprint);
            if (!winner.connectionId().equals(connectionId) || !winner.kind().equals(kind)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return connectionOperation(winner, true);
        }
        OffsetDateTime evidenceAt = now();
        String state = "SUCCEEDED";
        String errorCode = null;
        try {
            executeConnectionOperation(
                    tenantId, actorId, correlationId, operationId, connection, kind, request);
        } catch (AdminOperationFailure failure) {
            state = failure.unknown ? "UNKNOWN" : "FAILED";
            errorCode = failure.code;
        } catch (RuntimeException failure) {
            state = "UNKNOWN";
            errorCode = "CONNECTOR_RESULT_UNCERTAIN";
        }
        repository.completeConnectionOperation(
                tenantId, operationId, state, errorCode, evidenceAt);
        repository.audit(
                tenantId, actorId, "mail.connection." + kind.toLowerCase(Locale.ROOT),
                "MAIL_CONNECTION", connectionId.toString(), correlationId,
                Map.of("version", request.version()),
                resultEvidence(state, errorCode, operationId));
        return connectionOperation(repository.connectionOperation(
                tenantId, actorId, request.idempotencyKey()).orElseThrow(this::conflict), false);
    }

    private ConnectionOperation durableTestSend(
            long tenantId,
            long actorId,
            UUID connectionId,
            String correlationId,
            ConnectionOperationRequest request,
            String fingerprint,
            Map<String, Object> payload,
            AdminMailCompletionRepository.ConnectionRow connection) {
        if (operationDurability == null) {
            throw new IllegalStateException("TEST_SEND durability is unavailable");
        }
        AdminMailOperationDurability.Claim claim = operationDurability.claimTestSend(
                tenantId, actorId, connectionId, normalized(request.scope()), payload,
                request.idempotencyKey(), fingerprint, correlationId);
        if (!claim.created()) {
            AdminMailCompletionRepository.ConnectionOperationRow winner = claim.existing();
            requireFingerprint(winner.fingerprint(), fingerprint);
            if (!winner.connectionId().equals(connectionId)
                    || !winner.kind().equals("TEST_SEND")) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return connectionOperation(winner, true);
        }

        OffsetDateTime evidenceAt = now();
        String state = "SUCCEEDED";
        String errorCode = null;
        try {
            executeConnectionOperation(
                    tenantId, actorId, correlationId, claim.operationId(),
                    connection, "TEST_SEND", request);
        } catch (AdminOperationFailure failure) {
            state = failure.unknown ? "UNKNOWN" : "FAILED";
            errorCode = failure.code;
        } catch (RuntimeException failure) {
            state = "UNKNOWN";
            errorCode = "CONNECTOR_RESULT_UNCERTAIN";
        }
        AdminMailCompletionRepository.ConnectionOperationRow completed =
                operationDurability.complete(
                        tenantId, actorId, request.idempotencyKey(), claim.operationId(),
                        state, errorCode, evidenceAt);
        repository.audit(
                tenantId, actorId, "mail.connection.test_send",
                "MAIL_CONNECTION", connectionId.toString(), correlationId,
                Map.of("version", request.version()),
                resultEvidence(state, errorCode, claim.operationId()));
        return connectionOperation(completed, false);
    }

    private void executeConnectionOperation(
            long tenantId, long actorId, String correlationId,
            UUID operationId,
            AdminMailCompletionRepository.ConnectionRow connection,
            String kind, ConnectionOperationRequest request) {
        MailTypes.ProviderType provider;
        try {
            provider = MailTypes.ProviderType.valueOf(connection.providerType());
        } catch (IllegalArgumentException invalidProvider) {
            throw failure("PROVIDER_TYPE_UNSUPPORTED");
        }
        MailConnectorPort connector = connectorRegistry.connector(provider)
                .orElseThrow(() -> failure("CONNECTOR_ADAPTER_UNAVAILABLE"));
        MailConnectorPort.ConnectionContext context;
        try {
            context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(
                            Long.toString(tenantId), Long.toString(actorId),
                            Set.of("ADMIN.MAIL:MANAGE"), correlation(correlationId)),
                    connection.id(), connection.secretReference(), connection.mailDomain());
        } catch (RuntimeException invalidSecretReference) {
            throw failure("CREDENTIAL_REFERENCE_INVALID");
        }

        if ("DIAGNOSTIC".equals(kind)) {
            MailConnectorPort.Readiness readiness = connector.readiness(context);
            if (readiness.state() != MailConnectorPort.ReadinessState.READY) {
                throw failure(readiness.errorCode() == null
                        ? "CONNECTION_NOT_READY_" + readiness.state().name()
                        : readiness.errorCode());
            }
            return;
        }

        List<AdminMailCompletionRepository.AccountRow> accounts =
                repository.connectionAccounts(tenantId, connection.id());
        if (accounts.isEmpty()) throw failure("NO_ACTIVE_ACCOUNT");
        if ("SYNC".equals(kind)) {
            if (syncMaterializer == null) {
                throw failure("SYNC_MATERIALIZER_UNAVAILABLE");
            }
            for (var account : accounts) {
                AdminMailCompletionRepository.AccountRow pageAccount = account;
                boolean complete = false;
                for (int page = 0; page < MAX_SYNC_PAGES_PER_ACCOUNT; page++) {
                    MailConnectorPort.SyncBatch batch = connector.synchronize(
                            new MailConnectorPort.SyncRequest(
                                    context, pageAccount.providerAccountRef(),
                                    pageAccount.cursor(), 500));
                    try {
                        syncMaterializer.materialize(
                                tenantId, actorId, pageAccount, batch);
                    } catch (MailExternalSyncMaterializer.SyncFailure syncFailure) {
                        throw failure(syncFailure.code());
                    }
                    if (!batch.partial()) {
                        complete = true;
                        break;
                    }
                    if (Objects.equals(pageAccount.cursor(), batch.nextCursor())) {
                        throw failure("SYNC_CURSOR_DID_NOT_ADVANCE");
                    }
                    pageAccount = new AdminMailCompletionRepository.AccountRow(
                            pageAccount.id(), pageAccount.email(),
                            pageAccount.providerAccountRef(), batch.nextCursor());
                }
                if (!complete) {
                    throw failure("SYNC_PAGE_LIMIT_REACHED");
                }
            }
            if (repository.markConnectionSynchronized(
                    tenantId, connection.id(), connection.version(), actorId) != 1) {
                conflict("CONNECTION_VERSION_CONFLICT");
            }
            return;
        }

        if (!Boolean.TRUE.equals(request.confirmedExternalImpact())) {
            throw badRequest("TEST_SEND_REQUIRES_EXTERNAL_IMPACT_CONFIRMATION");
        }
        if (request.recipient() == null || request.recipient().isBlank()) {
            throw badRequest("TEST_SEND_RECIPIENT_REQUIRED");
        }
        AdminMailCompletionRepository.AccountRow account = accounts.getFirst();
        connector.send(new MailConnectorPort.SendRequest(
                context, account.providerAccountRef(), operationId,
                List.of(request.recipient().trim()), "DWP Mail connection test",
                "This test message verifies the configured DWP Mail connection.", null));
    }

    @Transactional(readOnly = true)
    public SharedInboxAccess sharedInboxAccess(long tenantId, UUID inboxId) {
        AdminMailCompletionRepository.SharedInboxRow inbox = repository.sharedInbox(tenantId, inboxId)
                .orElseThrow(this::notFound);
        List<SharedInboxAccessMember> members = repository.accessGrants(tenantId, inboxId).stream()
                .map(this::accessMember).toList();
        AdminMailCompletionRepository.ImpactRow impact =
                repository.accessImpact(tenantId, inboxId, null);
        return new SharedInboxAccess(
                inbox.id(), inbox.version(), providerState(members), members,
                impact(impact, !"DWP_SANDBOX".equals(inbox.providerType())));
    }

    @Transactional
    public SharedInboxAccess addSharedInboxMember(
            long tenantId, long actorId, UUID inboxId, String correlationId,
            SharedInboxMemberRequest request) {
        requireIdentity(tenantId, actorId);
        validatePermissions(request.permissions());
        String fingerprint = memberFingerprint(actorId, inboxId, null, "ADD", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "SHARED_MEMBER_ADD", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) {
            replayMember(tenantId, inboxId, replay, "SHARED_MEMBER");
            return sharedInboxAccess(tenantId, inboxId);
        }
        AdminMailCompletionRepository.SharedInboxRow inbox = repository.sharedInbox(tenantId, inboxId)
                .orElseThrow(this::notFound);
        if (inbox.version() != request.version()) conflict("SHARED_INBOX_VERSION_CONFLICT");
        if (repository.accessGrantByUser(tenantId, inboxId, request.userId()).isPresent()) {
            conflict("SHARED_MEMBER_ALREADY_EXISTS");
        }
        if (repository.bumpSharedInbox(tenantId, inboxId, request.version(), actorId) != 1) {
            conflict("SHARED_INBOX_VERSION_CONFLICT");
        }
        String providerState = providerMutationState(inbox.providerType());
        UUID memberId = repository.insertAccessGrant(
                tenantId, inboxId, request.userId(), displayName(request),
                trimToNull(request.department()), request.expiresAt(),
                request.permissions().read(), request.permissions().sendAs(),
                request.permissions().sendOnBehalf(), request.permissions().assign(),
                request.permissions().manage(), providerState, actorId);
        repository.upsertLegacyMember(
                tenantId, inboxId, inbox.accountId(), request.userId(),
                request.permissions().manage(), actorId);
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "SHARED_MEMBER", memberId);
        repository.audit(
                tenantId, actorId, "mail.shared.member.added", "MAIL_SHARED_INBOX",
                inboxId.toString(), correlationId, Map.of(),
                Map.of("memberId", memberId, "userId", request.userId(),
                        "providerState", providerState));
        repository.accessGrant(tenantId, inboxId, memberId).orElseThrow(this::conflict);
        return sharedInboxAccess(tenantId, inboxId);
    }

    @Transactional
    public SharedInboxAccess updateSharedInboxMember(
            long tenantId, long actorId, UUID inboxId, UUID memberId,
            String correlationId, SharedInboxMemberRequest request) {
        requireIdentity(tenantId, actorId);
        validatePermissions(request.permissions());
        String fingerprint = memberFingerprint(actorId, inboxId, memberId, "UPDATE", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "SHARED_MEMBER_UPDATE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) {
            replayMember(tenantId, inboxId, replay, "SHARED_MEMBER");
            return sharedInboxAccess(tenantId, inboxId);
        }
        AdminMailCompletionRepository.SharedInboxRow inbox = repository.sharedInbox(tenantId, inboxId)
                .orElseThrow(this::notFound);
        AdminMailCompletionRepository.AccessGrantRow before = repository
                .accessGrant(tenantId, inboxId, memberId).orElseThrow(this::notFound);
        if (before.userId() != request.userId()) badRequest("MEMBER_IDENTITY_IMMUTABLE");
        String providerState = providerMutationState(inbox.providerType());
        if (repository.updateAccessGrant(
                tenantId, inboxId, memberId, displayName(request),
                trimToNull(request.department()), request.expiresAt(),
                request.permissions().read(), request.permissions().sendAs(),
                request.permissions().sendOnBehalf(), request.permissions().assign(),
                request.permissions().manage(), providerState, request.version(), actorId) != 1) {
            conflict("SHARED_MEMBER_VERSION_CONFLICT");
        }
        repository.upsertLegacyMember(
                tenantId, inboxId, inbox.accountId(), request.userId(),
                request.permissions().manage(), actorId);
        if (repository.bumpSharedInbox(tenantId, inboxId, inbox.version(), actorId) != 1) {
            conflict("SHARED_INBOX_VERSION_CONFLICT");
        }
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "SHARED_MEMBER", memberId);
        repository.audit(
                tenantId, actorId, "mail.shared.member.updated", "MAIL_SHARED_INBOX_MEMBER",
                memberId.toString(), correlationId,
                Map.of("version", before.version()),
                Map.of("version", before.version() + 1, "providerState", providerState));
        repository.accessGrant(tenantId, inboxId, memberId).orElseThrow(this::conflict);
        return sharedInboxAccess(tenantId, inboxId);
    }

    @Transactional
    public SharedInboxAccess revokeSharedInboxMember(
            long tenantId, long actorId, UUID inboxId, UUID memberId,
            String correlationId, SharedInboxMemberRevokeRequest request) {
        requireIdentity(tenantId, actorId);
        String fingerprint = memberFingerprint(actorId, inboxId, memberId, "REVOKE", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "SHARED_MEMBER_REVOKE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) {
            replayMember(tenantId, inboxId, replay, "SHARED_MEMBER");
            return sharedInboxAccess(tenantId, inboxId);
        }
        AdminMailCompletionRepository.SharedInboxRow inbox = repository.sharedInbox(tenantId, inboxId)
                .orElseThrow(this::notFound);
        AdminMailCompletionRepository.AccessGrantRow before = repository
                .accessGrant(tenantId, inboxId, memberId).orElseThrow(this::notFound);
        AdminMailCompletionRepository.ImpactRow impact =
                repository.accessImpact(tenantId, inboxId, before.userId());
        if (impact.hasImpact() && !request.impactAcknowledged()) {
            conflict("REVOKE_IMPACT_ACKNOWLEDGEMENT_REQUIRED");
        }
        String providerState = providerMutationState(inbox.providerType());
        if (repository.revokeAccessGrant(
                tenantId, inboxId, memberId, request.version(), providerState, actorId) != 1) {
            conflict("SHARED_MEMBER_VERSION_CONFLICT");
        }
        repository.retireLegacyMember(tenantId, inboxId, before.userId(), actorId);
        if (repository.bumpSharedInbox(tenantId, inboxId, inbox.version(), actorId) != 1) {
            conflict("SHARED_INBOX_VERSION_CONFLICT");
        }
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "SHARED_MEMBER", memberId);
        repository.audit(
                tenantId, actorId, "mail.shared.member.revoked", "MAIL_SHARED_INBOX_MEMBER",
                memberId.toString(), correlationId,
                Map.of("version", before.version()),
                Map.of("version", before.version() + 1, "providerState", providerState,
                        "activeAssignments", impact.activeAssignments(),
                        "openDrafts", impact.openDrafts(),
                        "pendingCommands", impact.pendingCommands()));
        repository.accessGrant(tenantId, inboxId, memberId).orElseThrow(this::conflict);
        return sharedInboxAccess(tenantId, inboxId);
    }

    @Transactional(readOnly = true)
    public PolicyGovernance policyGovernance(long tenantId) {
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        List<PolicyEvidenceRow> rows = List.of(
                policyRow("externalSenderBanner", policy.externalBanner(), "UNKNOWN",
                        "UNVERIFIED", "CLIENT_RENDERER_NOT_ATTESTED", policy.updatedAt()),
                policyRow("blockRemoteImages", policy.blockRemoteImages(), policy.blockRemoteImages(),
                        "ENFORCED", "MailWorkspaceRepository", policy.updatedAt()),
                policyRow("allowSharedInboxes", policy.allowSharedInboxes(), policy.allowSharedInboxes(),
                        "ENFORCED", "MailAccessSql", policy.updatedAt()),
                policyRow("aiAssistanceEnabled", policy.aiAssistance(), policy.aiAssistance(),
                        "ENFORCED", "MailService", policy.updatedAt()),
                policyRow("aiCrossAppActionsEnabled", policy.aiCrossAppActions(),
                        policy.aiCrossAppActions(), "ENFORCED", "MailService", policy.updatedAt()),
                policyRow("aiAutoExecuteEnabled", policy.aiAutoExecute(), false,
                        "ENFORCED", "DATABASE_CONSTRAINT", policy.updatedAt()),
                policyRow("retentionDays", policy.retentionDays(), policy.retentionDays(),
                        "ENFORCED", "AdminMailCompletionService", policy.updatedAt()),
                policyRow("maximumAttachmentMb", policy.maximumAttachmentMb(),
                        policy.maximumAttachmentMb(), "ENFORCED",
                        "MailWorkspaceService", policy.updatedAt()));
        List<PolicyHistory> history = repository.policyHistory(tenantId, 100).stream()
                .map(row -> new PolicyHistory(
                        row.id(), row.version(), "Mail administrator", row.changedAt(),
                        row.diff().toString(), row.result(), nullToEmpty(row.correlationId())))
                .toList();
        return new PolicyGovernance(now(), policy.version(), rows, history);
    }

    @Transactional(readOnly = true)
    public RetentionSnapshot retention(long tenantId) {
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        List<ResourceRetentionPolicy> policies = List.of(
                new ResourceRetentionPolicy(
                        "THREADS", policy.retentionDays(), policy.retentionDays(),
                        "mail_tenant_policies.retention_days", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "MESSAGES", policy.retentionDays(), policy.retentionDays(),
                        "mail_tenant_policies.retention_days", "VERIFIED"));
        return new RetentionSnapshot(
                now(), policy.version(), policies,
                repository.legalHolds(tenantId).stream().map(this::legalHold).toList(),
                purgeJobs(tenantId));
    }

    @Transactional
    public LegalHold createLegalHold(
            long tenantId, long actorId, String correlationId, LegalHoldRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        validateLegalHold(request, false);
        String fingerprint = legalHoldFingerprint(actorId, null, "CREATE", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "LEGAL_HOLD_CREATE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) return replayHold(tenantId, replay);
        UUID id = repository.insertLegalHold(
                tenantId, request.name().trim(), request.safeCaseRef().trim(), request.scope(),
                request.startsAt(), request.expiresAt(), actorId);
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "LEGAL_HOLD", id);
        repository.audit(
                tenantId, actorId, "mail.legal.hold.created", "MAIL_LEGAL_HOLD",
                id.toString(), correlationId, Map.of(), Map.of("version", 0));
        return legalHold(repository.legalHold(tenantId, id).orElseThrow(this::conflict));
    }

    @Transactional
    public LegalHold updateLegalHold(
            long tenantId, long actorId, UUID holdId, String correlationId,
            LegalHoldRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        validateLegalHold(request, true);
        String fingerprint = legalHoldFingerprint(actorId, holdId, "UPDATE", request);
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "LEGAL_HOLD_UPDATE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) return replayHold(tenantId, replay);
        if (repository.updateLegalHold(
                tenantId, holdId, request.name().trim(), request.safeCaseRef().trim(),
                request.scope(), request.startsAt(), request.expiresAt(),
                request.version(), actorId) != 1) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "LEGAL_HOLD", holdId);
        repository.audit(
                tenantId, actorId, "mail.legal.hold.updated", "MAIL_LEGAL_HOLD",
                holdId.toString(), correlationId, Map.of("version", request.version()),
                Map.of("version", request.version() + 1));
        return legalHold(repository.legalHold(tenantId, holdId).orElseThrow(this::conflict));
    }

    @Transactional
    public LegalHold releaseLegalHold(
            long tenantId, long actorId, UUID holdId, String correlationId,
            LegalHoldReleaseRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        String fingerprint = fingerprints.digest(
                "LEGAL_HOLD", "RELEASE", actorId, holdId, request.version());
        AdminMailCompletionRepository.AdminReceiptRow replay = claimOrReplay(
                tenantId, actorId, "LEGAL_HOLD_RELEASE", request.idempotencyKey(),
                fingerprint, correlationId);
        if (replay != null) return replayHold(tenantId, replay);
        if (repository.releaseLegalHold(
                tenantId, holdId, request.version(), actorId) != 1) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        repository.completeAdminReceipt(
                tenantId, actorId, request.idempotencyKey(), "LEGAL_HOLD", holdId);
        repository.audit(
                tenantId, actorId, "mail.legal.hold.released", "MAIL_LEGAL_HOLD",
                holdId.toString(), correlationId, Map.of("version", request.version()),
                Map.of("version", request.version() + 1, "status", "RELEASED"));
        return legalHold(repository.legalHold(tenantId, holdId).orElseThrow(this::conflict));
    }

    @Transactional
    public PurgePreview previewPurge(
            long tenantId, long actorId, PurgePreviewRequest request) {
        requireIdentity(tenantId, actorId);
        validatePurgeRequest(request);
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) conflict("POLICY_VERSION_CONFLICT");
        OffsetDateTime effectiveBefore = retentionBoundary(request.before(), policy.retentionDays());
        var existing = repository.purgePreviewByCommand(
                tenantId, actorId, request.idempotencyKey());
        String commandFingerprint = fingerprints.digest(
                "PURGE_PREVIEW", actorId, request.scope(), normalizedResources(request.resourceTypes()),
                effectiveBefore, request.policyVersion());
        if (existing.isPresent()) {
            String existingCommand = fingerprints.digest(
                    "PURGE_PREVIEW", actorId, existing.get().scope(),
                    normalizedResources(existing.get().resourceTypes()), existing.get().before(),
                    existing.get().policyVersion());
            requireFingerprint(existingCommand, commandFingerprint);
            return purgePreview(existing.get());
        }
        AdminMailCompletionRepository.CandidateSet candidates =
                repository.purgeCandidates(tenantId, effectiveBefore);
        int activeHolds = repository.activeHoldCount(tenantId);
        int evidenceBlocked = requestedBlockedCount(candidates, request.resourceTypes());
        int eligibleCandidates = requestedCandidateCount(candidates, request.resourceTypes());
        int total = evidenceBlocked + eligibleCandidates;
        int held = activeHolds > 0 ? total : evidenceBlocked;
        int eligible = activeHolds > 0 ? 0 : eligibleCandidates;
        List<String> partial = candidates.hasExternalProvider()
                ? List.of("EXTERNAL_PROVIDER_DELETE_UNAVAILABLE") : List.of();
        String snapshotFingerprint = purgeFingerprint(
                tenantId, request.scope(), request.resourceTypes(), effectiveBefore,
                policy.version(), activeHolds, candidates);
        UUID snapshotId = repository.insertPurgePreview(
                tenantId, actorId, request.scope(), normalizedResources(request.resourceTypes()),
                effectiveBefore, snapshotFingerprint, total, held, eligible, partial,
                policy.version(), request.idempotencyKey(), now().plusMinutes(15));
        return purgePreview(repository.purgePreview(tenantId, snapshotId)
                .orElseThrow(this::conflict));
    }

    @Transactional
    public PurgeApproval approvePurge(
            long tenantId, long actorId, UUID snapshotId, PurgeApprovalRequest request) {
        requireIdentity(tenantId, actorId);
        if (!"APPROVE".equalsIgnoreCase(request.decision())) {
            badRequest("PURGE_APPROVAL_DECISION_INVALID");
        }
        AdminMailCompletionRepository.PurgePreviewRow preview = repository
                .purgePreview(tenantId, snapshotId).orElseThrow(this::notFound);
        requireLivePreview(preview, request.policyVersion());
        var replay = repository.purgeApprovalByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().snapshotId().equals(snapshotId)
                    || replay.get().policyVersion() != request.policyVersion()) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return purgeApproval(tenantId, replay.get());
        }
        UUID approvalId;
        try {
            approvalId = repository.insertPurgeApproval(
                    tenantId, snapshotId, actorId, request.policyVersion(),
                    request.idempotencyKey());
        } catch (DataIntegrityViolationException duplicateApprover) {
            conflict("PURGE_APPROVER_ALREADY_RECORDED");
            return null;
        }
        var approval = new AdminMailCompletionRepository.PurgeApprovalRow(
                approvalId, snapshotId, actorId, request.policyVersion(), now());
        repository.audit(
                tenantId, actorId, "mail.purge.approved", "MAIL_PURGE_PREVIEW",
                snapshotId.toString(), null, Map.of(),
                Map.of("approvalId", approvalId, "policyVersion", request.policyVersion()));
        return purgeApproval(tenantId, approval);
    }

    @Transactional
    public PurgeJob executePurge(
            long tenantId, long actorId, UUID snapshotId, String correlationId,
            PurgeExecuteRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        AdminMailCompletionRepository.PurgePreviewRow preview = repository
                .purgePreview(tenantId, snapshotId).orElseThrow(this::notFound);
        var replay = repository.purgeJobByCommand(tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().snapshotId().equals(snapshotId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            if (preview.policyVersion() != request.policyVersion()) {
                conflict("IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            requireFingerprint(
                    preview.fingerprint(), request.fingerprint().toLowerCase(Locale.ROOT));
            return purgeJob(replay.get());
        }
        if (repository.purgeJobBySnapshot(tenantId, snapshotId).isPresent()) {
            conflict("PURGE_SNAPSHOT_ALREADY_EXECUTED");
        }
        requireLivePreview(preview, request.policyVersion());
        requireFingerprint(preview.fingerprint(), request.fingerprint().toLowerCase(Locale.ROOT));
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) conflict("POLICY_VERSION_CONFLICT");
        int activeHolds = repository.activeHoldCount(tenantId);
        if (activeHolds > 0) conflict("ACTIVE_LEGAL_HOLD_BLOCKS_PURGE");
        if (!preview.partialSources().isEmpty()) conflict("PURGE_SOURCE_COVERAGE_INCOMPLETE");
        if (repository.distinctApprovals(tenantId, snapshotId, request.policyVersion()) < 2) {
            conflict("TWO_DISTINCT_APPROVERS_REQUIRED");
        }
        AdminMailCompletionRepository.CandidateSet candidates =
                repository.purgeCandidates(tenantId, preview.before());
        String currentFingerprint = purgeFingerprint(
                tenantId, preview.scope(), preview.resourceTypes(), preview.before(),
                policy.version(), activeHolds, candidates);
        requireFingerprint(preview.fingerprint(), currentFingerprint);
        if (requestedCandidateCount(candidates, preview.resourceTypes()) != preview.eligible()
                || requestedBlockedCount(candidates, preview.resourceTypes()) != preview.held()) {
            conflict("PURGE_CANDIDATE_SET_CHANGED");
        }
        UUID jobId = repository.insertPurgeJob(
                tenantId, snapshotId, actorId, request.idempotencyKey());
        AdminMailCompletionRepository.DeleteCounts counts;
        try {
            counts = purgeTransactions == null
                    ? repository.deletePurgeCandidates(tenantId, preview.before())
                    : purgeTransactions.deleteCandidates(tenantId, preview.before());
        } catch (RuntimeException deletionFailure) {
            List<Map<String, Object>> failedSteps = List.of(
                    Map.of("step", "REVALIDATE_POLICY", "state", "SUCCEEDED",
                            "policyVersion", policy.version()),
                    Map.of("step", "REVALIDATE_LEGAL_HOLDS", "state", "SUCCEEDED",
                            "activeHoldCount", activeHolds),
                    Map.of("step", "PRESERVE_IMMUTABLE_EVIDENCE", "state", "SUCCEEDED",
                            "blockedThreads", candidates.blockedThreadCount(),
                            "blockedMessages", candidates.blockedMessageCount()),
                    Map.of("step", "DELETE_LOCAL_THREADS", "state", "FAILED",
                            "errorCode", "LOCAL_DELETE_FAILED"),
                    Map.of("step", "VERIFY_LOCAL_ABSENCE", "state", "UNKNOWN"));
            repository.completePurgeJob(
                    tenantId, jobId, "FAILED",
                    new AdminMailCompletionRepository.DeleteCounts(0, 0),
                    failedSteps, "UNKNOWN", "LOCAL_DELETE_FAILED");
            return purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::conflict));
        }
        long remaining = repository.remainingPurgeCandidates(tenantId, preview.before());
        String state = remaining == 0 ? "SUCCEEDED" : "FAILED";
        String verification = remaining == 0 ? "VERIFIED" : "FAILED";
        String errorCode = remaining == 0 ? null : "PURGE_VERIFICATION_FAILED";
        List<Map<String, Object>> steps = List.of(
                Map.of("step", "REVALIDATE_POLICY", "state", "SUCCEEDED",
                        "policyVersion", policy.version()),
                Map.of("step", "REVALIDATE_LEGAL_HOLDS", "state", "SUCCEEDED",
                        "activeHoldCount", activeHolds),
                Map.of("step", "PRESERVE_IMMUTABLE_EVIDENCE", "state", "SUCCEEDED",
                        "blockedThreads", candidates.blockedThreadCount(),
                        "blockedMessages", candidates.blockedMessageCount()),
                Map.of("step", "DELETE_LOCAL_THREADS", "state", state,
                        "deletedThreads", counts.threads(),
                        "deletedMessages", counts.messages()),
                Map.of("step", "VERIFY_LOCAL_ABSENCE", "state", verification,
                        "remainingThreads", remaining));
        repository.completePurgeJob(
                tenantId, jobId, state, counts, steps, verification, errorCode);
        repository.audit(
                tenantId, actorId, "mail.purge.executed", "MAIL_PURGE_JOB",
                jobId.toString(), correlationId,
                Map.of("candidateSnapshotId", snapshotId, "fingerprint", preview.fingerprint()),
                Map.of("result", state, "deletedThreads", counts.threads(),
                        "deletedMessages", counts.messages(), "remaining", remaining,
                        "preservedThreads", candidates.blockedThreadCount(),
                        "preservedMessages", candidates.blockedMessageCount()));
        return purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public PurgeJob purgeJob(long tenantId, UUID jobId) {
        return purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        List<DeliveryAuditItem> items = repository.deliveries(
                tenantId, state, query, safeSize, safePage * safeSize).stream()
                .map(row -> deliveryItem(tenantId, row)).toList();
        return new DeliveryAuditPage(
                items, repository.deliveryCount(tenantId, state, query),
                safePage, safeSize, now());
    }

    @Transactional
    public DeliveryAuditItem recoverDelivery(
            long tenantId, long actorId, UUID deliveryId, String actionKind,
            String correlationId, DeliveryRecoveryRequest request) {
        requireIdentity(tenantId, actorId);
        String action = requiredEnum(actionKind, Set.of("RECONCILE", "RETRY", "CANCEL"));
        String fingerprint = fingerprints.digest(
                "DELIVERY_RECOVERY", actorId, deliveryId, action, request.version());
        var replay = repository.recoveryCommand(tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().fingerprint(), fingerprint);
            if (!replay.get().deliveryId().equals(deliveryId)
                    || !replay.get().action().equals(action)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return deliveryItem(tenantId, repository.delivery(tenantId, deliveryId)
                    .orElseThrow(this::notFound));
        }
        AdminMailCompletionRepository.DeliveryRow before = repository
                .delivery(tenantId, deliveryId).orElseThrow(this::notFound);
        if (before.version() != request.version()) conflict("DELIVERY_VERSION_CONFLICT");
        if (!fresh(before.updatedAt(), now(), DELIVERY_EVIDENCE_FRESH)) {
            conflict("STALE_DELIVERY_EVIDENCE");
        }

        int changed;
        if ("RETRY".equals(action)) {
            if (!retryEligible(before)) {
                conflict("RETRY_EVIDENCE_INSUFFICIENT");
            }
            changed = repository.retryDelivery(tenantId, deliveryId, request.version());
        } else if ("CANCEL".equals(action)) {
            if (!cancelEligible(before)) {
                conflict("CANCEL_EVIDENCE_INSUFFICIENT");
            }
            changed = repository.cancelDelivery(tenantId, deliveryId, request.version());
        } else {
            if (!reconcileEligible(before)) {
                conflict("RECONCILIATION_EVIDENCE_INSUFFICIENT");
            }
            changed = repository.failExpiredSandboxLease(
                    tenantId, deliveryId, request.version());
        }
        if (changed != 1) conflict("DELIVERY_VERSION_CONFLICT");
        AdminMailCompletionRepository.DeliveryRow after = repository
                .delivery(tenantId, deliveryId).orElseThrow(this::conflict);
        repository.insertRecoveryEvent(
                tenantId, deliveryId, actorId, action, "SUCCEEDED",
                recoveryEvidence(before, after, null), request.idempotencyKey(),
                fingerprint, correlationId);
        repository.audit(
                tenantId, actorId, "mail.delivery." + action.toLowerCase(Locale.ROOT),
                "MAIL_DELIVERY", deliveryId.toString(), correlationId,
                Map.of("state", before.status(), "version", before.version()),
                Map.of("state", after.status(), "version", after.version(),
                        "result", "SUCCEEDED"));
        return deliveryItem(tenantId, after);
    }

    @Transactional
    public DeliveryExport createDeliveryExport(
            long tenantId, long actorId, DeliveryExportRequest request) {
        requireIdentity(tenantId, actorId);
        String fingerprint = fingerprints.digest(
                "DELIVERY_EXPORT", actorId, request.filters(), request.purpose());
        var existing = repository.exportByCommand(tenantId, actorId, request.idempotencyKey());
        if (existing.isPresent()) {
            String existingFingerprint = fingerprints.digest(
                    "DELIVERY_EXPORT", actorId, existing.get().filters(), existing.get().purpose());
            requireFingerprint(existingFingerprint, fingerprint);
            return deliveryExport(existing.get());
        }
        OffsetDateTime snapshotCutoff = now();
        OffsetDateTime expiresAt = snapshotCutoff.plusHours(24);
        String watermark = "DWP MAIL AUDIT • tenant " + tenantId + " • user " + actorId
                + " • " + snapshotCutoff;
        UUID exportId = UUID.randomUUID();
        String state = request.filters().get("state") instanceof String value ? value : "";
        String query = request.filters().get("query") instanceof String value ? value : "";
        List<AdminMailCompletionRepository.DeliveryRow> candidates = repository.deliveries(
                tenantId, state, query, DELIVERY_EXPORT_ITEM_LIMIT + 1, 0);
        boolean truncated = candidates.size() > DELIVERY_EXPORT_ITEM_LIMIT;
        List<DeliveryAuditItem> items = candidates.stream()
                .limit(DELIVERY_EXPORT_ITEM_LIMIT)
                .map(row -> deliveryItem(tenantId, row))
                .toList();
        String payload = deliveryExportPayload(
                exportId, snapshotCutoff, expiresAt, watermark,
                request.purpose().trim(), request.filters(), items, truncated);
        String payloadSha256 = sha256(payload);
        UUID inserted = repository.insertExport(
                exportId, tenantId, actorId, request.filters(), request.purpose().trim(),
                watermark, request.idempotencyKey(), expiresAt, payload,
                payloadSha256, items.size(), truncated, snapshotCutoff).orElse(null);
        if (inserted == null) {
            AdminMailCompletionRepository.ExportRow winner = repository.exportByCommand(
                    tenantId, actorId, request.idempotencyKey()).orElseThrow(this::conflict);
            String existingFingerprint = fingerprints.digest(
                    "DELIVERY_EXPORT", actorId, winner.filters(), winner.purpose());
            requireFingerprint(existingFingerprint, fingerprint);
            return deliveryExport(winner);
        }
        return deliveryExport(repository.export(
                tenantId, actorId, inserted).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public String deliveryExportJson(long tenantId, long actorId, UUID exportId) {
        requireIdentity(tenantId, actorId);
        AdminMailCompletionRepository.ExportRow export = repository.export(
                        tenantId, actorId, exportId)
                .orElseThrow(this::notFound);
        if (export.expiresAt().isBefore(now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "DELIVERY_EXPORT_EXPIRED");
        }
        if (!"READY".equals(export.state())
                || export.snapshotPayload() == null
                || export.payloadSha256() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "DELIVERY_EXPORT_SNAPSHOT_UNAVAILABLE");
        }
        if (!MessageDigest.isEqual(
                export.payloadSha256().getBytes(StandardCharsets.US_ASCII),
                sha256(export.snapshotPayload()).getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "DELIVERY_EXPORT_INTEGRITY_CHECK_FAILED");
        }
        return export.snapshotPayload();
    }

    private String deliveryExportPayload(
            UUID exportId,
            OffsetDateTime snapshotCutoff,
            OffsetDateTime expiresAt,
            String watermark,
            String purpose,
            Map<String, Object> filters,
            List<DeliveryAuditItem> items,
            boolean truncated) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("exportId", exportId);
        payload.put("generatedAt", snapshotCutoff);
        payload.put("snapshotCutoff", snapshotCutoff);
        payload.put("expiresAt", expiresAt);
        payload.put("watermark", watermark);
        payload.put("purpose", purpose);
        payload.put("filters", filters);
        payload.put("itemCount", items.size());
        payload.put("truncated", truncated);
        payload.put("itemLimit", DELIVERY_EXPORT_ITEM_LIMIT);
        payload.put("items", items);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception serializationFailure) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY_EXPORT_SERIALIZATION_FAILED");
        }
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void validateConnectionRequest(String kind, ConnectionOperationRequest request) {
        if ("TEST_SEND".equals(kind)) {
            if (!Boolean.TRUE.equals(request.confirmedExternalImpact())) {
                badRequest("TEST_SEND_REQUIRES_EXTERNAL_IMPACT_CONFIRMATION");
            }
            if (request.recipient() == null || request.recipient().isBlank()) {
                badRequest("TEST_SEND_RECIPIENT_REQUIRED");
            }
        }
    }

    private void validatePermissions(AccessPermissions permissions) {
        if (!(permissions.read() || permissions.sendAs() || permissions.sendOnBehalf()
                || permissions.assign() || permissions.manage())) {
            badRequest("AT_LEAST_ONE_PERMISSION_REQUIRED");
        }
        if (permissions.manage() && (!permissions.read() || !permissions.assign())) {
            badRequest("MANAGE_REQUIRES_READ_AND_ASSIGN");
        }
    }

    private void validateLegalHold(LegalHoldRequest request, boolean versionRequired) {
        if (request.scope().isEmpty()) badRequest("LEGAL_HOLD_SCOPE_REQUIRED");
        if (request.expiresAt() != null && !request.expiresAt().isAfter(request.startsAt())) {
            badRequest("LEGAL_HOLD_WINDOW_INVALID");
        }
        if (versionRequired && request.version() == null) {
            badRequest("LEGAL_HOLD_VERSION_REQUIRED");
        }
    }

    private void validatePurgeRequest(PurgePreviewRequest request) {
        Set<String> normalized = Set.copyOf(normalizedResources(request.resourceTypes()));
        if (!normalized.equals(PURGE_RESOURCE_TYPES)) {
            badRequest("PURGE_RESOURCE_TYPE_UNSUPPORTED");
        }
        if (!Map.of("tenant", true).equals(request.scope())) {
            badRequest("PURGE_SCOPE_UNSUPPORTED");
        }
        if (request.before().isAfter(now())) badRequest("PURGE_BEFORE_MUST_NOT_BE_FUTURE");
    }

    private AdminMailCompletionRepository.AdminReceiptRow claimOrReplay(
            long tenantId, long actorId, String kind, UUID key,
            String fingerprint, String correlationId) {
        var existing = repository.adminReceipt(tenantId, actorId, key);
        if (existing.isPresent()) {
            requireAdminReceipt(existing.get(), kind, fingerprint);
            return existing.get();
        }
        if (repository.claimAdminReceipt(
                tenantId, actorId, kind, key, fingerprint, correlationId)) {
            return null;
        }
        AdminMailCompletionRepository.AdminReceiptRow winner = repository
                .adminReceipt(tenantId, actorId, key).orElseThrow(this::conflict);
        requireAdminReceipt(winner, kind, fingerprint);
        return winner;
    }

    private void requireAdminReceipt(
            AdminMailCompletionRepository.AdminReceiptRow receipt,
            String kind, String fingerprint) {
        if (!receipt.commandKind().equals(kind)) conflict("IDEMPOTENCY_COMMAND_MISMATCH");
        requireFingerprint(receipt.fingerprint(), fingerprint);
        if (receipt.completedAt() == null || receipt.aggregateId() == null) {
            conflict("IDEMPOTENT_COMMAND_IN_PROGRESS");
        }
    }

    private SharedInboxAccessMember replayMember(
            long tenantId, UUID inboxId,
            AdminMailCompletionRepository.AdminReceiptRow receipt,
            String expectedType) {
        if (!expectedType.equals(receipt.aggregateType())) conflict("IDEMPOTENCY_RESPONSE_MISMATCH");
        return accessMember(repository.accessGrant(tenantId, inboxId, receipt.aggregateId())
                .orElseThrow(this::conflict));
    }

    private LegalHold replayHold(
            long tenantId, AdminMailCompletionRepository.AdminReceiptRow receipt) {
        if (!"LEGAL_HOLD".equals(receipt.aggregateType())) {
            conflict("IDEMPOTENCY_RESPONSE_MISMATCH");
        }
        return legalHold(repository.legalHold(tenantId, receipt.aggregateId())
                .orElseThrow(this::conflict));
    }

    private String memberFingerprint(
            long actorId, UUID inboxId, UUID memberId, String kind,
            SharedInboxMemberRequest request) {
        return fingerprints.digest(
                "SHARED_MEMBER", kind, actorId, inboxId, memberId, request.userId(),
                normalized(request.displayName()), normalized(request.department()),
                request.permissions(), request.expiresAt(), request.impactAcknowledged(),
                request.version());
    }

    private String memberFingerprint(
            long actorId, UUID inboxId, UUID memberId, String kind,
            SharedInboxMemberRevokeRequest request) {
        return fingerprints.digest(
                "SHARED_MEMBER", kind, actorId, inboxId, memberId,
                request.impactAcknowledged(), request.version());
    }

    private String legalHoldFingerprint(
            long actorId, UUID holdId, String kind, LegalHoldRequest request) {
        return fingerprints.digest(
                "LEGAL_HOLD", kind, actorId, holdId, normalized(request.name()),
                normalized(request.safeCaseRef()), request.scope(), request.startsAt(),
                request.expiresAt(), request.version());
    }

    private SharedInboxAccessMember accessMember(
            AdminMailCompletionRepository.AccessGrantRow row) {
        return new SharedInboxAccessMember(
                row.id(), row.userId(), row.displayName(), row.department(), row.state(),
                row.expiresAt(), new AccessPermissions(
                        row.read(), row.sendAs(), row.sendOnBehalf(), row.assign(), row.manage()),
                row.providerState(), row.version());
    }

    private AccessImpact impact(
            AdminMailCompletionRepository.ImpactRow impact, boolean providerRevocationRequired) {
        return new AccessImpact(
                impact.activeAssignments(), impact.openDrafts(), impact.pendingCommands(),
                providerRevocationRequired);
    }

    private String providerState(List<SharedInboxAccessMember> members) {
        if (members.stream().anyMatch(member -> "UNAVAILABLE".equals(member.providerState()))) {
            return "UNAVAILABLE";
        }
        if (members.stream().anyMatch(member -> "PARTIAL".equals(member.providerState()))) {
            return "PARTIAL";
        }
        if (members.stream().anyMatch(member -> "PENDING".equals(member.providerState()))) {
            return "PENDING";
        }
        return "APPLIED";
    }

    private String providerMutationState(String providerType) {
        return "DWP_SANDBOX".equals(providerType) ? "APPLIED" : "UNAVAILABLE";
    }

    private String displayName(SharedInboxMemberRequest request) {
        String displayName = normalized(request.displayName());
        return displayName.isBlank() ? "Mail member " + request.userId() : displayName;
    }

    private PolicyEvidenceRow policyRow(
            String key, Object configured, Object effective, String state,
            String source, OffsetDateTime evidenceAt) {
        String error = "UNVERIFIED".equals(state) ? String.valueOf(source) : null;
        String evidenceSource = "UNVERIFIED".equals(state) ? "NO_RUNTIME_ATTESTATION" : source;
        return new PolicyEvidenceRow(
                key, String.valueOf(configured), String.valueOf(effective), state,
                "TENANT", evidenceSource, evidenceAt, error);
    }

    private LegalHold legalHold(AdminMailCompletionRepository.LegalHoldRow row) {
        String status = "ACTIVE".equals(row.status())
                && row.expiresAt() != null && !row.expiresAt().isAfter(now())
                ? "EXPIRED" : row.status();
        return new LegalHold(
                row.id(), row.name(), row.caseRef(), row.scope(), status,
                row.startsAt(), row.expiresAt(), row.version());
    }

    private List<PurgeJob> purgeJobs(long tenantId) {
        return repository.purgeJobs(tenantId, 50).stream().map(this::purgeJob).toList();
    }

    private PurgePreview purgePreview(AdminMailCompletionRepository.PurgePreviewRow row) {
        return new PurgePreview(
                row.id(), row.fingerprint(), row.total(), row.held(), row.eligible(),
                row.partialSources(), row.createdAt(), row.expiresAt(), row.policyVersion());
    }

    private PurgeApproval purgeApproval(
            long tenantId, AdminMailCompletionRepository.PurgeApprovalRow row) {
        return new PurgeApproval(
                row.id(), row.snapshotId(), repository.distinctApprovals(
                        tenantId, row.snapshotId(), row.policyVersion()),
                row.policyVersion(), row.approvedAt());
    }

    private PurgeJob purgeJob(AdminMailCompletionRepository.PurgeJobRow row) {
        return new PurgeJob(
                row.id(), row.snapshotId(), row.state(), row.deletedThreads(),
                row.deletedMessages(), row.steps(), row.verification(), row.errorCode(),
                row.startedAt(), row.completedAt());
    }

    private void requireLivePreview(
            AdminMailCompletionRepository.PurgePreviewRow preview, long policyVersion) {
        if (preview.expiresAt().isBefore(now())) conflict("PURGE_PREVIEW_EXPIRED");
        if (preview.policyVersion() != policyVersion) conflict("POLICY_VERSION_CONFLICT");
    }

    private OffsetDateTime retentionBoundary(OffsetDateTime requested, int retentionDays) {
        OffsetDateTime policyBoundary = now().minusDays(retentionDays);
        return requested.isBefore(policyBoundary) ? requested : policyBoundary;
    }

    private int requestedCandidateCount(
            AdminMailCompletionRepository.CandidateSet candidates,
            List<String> resourceTypes) {
        Set<String> requested = Set.copyOf(normalizedResources(resourceTypes));
        int total = 0;
        if (requested.contains("THREADS")) total += candidates.eligibleThreadCount();
        if (requested.contains("MESSAGES")) total += candidates.eligibleMessageCount();
        return total;
    }

    private int requestedBlockedCount(
            AdminMailCompletionRepository.CandidateSet candidates,
            List<String> resourceTypes) {
        Set<String> requested = Set.copyOf(normalizedResources(resourceTypes));
        int total = 0;
        if (requested.contains("THREADS")) total += candidates.blockedThreadCount();
        if (requested.contains("MESSAGES")) total += candidates.blockedMessageCount();
        return total;
    }

    private String purgeFingerprint(
            long tenantId, Map<String, Object> scope, List<String> resourceTypes,
            OffsetDateTime before, long policyVersion, int activeHolds,
            AdminMailCompletionRepository.CandidateSet candidates) {
        return fingerprints.digest(
                "PURGE_SNAPSHOT", tenantId, scope, normalizedResources(resourceTypes), before,
                policyVersion, activeHolds,
                candidates.eligibleThreadIds(), candidates.blockedThreadIds(),
                candidates.eligibleThreadCount(), candidates.eligibleMessageCount(),
                candidates.blockedThreadCount(), candidates.blockedMessageCount());
    }

    private DeliveryAuditItem deliveryItem(
            long tenantId, AdminMailCompletionRepository.DeliveryRow row) {
        List<DeliveryAuditTimeline> timeline = new ArrayList<>();
        timeline.add(new DeliveryAuditTimeline(
                "COMMAND_ACCEPTED", "SUCCEEDED", row.createdAt(), "mail_delivery_outbox",
                "VERIFIED", null));
        if (row.attemptCount() > 0) {
            timeline.add(new DeliveryAuditTimeline(
                    "PROVIDER_ATTEMPT", timelineState(row), row.updatedAt(),
                    "mail_delivery_outbox", deliveryEvidenceState(row), row.errorCode()));
        }
        if (row.acceptedAt() != null) {
            timeline.add(new DeliveryAuditTimeline(
                    "PROVIDER_ACCEPTED", "SUCCEEDED", row.acceptedAt(),
                    row.providerType(), "VERIFIED", null));
        }
        repository.recoveryEvents(tenantId, row.id()).forEach(event -> timeline.add(
                new DeliveryAuditTimeline(
                        "ADMIN_" + event.action(), event.result(), event.occurredAt(),
                        "mail_delivery_recovery_events", "VERIFIED",
                        stringValue(event.evidence().get("errorCode")))));
        boolean fresh = fresh(row.updatedAt(), now(), DELIVERY_EVIDENCE_FRESH);
        return new DeliveryAuditItem(
                row.id(), safeResourceReference("message", row.id().toString()),
                "SEND", "Mail administrator",
                row.accountName(), row.providerType(), deliveryStage(row),
                deliveryState(row), fresh && retryEligible(row) ? "ELIGIBLE" : "INELIGIBLE",
                providerDisposition(row), idempotencyState(row),
                fresh && reconcileEligible(row), fresh && cancelEligible(row),
                row.updatedAt(), nullToEmpty(row.correlationId()), timeline, row.version());
    }

    private String deliveryStage(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null) return "ACCEPTED_BY_PROVIDER";
        return switch (row.status()) {
            case "QUEUED", "RETRY_WAIT" -> "OUTBOX";
            case "LEASED" -> row.leaseExpiresAt() != null && row.leaseExpiresAt().isBefore(now())
                    ? "UNKNOWN" : "PROVIDER_SUBMITTED";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            default -> "UNKNOWN";
        };
    }

    private String deliveryState(AdminMailCompletionRepository.DeliveryRow row) {
        if ("DELIVERED".equals(row.status())) return "ACCEPTED_BY_PROVIDER";
        if ("LEASED".equals(row.status())
                && row.leaseExpiresAt() != null && row.leaseExpiresAt().isBefore(now())) {
            return "UNKNOWN";
        }
        return switch (row.status()) {
            case "QUEUED", "RETRY_WAIT" -> "QUEUED";
            case "FAILED" -> "FAILED";
            default -> "UNKNOWN";
        };
    }

    private String providerDisposition(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null && row.providerMessageRef() != null) return "ACCEPTED";
        if ("FAILED".equals(row.status()) && row.providerMessageRef() == null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode())) {
            return "NOT_ACCEPTED";
        }
        return "UNKNOWN";
    }

    private String idempotencyState(AdminMailCompletionRepository.DeliveryRow row) {
        return retryEligible(row) ? "REPLAY_SAFE" : "UNKNOWN";
    }

    private String timelineState(AdminMailCompletionRepository.DeliveryRow row) {
        if ("DELIVERED".equals(row.status())) return "SUCCEEDED";
        if ("FAILED".equals(row.status())) return "FAILED";
        if ("CANCELLED".equals(row.status())) return "BLOCKED";
        if ("LEASED".equals(row.status()) && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isBefore(now())) return "UNKNOWN";
        return "PENDING";
    }

    private String deliveryEvidenceState(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null && row.providerMessageRef() != null) return "VERIFIED";
        if ("FAILED".equals(row.status()) && row.providerMessageRef() == null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode())) return "VERIFIED";
        if ("LEASED".equals(row.status()) && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isBefore(now())) return "STALE";
        return "REPORTED";
    }

    private boolean retryEligible(AdminMailCompletionRepository.DeliveryRow row) {
        return "FAILED".equals(row.status())
                && row.acceptedAt() == null
                && row.providerMessageRef() == null
                && row.providerThreadRef() == null
                && row.leaseOwner() == null
                && row.leaseExpiresAt() == null
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode());
    }

    private boolean cancelEligible(AdminMailCompletionRepository.DeliveryRow row) {
        return Set.of("QUEUED", "RETRY_WAIT").contains(row.status())
                && row.acceptedAt() == null
                && row.providerMessageRef() == null
                && row.leaseOwner() == null
                && row.leaseExpiresAt() == null;
    }

    private boolean reconcileEligible(AdminMailCompletionRepository.DeliveryRow row) {
        return "DWP_SANDBOX".equals(row.providerType())
                && "LEASED".equals(row.status())
                && row.leaseExpiresAt() != null
                && row.leaseExpiresAt().isBefore(now())
                && row.acceptedAt() == null
                && row.providerMessageRef() == null;
    }

    private Map<String, Object> recoveryEvidence(
            AdminMailCompletionRepository.DeliveryRow before,
            AdminMailCompletionRepository.DeliveryRow after, String errorCode) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("beforeState", before.status());
        evidence.put("afterState", after.status());
        evidence.put("beforeVersion", before.version());
        evidence.put("afterVersion", after.version());
        evidence.put("providerDispositionBefore", providerDisposition(before));
        if (errorCode != null) evidence.put("errorCode", errorCode);
        return evidence;
    }

    private DeliveryExport deliveryExport(AdminMailCompletionRepository.ExportRow row) {
        return new DeliveryExport(
                row.id(), row.state(), row.expiresAt(), row.watermark(),
                row.itemCount(), row.truncated(), row.payloadSha256(), row.snapshotCutoff(),
                "/api/platform/v1/admin/mail/delivery-audit/exports/"
                        + row.id() + "/download");
    }

    private ConnectionOperation connectionOperation(
            AdminMailCompletionRepository.ConnectionOperationRow row, boolean replayed) {
        return new ConnectionOperation(
                row.id(), row.connectionId(), "SYNC".equals(row.kind())
                        ? "SYNCHRONIZE" : row.kind(), row.state(), row.acceptedAt(),
                row.completedAt(), nullToEmpty(row.correlationId()), row.evidenceAt(),
                row.errorCode(), replayed);
    }

    private Map<String, Object> resultEvidence(String state, String error, UUID operationId) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("operationId", operationId);
        evidence.put("result", state);
        if (error != null) evidence.put("errorCode", error);
        return evidence;
    }

    private String sourceState(OffsetDateTime evidenceAt, OffsetDateTime current) {
        if (evidenceAt == null) return "UNAVAILABLE";
        return fresh(evidenceAt, current, SOURCE_FRESH) ? "CURRENT" : "STALE";
    }

    private boolean fresh(OffsetDateTime evidenceAt, OffsetDateTime current, Duration maximumAge) {
        return evidenceAt != null && !evidenceAt.isAfter(current.plusSeconds(5))
                && !evidenceAt.isBefore(current.minus(maximumAge));
    }

    private List<String> normalizedResources(List<String> resources) {
        return resources.stream().map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct().sorted().toList();
    }

    private String requiredEnum(String value, Set<String> allowed) {
        String normalized = normalized(value).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) badRequest("UNSUPPORTED_OPERATION");
        return normalized;
    }

    private void requireIdentity(long tenantId, long actorId) {
        if (tenantId <= 0 || actorId <= 0) badRequest("IDENTITY_CONTEXT_REQUIRED");
    }

    private void requireFingerprint(String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            conflict("IDEMPOTENCY_FINGERPRINT_MISMATCH");
        }
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizedEmail(String value) {
        return normalized(value).toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        String normalized = normalized(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String safeResourceReference(String kind, String resourceReference) {
        String label = normalized(kind).toLowerCase(Locale.ROOT).replace('_', '-');
        if (resourceReference == null || resourceReference.isBlank()) return label;
        String value = resourceReference.trim();
        int suffixLength = Math.min(6, value.length());
        return label + ":••" + value.substring(value.length() - suffixLength);
    }

    private String correlation(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "MAIL_ADMIN_RESOURCE_NOT_FOUND");
    }

    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "MAIL_ADMIN_CONFLICT");
    }

    private void conflict(String code) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private ResponseStatusException badRequest(String code) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private AdminOperationFailure failure(String code) {
        return new AdminOperationFailure(code, false);
    }

    private static final class AdminOperationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean unknown;

        private AdminOperationFailure(String code, boolean unknown) {
            super(code);
            this.code = code;
            this.unknown = unknown;
        }
    }
}
