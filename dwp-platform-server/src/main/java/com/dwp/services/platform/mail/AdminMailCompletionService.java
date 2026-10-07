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
public class AdminMailCompletionService extends AdminMailCompletionPolicySupport {

    @Autowired
    public AdminMailCompletionService(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectorRegistry,
            ObjectMapper objectMapper,
            MailExternalSyncMaterializer syncMaterializer,
            AdminMailOperationDurability operationDurability,
            AdminMailPurgeTransactions purgeTransactions,
            MailMemberDirectory memberDirectory) {
        super(repository, connectorRegistry, objectMapper, syncMaterializer,
                operationDurability, purgeTransactions, memberDirectory);
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

}
