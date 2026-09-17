package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dwp.services.platform.mail.AdminMailWritingAssetDtos.*;

@Service
class AdminMailWritingAssetService {

    private static final Set<String> ALLOWED_TEMPLATE_VARIABLES = Set.of(
            "displayName", "department", "recipientName");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile(
            "\\{\\{\\s*([^{}]*?)\\s*}}", Pattern.UNICODE_CASE);

    private static final Safelist ORGANIZATION_HTML = new Safelist()
            .addTags(
                    "p", "div", "br", "strong", "b", "em", "i", "u", "s",
                    "ul", "ol", "li", "blockquote", "pre", "code", "a")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto");

    private final AdminMailWritingAssetRepository assets;
    private final MailWorkspaceRepository workspace;
    private final MailCommandRepository commands;
    private final MailAdminMutationReceipts receipts;

    AdminMailWritingAssetService(
            AdminMailWritingAssetRepository assets,
            MailWorkspaceRepository workspace,
            MailCommandRepository commands) {
        this(assets, workspace, commands, null);
    }

    @Autowired
    AdminMailWritingAssetService(
            AdminMailWritingAssetRepository assets,
            MailWorkspaceRepository workspace,
            MailCommandRepository commands,
            MailAdminMutationReceipts receipts) {
        this.assets = assets;
        this.workspace = workspace;
        this.commands = commands;
        this.receipts = receipts;
    }

