package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.core.io.Resource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.*;

class MailWorkspacePolicySupport extends MailWorkspaceContentSupport {

    MailWorkspacePolicySupport(
            MailWorkspaceRepository repository,
            MailQueryRepository queries,
            MailService mail,
            TenantMediaStorage storage,
            List<MailAttachmentScanner> attachmentScanners,
            MailConnectorRegistry connectors) {
        super(repository, queries, mail, storage, attachmentScanners, connectors);
    }

    Preferences effectivePreferences(long tenantId, long userId) {
        return effective(repository.preferences(tenantId, userId), tenantId);
    }

    Preferences effective(Preferences stored, long tenantId) {
        if (!repository.blockRemoteImages(tenantId)) return stored;
        Map<String, String> locks = Map.of(
                "remoteImages", "Organization policy blocks remote images.");
        return new Preferences(
                stored.density(), "BLOCK", stored.sendDelaySeconds(), stored.keyboardShortcuts(),
                stored.notifyNewMail(), stored.notifySharedAssignment(), stored.notifyFollowUpDue(),
                stored.defaultAccountId(), stored.defaultSignatureId(), locks, stored.version());
    }

    void validateAssetSelection(
            long tenantId, long userId, UUID accountId, UUID templateId, UUID signatureId) {
        if (templateId != null) {
            Template template = repository.template(tenantId, userId, templateId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.NOT_FOUND, "The selected template was not found."));
            requireAssetAccount(accountId, template.accountId());
        }
        if (signatureId != null) {
            Signature signature = repository.signature(tenantId, userId, signatureId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.NOT_FOUND, "The selected signature was not found."));
            requireAssetAccount(accountId, signature.accountId());
        }
    }

    void validateSendAssetSelection(
            long tenantId,
            long userId,
            UUID accountId,
            UUID templateId,
            UUID signatureId,
            String body,
            BodyFormat bodyFormat) {
        if (templateId != null) {
            Template template = repository.templateForSend(
                            tenantId, userId, accountId, templateId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.NOT_FOUND, "The selected template was not found."));
            requireMandatoryContent(body, bodyFormat, template.mandatoryContent(), "template");
        }

        Signature signature;
        if (signatureId != null) {
            signature = repository.signatureForSend(
                            tenantId, userId, accountId, signatureId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.NOT_FOUND, "The selected signature was not found."));
        } else {
            UUID preferredSignatureId = repository.preferredSignatureId(tenantId, userId)
                    .orElse(null);
            if (preferredSignatureId != null) {
                signature = repository.signatureForSend(
                                tenantId, userId, accountId, preferredSignatureId)
                        .orElseThrow(() -> conflict(
                                "The default signature is no longer available. Refresh before sending."));
            } else {
                signature = repository.defaultSignatureForNew(tenantId, userId, accountId)
                        .orElse(null);
            }
        }
        if (signature != null) {
            requireMandatoryContent(body, bodyFormat, signature.mandatoryContent(), "signature");
        }
    }

    void requireMandatoryContent(
            String body, BodyFormat bodyFormat, String mandatoryContent, String assetType) {
        if (mandatoryContent == null || mandatoryContent.isBlank()) return;
        String required = bodyFormat == BodyFormat.HTML
                ? sanitizedHtml(mandatoryContent) : mandatoryContent.trim();
        if (!body.contains(required)) {
            throw invalid("The message must include the selected "
                    + assetType + " mandatory content.");
        }
    }

    void validateAttachmentTotalSize(
            long tenantId, long userId, List<UUID> attachmentIds, UUID threadId) {
        if (attachmentIds.isEmpty()) return;
        long maximumBytes = Math.multiplyExact(
                repository.maximumAttachmentMb(tenantId), BYTES_PER_MIB);
        if (!repository.attachmentsWithinTotalSize(
                tenantId, userId, attachmentIds, threadId, maximumBytes)) {
            throw invalid("The combined attachment size exceeds the organization limit.");
        }
    }

    ComposeCapabilities accountCapabilities(
            long tenantId, long userId, UUID accountId, int maximumAttachmentMb) {
        AccountRuntime runtime = accountRuntime(
                tenantId, userId, accountId, maximumAttachmentMb, null);
        MailTypes.ProviderType providerType = repository
                .composeProviderContext(tenantId, userId, accountId)
                .map(MailWorkspaceRepository.ComposeProviderContext::providerType)
                .orElse(null);
        return providerType == null
                ? runtime.capabilities()
                : withAuthorizationAndFeatureEvidence(providerType, runtime).capabilities();
    }

    AccountRuntime accountRuntime(
            long tenantId,
            long userId,
            UUID accountId,
            int maximumAttachmentMb,
            MailDtos.AccountReadiness storedReadiness) {
        long maximumAttachmentBytes = Math.multiplyExact(
                (long) maximumAttachmentMb, BYTES_PER_MIB);
        ComposeCapabilities unavailable = new ComposeCapabilities(
                false, false, false, false, false, false, maximumAttachmentBytes, null);
        MailWorkspaceRepository.ComposeProviderContext provider = repository
                .composeProviderContext(tenantId, userId, accountId)
                .orElse(null);
        MailConnectorPort.SenderMode senderMode = repository
                .composeSenderMode(tenantId, userId, accountId)
                .orElse(null);
        if (provider == null) {
            return new AccountRuntime(unavailable, unavailableReadiness(
                    storedReadiness, "MAIL_ACCOUNT_CONNECTION_UNAVAILABLE",
                    activationAction(storedReadiness)));
        }
        boolean credentialConfigured = storedReadiness != null
                ? storedReadiness.credentialConfigured()
                : provider.providerType() == MailTypes.ProviderType.DWP_SANDBOX
                    || provider.credentialReference() != null;
        if (!credentialConfigured) {
            return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                    "UNAVAILABLE", "PERSISTED_CONNECTION", nowOffset(),
                    "MAIL_CREDENTIAL_NOT_CONFIGURED", false,
                    lastSync(storedReadiness), lastSyncScope(storedReadiness),
                    "ACTIVATE_EXTERNALLY"));
        }
        MailConnectorPort connector = connectors.connector(provider.providerType()).orElse(null);
        if (connector == null) {
            return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                    "UNAVAILABLE", "RUNTIME_REGISTRY", nowOffset(),
                    "MAIL_ADAPTER_NOT_DEPLOYED", credentialConfigured,
                    lastSync(storedReadiness), lastSyncScope(storedReadiness),
                    "CONTACT_ADMIN"));
        }
        Set<MailConnectorPort.Capability> capabilities = connector.manifest().capabilities();
        try {
            MailConnectorPort.ConnectionContext connection =
                    new MailConnectorPort.ConnectionContext(
                            new ExecutionContext(
                                    Long.toString(tenantId), Long.toString(userId), Set.of(),
                                    "mail-compose-capabilities:" + accountId),
                            provider.connectionId(), provider.credentialReference(),
                            provider.mailDomain());
            MailConnectorPort.Readiness readiness = connector.readiness(connection);
            OffsetDateTime observedAt = readiness.checkedAt().atOffset(ZoneOffset.UTC);
            if (readiness.state() != MailConnectorPort.ReadinessState.READY) {
                String action = readiness.state()
                        == MailConnectorPort.ReadinessState.CONFIGURATION_REQUIRED
                        || readiness.state()
                        == MailConnectorPort.ReadinessState.AUTHENTICATION_REQUIRED
                        ? "ACTIVATE_EXTERNALLY" : "RETRY";
                String state = readiness.state() == MailConnectorPort.ReadinessState.DEGRADED
                        ? "DEGRADED" : "UNAVAILABLE";
                return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                        state, "CONNECTOR_RUNTIME", observedAt,
                        readinessError(readiness.errorCode(), readiness.state().name()),
                        credentialConfigured, lastSync(storedReadiness),
                        lastSyncScope(storedReadiness), action));
            }
            if (!capabilities.contains(MailConnectorPort.Capability.SEND)) {
                return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                        "UNAVAILABLE", "CONNECTOR_RUNTIME", observedAt,
                        "MAIL_ADAPTER_SEND_NOT_SUPPORTED", credentialConfigured,
                        lastSync(storedReadiness), lastSyncScope(storedReadiness),
                        "CONTACT_ADMIN"));
            }
            if (senderMode == null) {
                return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                        "UNAVAILABLE", "ACCESS_POLICY", observedAt,
                        "MAIL_SEND_ACCESS_UNAVAILABLE", credentialConfigured,
                        lastSync(storedReadiness), lastSyncScope(storedReadiness),
                        "REQUEST_ACCESS"));
            }
            if (senderMode == MailConnectorPort.SenderMode.SEND_ON_BEHALF
                    && !capabilities.contains(MailConnectorPort.Capability.SEND_ON_BEHALF)) {
                return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                        "UNAVAILABLE", "CONNECTOR_RUNTIME", observedAt,
                        "MAIL_ADAPTER_SEND_ON_BEHALF_NOT_SUPPORTED", credentialConfigured,
                        lastSync(storedReadiness), lastSyncScope(storedReadiness),
                        "CONTACT_ADMIN"));
            }
            ComposeCapabilities available = new ComposeCapabilities(
                    true,
                    true,
                    capabilities.contains(MailConnectorPort.Capability.BCC),
                    capabilities.contains(MailConnectorPort.Capability.HTML_BODY),
                    capabilities.contains(MailConnectorPort.Capability.ATTACHMENTS)
                            && !attachmentScanners.isEmpty(),
                    true,
                    maximumAttachmentBytes,
                    senderMode);
            return new AccountRuntime(available, new MailDtos.AccountReadiness(
                    "READY", "CONNECTOR_RUNTIME", observedAt, null,
                    credentialConfigured, lastSync(storedReadiness),
                    lastSyncScope(storedReadiness), "NONE"));
        } catch (RuntimeException failure) {
            return new AccountRuntime(unavailable, new MailDtos.AccountReadiness(
                    "UNAVAILABLE", "CONNECTOR_RUNTIME", nowOffset(),
                    "MAIL_READINESS_CHECK_FAILED", credentialConfigured,
                    lastSync(storedReadiness), lastSyncScope(storedReadiness),
                    "RETRY"));
        }
    }

    MailDtos.AccountReadiness unavailableReadiness(
            MailDtos.AccountReadiness stored, String errorCode, String action) {
        return new MailDtos.AccountReadiness(
                "UNAVAILABLE", stored == null ? "NO_RUNTIME_ATTESTATION" : stored.source(),
                nowOffset(), errorCode,
                stored != null && stored.credentialConfigured(),
                lastSync(stored), lastSyncScope(stored), action);
    }

    AccountRuntime withAuthorizationAndFeatureEvidence(
            MailTypes.ProviderType providerType, AccountRuntime runtime) {
        MailDtos.AccountReadiness readiness = runtime.readiness();
        OffsetDateTime observedAt = readiness.observedAt() == null
                ? nowOffset() : readiness.observedAt();
        boolean sandbox = providerType == MailTypes.ProviderType.DWP_SANDBOX;
        MailDtos.AuthorizationEvidence consent = authorizationEvidence(
                sandbox, "CONSENT", observedAt);
        MailDtos.AuthorizationEvidence token = authorizationEvidence(
                sandbox, "TOKEN", observedAt);

        if (!sandbox && "READY".equals(readiness.state())) {
            String error = "MAIL_OAUTH_EVIDENCE_UNAVAILABLE";
            ComposeCapabilities unavailable = new ComposeCapabilities(
                    false, false, false, false, false, false,
                    runtime.capabilities().maximumAttachmentBytes(), null);
            MailDtos.AccountReadiness oauthUnavailable = new MailDtos.AccountReadiness(
                    "UNAVAILABLE", "NO_OAUTH_ATTESTATION", observedAt, error,
                    readiness.credentialConfigured(), readiness.lastSuccessfulSyncAt(),
                    readiness.lastSuccessfulSyncScope(), "ACTIVATE_EXTERNALLY");
            MailDtos.AccountReadiness unavailableReadiness = new MailDtos.AccountReadiness(
                    "UNAVAILABLE", "NO_OAUTH_ATTESTATION", observedAt, error,
                    readiness.credentialConfigured(), readiness.lastSuccessfulSyncAt(),
                    readiness.lastSuccessfulSyncScope(), "ACTIVATE_EXTERNALLY",
                    consent, token,
                    featureEvidence(unavailable, observedAt, error,
                            "NO_OAUTH_ATTESTATION", "ACTIVATE_EXTERNALLY",
                            oauthUnavailable));
            return new AccountRuntime(unavailable, unavailableReadiness);
        }

        Map<String, MailDtos.FeatureReadiness> features = featureEvidence(
                runtime.capabilities(), observedAt, readiness.errorCode(),
                readiness.source(), readiness.action(), readiness);
        MailDtos.AccountReadiness evidenced = new MailDtos.AccountReadiness(
                readiness.state(), readiness.source(), readiness.observedAt(),
                readiness.errorCode(), readiness.credentialConfigured(),
                readiness.lastSuccessfulSyncAt(), readiness.lastSuccessfulSyncScope(),
                readiness.action(), consent, token, features);
        return new AccountRuntime(runtime.capabilities(), evidenced);
    }

    MailDtos.AuthorizationEvidence authorizationEvidence(
            boolean sandbox, String kind, OffsetDateTime observedAt) {
        if (sandbox) {
            return new MailDtos.AuthorizationEvidence(
                    "NOT_REQUIRED", "CONNECTOR_RUNTIME", observedAt,
                    null, null, "NONE");
        }
        return new MailDtos.AuthorizationEvidence(
                "UNKNOWN", "NO_OAUTH_ATTESTATION", observedAt,
                null, "MAIL_" + kind + "_EVIDENCE_UNAVAILABLE",
                "ACTIVATE_EXTERNALLY");
    }

    Map<String, MailDtos.FeatureReadiness> featureEvidence(
            ComposeCapabilities capabilities,
            OffsetDateTime observedAt,
            String accountError,
            String accountSource,
            String accountAction,
            MailDtos.AccountReadiness readiness) {
        boolean accountReady = "READY".equals(readiness.state());
        Map<String, MailDtos.FeatureReadiness> evidence = new LinkedHashMap<>();
        evidence.put("SEND", featureEvidence(
                accountReady && capabilities.multipleRecipients(),
                accountReady ? "CONNECTOR_RUNTIME" : accountSource,
                observedAt, accountReady ? "MAIL_ADAPTER_SEND_NOT_SUPPORTED" : accountError,
                accountReady ? "CONTACT_ADMIN" : accountAction, readiness));
        evidence.put("BCC", featureEvidence(
                accountReady && capabilities.bcc(),
                accountReady ? "CONNECTOR_RUNTIME" : accountSource, observedAt,
                accountReady ? "MAIL_ADAPTER_BCC_NOT_SUPPORTED" : accountError,
                accountReady ? "CONTACT_ADMIN" : accountAction, readiness));
        evidence.put("HTML_BODY", featureEvidence(
                accountReady && capabilities.html(),
                accountReady ? "CONNECTOR_RUNTIME" : accountSource, observedAt,
                accountReady ? "MAIL_ADAPTER_HTML_NOT_SUPPORTED" : accountError,
                accountReady ? "CONTACT_ADMIN" : accountAction, readiness));
        evidence.put("ATTACHMENTS", featureEvidence(
                accountReady && capabilities.attachments(),
                accountReady ? "CONNECTOR_RUNTIME" : accountSource, observedAt,
                accountReady ? "MAIL_ATTACHMENTS_UNAVAILABLE" : accountError,
                accountReady ? "CONTACT_ADMIN" : accountAction, readiness));
        evidence.put("SCHEDULING", featureEvidence(
                accountReady && capabilities.scheduling(),
                accountReady ? "PLATFORM_OUTBOX" : accountSource, observedAt,
                accountReady ? "MAIL_SCHEDULING_UNAVAILABLE" : accountError,
                accountReady ? "CONTACT_ADMIN" : accountAction, readiness));
        return Map.copyOf(evidence);
    }

    MailDtos.FeatureReadiness featureEvidence(
            boolean ready,
            String source,
            OffsetDateTime observedAt,
            String unavailableError,
            String unavailableAction,
            MailDtos.AccountReadiness readiness) {
        return new MailDtos.FeatureReadiness(
                ready ? "READY" : "UNAVAILABLE", source, observedAt,
                ready ? null : readinessError(unavailableError, "FEATURE_UNAVAILABLE"),
                null, "UNAVAILABLE",
                ready ? "NONE" : normalizedFeatureAction(unavailableAction));
    }

    String normalizedFeatureAction(String action) {
        return Set.of("NONE", "RETRY", "ACTIVATE_EXTERNALLY", "CONTACT_ADMIN",
                        "REQUEST_ACCESS").contains(action)
                ? action : "RETRY";
    }

    String activationAction(MailDtos.AccountReadiness stored) {
        return stored != null && "ACTIVATE_EXTERNALLY".equals(stored.action())
                ? "ACTIVATE_EXTERNALLY" : "RETRY";
    }

    OffsetDateTime lastSync(MailDtos.AccountReadiness stored) {
        return stored == null ? null : stored.lastSuccessfulSyncAt();
    }

    String lastSyncScope(MailDtos.AccountReadiness stored) {
        return stored == null ? null : stored.lastSuccessfulSyncScope();
    }

    OffsetDateTime nowOffset() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    String readinessError(String errorCode, String fallback) {
        if (errorCode == null || !errorCode.matches("[A-Za-z0-9._:-]{1,120}")) {
            return "MAIL_ADAPTER_" + fallback;
        }
        return errorCode;
    }

    record AccountRuntime(
            ComposeCapabilities capabilities,
            MailDtos.AccountReadiness readiness) { }

    void validateProviderCapabilities(
            long tenantId,
            long userId,
            UUID accountId,
            List<Recipient> recipients,
            BodyFormat bodyFormat,
            List<UUID> attachmentIds,
            OffsetDateTime requestedSchedule) {
        ComposeCapabilities capabilities = accountCapabilities(
                tenantId, userId, accountId, repository.maximumAttachmentMb(tenantId));
        if (!capabilities.multipleRecipients()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The selected account provider is not ready for sending.");
        }
        if (recipients.stream().anyMatch(recipient -> recipient.type() == RecipientType.CC)
                && !capabilities.cc()) {
            throw invalid("The selected account provider does not support Cc recipients.");
        }
        if (recipients.stream().anyMatch(recipient -> recipient.type() == RecipientType.BCC)
                && !capabilities.bcc()) {
            throw invalid("The selected account provider does not support Bcc recipients.");
        }
        if (bodyFormat == BodyFormat.HTML && !capabilities.html()) {
            throw invalid("The selected account provider does not support HTML messages.");
        }
        if (!attachmentIds.isEmpty() && !capabilities.attachments()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Attachments are unavailable for the selected account provider.");
        }
        if (requestedSchedule != null && !capabilities.scheduling()) {
            throw invalid("The selected account provider does not support scheduled sending.");
        }
    }

    boolean validateExternalRecipientPolicy(
            long tenantId,
            long userId,
            UUID accountId,
            List<Recipient> recipients,
            MailTypes.Classification classification,
            Boolean externalRecipientConfirmed) {
        if (classification == null || externalRecipientConfirmed == null) {
            throw invalid("Message classification and external-recipient confirmation are required.");
        }
        String senderDomain = repository.composeAccountDomain(tenantId, userId, accountId)
                .or(() -> repository.composeProviderContext(tenantId, userId, accountId)
                        .map(MailWorkspaceRepository.ComposeProviderContext::mailDomain))
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The selected sending account is no longer available."));
        boolean external = recipients.stream()
                .map(Recipient::email)
                .map(this::emailDomain)
                .anyMatch(domain -> !senderDomain.equals(domain));
        if (external && classification != MailTypes.Classification.PUBLIC
                && !externalRecipientConfirmed) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Confirm the external recipients before sending non-public content.");
        }
        return external;
    }

    OffsetDateTime preferenceDelay(long tenantId, long userId) {
        int delay = effectivePreferences(tenantId, userId).sendDelaySeconds();
        return delay > 0 ? OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(delay) : null;
    }

}
