package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MailProductSurfaceMutationContractTest {

    private static final Set<Class<?>> PUBLIC_MAIL_CONTROLLERS = Set.of(
            MailController.class,
            MailLifecycleController.class,
            MailAddressBookController.class,
            MailWorkspaceController.class,
            MailOrganizationController.class,
            AdminMailController.class,
            AdminMailCompletionController.class,
            AdminMailWritingAssetController.class);

    private final MailProductSurfaceContract contract = new MailProductSurfaceContract();

    @Test
    void everyPublicMutationHasThePinnedCapabilityAuthorityAndActionBinding() {
        Map<Route, ExpectedAccess> expected = expectedMutations();
        Set<Route> controllerRoutes = controllerMutationRoutes();
        Map<Route, MailProductSurfaceContract.BindingContract> actionBindings =
                actionBindings();

        assertThat(controllerRoutes).containsExactlyInAnyOrderElementsOf(expected.keySet());
        assertThat(actionBindings.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());

        expected.forEach((route, access) -> {
            MailProductSurfaceContract.BindingContract binding = actionBindings.get(route);
            assertThat(binding).as("exact binding for %s %s", route.method(), route.path())
                    .isNotNull();
            assertThat(binding.routeKind())
                    .isEqualTo(MailProductSurfaceContract.RouteKind.ACTION);
            assertThat(binding.readOnly()).isFalse();
            assertThat(binding.accessContractType())
                    .isEqualTo(MailProductSurfaceContract.AccessContractType.CAPABILITY);
            assertThat(binding.accessContractKey()).isEqualTo(access.capability());
            assertThat(binding.resolvedAuthority()).isEqualTo(access.authority());
            assertThat(binding.servicePath()).isEqualTo(route.path());
            assertThat(binding.gatewayPath()).isEqualTo("/api/platform" + route.path());
            assertThat(binding.routeContractKey()).endsWith(".action");
            String concretePath = route.path().replaceAll(
                    "\\{[^/]+}", "11111111-1111-4111-8111-111111111111");
            assertThat(contract.resolveOwner(route.method(), concretePath))
                    .get()
                    .extracting(MailProductSurfaceContract.Binding::routeContractKey)
                    .isEqualTo(binding.routeContractKey());
        });
    }

    @Test
    void futureCanonicalMailMutationsRequireOwnerEnforcementWithoutABinding() {
        for (String path : Set.of(
                "/v1/mail/future-command",
                "/v1/admin/mail/future-command")) {
            for (String method : Set.of("POST", "PUT", "PATCH", "DELETE")) {
                assertThat(contract.resolveOwner(method, path)).isEmpty();
                assertThat(contract.requiresOwnerEnforcement(method, path)).isTrue();
            }
            assertThat(contract.requiresOwnerEnforcement("GET", path)).isFalse();
            assertThat(contract.requiresOwnerEnforcement("HEAD", path)).isFalse();
            assertThat(contract.requiresOwnerEnforcement("OPTIONS", path)).isFalse();
        }

        assertThat(contract.requiresOwnerEnforcement("POST", "/v1/mailer/future-command"))
                .isFalse();
        assertThat(contract.requiresOwnerEnforcement(
                "POST", "/v1/admin/mailer/future-command"))
                .isFalse();
    }

    private Map<Route, MailProductSurfaceContract.BindingContract> actionBindings() {
        Map<Route, MailProductSurfaceContract.BindingContract> result = new LinkedHashMap<>();
        contract.bindingContracts().stream()
                .filter(binding -> binding.routeKind()
                        == MailProductSurfaceContract.RouteKind.ACTION)
                .forEach(binding -> {
                    Route route = new Route(binding.method(), binding.servicePath());
                    assertThat(result.putIfAbsent(route, binding))
                            .as("duplicate owner binding for %s %s", route.method(), route.path())
                            .isNull();
                });
        return result;
    }

    private Set<Route> controllerMutationRoutes() {
        Set<Route> result = new LinkedHashSet<>();
        for (Class<?> controller : PUBLIC_MAIL_CONTROLLERS) {
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(
                    controller, RequestMapping.class);
            assertThat(root).as("root mapping for %s", controller.getSimpleName()).isNotNull();
            Set<String> roots = paths(root);
            assertThat(roots).hasSize(1);

            for (Method handler : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                        handler, RequestMapping.class);
                if (mapping == null) continue;
                for (RequestMethod method : mapping.method()) {
                    if (!mutation(method)) continue;
                    for (String rootPath : roots) {
                        for (String childPath : paths(mapping)) {
                            Route route = new Route(method.name(), rootPath + childPath);
                            assertThat(result.add(route))
                                    .as("unique controller mapping for %s %s",
                                            route.method(), route.path())
                                    .isTrue();
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean mutation(RequestMethod method) {
        return method != RequestMethod.GET
                && method != RequestMethod.HEAD
                && method != RequestMethod.OPTIONS;
    }

    private Set<String> paths(RequestMapping mapping) {
        String[] paths = mapping.path().length == 0 ? mapping.value() : mapping.path();
        return paths.length == 0 ? Set.of("") : Set.copyOf(Arrays.asList(paths));
    }

    private Map<Route, ExpectedAccess> expectedMutations() {
        Map<Route, ExpectedAccess> routes = new LinkedHashMap<>();
        expected(routes, "ADMIN.MAIL:CONNECTION_MANAGE", "mail.management.connection.manage",
                "PUT /v1/admin/mail/connections/{connectionId}",
                "POST /v1/admin/mail/connections/{connectionId}/diagnostics",
                "POST /v1/admin/mail/connections/{connectionId}/sync",
                "POST /v1/admin/mail/connections/{connectionId}/test-send");
        expected(routes, "ADMIN.MAIL:DELIVERY_CANCEL", "mail.management.delivery.cancel",
                "POST /v1/admin/mail/delivery-audit/{deliveryId}/cancel");
        expected(routes, "ADMIN.MAIL:DELIVERY_RECONCILE", "mail.management.delivery.reconcile",
                "POST /v1/admin/mail/delivery-audit/{deliveryId}/reconcile");
        expected(routes, "ADMIN.MAIL:DELIVERY_RETRY", "mail.management.delivery.retry",
                "POST /v1/admin/mail/delivery-audit/{deliveryId}/retry");
        expected(routes, "ADMIN.MAIL:EVIDENCE_EXPORT", "mail.management.evidence.export",
                "POST /v1/admin/mail/retention/evidence-exports",
                "POST /v1/admin/mail/retention/evidence-exports/{exportId}/approvals",
                "POST /v1/admin/mail/delivery-audit/exports",
                "POST /v1/admin/mail/delivery-audit/exports/{exportId}/approvals");
        expected(routes, "ADMIN.MAIL:HOLD_MANAGE", "mail.management.hold.manage",
                "POST /v1/admin/mail/retention/holds",
                "PUT /v1/admin/mail/retention/holds/{holdId}",
                "POST /v1/admin/mail/retention/holds/{holdId}/release-previews",
                "POST /v1/admin/mail/retention/hold-release-previews/{previewId}/approvals",
                "POST /v1/admin/mail/retention/hold-release-previews/{previewId}/execute");
        expected(routes, "ADMIN.MAIL:POLICY_MANAGE", "mail.management.policy.manage",
                "PUT /v1/admin/mail/policy");
        expected(routes, "ADMIN.MAIL:PURGE_AUTHORIZE", "mail.management.purge.authorize",
                "POST /v1/admin/mail/retention/purges/{snapshotId}/approvals");
        expected(routes, "ADMIN.MAIL:PURGE_EXECUTE", "mail.management.purge.execute",
                "POST /v1/admin/mail/retention/purges/{snapshotId}/execute");
        expected(routes, "ADMIN.MAIL:PURGE_PREVIEW", "mail.management.purge.preview",
                "POST /v1/admin/mail/retention/purge-previews");
        expected(routes, "ADMIN.MAIL:SHARED_INBOX_MANAGE",
                "mail.management.shared-inbox.manage",
                "PUT /v1/admin/mail/shared-inboxes/{sharedInboxId}",
                "POST /v1/admin/mail/shared-inboxes/{inboxId}/members",
                "PUT /v1/admin/mail/shared-inboxes/{inboxId}/members/{memberId}",
                "POST /v1/admin/mail/shared-inboxes/{inboxId}/members/{memberId}/revoke-preview",
                "POST /v1/admin/mail/shared-inboxes/{inboxId}/members/{memberId}/revoke");
        expected(routes, "ADMIN.MAIL:WRITING_ASSET_APPROVE",
                "mail.management.writing-asset.approve",
                "POST /v1/admin/mail/writing-assets/{kind}/{assetId}/approve");
        expected(routes, "ADMIN.MAIL:WRITING_ASSET_EDIT", "mail.management.writing-asset.edit",
                "POST /v1/admin/mail/writing-assets/{kind}/drafts",
                "PUT /v1/admin/mail/writing-assets/{kind}/drafts/{assetId}");
        expected(routes, "ADMIN.MAIL:WRITING_ASSET_PUBLISH",
                "mail.management.writing-asset.publish",
                "POST /v1/admin/mail/writing-assets/{kind}/{assetId}/publish");
        expected(routes, "ADMIN.MAIL:WRITING_ASSET_RETIRE",
                "mail.management.writing-asset.retire",
                "POST /v1/admin/mail/writing-assets/{kind}/{assetId}/retire");
        expected(routes, "ADMIN.MAIL:WRITING_ASSET_SUBMIT",
                "mail.management.writing-asset.submit",
                "POST /v1/admin/mail/writing-assets/{kind}/{assetId}/submit");

        expected(routes, "APP.MAIL:CREATE", "mail.work.folder.create",
                "POST /v1/mail/organization/folders");
        expected(routes, "APP.MAIL:CREATE", "mail.work.message.create",
                "POST /v1/mail/messages",
                "POST /v1/mail/drafts");
        expected(routes, "APP.MAIL:CREATE", "mail.work.rule.create",
                "POST /v1/mail/organization/rules");
        expected(routes, "APP.MAIL:CREATE", "mail.work.saved-view.create",
                "POST /v1/mail/saved-views");
        expected(routes, "APP.MAIL:CREATE", "mail.work.writing-asset.create",
                "POST /v1/mail/templates",
                "POST /v1/mail/signatures");
        expected(routes, "APP.MAIL:DECIDE", "mail.work.proposal.decide",
                "POST /v1/mail/proposals/{proposalId}/decision",
                "POST /v1/mail/proposals/{proposalId}/handoff/cancel");
        expected(routes, "APP.MAIL:DELETE", "mail.work.address-book.delete",
                "DELETE /v1/mail/contacts/{contactId}",
                "DELETE /v1/mail/contact-groups/{groupId}");
        expected(routes, "APP.MAIL:DELETE", "mail.work.follow-up.delete",
                "DELETE /v1/mail/follow-ups/{followUpId}");
        expected(routes, "APP.MAIL:DELETE", "mail.work.message.delete",
                "DELETE /v1/mail/attachments/{attachmentId}");
        expected(routes, "APP.MAIL:DELETE", "mail.work.saved-view.delete",
                "DELETE /v1/mail/saved-views/{savedViewId}");
        expected(routes, "APP.MAIL:DELETE", "mail.work.writing-asset.delete",
                "DELETE /v1/mail/templates/{templateId}",
                "DELETE /v1/mail/signatures/{signatureId}");
        expected(routes, "APP.MAIL:SEND", "mail.work.message.send",
                "POST /v1/mail/messages/advanced",
                "POST /v1/mail/threads/{threadId}/replies",
                "POST /v1/mail/threads/{threadId}/messages/{messageId}/retry",
                "POST /v1/mail/contact-groups/{groupId}/messages",
                "POST /v1/mail/deliveries/{deliveryId}/retry");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.address-book.manage",
                "POST /v1/mail/contacts",
                "PUT /v1/mail/contacts/{contactId}",
                "POST /v1/mail/contact-groups",
                "PUT /v1/mail/contact-groups/{groupId}",
                "PUT /v1/mail/contact-groups/{groupId}/members");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.attachment.manage",
                "POST /v1/mail/attachments");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.delivery.manage",
                "POST /v1/mail/deliveries/{deliveryId}/reschedule",
                "POST /v1/mail/deliveries/{deliveryId}/cancel",
                "POST /v1/mail/deliveries/{deliveryId}/reconcile");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.folder.manage",
                "PUT /v1/mail/organization/folders/{folderId}",
                "POST /v1/mail/organization/folders/{folderId}/archive");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.follow-up.manage",
                "POST /v1/mail/threads/{threadId}/follow-up",
                "PUT /v1/mail/follow-ups/{followUpId}");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.message.update",
                "PUT /v1/mail/drafts/{threadId}",
                "PUT /v1/mail/threads/{threadId}/draft",
                "POST /v1/mail/threads/{threadId}/lifecycle/preview",
                "POST /v1/mail/threads/{threadId}/lifecycle",
                "POST /v1/mail/threads/{threadId}/actions",
                "POST /v1/mail/threads/{threadId}/snooze");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.preferences.manage",
                "PUT /v1/mail/preferences");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.proposal.update",
                "PUT /v1/mail/proposals/{proposalId}");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.rule.manage",
                "PUT /v1/mail/organization/rules/{ruleId}",
                "PUT /v1/mail/organization/rules/order",
                "POST /v1/mail/organization/rules/{ruleId}/archive",
                "POST /v1/mail/organization/rules/{ruleId}/run",
                "POST /v1/mail/organization/accounts/{accountId}/rules/backfill");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.saved-view.manage",
                "PUT /v1/mail/saved-views/{savedViewId}");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.shared-thread.manage",
                "POST /v1/mail/threads/{threadId}/assignment",
                "POST /v1/mail/threads/{threadId}/comments");
        expected(routes, "APP.MAIL:UPDATE", "mail.work.writing-asset.manage",
                "PUT /v1/mail/templates/{templateId}",
                "PUT /v1/mail/signatures/{signatureId}");
        return Map.copyOf(routes);
    }

    private void expected(
            Map<Route, ExpectedAccess> routes,
            String authority,
            String capability,
            String... values) {
        for (String value : values) {
            int separator = value.indexOf(' ');
            Route route = new Route(
                    value.substring(0, separator),
                    value.substring(separator + 1));
            assertThat(routes.putIfAbsent(route, new ExpectedAccess(authority, capability)))
                    .as("unique expected route for %s %s", route.method(), route.path())
                    .isNull();
        }
    }

    private record Route(String method, String path) {
    }

    private record ExpectedAccess(String authority, String capability) {
    }
}
