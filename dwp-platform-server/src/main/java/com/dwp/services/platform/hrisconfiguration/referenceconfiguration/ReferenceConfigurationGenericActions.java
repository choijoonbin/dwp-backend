package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.util.List;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.AvailableAction;

/** Existing generic V1 action templates, kept separate from target-bound PER V2 descriptors. */
final class ReferenceConfigurationGenericActions {
    static final List<AvailableAction> AUTHOR_ACTIONS = List.of(
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.validate",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/validate"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.simulate",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/simulate"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.submit",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/submit"));
    static final List<AvailableAction> PUBLISHER_ACTIONS = List.of(
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.approve",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/approve"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.publish",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/publish"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.schedule",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/schedule"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.rollback",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/rollback"),
            action(
                    "hris.action.sys.post.platform.v1.admin.hris.configurations.resource.versions.resource.retire",
                    "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}/retire"));


    private static AvailableAction action(String key, String href) {
        return new AvailableAction(key, "POST", href, "REQUIRED_IDEMPOTENCY_KEY",
                "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
    }
    private ReferenceConfigurationGenericActions() { }
}
