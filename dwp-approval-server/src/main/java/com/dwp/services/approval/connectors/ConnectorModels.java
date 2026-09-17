package com.dwp.services.approval.connectors;

import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConnectorModels {
    private ConnectorModels() {
    }

    public enum ConnectorType {
        REST, WEBHOOK, KAFKA, ERP, SIGNATURE, CUSTOM
    }

    public enum Lifecycle {
        DRAFT, ACTIVE, DISABLED, RETIRED
    }

    public enum IdempotencyMode {
        HEADER, BODY_HASH, PROVIDER_NATIVE
    }

    public enum SigningMode {
        NONE, HMAC_SHA256, JWS_ED25519, MTLS
    }

    public enum ProbeKind {
        READINESS, SYNTHETIC_TEST
    }

    public enum ProbeState {
        PENDING, RUNNING, VERIFIED, FAILED, UNKNOWN_REMOTE_OUTCOME
    }

    public record Context(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            UUID actorPersonPublicId,
            String idempotencyKey) {
        public Context {
            if (tenantId < 1 || actorUserId < 1 || actorPersonPublicId == null
                    || resourceSetKey == null
                    || !resourceSetKey.matches("RS_[A-Z0-9_]{1,76}")
                    || idempotencyKey == null
                    || !idempotencyKey.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")) {
                throw ConnectorRejected.invalid("Current connector command context is invalid.");
            }
        }

        public static Context current(String idempotencyKey) {
            ApprovalRequestContext.Actor actor = ApprovalRequestContext.require();
            if (actor.tenantId() == null || actor.userId() == null) {
                throw ConnectorRejected.forbidden("Current connector authority is incomplete.");
            }
            return new Context(actor.tenantId(),
                    ApprovalManagementScopeContext.requireResourceSetKey(),
                    actor.userId(), actor.personPublicId(), idempotencyKey);
        }
    }

    public record ConnectorDraft(
            UUID connectorId,
            String connectorKey,
            String displayName,
            ConnectorType connectorType,
            String endpointUri,
            String credentialReference,
            List<String> headerAllowlist,
            Map<String, Object> requestMapping,
            Map<String, Object> responseMapping,
            int timeoutMillis,
            int rateLimitPerMinute,
            int maxAttempts,
            int initialBackoffMillis,
            int maxBackoffMillis,
            IdempotencyMode idempotencyMode,
            SigningMode signingMode,
            long expectedVersion) {
    }

    public record ConnectorView(
            UUID connectorId,
            String connectorKey,
            String displayName,
            ConnectorType connectorType,
            Lifecycle lifecycle,
            long version,
            UUID draftRevisionId,
            UUID publishedRevisionId,
            UUID selectedRevisionId,
            long revisionNumber,
            String endpointUri,
            String credentialReference,
            List<String> headerAllowlist,
            Map<String, Object> requestMapping,
            Map<String, Object> responseMapping,
            int timeoutMillis,
            int rateLimitPerMinute,
            int maxAttempts,
            int initialBackoffMillis,
            int maxBackoffMillis,
            IdempotencyMode idempotencyMode,
            SigningMode signingMode,
            String definitionSha256,
            long makerUserId,
            UUID makerPersonPublicId,
            long editorUserId,
            UUID editorPersonPublicId) {
    }

    public record ProbeStart(
            UUID probeId,
            UUID revisionId,
            ProbeKind probeKind,
            String requestSha256,
            long expectedConnectorVersion) {
    }

    public record ProbeCompletion(
            long expectedProbeVersion,
            ProbeState state,
            String evidenceRevision,
            String evidenceSha256,
            Map<String, Object> diagnostics,
            Instant completedAt,
            Instant validUntil,
            String evidencePayloadBase64Url,
            String evidenceSignatureBase64Url) {
    }

    public record ProbeView(
            UUID connectorId,
            UUID probeId,
            UUID revisionId,
            ProbeKind probeKind,
            ProbeState state,
            String requestSha256,
            String evidenceRevision,
            String evidenceSha256,
            Map<String, Object> diagnostics,
            Instant startedAt,
            Instant completedAt,
            Instant validUntil,
            long version) {
    }

    public record ConnectorDetail(
            ConnectorView connector,
            List<ProbeView> probes) {
    }

    public record PublishCommand(
            UUID revisionId,
            UUID verifiedProbeId,
            long expectedVersion,
            String reviewEvidenceSha256) {
    }

    public record LifecycleCommand(
            Lifecycle lifecycle,
            long expectedVersion,
            String reason) {
    }
}