    @Transactional(readOnly = true)
    List<OrganizationAsset> assets(long tenantId, AssetKind kind, String state) {
        String normalized = state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.isEmpty()) {
            try {
                PublicationState.valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The organization asset state is not supported.");
            }
        }
        return assets.assets(tenantId, kind, normalized);
    }

    @Transactional
    OrganizationAsset createDraft(
            long tenantId,
            long userId,
            AssetKind kind,
            String correlationId,
            UUID idempotencyKey,
            DraftRequest request) {
        request = validated(kind, request, false);
        String fingerprint = receipts().fingerprint("WRITING_ASSET_DRAFT_CREATE", kind, request);
        MailAdminMutationReceipts.Receipt replay = receipts().claimOrReplay(
                tenantId, userId, "WRITING_ASSET_DRAFT_CREATE", idempotencyKey,
                fingerprint, correlationId);
        if (replay != null) return replayAsset(tenantId, kind, replay);
        OrganizationAsset created;
        try {
            created = assets.createDraft(tenantId, userId, kind, request);
        } catch (IllegalStateException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE, exception.getMessage());
        }
        audit(tenantId, userId, correlationId, "mail.organization-asset.draft-created",
                created, Map.of(), snapshot(created));
        receipts().complete(
                tenantId, userId, idempotencyKey,
                "MAIL_ORGANIZATION_ASSET", created.assetId());
        return created;
    }

    @Transactional
    OrganizationAsset updateDraft(
            long tenantId,
            long userId,
            AssetKind kind,
            UUID assetId,
            String correlationId,
            UUID idempotencyKey,
            DraftRequest request) {
        request = validated(kind, request, true);
        String fingerprint = receipts().fingerprint(
                "WRITING_ASSET_DRAFT_UPDATE", kind, assetId, request);
        MailAdminMutationReceipts.Receipt replay = receipts().claimOrReplay(
                tenantId, userId, "WRITING_ASSET_DRAFT_UPDATE", idempotencyKey,
                fingerprint, correlationId);
        if (replay != null) return replayAsset(tenantId, kind, replay);
        OrganizationAsset before = required(tenantId, kind, assetId);
        OrganizationAsset after = assets.updateDraft(
                        tenantId, userId, kind, assetId, request)
                .orElseThrow(AdminMailWritingAssetService::conflict);
        audit(tenantId, userId, correlationId, "mail.organization-asset.draft-updated",
                after, snapshot(before), snapshot(after));
        receipts().complete(
                tenantId, userId, idempotencyKey,
                "MAIL_ORGANIZATION_ASSET", assetId);
        return after;
    }

    @Transactional
    OrganizationAsset submit(
            long tenantId, long userId, AssetKind kind, UUID assetId,
            String correlationId, UUID idempotencyKey, TransitionRequest request) {
        OrganizationAsset replay = claimTransition(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, "WRITING_ASSET_SUBMIT", request);
        if (replay != null) return replay;
        OrganizationAsset result = transition(
                tenantId, userId, kind, assetId, correlationId, request,
                PublicationState.DRAFT, PublicationState.PENDING_APPROVAL,
                "mail.organization-asset.submitted");
        completeTransition(tenantId, userId, idempotencyKey, assetId);
        return result;
    }

    @Transactional
    OrganizationAsset approve(
            long tenantId, long userId, AssetKind kind, UUID assetId,
            String correlationId, UUID idempotencyKey, TransitionRequest request) {
        OrganizationAsset replay = claimTransition(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, "WRITING_ASSET_APPROVE", request);
        if (replay != null) return replay;
        OrganizationAsset current = required(tenantId, kind, assetId);
        if (current.createdBy() == userId) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A different mail administrator must approve the organization asset.");
        }
        OrganizationAsset result = transition(
                tenantId, userId, kind, assetId, correlationId, request,
                PublicationState.PENDING_APPROVAL, PublicationState.APPROVED,
                "mail.organization-asset.approved");
        completeTransition(tenantId, userId, idempotencyKey, assetId);
        return result;
    }

    @Transactional
    OrganizationAsset publish(
            long tenantId, long userId, AssetKind kind, UUID assetId,
            String correlationId, UUID idempotencyKey, TransitionRequest request) {
        OrganizationAsset replay = claimTransition(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, "WRITING_ASSET_PUBLISH", request);
        if (replay != null) return replay;
        OrganizationAsset before = required(tenantId, kind, assetId);
        if (before.publicationState() != PublicationState.APPROVED) {
            throw conflict();
        }
        assets.retirePublishedPredecessor(tenantId, userId, kind, before);
        OrganizationAsset after = assets.transition(
                        tenantId, userId, kind, assetId,
                        PublicationState.APPROVED, PublicationState.PUBLISHED,
                        request.version())
                .orElseThrow(AdminMailWritingAssetService::conflict);
        audit(tenantId, userId, correlationId, "mail.organization-asset.published",
                after, snapshot(before), snapshot(after));
        commands.domainEvent(
                tenantId, "MAIL_ORGANIZATION_ASSET", after.assetId(),
                "mail.organization-asset.published", Map.of(
                        "assetId", after.assetId(), "assetKind", after.kind().name(),
                        "publicationKey", after.publicationKey(),
                        "publicationVersion", after.publicationVersion()), correlationId);
        completeTransition(tenantId, userId, idempotencyKey, assetId);
        return after;
    }

    @Transactional
    OrganizationAsset retire(
            long tenantId, long userId, AssetKind kind, UUID assetId,
            String correlationId, UUID idempotencyKey, TransitionRequest request) {
        OrganizationAsset replay = claimTransition(
                tenantId, userId, kind, assetId, correlationId,
                idempotencyKey, "WRITING_ASSET_RETIRE", request);
        if (replay != null) return replay;
        OrganizationAsset result = transition(
                tenantId, userId, kind, assetId, correlationId, request,
                PublicationState.PUBLISHED, PublicationState.RETIRED,
                "mail.organization-asset.retired");
        completeTransition(tenantId, userId, idempotencyKey, assetId);
        return result;
    }

    private OrganizationAsset transition(
            long tenantId,
            long userId,
            AssetKind kind,
            UUID assetId,
            String correlationId,
            TransitionRequest request,
            PublicationState from,
            PublicationState to,
            String action) {
        OrganizationAsset before = required(tenantId, kind, assetId);
        if (before.publicationState() != from) throw conflict();
        OrganizationAsset after = assets.transition(
                        tenantId, userId, kind, assetId, from, to, request.version())
                .orElseThrow(AdminMailWritingAssetService::conflict);
        audit(tenantId, userId, correlationId, action,
                after, snapshot(before), snapshot(after));
        return after;
    }

    private OrganizationAsset required(long tenantId, AssetKind kind, UUID assetId) {
        return assets.asset(tenantId, kind, assetId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private OrganizationAsset claimTransition(
            long tenantId,
            long userId,
            AssetKind kind,
            UUID assetId,
            String correlationId,
            UUID idempotencyKey,
            String commandKind,
            TransitionRequest request) {
        String fingerprint = receipts().fingerprint(
                commandKind, kind, assetId, request);
        MailAdminMutationReceipts.Receipt replay = receipts().claimOrReplay(
                tenantId, userId, commandKind, idempotencyKey,
                fingerprint, correlationId);
        return replay == null ? null : replayAsset(tenantId, kind, replay);
    }

    private void completeTransition(
            long tenantId, long userId, UUID idempotencyKey, UUID assetId) {
        receipts().complete(
                tenantId, userId, idempotencyKey,
                "MAIL_ORGANIZATION_ASSET", assetId);
    }

    private OrganizationAsset replayAsset(
            long tenantId,
            AssetKind kind,
            MailAdminMutationReceipts.Receipt receipt) {
        if (!"MAIL_ORGANIZATION_ASSET".equals(receipt.aggregateType())) {
            throw conflict();
        }
        return assets.asset(tenantId, kind, receipt.aggregateId())
                .orElseThrow(AdminMailWritingAssetService::conflict);
    }

    private MailAdminMutationReceipts receipts() {
        if (receipts == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Mail administrator command custody is unavailable.");
        }
        return receipts;
    }

    private DraftRequest validated(AssetKind kind, DraftRequest request, boolean update) {
        if (update && request.version() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "The organization asset version is required.");
        }
        if (kind == AssetKind.TEMPLATE
                && request.subject() != null && request.subject().length() > 500) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The template subject is too long.");
        }
        validateTemplateVariables(request.subject(), request.body(), request.mandatoryContent());
        if (request.bodyFormat() != MailWorkspaceDtos.BodyFormat.HTML) return request;
        String body = Jsoup.clean(request.body(), "", ORGANIZATION_HTML, outputSettings());
        String mandatory = Jsoup.clean(
                request.mandatoryContent(), "", ORGANIZATION_HTML, outputSettings());
        if (Jsoup.parseBodyFragment(body).text().isBlank()
                || Jsoup.parseBodyFragment(mandatory).text().isBlank()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Organization writing assets require visible, safe content.");
        }
        return new DraftRequest(
                request.name(), request.subject(), body, request.bodyFormat(), mandatory,
                request.defaultForNew(), request.defaultForReply(),
                request.supersedesId(), request.version());
    }

    private void validateTemplateVariables(String... values) {
        for (String value : values) {
            Matcher matcher = TEMPLATE_VARIABLE.matcher(value == null ? "" : value);
            while (matcher.find()) {
                String variable = matcher.group(1).trim();
                if (!ALLOWED_TEMPLATE_VARIABLES.contains(variable)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "Unsupported organization writing asset variable: " + variable);
                }
            }
        }
    }

    private org.jsoup.nodes.Document.OutputSettings outputSettings() {
        return new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false);
    }

    private void audit(
            long tenantId,
            long userId,
            String correlationId,
            String action,
            OrganizationAsset asset,
            Map<String, Object> before,
            Map<String, Object> after) {
        workspace.audit(
                tenantId, userId, action, "MAIL_ORGANIZATION_ASSET",
                asset.assetId().toString(), correlationId, before, after);
    }

    private Map<String, Object> snapshot(OrganizationAsset asset) {
        return Map.of(
                "kind", asset.kind().name(),
                "publicationKey", asset.publicationKey(),
                "publicationVersion", asset.publicationVersion(),
                "publicationState", asset.publicationState().name(),
                "version", asset.version());
    }

    private static BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The organization asset changed. Refresh before continuing.");
    }
}
