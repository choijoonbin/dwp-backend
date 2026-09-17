package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Owner-service projection for the current Mail PAGE/DATA/ACTION contract. */
@Component
public final class MailProductSurfaceContract {

    public static final String POLICY_ID = "P-MAIL";
    public static final String PRODUCT_ID = "mail";
    public static final String SURFACE_KEY = "mail.work";
    public static final String MANAGEMENT_SURFACE_KEY = "mail.management";
    public static final String ACCESS_POLICY_KEY = "mail.work-access.v1";
    public static final String MESSAGE_CREATE_CAPABILITY_KEY = "mail.work.message.create";
    public static final String OWNER_SERVICE = "dwp-platform-server";
    public static final String SERVICE_KEY = "platform";

    public static final String HOME_PAGE_ROUTE = "route.mail.work.home.page";
    public static final String THREADS_DATA_ROUTE = "route.mail.work.threads.data";
    public static final String MESSAGE_CREATE_ACTION_ROUTE =
            "route.mail.work.message-create.action";

    private static final List<Binding> BINDINGS = List.of(
            work(
                    HOME_PAGE_ROUTE,
                    RouteKind.PAGE,
                    "GET",
                    "/v1/mail/home",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.MAIL:VIEW",
                    true),
            work(
                    THREADS_DATA_ROUTE,
                    RouteKind.DATA,
                    "GET",
                    "/v1/mail/threads",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.MAIL:VIEW",
                    true),
            work(
                    MESSAGE_CREATE_ACTION_ROUTE,
                    RouteKind.ACTION,
                    "POST",
                    "/v1/mail/messages",
                    AccessContractType.CAPABILITY,
                    MESSAGE_CREATE_CAPABILITY_KEY,
                    "APP.MAIL:CREATE",
                    false),
            work("route.mail.work.draft-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/drafts", AccessContractType.CAPABILITY,
                    MESSAGE_CREATE_CAPABILITY_KEY, "APP.MAIL:CREATE", false),
            work("route.mail.work.draft-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/drafts/{threadId}", AccessContractType.CAPABILITY,
                    "mail.work.message.update", "APP.MAIL:UPDATE", false),
            work("route.mail.work.thread-draft-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/threads/{threadId}/draft", AccessContractType.CAPABILITY,
                    "mail.work.message.update", "APP.MAIL:UPDATE", false),
            work("route.mail.work.message-advanced-send.action", RouteKind.ACTION, "POST",
                    "/v1/mail/messages/advanced", AccessContractType.CAPABILITY,
                    "mail.work.message.send", "APP.MAIL:SEND", false),
            work("route.mail.work.thread-reply.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/replies", AccessContractType.CAPABILITY,
                    "mail.work.message.send", "APP.MAIL:SEND", false),
            work("route.mail.work.delivery-retry.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/messages/{messageId}/retry",
                    AccessContractType.CAPABILITY, "mail.work.message.send",
                    "APP.MAIL:SEND", false),
            work("route.mail.work.attachment-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/attachments/{attachmentId}", AccessContractType.CAPABILITY,
                    "mail.work.message.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.proposal-decision.action", RouteKind.ACTION, "POST",
                    "/v1/mail/proposals/{proposalId}/decision", AccessContractType.CAPABILITY,
                    "mail.work.proposal.decide", "APP.MAIL:DECIDE", false),
            work("route.mail.work.proposal-handoff-cancel.action", RouteKind.ACTION, "POST",
                    "/v1/mail/proposals/{proposalId}/handoff/cancel",
                    AccessContractType.CAPABILITY,
                    "mail.work.proposal.decide", "APP.MAIL:DECIDE", false),
            work("route.mail.work.compose-context.data", RouteKind.DATA, "GET",
                    "/v1/mail/compose-context", AccessContractType.POLICY,
                    ACCESS_POLICY_KEY, "APP.MAIL:VIEW", true),
            work("route.mail.work.group-message.action", RouteKind.ACTION, "POST",
                    "/v1/mail/contact-groups/{groupId}/messages", AccessContractType.CAPABILITY,
                    "mail.work.message.send", "APP.MAIL:SEND", false),
            work("route.mail.work.lifecycle-preview.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/lifecycle/preview",
                    AccessContractType.CAPABILITY, "mail.work.message.update",
                    "APP.MAIL:UPDATE", false),
            work("route.mail.work.lifecycle.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/lifecycle", AccessContractType.CAPABILITY,
                    "mail.work.message.update", "APP.MAIL:UPDATE", false),
            work("route.mail.work.folder-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/organization/folders", AccessContractType.CAPABILITY,
                    "mail.work.folder.create", "APP.MAIL:CREATE", false),
            work("route.mail.work.folder-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/organization/folders/{folderId}", AccessContractType.CAPABILITY,
                    "mail.work.folder.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.folder-archive.action", RouteKind.ACTION, "POST",
                    "/v1/mail/organization/folders/{folderId}/archive",
                    AccessContractType.CAPABILITY,
                    "mail.work.folder.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.rule-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/organization/rules", AccessContractType.CAPABILITY,
                    "mail.work.rule.create", "APP.MAIL:CREATE", false),
            work("route.mail.work.rule-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/organization/rules/{ruleId}", AccessContractType.CAPABILITY,
                    "mail.work.rule.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.rule-order.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/organization/rules/order", AccessContractType.CAPABILITY,
                    "mail.work.rule.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.rule-archive.action", RouteKind.ACTION, "POST",
                    "/v1/mail/organization/rules/{ruleId}/archive",
                    AccessContractType.CAPABILITY,
                    "mail.work.rule.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.rule-run.action", RouteKind.ACTION, "POST",
                    "/v1/mail/organization/rules/{ruleId}/run",
                    AccessContractType.CAPABILITY,
                    "mail.work.rule.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.rule-backfill.action", RouteKind.ACTION, "POST",
                    "/v1/mail/organization/accounts/{accountId}/rules/backfill",
                    AccessContractType.CAPABILITY,
                    "mail.work.rule.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.thread-action.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/actions", AccessContractType.CAPABILITY,
                    "mail.work.message.update", "APP.MAIL:UPDATE", false),
            work("route.mail.work.thread-snooze.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/snooze", AccessContractType.CAPABILITY,
                    "mail.work.message.update", "APP.MAIL:UPDATE", false),
            work("route.mail.work.thread-assignment.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/assignment", AccessContractType.CAPABILITY,
                    "mail.work.shared-thread.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.thread-comment.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/comments", AccessContractType.CAPABILITY,
                    "mail.work.shared-thread.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.proposal-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/proposals/{proposalId}", AccessContractType.CAPABILITY,
                    "mail.work.proposal.update", "APP.MAIL:UPDATE", false),
            work("route.mail.work.contact-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/contacts", AccessContractType.CAPABILITY,
                    "mail.work.address-book.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.contact-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/contacts/{contactId}", AccessContractType.CAPABILITY,
                    "mail.work.address-book.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.contact-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/contacts/{contactId}", AccessContractType.CAPABILITY,
                    "mail.work.address-book.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.contact-group-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/contact-groups", AccessContractType.CAPABILITY,
                    "mail.work.address-book.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.contact-group-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/contact-groups/{groupId}", AccessContractType.CAPABILITY,
                    "mail.work.address-book.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.contact-group-members-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/contact-groups/{groupId}/members",
                    AccessContractType.CAPABILITY,
                    "mail.work.address-book.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.contact-group-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/contact-groups/{groupId}", AccessContractType.CAPABILITY,
                    "mail.work.address-book.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.attachment-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/attachments", AccessContractType.CAPABILITY,
                    "mail.work.attachment.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.saved-view-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/saved-views", AccessContractType.CAPABILITY,
                    "mail.work.saved-view.create", "APP.MAIL:CREATE", false),
            work("route.mail.work.saved-view-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/saved-views/{savedViewId}", AccessContractType.CAPABILITY,
                    "mail.work.saved-view.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.saved-view-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/saved-views/{savedViewId}", AccessContractType.CAPABILITY,
                    "mail.work.saved-view.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.follow-up-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/threads/{threadId}/follow-up", AccessContractType.CAPABILITY,
                    "mail.work.follow-up.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.follow-up-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/follow-ups/{followUpId}", AccessContractType.CAPABILITY,
                    "mail.work.follow-up.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.follow-up-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/follow-ups/{followUpId}", AccessContractType.CAPABILITY,
                    "mail.work.follow-up.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.delivery-reschedule.action", RouteKind.ACTION, "POST",
                    "/v1/mail/deliveries/{deliveryId}/reschedule",
                    AccessContractType.CAPABILITY,
                    "mail.work.delivery.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.delivery-cancel.action", RouteKind.ACTION, "POST",
                    "/v1/mail/deliveries/{deliveryId}/cancel", AccessContractType.CAPABILITY,
                    "mail.work.delivery.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.delivery-reconcile.action", RouteKind.ACTION, "POST",
                    "/v1/mail/deliveries/{deliveryId}/reconcile",
                    AccessContractType.CAPABILITY,
                    "mail.work.delivery.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.delivery-command-retry.action", RouteKind.ACTION, "POST",
                    "/v1/mail/deliveries/{deliveryId}/retry", AccessContractType.CAPABILITY,
                    "mail.work.message.send", "APP.MAIL:SEND", false),
            work("route.mail.work.template-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/templates", AccessContractType.CAPABILITY,
                    "mail.work.writing-asset.create", "APP.MAIL:CREATE", false),
            work("route.mail.work.template-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/templates/{templateId}", AccessContractType.CAPABILITY,
                    "mail.work.writing-asset.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.template-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/templates/{templateId}", AccessContractType.CAPABILITY,
                    "mail.work.writing-asset.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.signature-create.action", RouteKind.ACTION, "POST",
                    "/v1/mail/signatures", AccessContractType.CAPABILITY,
                    "mail.work.writing-asset.create", "APP.MAIL:CREATE", false),
            work("route.mail.work.signature-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/signatures/{signatureId}", AccessContractType.CAPABILITY,
                    "mail.work.writing-asset.manage", "APP.MAIL:UPDATE", false),
            work("route.mail.work.signature-delete.action", RouteKind.ACTION, "DELETE",
                    "/v1/mail/signatures/{signatureId}", AccessContractType.CAPABILITY,
                    "mail.work.writing-asset.delete", "APP.MAIL:DELETE", false),
            work("route.mail.work.preferences-update.action", RouteKind.ACTION, "PUT",
                    "/v1/mail/preferences", AccessContractType.CAPABILITY,
                    "mail.work.preferences.manage", "APP.MAIL:UPDATE", false),

            admin("route.admin.mail.policy-update.action", RouteKind.ACTION, "PUT",
                    "/v1/admin/mail/policy", "mail.management.policy.manage",
                    "ADMIN.MAIL:POLICY_MANAGE", false),
            admin("route.admin.mail.connection-update.action", RouteKind.ACTION, "PUT",
                    "/v1/admin/mail/connections/{connectionId}",
                    "mail.management.connection.manage", "ADMIN.MAIL:CONNECTION_MANAGE", false),
            admin("route.admin.mail.connection-diagnostics.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/connections/{connectionId}/diagnostics",
                    "mail.management.connection.manage", "ADMIN.MAIL:CONNECTION_MANAGE", false),
            admin("route.admin.mail.connection-sync.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/connections/{connectionId}/sync",
                    "mail.management.connection.manage", "ADMIN.MAIL:CONNECTION_MANAGE", false),
            admin("route.admin.mail.connection-test-send.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/connections/{connectionId}/test-send",
                    "mail.management.connection.manage", "ADMIN.MAIL:CONNECTION_MANAGE", false),
            admin("route.admin.mail.shared-inbox-update.action", RouteKind.ACTION, "PUT",
                    "/v1/admin/mail/shared-inboxes/{sharedInboxId}",
                    "mail.management.shared-inbox.manage", "ADMIN.MAIL:SHARED_INBOX_MANAGE", false),
            admin("route.admin.mail.shared-member-create.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/shared-inboxes/{inboxId}/members",
                    "mail.management.shared-inbox.manage", "ADMIN.MAIL:SHARED_INBOX_MANAGE", false),
            admin("route.admin.mail.shared-member-update.action", RouteKind.ACTION, "PUT",
                    "/v1/admin/mail/shared-inboxes/{inboxId}/members/{memberId}",
                    "mail.management.shared-inbox.manage", "ADMIN.MAIL:SHARED_INBOX_MANAGE", false),
            admin("route.admin.mail.shared-member-revoke-preview.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/shared-inboxes/{inboxId}/members/{memberId}/revoke-preview",
                    "mail.management.shared-inbox.manage", "ADMIN.MAIL:SHARED_INBOX_MANAGE", false),
            admin("route.admin.mail.shared-member-revoke.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/shared-inboxes/{inboxId}/members/{memberId}/revoke",
                    "mail.management.shared-inbox.manage", "ADMIN.MAIL:SHARED_INBOX_MANAGE", false),
            admin("route.admin.mail.shared-member-candidates.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/shared-inboxes/member-candidates",
                    "mail.management.shared-inbox.manage", "ADMIN.MAIL:SHARED_INBOX_MANAGE", true),
            admin("route.admin.mail.retention.hold-create.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/holds", "mail.management.hold.manage",
                    "ADMIN.MAIL:HOLD_MANAGE", false),
            admin("route.admin.mail.retention.hold-update.action", RouteKind.ACTION, "PUT",
                    "/v1/admin/mail/retention/holds/{holdId}",
                    "mail.management.hold.manage", "ADMIN.MAIL:HOLD_MANAGE", false),
            admin("route.admin.mail.retention.hold-release-preview-create.action",
                    RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/holds/{holdId}/release-previews",
                    "mail.management.hold.manage", "ADMIN.MAIL:HOLD_MANAGE", false),
            admin("route.admin.mail.retention.hold-release-preview.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/retention/hold-release-previews/{previewId}",
                    "mail.management.hold.manage", "ADMIN.MAIL:HOLD_MANAGE", true),
            admin("route.admin.mail.retention.hold-release-approve.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/hold-release-previews/{previewId}/approvals",
                    "mail.management.hold.manage", "ADMIN.MAIL:HOLD_MANAGE", false),
            admin("route.admin.mail.retention.hold-release-execute.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/hold-release-previews/{previewId}/execute",
                    "mail.management.hold.manage", "ADMIN.MAIL:HOLD_MANAGE", false),
            admin("route.admin.mail.retention.purge-preview.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/purge-previews", "mail.management.purge.preview",
                    "ADMIN.MAIL:PURGE_PREVIEW", false),
            adminAny("route.admin.mail.retention.purge-previews.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/retention/purge-previews", "mail.management.purge.preview",
                    "ADMIN.MAIL:PURGE_PREVIEW", true, Set.of(
                            "ADMIN.MAIL:PURGE_PREVIEW", "ADMIN.MAIL:PURGE_AUTHORIZE",
                            "ADMIN.MAIL:PURGE_EXECUTE")),
            adminAny("route.admin.mail.retention.purge-preview.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/retention/purge-previews/{snapshotId}",
                    "mail.management.purge.preview", "ADMIN.MAIL:PURGE_PREVIEW", true, Set.of(
                            "ADMIN.MAIL:PURGE_PREVIEW", "ADMIN.MAIL:PURGE_AUTHORIZE",
                            "ADMIN.MAIL:PURGE_EXECUTE")),
            admin("route.admin.mail.retention.purge-authorize.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/purges/{snapshotId}/approvals",
                    "mail.management.purge.authorize", "ADMIN.MAIL:PURGE_AUTHORIZE", false),
            admin("route.admin.mail.retention.purge-execute.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/purges/{snapshotId}/execute",
                    "mail.management.purge.execute", "ADMIN.MAIL:PURGE_EXECUTE", false),
            adminAny("route.admin.mail.retention.purge-job.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/retention/purge-jobs/{jobId}",
                    "mail.management.purge.execute", "ADMIN.MAIL:PURGE_EXECUTE", true, Set.of(
                            "ADMIN.MAIL:PURGE_PREVIEW", "ADMIN.MAIL:PURGE_AUTHORIZE",
                            "ADMIN.MAIL:PURGE_EXECUTE")),
            admin("route.admin.mail.retention.evidence-export.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/evidence-exports",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", false),
            admin("route.admin.mail.retention.evidence-export.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/retention/evidence-exports/{exportId}",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", true),
            admin("route.admin.mail.retention.evidence-export-approve.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/retention/evidence-exports/{exportId}/approvals",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", false),
            admin("route.admin.mail.retention.evidence-export-download.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/retention/evidence-exports/{exportId}/download",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", true),
            adminAny("route.admin.mail.delivery-audit.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/delivery-audit", "mail.management.audit.read",
                    "ADMIN.MAIL:AUDIT_READ", true, Set.of(
                            "ADMIN.MAIL:AUDIT_READ", "ADMIN.MAIL:DELIVERY_RECONCILE",
                            "ADMIN.MAIL:DELIVERY_RETRY", "ADMIN.MAIL:DELIVERY_CANCEL")),
            admin("route.admin.mail.delivery.reconcile.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/delivery-audit/{deliveryId}/reconcile",
                    "mail.management.delivery.reconcile", "ADMIN.MAIL:DELIVERY_RECONCILE", false),
            admin("route.admin.mail.delivery.retry.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/delivery-audit/{deliveryId}/retry",
                    "mail.management.delivery.retry", "ADMIN.MAIL:DELIVERY_RETRY", false),
            admin("route.admin.mail.delivery.cancel.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/delivery-audit/{deliveryId}/cancel",
                    "mail.management.delivery.cancel", "ADMIN.MAIL:DELIVERY_CANCEL", false),
            admin("route.admin.mail.delivery-export-create.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/delivery-audit/exports", "mail.management.evidence.export",
                    "ADMIN.MAIL:EVIDENCE_EXPORT", false),
            admin("route.admin.mail.delivery-export.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/delivery-audit/exports/{exportId}",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", true),
            admin("route.admin.mail.delivery-export-approve.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/delivery-audit/exports/{exportId}/approvals",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", false),
            admin("route.admin.mail.delivery-export-download.data", RouteKind.DATA, "GET",
                    "/v1/admin/mail/delivery-audit/exports/{exportId}/download",
                    "mail.management.evidence.export", "ADMIN.MAIL:EVIDENCE_EXPORT", true),
            admin("route.admin.mail.writing-asset-create.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/writing-assets/{kind}/drafts",
                    "mail.management.writing-asset.edit", "ADMIN.MAIL:WRITING_ASSET_EDIT", false),
            admin("route.admin.mail.writing-asset-edit.action", RouteKind.ACTION, "PUT",
                    "/v1/admin/mail/writing-assets/{kind}/drafts/{assetId}",
                    "mail.management.writing-asset.edit", "ADMIN.MAIL:WRITING_ASSET_EDIT", false),
            admin("route.admin.mail.writing-asset-submit.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/writing-assets/{kind}/{assetId}/submit",
                    "mail.management.writing-asset.submit", "ADMIN.MAIL:WRITING_ASSET_SUBMIT", false),
            admin("route.admin.mail.writing-asset-approve.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/writing-assets/{kind}/{assetId}/approve",
                    "mail.management.writing-asset.approve", "ADMIN.MAIL:WRITING_ASSET_APPROVE", false),
            admin("route.admin.mail.writing-asset-publish.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/writing-assets/{kind}/{assetId}/publish",
                    "mail.management.writing-asset.publish", "ADMIN.MAIL:WRITING_ASSET_PUBLISH", false),
            admin("route.admin.mail.writing-asset-retire.action", RouteKind.ACTION, "POST",
                    "/v1/admin/mail/writing-assets/{kind}/{assetId}/retire",
                    "mail.management.writing-asset.retire", "ADMIN.MAIL:WRITING_ASSET_RETIRE", false));

    public Optional<Binding> resolveOwner(String method, String path) {
        if (!canonicalMethod(method) || !canonicalPath(path)) return Optional.empty();
        List<Binding> matches = BINDINGS.stream()
                .filter(binding -> binding.method().equals(method))
                .filter(binding -> pathMatches(binding.servicePath(), path))
                .toList();
        if (matches.isEmpty()) return Optional.empty();
        int highestSpecificity = matches.stream()
                .mapToInt(binding -> literalSegmentCount(binding.servicePath()))
                .max()
                .orElse(-1);
        List<Binding> mostSpecific = matches.stream()
                .filter(binding -> literalSegmentCount(binding.servicePath())
                        == highestSpecificity)
                .toList();
        return mostSpecific.size() == 1
                ? Optional.of(mostSpecific.getFirst())
                : Optional.empty();
    }

    boolean requiresOwnerEnforcement(String method, String path) {
        if (resolveOwner(method, path).isPresent()) return true;
        if (!mailPath(path)) return false;
        if (!canonicalPath(path)) return true;
        return !"GET".equals(method)
                && !"HEAD".equals(method)
                && !"OPTIONS".equals(method);
    }

    private boolean mailPath(String path) {
        return path != null
                && (path.equals("/v1/mail") || path.startsWith("/v1/mail/")
                || path.equals("/v1/admin/mail") || path.startsWith("/v1/admin/mail/"));
    }

    private boolean canonicalMethod(String method) {
        return method != null && method.matches("[A-Z]{3,8}");
    }

    private boolean canonicalPath(String path) {
        if (path == null || !path.startsWith("/") || path.length() > 2_000
                || path.endsWith("/") || path.contains("//") || path.indexOf('%') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            return false;
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    private boolean pathMatches(String template, String path) {
        String[] expected = template.substring(1).split("/", -1);
        String[] actual = path.substring(1).split("/", -1);
        if (expected.length != actual.length) return false;
        for (int index = 0; index < expected.length; index++) {
            String segment = expected[index];
            if (segment.startsWith("{") && segment.endsWith("}")) {
                if (!actual[index].matches("[A-Za-z0-9][A-Za-z0-9._~-]{0,255}")) return false;
            } else if (!segment.equals(actual[index])) {
                return false;
            }
        }
        return true;
    }

    private int literalSegmentCount(String template) {
        int count = 0;
        for (String segment : template.substring(1).split("/", -1)) {
            if (!(segment.startsWith("{") && segment.endsWith("}"))) count++;
        }
        return count;
    }

    public List<BindingContract> bindingContracts() {
        return BINDINGS.stream().map(binding -> new BindingContract(
                POLICY_ID,
                PRODUCT_ID,
                binding.surfaceKey(),
                OWNER_SERVICE,
                SERVICE_KEY,
                binding.routeContractKey(),
                binding.routeKind(),
                binding.method(),
                binding.gatewayPath(),
                binding.servicePath(),
                binding.accessContractType(),
                binding.accessContractKey(),
                binding.resolvedAuthority(),
                binding.readOnly())).toList();
    }

    private static Binding work(
            String routeKey, RouteKind kind, String method, String path,
            AccessContractType accessType, String accessKey, String authority,
            boolean readOnly) {
        return new Binding(routeKey, kind, method, "/api/platform" + path, path,
                accessType, accessKey, authority, readOnly, SURFACE_KEY,
                "SELF", "SELF", Set.of(authority));
    }

    private static Binding admin(
            String routeKey, RouteKind kind, String method, String path,
            String accessKey, String authority, boolean readOnly) {
        return adminAny(routeKey, kind, method, path, accessKey, authority,
                readOnly, Set.of(authority));
    }

    private static Binding adminAny(
            String routeKey, RouteKind kind, String method, String path,
            String accessKey, String authority, boolean readOnly,
            Set<String> acceptedAuthorities) {
        return new Binding(routeKey, kind, method, "/api/platform" + path, path,
                AccessContractType.CAPABILITY, accessKey, authority, readOnly,
                MANAGEMENT_SURFACE_KEY, "APP_MAIL", "RESOURCE_SET",
                Set.copyOf(acceptedAuthorities));
    }

    public enum RouteKind {
        PAGE,
        DATA,
        ACTION
    }

    public enum AccessContractType {
        POLICY,
        CAPABILITY
    }

    public record Binding(
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            AccessContractType accessContractType,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly,
            String surfaceKey,
            String scopeSource,
            String scopeKind,
            Set<String> acceptedAuthorities) {
    }

    public record BindingContract(
            String policyId,
            String productId,
            String surfaceKey,
            String ownerService,
            String serviceKey,
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            AccessContractType accessContractType,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly) {
    }
}
