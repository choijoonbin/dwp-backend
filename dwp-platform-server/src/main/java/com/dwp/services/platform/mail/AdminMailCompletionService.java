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
import java.time.LocalDate;
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
    private static final Set<String> PURGE_RESOURCE_TYPES = Set.of(
            "THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS");
    private static final Set<String> RETENTION_RESOURCE_TYPES = Set.of(
            "THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS",
            "RECIPIENT_SNAPSHOTS", "COMMAND_RECEIPTS", "AUDIT_EVENTS");
    private static final Set<String> DELIVERY_ACCESS_TERMINAL_ERRORS = Set.of(
            "MAIL_SEND_AUTHORIZATION_REVOKED",
            "MAIL_ADAPTER_NOT_DEPLOYED",
            "MAIL_ADAPTER_SEND_NOT_SUPPORTED",
            "MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED",
            "MAIL_ADAPTER_BCC_NOT_SUPPORTED",
            "MAIL_ADAPTER_HTML_NOT_SUPPORTED",
            "MAIL_ADAPTER_ATTACHMENTS_NOT_SUPPORTED",
            "MAIL_ADAPTER_CONFIGURATION_REQUIRED",
            "MAIL_ADAPTER_AUTHENTICATION_REQUIRED");
    private static final Set<String> SCOPE_KEYS = Set.of(
            "tenant", "accountId", "accountIds", "threadId", "threadIds", "resourceTypes");

    private final AdminMailCompletionRepository repository;
    private final MailConnectorRegistry connectorRegistry;
    private final AdminMailCommandFingerprint fingerprints;
    private final ObjectMapper objectMapper;
    private final MailExternalSyncMaterializer syncMaterializer;
    private final AdminMailOperationDurability operationDurability;
    private final AdminMailPurgeTransactions purgeTransactions;
    private final MailMemberDirectory memberDirectory;

    @Autowired
    public AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability,
            AdminMailPurgeTransactions purgeTransactions,
            MailMemberDirectory memberDirectory) {
        this.repository = repository;
        this.connectorRegistry = connectorRegistry;
        this.fingerprints = new AdminMailCommandFingerprint(objectMapper);
        this.objectMapper = objectMapper;
        this.syncMaterializer = syncMaterializer;
        this.operationDurability = operationDurability;
        this.purgeTransactions = purgeTransactions;
        this.memberDirectory = memberDirectory;
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability,
            AdminMailPurgeTransactions purgeTransactions) {
        this(repository, connectorRegistry, objectMapper, syncMaterializer,
                operationDurability, purgeTransactions, null);
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability) {
        this(repository, connectorRegistry, objectMapper, syncMaterializer,
                operationDurability, null, null);
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer) {
        this(repository, connectorRegistry, objectMapper, syncMaterializer, null, null, null);
    }

    AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper) {
        this(repository, connectorRegistry, objectMapper, null, null, null, null);
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

    @Transactional(readOnly = true)
    public List<SharedInboxMemberCandidate> sharedInboxMemberCandidates(
            long tenantId, String query, int limit) {
        requireIdentity(tenantId, 1);
        if (memberDirectory == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_DIRECTORY_UNAVAILABLE");
        }
        return memberDirectory.searchActive(tenantId, query, limit).stream()
                .map(identity -> new SharedInboxMemberCandidate(
                        identity.userId(), identity.displayName().strip(),
                        trimToNull(identity.department()), trimToNull(identity.email())))
                .toList();
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
        MailMemberDirectory.MemberIdentity identity = requireActiveMember(
                tenantId, request.userId());
        if (repository.bumpSharedInbox(tenantId, inboxId, request.version(), actorId) != 1) {
            conflict("SHARED_INBOX_VERSION_CONFLICT");
        }
        String providerState = providerMutationState(inbox.providerType());
        UUID memberId = repository.insertAccessGrant(
                tenantId, inboxId, request.userId(), identity.displayName().strip(),
                trimToNull(identity.department()), request.expiresAt(),
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
        MailMemberDirectory.MemberIdentity identity = requireActiveMember(
                tenantId, request.userId());
        String providerState = providerMutationState(inbox.providerType());
        if (repository.updateAccessGrant(
                tenantId, inboxId, memberId, identity.displayName().strip(),
                trimToNull(identity.department()), request.expiresAt(),
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
    public SharedInboxMemberRevokePreview previewSharedInboxMemberRevoke(
            long tenantId, long actorId, UUID inboxId, UUID memberId,
            SharedInboxMemberRevokePreviewRequest request) {
        requireIdentity(tenantId, actorId);
        AdminMailCompletionRepository.SharedInboxRow inbox = repository.sharedInbox(tenantId, inboxId)
                .orElseThrow(this::notFound);
        AdminMailCompletionRepository.AccessGrantRow member = repository
                .accessGrant(tenantId, inboxId, memberId).orElseThrow(this::notFound);
        if (member.version() != request.memberVersion() || "REVOKED".equals(member.state())) {
            conflict("SHARED_MEMBER_VERSION_CONFLICT");
        }
        AdminMailCompletionRepository.ImpactRow impact =
                repository.accessImpact(tenantId, inboxId, member.userId());
        boolean providerRevocationRequired = !"DWP_SANDBOX".equals(inbox.providerType());
        OffsetDateTime generatedAt = now();
        OffsetDateTime expiresAt = generatedAt.plusMinutes(10);
        String fingerprint = memberRevokePreviewFingerprint(
                actorId, inboxId, memberId, member.version(), impact,
                providerRevocationRequired);
        UUID previewId = repository.insertMemberRevokePreview(
                tenantId, inboxId, memberId, actorId, member.version(), impact,
                providerRevocationRequired, fingerprint, expiresAt);
        return new SharedInboxMemberRevokePreview(
                previewId, fingerprint, impact.activeAssignments(), impact.openDrafts(),
                impact.pendingCommands(), providerRevocationRequired, member.version(),
                generatedAt, expiresAt);
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
        AdminMailCompletionRepository.MemberRevokePreviewRow preview = repository
                .memberRevokePreview(tenantId, request.previewId(), inboxId, memberId, actorId)
                .orElseThrow(() -> conflictException("REVOKE_PREVIEW_NOT_FOUND"));
        if (preview.consumedAt() != null || !preview.expiresAt().isAfter(now())) {
            conflict("REVOKE_PREVIEW_EXPIRED");
        }
        requireFingerprint(preview.fingerprint(), request.fingerprint().toLowerCase(Locale.ROOT));
        if (preview.memberVersion() != request.version()
                || before.version() != request.version()) {
            conflict("REVOKE_PREVIEW_STALE");
        }
        AdminMailCompletionRepository.ImpactRow impact =
                repository.accessImpact(tenantId, inboxId, before.userId());
        boolean providerRevocationRequired = !"DWP_SANDBOX".equals(inbox.providerType());
        String currentPreviewFingerprint = memberRevokePreviewFingerprint(
                actorId, inboxId, memberId, before.version(), impact,
                providerRevocationRequired);
        requireFingerprint(preview.fingerprint(), currentPreviewFingerprint);
        if (impact.hasImpact() && !request.impactAcknowledged()) {
            conflict("REVOKE_IMPACT_ACKNOWLEDGEMENT_REQUIRED");
        }
        String providerState = providerMutationState(inbox.providerType());
        if (repository.revokeAccessGrant(
                tenantId, inboxId, memberId, request.version(), providerState, actorId) != 1) {
            conflict("SHARED_MEMBER_VERSION_CONFLICT");
        }
        if (repository.consumeMemberRevokePreview(tenantId, preview.id(), actorId) != 1) {
            conflict("REVOKE_PREVIEW_STALE");
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
                        "MailWorkspaceService", policy.updatedAt()),
                policyUnavailableRow("attachmentAllowlist", "ATTACHMENT", "ALLOWLIST",
                        "ATTACHMENT_ALLOWLIST_NOT_CONFIGURED"),
                policyUnavailableRow("attachmentScannerReadiness", "ATTACHMENT", "SCANNER",
                        "SCANNER_RUNTIME_ATTESTATION_UNAVAILABLE"),
                policyUnavailableRow("dlpReadiness", "CONTENT", "DLP",
                        "DLP_RUNTIME_ATTESTATION_UNAVAILABLE"),
                policyUnavailableRow("aiTargetApplications", "AI", "TARGET_APPLICATIONS",
                        "AI_TARGET_SCOPE_NOT_CONFIGURED"),
                policyUnavailableRow("aiDataScopes", "AI", "DATA_SCOPE",
                        "AI_DATA_SCOPE_NOT_CONFIGURED"),
                policyUnavailableRow("aiExternalTransfer", "AI", "EXTERNAL_TRANSFER",
                        "AI_EXTERNAL_TRANSFER_EVIDENCE_UNAVAILABLE"),
                policyRow("aiReviewRequirement", !policy.aiAutoExecute(),
                        !policy.aiAutoExecute(), "ENFORCED", "MailService", policy.updatedAt()));
        List<PolicyHistory> history = repository.policyHistory(tenantId, 100).stream()
                .map(row -> new PolicyHistory(
                        row.id(), row.version(), "user:" + row.actorId(), row.changedAt(),
                        row.diff().toString(), row.result(), nullToEmpty(row.correlationId()),
                        "user:" + row.actorId(), null,
                        "APPLIED".equals(row.result()) ? row.changedAt() : null,
                        "APPLIED".equals(row.result()) ? null : row.result(),
                        "UNAVAILABLE", null))
                .toList();
        Map<String, List<PolicyEvidenceRow>> domains = rows.stream().collect(
                java.util.stream.Collectors.groupingBy(
                        PolicyEvidenceRow::domain, LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        List<PolicyRecoveryEvidence> recovery = history.stream()
                .filter(row -> row.failureCode() != null)
                .map(row -> new PolicyRecoveryEvidence(
                        row.historyId(), row.version(), row.recoveryState(),
                        row.failureCode(), row.recoveryRef(), row.changedAt()))
                .toList();
        return new PolicyGovernance(
                now(), policy.version(), rows, history, domains, List.of(), recovery);
    }

    @Transactional(readOnly = true)
    public RetentionSnapshot retention(long tenantId) {
        return retention(tenantId, true);
    }

    @Transactional(readOnly = true)
    public RetentionSnapshot retention(long tenantId, boolean includeSensitiveEvidence) {
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        List<ResourceRetentionPolicy> policies = List.of(
                new ResourceRetentionPolicy(
                        "THREADS", policy.retentionDays(), policy.retentionDays(),
                        "mail_tenant_policies.retention_days", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "MESSAGES", policy.retentionDays(), policy.retentionDays(),
                        "mail_tenant_policies.retention_days", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "ATTACHMENTS", policy.retentionDays(), policy.retentionDays(),
                        "mail_compose_attachments via thread lifecycle", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "DRAFTS", policy.retentionDays(), policy.retentionDays(),
                        "mail_draft_options via thread lifecycle", "VERIFIED"),
                new ResourceRetentionPolicy(
                        "RECIPIENT_SNAPSHOTS", policy.retentionDays(), null,
                        "mail_group_recipient_snapshots append-only evidence", "RETAINED"),
                new ResourceRetentionPolicy(
                        "COMMAND_RECEIPTS", policy.retentionDays(), null,
                        "mail_draft_command_receipts immutable evidence", "RETAINED"),
                new ResourceRetentionPolicy(
                        "AUDIT_EVENTS", policy.retentionDays(), null,
                        "mail_audit_events governance evidence", "RETAINED"));
        return new RetentionSnapshot(
                now(), policy.version(), policies,
                repository.legalHolds(tenantId).stream()
                        .map(row -> includeSensitiveEvidence
                                ? legalHold(row) : redactedLegalHold(row))
                        .toList(),
                includeSensitiveEvidence ? purgeJobs(tenantId) : List.of());
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
        AdminMailCompletionRepository.LegalHoldRow current = repository
                .legalHold(tenantId, holdId).orElseThrow(this::notFound);
        if (current.version() != request.version()) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        requireNonReducingActiveHoldUpdate(current, request);
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
    public LegalHoldReleasePreview previewLegalHoldRelease(
            long tenantId, long actorId, UUID holdId, String correlationId,
            LegalHoldReleasePreviewRequest request) {
        requireIdentity(tenantId, actorId);
        repository.lockRetentionLifecycle(tenantId);
        String requestFingerprint = fingerprints.digest(
                "LEGAL_HOLD_RELEASE_PREVIEW", actorId, holdId,
                request.holdVersion(), request.policyVersion());
        var replay = repository.legalHoldReleasePreviewByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), requestFingerprint);
            if (!replay.get().holdId().equals(holdId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return legalHoldReleasePreview(tenantId, replay.get());
        }
        AdminMailCompletionRepository.LegalHoldRow hold = repository
                .legalHold(tenantId, holdId).orElseThrow(this::notFound);
        requireReleasableHold(hold, request.holdVersion());
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) {
            conflict("POLICY_VERSION_CONFLICT");
        }
        OffsetDateTime generatedAt = now();
        OffsetDateTime retentionBoundary = generatedAt.minusDays(policy.retentionDays());
        List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds = repository
                .activeLegalHolds(tenantId).stream()
                .filter(active -> !active.id().equals(hold.id())).toList();
        LegalHoldReleaseAssessment assessment = assessLegalHoldRelease(
                repository.purgeCandidates(tenantId, retentionBoundary), hold,
                otherActiveHolds);
        String snapshotFingerprint = legalHoldReleaseSnapshotFingerprint(
                tenantId, hold, policy.version(), retentionBoundary,
                otherActiveHolds, assessment);
        UUID previewId = repository.insertLegalHoldReleasePreview(
                tenantId, holdId, actorId, hold.version(), policy.version(), hold.scope(),
                retentionBoundary, snapshotFingerprint, requestFingerprint,
                assessment.affected(), assessment.currentlyHeld(), assessment.purgeSafe(),
                assessment.stillProtected(), assessment.providerRequired(),
                request.idempotencyKey(), generatedAt.plusMinutes(15));
        repository.audit(
                tenantId, actorId, "mail.legal.hold.release.previewed",
                "MAIL_LEGAL_HOLD_RELEASE_PREVIEW", previewId.toString(), correlationId,
                Map.of("holdId", holdId, "holdVersion", hold.version(),
                        "policyVersion", policy.version()),
                Map.of("fingerprint", snapshotFingerprint,
                        "affected", assessment.affected(),
                        "purgeSafeAfterRelease", assessment.purgeSafe(),
                        "expiresAt", generatedAt.plusMinutes(15)));
        return legalHoldReleasePreview(tenantId, repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public LegalHoldReleasePreview legalHoldReleasePreview(
            long tenantId, UUID previewId) {
        return legalHoldReleasePreview(tenantId, repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::notFound));
    }

    @Transactional
    public LegalHoldReleaseApproval approveLegalHoldRelease(
            long tenantId, long actorId, UUID previewId, String correlationId,
            LegalHoldReleaseApprovalRequest request) {
        requireIdentity(tenantId, actorId);
        String decision = requiredEnum(request.decision(), Set.of("APPROVE", "REJECT"));
        String requestFingerprint = fingerprints.digest(
                "LEGAL_HOLD_RELEASE_APPROVAL", actorId, previewId, decision,
                request.fingerprint().toLowerCase(Locale.ROOT), request.holdVersion(),
                request.policyVersion());
        repository.lockRetentionLifecycle(tenantId);
        var replay = repository.legalHoldReleaseApprovalByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), requestFingerprint);
            if (!replay.get().previewId().equals(previewId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return legalHoldReleaseApproval(replay.get());
        }
        AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview = repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::notFound);
        requireLiveLegalHoldReleasePreview(preview, request.fingerprint(),
                request.holdVersion(), request.policyVersion());
        if (preview.requesterId() == actorId) {
            conflict("LEGAL_HOLD_RELEASE_SELF_APPROVAL_FORBIDDEN");
        }
        if (repository.legalHoldReleaseExecutionByPreview(tenantId, previewId).isPresent()) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_ALREADY_EXECUTED");
        }
        if (repository.legalHoldReleaseApprovals(tenantId, previewId).stream()
                .anyMatch(approval -> "REJECT".equals(approval.decision()))) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_REJECTED");
        }
        requireCurrentLegalHoldReleaseVersions(tenantId, preview);
        UUID approvalId;
        try {
            approvalId = repository.insertLegalHoldReleaseApproval(
                    tenantId, previewId, actorId, decision, preview.holdVersion(),
                    preview.policyVersion(), preview.fingerprint(), requestFingerprint,
                    request.idempotencyKey());
        } catch (DataIntegrityViolationException duplicate) {
            conflict("LEGAL_HOLD_RELEASE_APPROVER_ALREADY_RECORDED");
            return null;
        }
        AdminMailCompletionRepository.LegalHoldReleaseApprovalRow approval =
                new AdminMailCompletionRepository.LegalHoldReleaseApprovalRow(
                        approvalId, previewId, actorId, decision, preview.holdVersion(),
                        preview.policyVersion(), preview.fingerprint(), requestFingerprint,
                        request.idempotencyKey(), now());
        repository.audit(
                tenantId, actorId, "mail.legal.hold.release." + decision.toLowerCase(Locale.ROOT),
                "MAIL_LEGAL_HOLD_RELEASE_PREVIEW", previewId.toString(), correlationId,
                Map.of("state", "AWAITING_APPROVAL"),
                Map.of("decision", decision, "approvalId", approvalId,
                        "holdVersion", preview.holdVersion(),
                        "policyVersion", preview.policyVersion()));
        return legalHoldReleaseApproval(approval);
    }

    @Transactional
    public LegalHoldReleaseExecution executeLegalHoldRelease(
            long tenantId, long actorId, UUID previewId, String correlationId,
            LegalHoldReleaseExecuteRequest request) {
        requireIdentity(tenantId, actorId);
        String normalizedFingerprint = request.fingerprint().toLowerCase(Locale.ROOT);
        String requestFingerprint = fingerprints.digest(
                "LEGAL_HOLD_RELEASE_EXECUTE", actorId, previewId, normalizedFingerprint,
                request.holdVersion(), request.policyVersion());
        repository.lockRetentionLifecycle(tenantId);
        var replay = repository.legalHoldReleaseExecutionByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), requestFingerprint);
            if (!replay.get().previewId().equals(previewId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            return legalHoldReleaseExecution(tenantId, replay.get(), true);
        }
        if (repository.legalHoldReleaseExecutionByPreview(tenantId, previewId).isPresent()) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_ALREADY_EXECUTED");
        }
        AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview = repository
                .legalHoldReleasePreview(tenantId, previewId).orElseThrow(this::notFound);
        requireLiveLegalHoldReleasePreview(preview, normalizedFingerprint,
                request.holdVersion(), request.policyVersion());
        AdminMailCompletionRepository.LegalHoldRow hold =
                requireCurrentLegalHoldReleaseVersions(tenantId, preview);
        List<AdminMailCompletionRepository.LegalHoldReleaseApprovalRow> approvals =
                repository.legalHoldReleaseApprovals(tenantId, previewId);
        if (approvals.stream().anyMatch(approval -> "REJECT".equals(approval.decision()))) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_REJECTED");
        }
        AdminMailCompletionRepository.LegalHoldReleaseApprovalRow approval = approvals.stream()
                .filter(item -> "APPROVE".equals(item.decision()))
                .filter(item -> item.approverId() != preview.requesterId())
                .findFirst().orElseThrow(() ->
                        conflictException("LEGAL_HOLD_RELEASE_APPROVAL_REQUIRED"));
        List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds = repository
                .activeLegalHolds(tenantId).stream()
                .filter(active -> !active.id().equals(hold.id())).toList();
        LegalHoldReleaseAssessment currentAssessment = assessLegalHoldRelease(
                repository.purgeCandidates(tenantId, preview.retentionBoundary()),
                hold, otherActiveHolds);
        String currentFingerprint = legalHoldReleaseSnapshotFingerprint(
                tenantId, hold, preview.policyVersion(), preview.retentionBoundary(),
                otherActiveHolds, currentAssessment);
        if (!preview.fingerprint().equals(currentFingerprint)
                || !releaseAssessmentMatches(preview, currentAssessment)) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_STALE");
        }
        if (repository.releaseLegalHold(
                tenantId, hold.id(), hold.version(), actorId) != 1) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        UUID executionId = repository.insertLegalHoldReleaseExecution(
                tenantId, previewId, hold.id(), preview.requesterId(),
                approval.approverId(), actorId, hold.version(), preview.policyVersion(),
                preview.fingerprint(), requestFingerprint, request.idempotencyKey());
        repository.audit(
                tenantId, actorId, "mail.legal.hold.released", "MAIL_LEGAL_HOLD",
                hold.id().toString(), correlationId,
                Map.of("version", hold.version(), "status", hold.status(),
                        "releasePreviewId", previewId,
                        "fingerprint", preview.fingerprint()),
                Map.of("version", hold.version() + 1, "status", "RELEASED",
                        "executionId", executionId, "approvedBy", approval.approverId(),
                        "purgeStarted", false));
        AdminMailCompletionRepository.LegalHoldReleaseExecutionRow execution =
                new AdminMailCompletionRepository.LegalHoldReleaseExecutionRow(
                        executionId, previewId, hold.id(), preview.requesterId(),
                        approval.approverId(), actorId, hold.version(), hold.version() + 1,
                        preview.policyVersion(), preview.fingerprint(), requestFingerprint,
                        request.idempotencyKey(), now());
        return legalHoldReleaseExecution(tenantId, execution, false);
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
            return purgePreview(tenantId, existing.get());
        }
        AdminMailCompletionRepository.CandidateSet candidates =
                repository.purgeCandidates(tenantId, effectiveBefore);
        List<AdminMailCompletionRepository.LegalHoldRow> activeHolds =
                repository.activeLegalHolds(tenantId);
        PurgeAssessment assessment = assessPurge(
                candidates, request.scope(), request.resourceTypes(), activeHolds);
        List<String> partial = assessment.hasExternalProvider()
                ? List.of("EXTERNAL_PROVIDER_DELETE_UNAVAILABLE") : List.of();
        String snapshotFingerprint = purgeFingerprint(
                tenantId, request.scope(), request.resourceTypes(), effectiveBefore,
                policy.version(), activeHolds, assessment);
        UUID snapshotId = repository.insertPurgePreview(
                tenantId, actorId, request.scope(), normalizedResources(request.resourceTypes()),
                effectiveBefore, snapshotFingerprint, assessment.total(), assessment.held(),
                assessment.eligible(), partial,
                policy.version(), request.idempotencyKey(), now().plusMinutes(15));
        repository.insertPurgeCandidateRows(
                tenantId, snapshotId, assessment.snapshotRows());
        return purgePreview(tenantId, repository.purgePreview(tenantId, snapshotId)
                .orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public List<PurgePreview> activePurgePreviews(long tenantId) {
        return activePurgePreviews(tenantId, true);
    }

    @Transactional(readOnly = true)
    public List<PurgePreview> activePurgePreviews(
            long tenantId, boolean includeSensitiveScope) {
        return repository.activePurgePreviews(tenantId, 50).stream()
                .map(row -> purgePreview(tenantId, row))
                .map(preview -> purgePreviewProjection(preview, includeSensitiveScope))
                .toList();
    }

    @Transactional(readOnly = true)
    public PurgePreview purgePreview(long tenantId, UUID snapshotId) {
        return purgePreview(tenantId, snapshotId, true);
    }

    @Transactional(readOnly = true)
    public PurgePreview purgePreview(
            long tenantId, UUID snapshotId, boolean includeSensitiveScope) {
        return purgePreviewProjection(
                purgePreview(tenantId, repository.purgePreview(tenantId, snapshotId)
                        .orElseThrow(this::notFound)),
                includeSensitiveScope);
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
        if (repository.activeHoldCount(tenantId) > 0) {
            conflict("ACTIVE_LEGAL_HOLD_BLOCKS_PURGE");
        }
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) conflict("POLICY_VERSION_CONFLICT");
        if (!preview.partialSources().isEmpty()) conflict("PURGE_SOURCE_COVERAGE_INCOMPLETE");
        if (repository.distinctApprovals(tenantId, snapshotId, request.policyVersion()) < 2) {
            conflict("TWO_DISTINCT_APPROVERS_REQUIRED");
        }
        AdminMailCompletionRepository.CandidateSet candidates =
                repository.purgeCandidates(tenantId, preview.before());
        List<AdminMailCompletionRepository.LegalHoldRow> activeHolds =
                repository.activeLegalHolds(tenantId);
        PurgeAssessment assessment = assessPurge(
                candidates, preview.scope(), preview.resourceTypes(), activeHolds);
        String currentFingerprint = purgeFingerprint(
                tenantId, preview.scope(), preview.resourceTypes(), preview.before(),
                policy.version(), activeHolds, assessment);
        requireFingerprint(preview.fingerprint(), currentFingerprint);
        if (assessment.total() != preview.total()
                || assessment.eligible() != preview.eligible()
                || assessment.held() != preview.held()) {
            conflict("PURGE_CANDIDATE_SET_CHANGED");
        }
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows =
                repository.purgeCandidateRows(tenantId, snapshotId);
        if (!snapshotRows.equals(assessment.snapshotRows())) {
            conflict("PURGE_CANDIDATE_SNAPSHOT_MISMATCH");
        }
        List<UUID> approvedEligibleThreadIds = snapshotRows.stream()
                .filter(row -> !row.held()).map(
                        AdminMailCompletionRepository.PurgeCandidateSnapshotRow::threadId)
                .toList();
        List<Map<String, Object>> acceptanceSteps = List.of(
                Map.of("step", "REVALIDATE_POLICY", "state", "SUCCEEDED",
                        "policyVersion", policy.version()),
                Map.of("step", "REVALIDATE_LEGAL_HOLDS", "state", "SUCCEEDED",
                        "activeHoldCount", activeHolds.size()),
                Map.of("step", "PRESERVE_IMMUTABLE_EVIDENCE", "state", "SUCCEEDED",
                        "blockedThreads", assessment.blockedThreadCount(),
                        "blockedMessages", assessment.blockedMessageCount()));
        UUID jobId = repository.insertPurgeJob(
                tenantId, snapshotId, actorId, request.idempotencyKey(),
                approvedEligibleThreadIds, acceptanceSteps);
        repository.audit(
                tenantId, actorId, "mail.purge.accepted", "MAIL_PURGE_JOB",
                jobId.toString(), correlationId,
                Map.of("candidateSnapshotId", snapshotId, "fingerprint", preview.fingerprint()),
                Map.of("result", "ACCEPTED",
                        "candidateThreads", approvedEligibleThreadIds.size(),
                        "preservedThreads", assessment.blockedThreadCount()));
        return purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public PurgeJob purgeJob(long tenantId, UUID jobId) {
        return purgeJob(tenantId, jobId, true);
    }

    @Transactional(readOnly = true)
    public PurgeJob purgeJob(long tenantId, UUID jobId, boolean includeSensitiveEvidence) {
        return purgeJobProjection(
                purgeJob(repository.purgeJob(tenantId, jobId).orElseThrow(this::notFound)),
                includeSensitiveEvidence);
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, int page, int pageSize) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        List<DeliveryAuditItem> items = repository.deliveries(
                tenantId, state, query, safeSize, safePage * safeSize).stream()
                .map(row -> deliveryItem(tenantId, row, true)).toList();
        return new DeliveryAuditPage(
                items, repository.deliveryCount(tenantId, state, query),
                safePage, safeSize, now());
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, UUID accountId,
            String provider, String command, LocalDate dateFrom, LocalDate dateTo,
            int page, int pageSize) {
        return deliveryAudit(tenantId, state, query, accountId, provider, command,
                dateFrom, dateTo, page, pageSize, true);
    }

    @Transactional(readOnly = true)
    public DeliveryAuditPage deliveryAudit(
            long tenantId, String state, String query, UUID accountId,
            String provider, String command, LocalDate dateFrom, LocalDate dateTo,
            int page, int pageSize, boolean revealSensitive) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, pageSize));
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            badRequest("DELIVERY_AUDIT_DATE_RANGE_INVALID");
        }
        String normalizedCommand = normalized(command).toUpperCase(Locale.ROOT);
        if (!normalizedCommand.isEmpty() && !"SEND".equals(normalizedCommand)) {
            badRequest("DELIVERY_AUDIT_COMMAND_UNSUPPORTED");
        }
        List<DeliveryAuditItem> items = repository.deliveries(
                tenantId, state, query, accountId, provider, normalizedCommand,
                dateFrom, dateTo, safeSize, safePage * safeSize).stream()
                .map(row -> deliveryItem(tenantId, row, revealSensitive)).toList();
        return new DeliveryAuditPage(
                items, repository.deliveryCount(
                        tenantId, state, query, accountId, provider, normalizedCommand,
                        dateFrom, dateTo),
                safePage, safeSize, now());
    }

    @Transactional(noRollbackFor = DeliveryRecoveryBlocked.class)
    public DeliveryAuditItem recoverDelivery(
            long tenantId, long actorId, UUID deliveryId, String actionKind,
            String correlationId, DeliveryRecoveryRequest request) {
        return recoverDelivery(
                tenantId, actorId, deliveryId, actionKind, correlationId, request, false);
    }

    @Transactional(noRollbackFor = DeliveryRecoveryBlocked.class)
    public DeliveryAuditItem recoverDelivery(
            long tenantId, long actorId, UUID deliveryId, String actionKind,
            String correlationId, DeliveryRecoveryRequest request,
            boolean revealSensitive) {
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
            if ("BLOCKED".equals(replay.get().result())) {
                throw new DeliveryRecoveryBlocked(
                        "DELIVERY_RECOVERY_BLOCKED_BY_ACCESS");
            }
            return deliveryItem(tenantId, repository.delivery(tenantId, deliveryId)
                    .orElseThrow(this::notFound), revealSensitive);
        }
        AdminMailCompletionRepository.DeliveryRow before = repository
                .delivery(tenantId, deliveryId).orElseThrow(this::notFound);
        if (before.version() != request.version()) conflict("DELIVERY_VERSION_CONFLICT");
        if (!fresh(before.updatedAt(), now(), DELIVERY_EVIDENCE_FRESH)) {
            conflict("STALE_DELIVERY_EVIDENCE");
        }
        if (deliveryBlockedByAccess(before)) {
            blockDeliveryRecovery(
                    tenantId, actorId, deliveryId, action, correlationId, request,
                    fingerprint, before, before.errorCode());
        }
        if ("RETRY".equals(action)) {
            if (!retryEligible(before)) {
                conflict("RETRY_EVIDENCE_INSUFFICIENT");
            }
        } else if ("CANCEL".equals(action)) {
            if (!cancelEligible(before)) {
                conflict("CANCEL_EVIDENCE_INSUFFICIENT");
            }
        } else {
            if (!reconcileEligible(before)) {
                conflict("RECONCILIATION_EVIDENCE_INSUFFICIENT");
            }
        }
        String authorizationError = deliveryRecoveryAuthorizationError(tenantId, before);
        if (authorizationError != null) {
            blockDeliveryRecovery(
                    tenantId, actorId, deliveryId, action, correlationId, request,
                    fingerprint, before, authorizationError);
        }

        int changed = switch (action) {
            case "RETRY" -> repository.retryDelivery(
                    tenantId, deliveryId, request.version());
            case "CANCEL" -> repository.cancelDelivery(
                    tenantId, deliveryId, request.version());
            default -> repository.failExpiredSandboxLease(
                    tenantId, deliveryId, request.version());
        };
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
        return deliveryItem(tenantId, after, revealSensitive);
    }

    private void blockDeliveryRecovery(
            long tenantId, long actorId, UUID deliveryId, String action,
            String correlationId, DeliveryRecoveryRequest request, String fingerprint,
            AdminMailCompletionRepository.DeliveryRow before, String errorCode) {
        String normalizedError = nullToEmpty(errorCode).isBlank()
                ? "MAIL_SEND_AUTHORIZATION_REVOKED" : errorCode;
        long blockedVersion = before.version();
        if (!deliveryBlockedByAccess(before)) {
            if (repository.blockDeliveryAccess(
                    tenantId, deliveryId, before.version(), normalizedError) != 1) {
                conflict("DELIVERY_VERSION_CONFLICT");
            }
            blockedVersion++;
        }
        Map<String, Object> evidence = Map.of(
                "errorCode", normalizedError,
                "state", before.status(),
                "versionBefore", before.version(),
                "versionAfter", blockedVersion,
                "authorizationCheckedAt", now().toString());
        repository.insertRecoveryEvent(
                tenantId, deliveryId, actorId, action, "BLOCKED",
                evidence, request.idempotencyKey(), fingerprint, correlationId);
        repository.audit(
                tenantId, actorId,
                "mail.delivery." + action.toLowerCase(Locale.ROOT) + ".blocked",
                "MAIL_DELIVERY", deliveryId.toString(), correlationId,
                Map.of("state", before.status(), "version", before.version()), evidence);
        throw new DeliveryRecoveryBlocked(normalizedError);
    }

    @Transactional
    public DeliveryExport createDeliveryExport(
            long tenantId, long actorId, DeliveryExportRequest request) {
        return createDeliveryExport(tenantId, actorId, request, true, true);
    }

    @Transactional
    public DeliveryExport createDeliveryExport(
            long tenantId, long actorId, DeliveryExportRequest request,
            boolean revealSensitive) {
        return createDeliveryExport(
                tenantId, actorId, request, revealSensitive, false);
    }

    private DeliveryExport createDeliveryExport(
            long tenantId, long actorId, DeliveryExportRequest request,
            boolean revealSensitive, boolean legacyImmediateExport) {
        requireIdentity(tenantId, actorId);
        String fingerprint = fingerprints.digest(
                "DELIVERY_EXPORT", actorId, request.filters(), request.purpose());
        var existing = repository.exportByCommand(tenantId, actorId, request.idempotencyKey());
        if (existing.isPresent()) {
            String existingFingerprint = existing.get().requestFingerprint() == null
                    ? fingerprints.digest("DELIVERY_EXPORT", actorId,
                            existing.get().filters(), existing.get().purpose())
                    : existing.get().requestFingerprint();
            requireFingerprint(existingFingerprint, fingerprint);
            requireExportKind(existing.get(), "DELIVERY_AUDIT");
            return deliveryExport(tenantId, existing.get());
        }
        OffsetDateTime snapshotCutoff = now();
        OffsetDateTime expiresAt = snapshotCutoff.plusHours(24);
        String watermark = "DWP MAIL AUDIT • tenant " + tenantId + " • user " + actorId
                + " • " + snapshotCutoff;
        UUID exportId = UUID.randomUUID();
        String state = exportText(request.filters(), "state");
        String query = exportText(request.filters(), "query");
        UUID accountId = exportUuid(request.filters(), "accountId");
        String provider = exportText(request.filters(), "provider");
        String command = exportText(request.filters(), "command").toUpperCase(Locale.ROOT);
        LocalDate dateFrom = exportDate(request.filters(), "dateFrom");
        LocalDate dateTo = exportDate(request.filters(), "dateTo");
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            badRequest("DELIVERY_AUDIT_DATE_RANGE_INVALID");
        }
        if (!command.isEmpty() && !"SEND".equals(command)) {
            badRequest("DELIVERY_AUDIT_COMMAND_UNSUPPORTED");
        }
        List<AdminMailCompletionRepository.DeliveryRow> candidates =
                request.filters().isEmpty() && revealSensitive
                ? repository.deliveries(
                        tenantId, state, query, DELIVERY_EXPORT_ITEM_LIMIT + 1, 0)
                : repository.deliveries(
                        tenantId, state, query, accountId, provider, command,
                        dateFrom, dateTo, DELIVERY_EXPORT_ITEM_LIMIT + 1, 0);
        boolean truncated = candidates.size() > DELIVERY_EXPORT_ITEM_LIMIT;
        List<DeliveryAuditItem> items = candidates.stream()
                .limit(DELIVERY_EXPORT_ITEM_LIMIT)
                .map(row -> deliveryItem(tenantId, row, revealSensitive))
                .toList();
        String payload = deliveryExportPayload(
                exportId, snapshotCutoff, expiresAt, watermark,
                request.purpose().trim(), request.filters(), items, truncated);
        String payloadSha256 = sha256(payload);
        UUID inserted = (legacyImmediateExport
                ? repository.insertExport(
                        exportId, tenantId, actorId, request.filters(),
                        request.purpose().trim(), watermark, request.idempotencyKey(),
                        expiresAt, payload, payloadSha256, items.size(), truncated,
                        snapshotCutoff)
                : repository.insertExport(
                        exportId, tenantId, actorId, request.filters(),
                        request.purpose().trim(), watermark, request.idempotencyKey(),
                        expiresAt, payload, payloadSha256, items.size(), truncated,
                        snapshotCutoff, "DELIVERY_AUDIT", Map.of(), null, fingerprint))
                .orElse(null);
        if (inserted == null) {
            AdminMailCompletionRepository.ExportRow winner = repository.exportByCommand(
                    tenantId, actorId, request.idempotencyKey()).orElseThrow(this::conflict);
            String existingFingerprint = winner.requestFingerprint() == null
                    ? fingerprints.digest("DELIVERY_EXPORT", actorId,
                            winner.filters(), winner.purpose())
                    : winner.requestFingerprint();
            requireFingerprint(existingFingerprint, fingerprint);
            requireExportKind(winner, "DELIVERY_AUDIT");
            return deliveryExport(tenantId, winner);
        }
        return deliveryExport(tenantId, repository.export(
                tenantId, actorId, inserted).orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public DeliveryExport deliveryExport(long tenantId, UUID exportId) {
        AdminMailCompletionRepository.ExportRow export = repository
                .export(tenantId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "DELIVERY_AUDIT");
        return deliveryExport(tenantId, export);
    }

    @Transactional
    public DeliveryExport approveDeliveryExport(
            long tenantId, long actorId, UUID exportId,
            EvidenceExportApprovalRequest request) {
        approveEvidenceExport(tenantId, actorId, exportId, "DELIVERY_AUDIT", request);
        return deliveryExport(tenantId, repository.export(tenantId, exportId)
                .orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public String deliveryExportJson(long tenantId, long actorId, UUID exportId) {
        requireIdentity(tenantId, actorId);
        AdminMailCompletionRepository.ExportRow export = repository
                .export(tenantId, actorId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "DELIVERY_AUDIT");
        return evidenceExportJson(export);
    }

    @Transactional
    public RetentionEvidenceExport createRetentionEvidenceExport(
            long tenantId, long actorId, RetentionEvidenceExportRequest request) {
        requireIdentity(tenantId, actorId);
        validateScope(request.scope(), "RETENTION_EXPORT_SCOPE_INVALID");
        if (!Boolean.TRUE.equals(request.scope().get("tenant"))
                || request.scope().containsKey("accountId")
                || request.scope().containsKey("accountIds")
                || request.scope().containsKey("threadId")
                || request.scope().containsKey("threadIds")) {
            badRequest("RETENTION_EXPORT_NARROW_SCOPE_UNSUPPORTED");
        }
        if (request.scope().containsKey("resourceTypes")
                && !Set.copyOf(stringSelectors(request.scope().get("resourceTypes")))
                        .equals(RETENTION_RESOURCE_TYPES)) {
            badRequest("RETENTION_EXPORT_PARTIAL_RESOURCE_SCOPE_UNSUPPORTED");
        }
        AdminMailCompletionRepository.PolicyRow policy = repository.policy(tenantId);
        if (policy.version() != request.policyVersion()) {
            conflict("RETENTION_POLICY_VERSION_CONFLICT");
        }
        String fingerprint = fingerprints.digest(
                "RETENTION_EVIDENCE_EXPORT", actorId, request.scope(),
                request.purpose().trim(), request.policyVersion());
        var existing = repository.exportByCommand(tenantId, actorId, request.idempotencyKey());
        if (existing.isPresent()) {
            requireExportKind(existing.get(), "RETENTION_EVIDENCE");
            requireFingerprint(existing.get().requestFingerprint(), fingerprint);
            return retentionEvidenceExport(tenantId, existing.get());
        }
        OffsetDateTime cutoff = now();
        OffsetDateTime expiresAt = cutoff.plusHours(24);
        UUID exportId = UUID.randomUUID();
        String watermark = "DWP MAIL RETENTION EVIDENCE • tenant " + tenantId
                + " • user " + actorId + " • " + cutoff;
        RetentionSnapshot snapshot = retention(tenantId, true);
        Map<String, Object> payloadValue = new LinkedHashMap<>();
        payloadValue.put("exportId", exportId);
        payloadValue.put("generatedAt", cutoff);
        payloadValue.put("snapshotCutoff", cutoff);
        payloadValue.put("expiresAt", expiresAt);
        payloadValue.put("watermark", watermark);
        payloadValue.put("purpose", request.purpose().trim());
        payloadValue.put("scope", request.scope());
        payloadValue.put("policyVersion", request.policyVersion());
        payloadValue.put("retention", snapshot);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(payloadValue);
        } catch (Exception failure) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "RETENTION_EXPORT_SERIALIZATION_FAILED");
        }
        String payloadSha256 = sha256(payload);
        UUID inserted = repository.insertExport(
                exportId, tenantId, actorId, Map.of(), request.purpose().trim(),
                watermark, request.idempotencyKey(), expiresAt, payload, payloadSha256,
                snapshot.resourcePolicies().size() + snapshot.holds().size()
                        + snapshot.purgeJobs().size(),
                false, cutoff, "RETENTION_EVIDENCE", request.scope(),
                request.policyVersion(), fingerprint).orElse(null);
        if (inserted == null) {
            AdminMailCompletionRepository.ExportRow winner = repository
                    .exportByCommand(tenantId, actorId, request.idempotencyKey())
                    .orElseThrow(this::conflict);
            requireExportKind(winner, "RETENTION_EVIDENCE");
            requireFingerprint(winner.requestFingerprint(), fingerprint);
            return retentionEvidenceExport(tenantId, winner);
        }
        return retentionEvidenceExport(tenantId, repository.export(tenantId, inserted)
                .orElseThrow(this::conflict));
    }

    @Transactional(readOnly = true)
    public RetentionEvidenceExport retentionEvidenceExport(long tenantId, UUID exportId) {
        AdminMailCompletionRepository.ExportRow export = repository.export(tenantId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "RETENTION_EVIDENCE");
        return retentionEvidenceExport(tenantId, export);
    }

    @Transactional
    public RetentionEvidenceExport approveRetentionEvidenceExport(
            long tenantId, long actorId, UUID exportId,
            EvidenceExportApprovalRequest request) {
        approveEvidenceExport(tenantId, actorId, exportId, "RETENTION_EVIDENCE", request);
        return retentionEvidenceExport(tenantId, repository.export(tenantId, exportId)
                .orElseThrow(this::notFound));
    }

    @Transactional(readOnly = true)
    public String retentionEvidenceExportJson(long tenantId, long actorId, UUID exportId) {
        requireIdentity(tenantId, actorId);
        AdminMailCompletionRepository.ExportRow export = repository.export(tenantId, exportId)
                .orElseThrow(this::notFound);
        requireExportKind(export, "RETENTION_EVIDENCE");
        return evidenceExportJson(export);
    }

    private String evidenceExportJson(AdminMailCompletionRepository.ExportRow export) {
        if (export.expiresAt().isBefore(now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "EVIDENCE_EXPORT_EXPIRED");
        }
        if (!"READY".equals(export.state())
                || export.snapshotPayload() == null
                || export.payloadSha256() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "EVIDENCE_EXPORT_APPROVAL_REQUIRED");
        }
        if (!MessageDigest.isEqual(
                export.payloadSha256().getBytes(StandardCharsets.US_ASCII),
                sha256(export.snapshotPayload()).getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "EVIDENCE_EXPORT_INTEGRITY_CHECK_FAILED");
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
        validateScope(request.scope(), "LEGAL_HOLD_SCOPE_UNSUPPORTED");
        if (request.expiresAt() != null && !request.expiresAt().isAfter(request.startsAt())) {
            badRequest("LEGAL_HOLD_WINDOW_INVALID");
        }
        if (versionRequired && request.version() == null) {
            badRequest("LEGAL_HOLD_VERSION_REQUIRED");
        }
    }

    /**
     * An active hold may be widened in place, but its protected population or time window may
     * only be reduced through the separately approved release workflow. The tenant policy row is
     * locked before this comparison, so every hold mutation and release observes one serialized
     * retention lifecycle.
     */
    private void requireNonReducingActiveHoldUpdate(
            AdminMailCompletionRepository.LegalHoldRow current,
            LegalHoldRequest request) {
        if (!"ACTIVE".equals(current.status())) return;
        if (request.startsAt().isAfter(current.startsAt())) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
        if (current.expiresAt() == null && request.expiresAt() != null) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
        if (current.expiresAt() != null && request.expiresAt() != null
                && request.expiresAt().isBefore(current.expiresAt())) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
        if (!scopeContains(current.scope(), request.scope())) {
            conflict("LEGAL_HOLD_PROTECTION_REDUCTION_REQUIRES_RELEASE");
        }
    }

    private boolean scopeContains(
            Map<String, Object> current, Map<String, Object> requested) {
        boolean currentTenant = Boolean.TRUE.equals(current.get("tenant"));
        boolean requestedTenant = Boolean.TRUE.equals(requested.get("tenant"));
        if (currentTenant && !requestedTenant) return false;

        Set<UUID> currentAccounts = uuidSelectors(current, "accountId", "accountIds");
        Set<UUID> requestedAccounts = uuidSelectors(requested, "accountId", "accountIds");
        Set<UUID> currentThreads = uuidSelectors(current, "threadId", "threadIds");
        Set<UUID> requestedThreads = uuidSelectors(requested, "threadId", "threadIds");
        if (!requestedTenant) {
            if (currentAccounts.isEmpty() != requestedAccounts.isEmpty()
                    || currentThreads.isEmpty() != requestedThreads.isEmpty()) {
                return false;
            }
            if (!requestedAccounts.containsAll(currentAccounts)
                    || !requestedThreads.containsAll(currentThreads)) {
                return false;
            }
        }

        boolean currentAllResources = !current.containsKey("resourceTypes");
        boolean requestedAllResources = !requested.containsKey("resourceTypes");
        if (currentAllResources && !requestedAllResources) return false;
        if (requestedAllResources) return true;
        Set<String> currentResources = Set.copyOf(stringSelectors(current.get("resourceTypes")));
        Set<String> requestedResources = Set.copyOf(
                stringSelectors(requested.get("resourceTypes")));
        return requestedResources.containsAll(currentResources);
    }

    private void validatePurgeRequest(PurgePreviewRequest request) {
        validateScope(request.scope(), "PURGE_SCOPE_UNSUPPORTED");
        Set<String> normalized = Set.copyOf(normalizedResources(request.resourceTypes()));
        if (normalized.isEmpty() || !PURGE_RESOURCE_TYPES.containsAll(normalized)) {
            badRequest("PURGE_RESOURCE_TYPE_UNSUPPORTED");
        }
        if (request.before().isAfter(now())) badRequest("PURGE_BEFORE_MUST_NOT_BE_FUTURE");
    }

    private void validateScope(Map<String, Object> scope, String errorCode) {
        if (scope == null || scope.isEmpty() || !SCOPE_KEYS.containsAll(scope.keySet())) {
            badRequest(errorCode);
        }
        boolean tenant = Boolean.TRUE.equals(scope.get("tenant"));
        boolean hasAccount = scope.containsKey("accountId") || scope.containsKey("accountIds");
        boolean hasThread = scope.containsKey("threadId") || scope.containsKey("threadIds");
        if (!tenant && !hasAccount && !hasThread) badRequest(errorCode);
        if (tenant && (hasAccount || hasThread)) badRequest(errorCode);
        try {
            uuidSelectors(scope, "accountId", "accountIds");
            uuidSelectors(scope, "threadId", "threadIds");
            if (scope.containsKey("resourceTypes")) {
                Set<String> resources = Set.copyOf(stringSelectors(scope.get("resourceTypes")));
                if (resources.isEmpty() || !RETENTION_RESOURCE_TYPES.containsAll(resources)) {
                    badRequest(errorCode);
                }
            }
        } catch (IllegalArgumentException invalidScope) {
            badRequest(errorCode);
        }
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
                request.permissions(), request.expiresAt(), request.impactAcknowledged(),
                request.version());
    }

    private String memberFingerprint(
            long actorId, UUID inboxId, UUID memberId, String kind,
            SharedInboxMemberRevokeRequest request) {
        return fingerprints.digest(
                "SHARED_MEMBER", kind, actorId, inboxId, memberId,
                request.previewId(), normalized(request.fingerprint()),
                request.impactAcknowledged(), request.version());
    }

    private String memberRevokePreviewFingerprint(
            long actorId, UUID inboxId, UUID memberId, long memberVersion,
            AdminMailCompletionRepository.ImpactRow impact,
            boolean providerRevocationRequired) {
        return fingerprints.digest(
                "SHARED_MEMBER_REVOKE_PREVIEW", actorId, inboxId, memberId, memberVersion,
                impact.activeAssignments(), impact.openDrafts(), impact.pendingCommands(),
                providerRevocationRequired);
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

    private MailMemberDirectory.MemberIdentity requireActiveMember(
            long tenantId, long userId) {
        if (memberDirectory == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_DIRECTORY_UNAVAILABLE");
        }
        MailMemberDirectory.MemberIdentity identity =
                memberDirectory.requireActive(tenantId, userId);
        if (identity == null || !identity.activeTenantUser(tenantId, userId)) {
            badRequest("SHARED_MEMBER_USER_NOT_ACTIVE");
        }
        return identity;
    }

    private PolicyEvidenceRow policyRow(
            String key, Object configured, Object effective, String state,
            String source, OffsetDateTime evidenceAt) {
        String error = "UNVERIFIED".equals(state) ? String.valueOf(source) : null;
        String evidenceSource = "UNVERIFIED".equals(state) ? "NO_RUNTIME_ATTESTATION" : source;
        String domain = policyDomain(key);
        String reviewRequirement = "aiReviewRequirement".equals(key)
                ? (Boolean.TRUE.equals(effective) ? "REQUIRED" : "NOT_REQUIRED")
                : "UNAVAILABLE";
        return new PolicyEvidenceRow(
                key, String.valueOf(configured), String.valueOf(effective), state,
                "TENANT", evidenceSource, evidenceAt, error,
                domain, key, "TENANT_DIRECT", false, null, null, "UNAVAILABLE",
                List.of(), List.of(), "UNAVAILABLE", reviewRequirement);
    }

    private PolicyEvidenceRow policyUnavailableRow(
            String key, String domain, String contentKind, String errorCode) {
        return new PolicyEvidenceRow(
                key, "UNAVAILABLE", "UNAVAILABLE", "UNAVAILABLE", "TENANT",
                "NO_RUNTIME_ATTESTATION", now(), errorCode,
                domain, contentKind, "TENANT_DIRECT", false, null,
                null, "UNAVAILABLE", List.of(), List.of(),
                "UNAVAILABLE", "UNAVAILABLE");
    }

    private String policyDomain(String key) {
        if (key.startsWith("ai")) return "AI";
        if (key.toLowerCase(Locale.ROOT).contains("attachment")) return "ATTACHMENT";
        if (key.toLowerCase(Locale.ROOT).contains("retention")) return "RETENTION";
        return "CONTENT";
    }

    private LegalHold legalHold(AdminMailCompletionRepository.LegalHoldRow row) {
        String status = "ACTIVE".equals(row.status())
                && row.expiresAt() != null && !row.expiresAt().isAfter(now())
                ? "EXPIRED" : row.status();
        return new LegalHold(
                row.id(), row.name(), row.caseRef(), row.scope(), status,
                row.startsAt(), row.expiresAt(), row.version());
    }

    private LegalHold redactedLegalHold(AdminMailCompletionRepository.LegalHoldRow row) {
        LegalHold hold = legalHold(row);
        return new LegalHold(
                hold.holdId(), hold.name(), "REDACTED", Map.of(), hold.status(),
                hold.startsAt(), hold.expiresAt(), hold.version());
    }

    private LegalHoldReleasePreview legalHoldReleasePreview(
            long tenantId, AdminMailCompletionRepository.LegalHoldReleasePreviewRow row) {
        List<LegalHoldReleaseApproval> approvals = repository
                .legalHoldReleaseApprovals(tenantId, row.id()).stream()
                .map(this::legalHoldReleaseApproval).toList();
        boolean released = repository
                .legalHoldReleaseExecutionByPreview(tenantId, row.id()).isPresent();
        String state;
        if (released) {
            state = "RELEASED";
        } else if (approvals.stream().anyMatch(item -> "REJECT".equals(item.decision()))) {
            state = "REJECTED";
        } else if (!row.expiresAt().isAfter(now())) {
            state = "EXPIRED";
        } else if (approvals.stream().anyMatch(item -> "APPROVE".equals(item.decision()))) {
            state = "APPROVED";
        } else {
            state = "AWAITING_APPROVAL";
        }
        int approverCount = (int) approvals.stream()
                .filter(item -> "APPROVE".equals(item.decision()))
                .map(LegalHoldReleaseApproval::approverUserId).distinct().count();
        return new LegalHoldReleasePreview(
                row.id(), row.holdId(), row.requesterId(), row.holdVersion(),
                row.policyVersion(), row.holdScope(), row.retentionBoundary(),
                row.fingerprint(), new LegalHoldReleaseImpact(
                        releaseCounts(row.affectedCounts()),
                        releaseCounts(row.currentlyHeldCounts()),
                        releaseCounts(row.purgeSafeCounts()),
                        releaseCounts(row.protectedCounts()),
                        releaseCounts(row.providerRequiredCounts())),
                state, approverCount, approvals, row.generatedAt(), row.expiresAt());
    }

    private LegalHoldReleaseApproval legalHoldReleaseApproval(
            AdminMailCompletionRepository.LegalHoldReleaseApprovalRow row) {
        return new LegalHoldReleaseApproval(
                row.id(), row.previewId(), row.approverId(), row.decision(),
                row.holdVersion(), row.policyVersion(), row.decidedAt());
    }

    private LegalHoldReleaseExecution legalHoldReleaseExecution(
            long tenantId, AdminMailCompletionRepository.LegalHoldReleaseExecutionRow row,
            boolean replayed) {
        LegalHold hold = legalHold(repository.legalHold(tenantId, row.holdId())
                .orElseThrow(this::conflict));
        return new LegalHoldReleaseExecution(
                row.id(), row.previewId(), row.holdId(), row.requesterId(),
                row.approvedById(), row.executorId(), row.policyVersion(),
                row.previewFingerprint(), hold, row.executedAt(), replayed);
    }

    private void requireLiveLegalHoldReleasePreview(
            AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview,
            String fingerprint, long holdVersion, long policyVersion) {
        if (!preview.expiresAt().isAfter(now())) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_EXPIRED");
        }
        requireFingerprint(preview.fingerprint(), fingerprint.toLowerCase(Locale.ROOT));
        if (preview.holdVersion() != holdVersion) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
        if (preview.policyVersion() != policyVersion) {
            conflict("POLICY_VERSION_CONFLICT");
        }
    }

    private AdminMailCompletionRepository.LegalHoldRow requireCurrentLegalHoldReleaseVersions(
            long tenantId, AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview) {
        AdminMailCompletionRepository.LegalHoldRow hold = repository
                .legalHold(tenantId, preview.holdId()).orElseThrow(this::notFound);
        requireReleasableHold(hold, preview.holdVersion());
        if (!fingerprints.digest(hold.scope()).equals(
                fingerprints.digest(preview.holdScope()))) {
            conflict("LEGAL_HOLD_RELEASE_PREVIEW_STALE");
        }
        if (repository.policy(tenantId).version() != preview.policyVersion()) {
            conflict("POLICY_VERSION_CONFLICT");
        }
        return hold;
    }

    private void requireReleasableHold(
            AdminMailCompletionRepository.LegalHoldRow hold, long expectedVersion) {
        if (!"ACTIVE".equals(hold.status())) {
            conflict("LEGAL_HOLD_NOT_ACTIVE");
        }
        if (hold.version() != expectedVersion) {
            conflict("LEGAL_HOLD_VERSION_CONFLICT");
        }
    }

    private LegalHoldReleaseAssessment assessLegalHoldRelease(
            AdminMailCompletionRepository.CandidateSet candidates,
            AdminMailCompletionRepository.LegalHoldRow targetHold,
            List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds) {
        List<String> resourceTypes = legalHoldPurgeResourceTypes(targetHold.scope());
        PurgeAssessment withTarget = assessPurge(
                candidates, targetHold.scope(), resourceTypes, List.of(targetHold));
        PurgeAssessment afterRelease = assessPurge(
                candidates, targetHold.scope(), resourceTypes, otherActiveHolds);
        Map<String, Long> affected = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes, row -> true);
        Map<String, Long> currentlyHeld = releaseResourceCounts(
                withTarget.snapshotRows(), resourceTypes,
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::held);
        Map<String, Long> purgeSafe = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes,
                row -> !row.held() && "DWP_SANDBOX".equals(row.providerType()));
        Map<String, Long> stillProtected = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes,
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::held);
        Map<String, Long> providerRequired = releaseResourceCounts(
                afterRelease.snapshotRows(), resourceTypes,
                row -> !row.held() && !"DWP_SANDBOX".equals(row.providerType()));
        return new LegalHoldReleaseAssessment(
                affected, currentlyHeld, purgeSafe, stillProtected, providerRequired,
                afterRelease.fingerprintRows());
    }

    private List<String> legalHoldPurgeResourceTypes(Map<String, Object> scope) {
        if (!scope.containsKey("resourceTypes")) {
            return normalizedResources(List.copyOf(PURGE_RESOURCE_TYPES));
        }
        return stringSelectors(scope.get("resourceTypes")).stream()
                .filter(PURGE_RESOURCE_TYPES::contains).sorted().toList();
    }

    private Map<String, Long> releaseResourceCounts(
            List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> rows,
            List<String> resourceTypes,
            java.util.function.Predicate<AdminMailCompletionRepository.PurgeCandidateSnapshotRow>
                    included) {
        Set<String> requested = Set.copyOf(resourceTypes);
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> selected = rows.stream()
                .filter(included).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("THREADS", requested.contains("THREADS") ? (long) selected.size() : 0L);
        counts.put("MESSAGES", requested.contains("MESSAGES") ? selected.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::messageCount)
                .sum() : 0L);
        counts.put("ATTACHMENTS", requested.contains("ATTACHMENTS") ? selected.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::attachmentCount)
                .sum() : 0L);
        counts.put("DRAFTS", requested.contains("DRAFTS") ? selected.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::draftCount)
                .sum() : 0L);
        return Map.copyOf(counts);
    }

    private Map<String, Long> releaseCounts(Map<String, Object> stored) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String resource : List.of("THREADS", "MESSAGES", "ATTACHMENTS", "DRAFTS")) {
            Object value = stored.get(resource);
            if (!(value instanceof Number number) || number.longValue() < 0) {
                conflict("LEGAL_HOLD_RELEASE_PREVIEW_EVIDENCE_INVALID");
            }
            counts.put(resource, ((Number) value).longValue());
        }
        return Map.copyOf(counts);
    }

    private String legalHoldReleaseSnapshotFingerprint(
            long tenantId, AdminMailCompletionRepository.LegalHoldRow hold,
            long policyVersion, OffsetDateTime retentionBoundary,
            List<AdminMailCompletionRepository.LegalHoldRow> otherActiveHolds,
            LegalHoldReleaseAssessment assessment) {
        return fingerprints.digest(
                "LEGAL_HOLD_RELEASE_SNAPSHOT", tenantId, hold.id(), hold.version(),
                hold.scope(), hold.status(), hold.startsAt(), hold.expiresAt(),
                policyVersion, retentionBoundary,
                otherActiveHolds.stream().map(other -> List.of(
                        other.id(), other.scope(), other.version())).toList(),
                assessment.fingerprintRows(), assessment.affected(),
                assessment.currentlyHeld(), assessment.purgeSafe(),
                assessment.stillProtected(), assessment.providerRequired());
    }

    private boolean releaseAssessmentMatches(
            AdminMailCompletionRepository.LegalHoldReleasePreviewRow preview,
            LegalHoldReleaseAssessment assessment) {
        return releaseCounts(preview.affectedCounts()).equals(assessment.affected())
                && releaseCounts(preview.currentlyHeldCounts())
                        .equals(assessment.currentlyHeld())
                && releaseCounts(preview.purgeSafeCounts()).equals(assessment.purgeSafe())
                && releaseCounts(preview.protectedCounts()).equals(assessment.stillProtected())
                && releaseCounts(preview.providerRequiredCounts())
                        .equals(assessment.providerRequired());
    }

    private List<PurgeJob> purgeJobs(long tenantId) {
        return repository.purgeJobs(tenantId, 50).stream().map(this::purgeJob).toList();
    }

    private PurgePreview purgePreview(
            long tenantId, AdminMailCompletionRepository.PurgePreviewRow row) {
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> candidates =
                repository.purgeCandidateRows(tenantId, row.id());
        Map<String, Long> resourceCounts = new LinkedHashMap<>();
        resourceCounts.put("THREADS", (long) candidates.size());
        resourceCounts.put("MESSAGES", candidates.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::messageCount)
                .sum());
        resourceCounts.put("ATTACHMENTS", candidates.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::attachmentCount)
                .sum());
        resourceCounts.put("DRAFTS", candidates.stream()
                .mapToLong(AdminMailCompletionRepository.PurgeCandidateSnapshotRow::draftCount)
                .sum());
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> heldCandidates =
                candidates.stream().filter(
                        AdminMailCompletionRepository.PurgeCandidateSnapshotRow::held).toList();
        Map<String, Long> heldResourceCounts = new LinkedHashMap<>();
        heldResourceCounts.put("THREADS", (long) heldCandidates.size());
        heldResourceCounts.put("MESSAGES", heldCandidates.stream().mapToLong(
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::messageCount).sum());
        heldResourceCounts.put("ATTACHMENTS", heldCandidates.stream().mapToLong(
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::attachmentCount).sum());
        heldResourceCounts.put("DRAFTS", heldCandidates.stream().mapToLong(
                AdminMailCompletionRepository.PurgeCandidateSnapshotRow::draftCount).sum());
        Map<String, Long> exclusionReasonCounts = new LinkedHashMap<>();
        exclusionReasonCounts.put("LEGAL_HOLD", candidates.stream().filter(candidate ->
                Boolean.TRUE.equals(candidate.evidence().get("legalHoldBlocked"))).count());
        exclusionReasonCounts.put("IMMUTABLE_EVIDENCE", candidates.stream().filter(candidate ->
                Boolean.TRUE.equals(candidate.evidence().get("immutableEvidenceBlocked"))).count());
        return new PurgePreview(
                row.id(), row.fingerprint(), row.total(), row.held(), row.eligible(),
                row.partialSources(), row.createdAt(), row.expiresAt(), row.policyVersion(),
                repository.distinctApprovals(tenantId, row.id(), row.policyVersion()),
                Map.copyOf(resourceCounts), row.resourceTypes(), row.scope(), row.before(),
                Map.copyOf(heldResourceCounts), Map.copyOf(exclusionReasonCounts));
    }

    private PurgeApproval purgeApproval(
            long tenantId, AdminMailCompletionRepository.PurgeApprovalRow row) {
        return new PurgeApproval(
                row.id(), row.snapshotId(), repository.distinctApprovals(
                        tenantId, row.snapshotId(), row.policyVersion()),
                row.policyVersion(), row.approvedAt());
    }

    private PurgePreview purgePreviewProjection(
            PurgePreview preview, boolean includeSensitiveScope) {
        if (includeSensitiveScope) return preview;
        return new PurgePreview(
                preview.candidateSnapshotId(), preview.fingerprint(),
                preview.totalCandidates(), preview.heldCount(), preview.eligibleCount(),
                preview.partialSources(), preview.generatedAt(), preview.expiresAt(),
                preview.policyVersion(), preview.distinctApproverCount(), Map.of(),
                preview.resourceTypes(), Map.of(), null, Map.of(), Map.of());
    }

    private PurgeJob purgeJob(AdminMailCompletionRepository.PurgeJobRow row) {
        return new PurgeJob(
                row.id(), row.snapshotId(), row.state(), row.deletedThreads(),
                row.deletedMessages(), row.steps(), row.verification(), row.errorCode(),
                row.startedAt(), row.completedAt());
    }

    private PurgeJob purgeJobProjection(
            PurgeJob job, boolean includeSensitiveEvidence) {
        if (includeSensitiveEvidence) return job;
        return new PurgeJob(
                job.jobId(), job.candidateSnapshotId(), job.state(),
                job.deletedThreads(), job.deletedMessages(), List.of(),
                job.verificationState(), null, job.startedAt(), job.completedAt());
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

    private String purgeFingerprint(
            long tenantId, Map<String, Object> scope, List<String> resourceTypes,
            OffsetDateTime before, long policyVersion,
            List<AdminMailCompletionRepository.LegalHoldRow> activeHolds,
            PurgeAssessment assessment) {
        return fingerprints.digest(
                "PURGE_SNAPSHOT", tenantId, scope, normalizedResources(resourceTypes), before,
                policyVersion,
                activeHolds.stream().map(hold -> List.of(
                        hold.id(), hold.scope(), hold.version())).toList(),
                assessment.fingerprintRows(), assessment.total(), assessment.held(),
                assessment.eligible());
    }

    private PurgeAssessment assessPurge(
            AdminMailCompletionRepository.CandidateSet candidates,
            Map<String, Object> scope,
            List<String> resourceTypes,
            List<AdminMailCompletionRepository.LegalHoldRow> activeHolds) {
        Set<String> requested = Set.copyOf(normalizedResources(resourceTypes));
        List<AdminMailCompletionRepository.CandidateRow> scoped = candidates.rows().stream()
                .filter(row -> matchesSelectors(scope, row))
                .toList();
        int total = 0;
        int held = 0;
        int blockedThreads = 0;
        int blockedMessages = 0;
        boolean external = false;
        List<UUID> eligibleThreadIds = new ArrayList<>();
        List<List<Object>> fingerprintRows = new ArrayList<>();
        List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows =
                new ArrayList<>();
        for (AdminMailCompletionRepository.CandidateRow row : scoped) {
            int units = candidateUnits(row, requested);
            List<UUID> matchingHoldIds = activeHolds.stream()
                    .filter(hold -> holdMatches(hold.scope(), row, requested))
                    .map(AdminMailCompletionRepository.LegalHoldRow::id).toList();
            boolean legalHoldBlocked = !matchingHoldIds.isEmpty();
            boolean blocked = row.immutableEvidenceBlocked() || legalHoldBlocked;
            total += units;
            if (blocked) {
                held += units;
                blockedThreads++;
                blockedMessages += row.messageCount();
            } else if (units > 0) {
                eligibleThreadIds.add(row.threadId());
                external |= !"DWP_SANDBOX".equals(row.providerType());
            }
            fingerprintRows.add(List.of(
                    row.threadId(), row.accountId(), row.messageCount(),
                    row.attachmentCount(), row.draftCount(), row.providerType(), blocked));
            snapshotRows.add(new AdminMailCompletionRepository.PurgeCandidateSnapshotRow(
                    row.threadId(), row.accountId(), row.messageCount(),
                    row.attachmentCount(), row.draftCount(), row.providerType(), blocked,
                    matchingHoldIds,
                    Map.of("immutableEvidenceBlocked", row.immutableEvidenceBlocked(),
                            "legalHoldBlocked", legalHoldBlocked,
                            "resourceTypes", normalizedResources(resourceTypes))));
        }
        return new PurgeAssessment(
                total, held, total - held, List.copyOf(eligibleThreadIds),
                blockedThreads, blockedMessages, external, List.copyOf(fingerprintRows),
                List.copyOf(snapshotRows));
    }

    private int candidateUnits(
            AdminMailCompletionRepository.CandidateRow row, Set<String> requested) {
        int total = 0;
        if (requested.contains("THREADS")) total++;
        if (requested.contains("MESSAGES")) total += row.messageCount();
        if (requested.contains("ATTACHMENTS")) total += row.attachmentCount();
        if (requested.contains("DRAFTS")) total += row.draftCount();
        return total;
    }

    private boolean matchesSelectors(
            Map<String, Object> scope, AdminMailCompletionRepository.CandidateRow row) {
        if (Boolean.TRUE.equals(scope.get("tenant"))) return true;
        Set<UUID> accounts = uuidSelectors(scope, "accountId", "accountIds");
        Set<UUID> threads = uuidSelectors(scope, "threadId", "threadIds");
        return (accounts.isEmpty() || accounts.contains(row.accountId()))
                && (threads.isEmpty() || threads.contains(row.threadId()));
    }

    private boolean holdMatches(
            Map<String, Object> holdScope,
            AdminMailCompletionRepository.CandidateRow row,
            Set<String> requestedResources) {
        if (holdScope == null || holdScope.isEmpty()
                || !SCOPE_KEYS.containsAll(holdScope.keySet())) {
            return true;
        }
        try {
            if (!matchesSelectors(holdScope, row)) return false;
            if (!holdScope.containsKey("resourceTypes")) return true;
            Set<String> heldResources = Set.copyOf(stringSelectors(holdScope.get("resourceTypes")));
            return heldResources.stream().anyMatch(requestedResources::contains);
        } catch (RuntimeException malformedLegacyScope) {
            return true;
        }
    }

    private Set<UUID> uuidSelectors(
            Map<String, Object> scope, String singularKey, String pluralKey) {
        java.util.LinkedHashSet<UUID> values = new java.util.LinkedHashSet<>();
        Object singular = scope.get(singularKey);
        if (singular != null) values.add(UUID.fromString(String.valueOf(singular)));
        Object plural = scope.get(pluralKey);
        if (plural != null) {
            if (!(plural instanceof List<?> list)) throw new IllegalArgumentException("selector");
            for (Object value : list) values.add(UUID.fromString(String.valueOf(value)));
        }
        return Set.copyOf(values);
    }

    private List<String> stringSelectors(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("selector");
        return list.stream().map(String::valueOf)
                .map(item -> item.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private record PurgeAssessment(
            int total, int held, int eligible, List<UUID> eligibleThreadIds,
            int blockedThreadCount, int blockedMessageCount,
            boolean hasExternalProvider, List<List<Object>> fingerprintRows,
            List<AdminMailCompletionRepository.PurgeCandidateSnapshotRow> snapshotRows) { }

    private record LegalHoldReleaseAssessment(
            Map<String, Long> affected,
            Map<String, Long> currentlyHeld,
            Map<String, Long> purgeSafe,
            Map<String, Long> stillProtected,
            Map<String, Long> providerRequired,
            List<List<Object>> fingerprintRows) { }

    private DeliveryAuditItem deliveryItem(
            long tenantId, AdminMailCompletionRepository.DeliveryRow row) {
        return deliveryItem(tenantId, row, true);
    }

    private DeliveryAuditItem deliveryItem(
            long tenantId, AdminMailCompletionRepository.DeliveryRow row,
            boolean revealSensitive) {
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
        List<AdminMailCompletionRepository.DeliveryEvidenceRow> evidence =
                repository.deliveryEvidence(tenantId, row);
        if (evidence != null) evidence.forEach(event -> timeline.add(
                new DeliveryAuditTimeline(
                        event.stage(), event.state(), event.at(), event.source(),
                        event.evidenceState(), event.code())));
        timeline.sort(java.util.Comparator.comparing(DeliveryAuditTimeline::at));
        List<DeliveryAuditTimeline> projectedTimeline = timeline;
        if (!revealSensitive) {
            projectedTimeline = timeline.stream().map(event -> new DeliveryAuditTimeline(
                    event.stage(), event.state(), event.at(), "EVIDENCE_REDACTED",
                    event.evidenceState(), null)).toList();
        }
        boolean fresh = fresh(row.updatedAt(), now(), DELIVERY_EVIDENCE_FRESH);
        return new DeliveryAuditItem(
                row.id(), safeResourceReference("message", row.id().toString()),
                "SEND", revealSensitive ? "Mail administrator" : "REDACTED",
                revealSensitive ? row.accountName() : "REDACTED",
                revealSensitive ? row.providerType() : "REDACTED", deliveryStage(row),
                deliveryState(row), fresh && retryEligible(row) ? "ELIGIBLE" : "INELIGIBLE",
                providerDisposition(row), idempotencyState(row),
                fresh && reconcileEligible(row), fresh && cancelEligible(row),
                row.updatedAt(), revealSensitive ? nullToEmpty(row.correlationId()) : "",
                projectedTimeline, row.version());
    }

    private String deliveryStage(AdminMailCompletionRepository.DeliveryRow row) {
        if (row.acceptedAt() != null) return "ACCEPTED_BY_PROVIDER";
        if (deliveryBlockedByAccess(row)) return "BLOCKED_BY_ACCESS";
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
        if (deliveryBlockedByAccess(row)) return "BLOCKED_BY_ACCESS";
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
        if (deliveryBlockedByAccess(row)) return "BLOCKED";
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
                && !deliveryBlockedByAccess(row)
                && !"MAIL_PROVIDER_RESULT_UNKNOWN".equals(row.errorCode());
    }

    private boolean deliveryBlockedByAccess(
            AdminMailCompletionRepository.DeliveryRow row) {
        String code = nullToEmpty(row.errorCode()).toUpperCase(Locale.ROOT);
        return DELIVERY_ACCESS_TERMINAL_ERRORS.contains(code)
                || code.startsWith("MAIL_SEND_AUTHORIZATION_")
                || code.startsWith("MAIL_ADAPTER_AUTHENTICATION_")
                || code.startsWith("MAIL_ADAPTER_CONFIGURATION_")
                || code.startsWith("MAIL_ADAPTER_READINESS_");
    }

    private String deliveryRecoveryAuthorizationError(
            long tenantId, AdminMailCompletionRepository.DeliveryRow delivery) {
        AdminMailCompletionRepository.DeliveryAuthorizationRow authorization = repository
                .deliveryAuthorization(tenantId, delivery.id()).orElse(null);
        if (authorization == null) return "MAIL_SEND_AUTHORIZATION_REVOKED";

        MailTypes.ProviderType providerType;
        try {
            providerType = MailTypes.ProviderType.valueOf(authorization.providerType());
        } catch (RuntimeException invalidProvider) {
            return "MAIL_ADAPTER_NOT_DEPLOYED";
        }
        MailConnectorPort connector = connectorRegistry.connector(providerType).orElse(null);
        if (connector == null) return "MAIL_ADAPTER_NOT_DEPLOYED";

        MailConnectorPort.ConnectionContext context;
        try {
            context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(
                            Long.toString(tenantId), Long.toString(authorization.senderId()),
                            Set.of(), correlation(delivery.correlationId())),
                    authorization.connectionId(), authorization.secretReference(),
                    authorization.mailDomain());
            MailConnectorPort.Readiness readiness = connector.readiness(context);
            if (readiness.state() != MailConnectorPort.ReadinessState.READY) {
                return switch (readiness.state()) {
                    case AUTHENTICATION_REQUIRED ->
                            "MAIL_ADAPTER_AUTHENTICATION_REQUIRED";
                    case CONFIGURATION_REQUIRED ->
                            "MAIL_ADAPTER_CONFIGURATION_REQUIRED";
                    default -> "MAIL_ADAPTER_READINESS_" + readiness.state().name();
                };
            }
        } catch (RuntimeException unavailable) {
            return "MAIL_ADAPTER_READINESS_UNAVAILABLE";
        }

        Set<MailConnectorPort.Capability> capabilities = connector.manifest().capabilities();
        if (!capabilities.contains(MailConnectorPort.Capability.SEND)) {
            return "MAIL_ADAPTER_SEND_NOT_SUPPORTED";
        }
        if ("SEND_ON_BEHALF".equals(authorization.senderMode())
                && !capabilities.contains(MailConnectorPort.Capability.SEND_ON_BEHALF)) {
            return "MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED";
        }
        if (authorization.hasBcc()
                && !capabilities.contains(MailConnectorPort.Capability.BCC)) {
            return "MAIL_ADAPTER_BCC_NOT_SUPPORTED";
        }
        if ("HTML".equals(authorization.bodyFormat())
                && !capabilities.contains(MailConnectorPort.Capability.HTML_BODY)) {
            return "MAIL_ADAPTER_HTML_NOT_SUPPORTED";
        }
        if (authorization.attachmentCount() > 0
                && !capabilities.contains(MailConnectorPort.Capability.ATTACHMENTS)) {
            return "MAIL_ADAPTER_ATTACHMENTS_NOT_SUPPORTED";
        }
        return null;
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

    private void approveEvidenceExport(
            long tenantId, long actorId, UUID exportId, String expectedKind,
            EvidenceExportApprovalRequest request) {
        requireIdentity(tenantId, actorId);
        if (!"APPROVE".equalsIgnoreCase(request.decision().trim())) {
            badRequest("EVIDENCE_EXPORT_DECISION_UNSUPPORTED");
        }
        AdminMailCompletionRepository.ExportRow export = repository
                .exportForUpdate(tenantId, exportId).orElseThrow(this::notFound);
        requireExportKind(export, expectedKind);
        if (export.expiresAt().isBefore(now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "EVIDENCE_EXPORT_EXPIRED");
        }
        if (export.actorId() == actorId) {
            conflict("EVIDENCE_EXPORT_SELF_APPROVAL_FORBIDDEN");
        }
        String fingerprint = fingerprints.digest(
                "EVIDENCE_EXPORT_APPROVAL", actorId, exportId, expectedKind, "APPROVED");
        var replay = repository.exportApprovalByCommand(
                tenantId, actorId, request.idempotencyKey());
        if (replay.isPresent()) {
            requireFingerprint(replay.get().requestFingerprint(), fingerprint);
            if (!replay.get().exportId().equals(exportId)) {
                conflict("IDEMPOTENCY_TARGET_MISMATCH");
            }
            repository.markExportReady(tenantId, exportId);
            return;
        }
        UUID approvalId = repository.insertExportApproval(
                tenantId, exportId, actorId, request.idempotencyKey(), fingerprint)
                .orElse(null);
        if (approvalId == null) {
            conflict("EVIDENCE_EXPORT_APPROVAL_ALREADY_RECORDED");
        }
        repository.markExportReady(tenantId, exportId);
        repository.audit(
                tenantId, actorId, "mail.evidence-export.approved",
                "MAIL_EVIDENCE_EXPORT", exportId.toString(), null,
                Map.of("state", export.state()), Map.of(
                        "state", "READY", "approvalId", approvalId,
                        "exportKind", expectedKind));
    }

    private DeliveryExport deliveryExport(
            long tenantId, AdminMailCompletionRepository.ExportRow row) {
        List<EvidenceExportApproval> approvals = evidenceExportApprovals(tenantId, row.id());
        boolean ready = "READY".equals(row.state()) && row.expiresAt().isAfter(now());
        return new DeliveryExport(
                row.id(), row.state(), row.filters(), row.expiresAt(), row.watermark(),
                row.itemCount(), row.truncated(), row.payloadSha256(), row.snapshotCutoff(),
                ready ? "/api/platform/v1/admin/mail/delivery-audit/exports/"
                        + row.id() + "/download" : null,
                ready ? "APPROVED" : "PENDING_APPROVAL", row.requiredApprovals(),
                approvals.size(), approvals);
    }

    private RetentionEvidenceExport retentionEvidenceExport(
            long tenantId, AdminMailCompletionRepository.ExportRow row) {
        List<EvidenceExportApproval> approvals = evidenceExportApprovals(tenantId, row.id());
        boolean ready = "READY".equals(row.state()) && row.expiresAt().isAfter(now());
        return new RetentionEvidenceExport(
                row.id(), row.state(), row.exportScope(),
                row.policyVersion() == null ? 0 : row.policyVersion(),
                row.expiresAt(), row.watermark(), row.payloadSha256(), row.snapshotCutoff(),
                ready ? "/api/platform/v1/admin/mail/retention/evidence-exports/"
                        + row.id() + "/download" : null,
                ready ? "APPROVED" : "PENDING_APPROVAL", row.requiredApprovals(),
                approvals.size(), approvals);
    }

    private List<EvidenceExportApproval> evidenceExportApprovals(
            long tenantId, UUID exportId) {
        return repository.exportApprovals(tenantId, exportId).stream()
                .map(row -> new EvidenceExportApproval(
                        row.id(), row.approverUserId(), row.decision(), row.decidedAt()))
                .toList();
    }

    private void requireExportKind(
            AdminMailCompletionRepository.ExportRow export, String expectedKind) {
        if (!expectedKind.equals(export.exportKind())) {
            throw notFound();
        }
    }

    private String exportText(Map<String, Object> filters, String key) {
        Object value = filters.get(key);
        if (value == null) return "";
        if (!(value instanceof String)) badRequest("DELIVERY_EXPORT_FILTER_INVALID");
        return ((String) value).trim();
    }

    private UUID exportUuid(Map<String, Object> filters, String key) {
        String value = exportText(filters, key);
        if (value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            badRequest("DELIVERY_EXPORT_FILTER_INVALID");
            return null;
        }
    }

    private LocalDate exportDate(Map<String, Object> filters, String key) {
        String value = exportText(filters, key);
        if (value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            badRequest("DELIVERY_EXPORT_FILTER_INVALID");
            return null;
        }
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

    private ResponseStatusException conflictException(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
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

    private static final class DeliveryRecoveryBlocked extends ResponseStatusException {
        private static final long serialVersionUID = 1L;

        private DeliveryRecoveryBlocked(String code) {
            super(HttpStatus.CONFLICT, code);
        }
    }
}
