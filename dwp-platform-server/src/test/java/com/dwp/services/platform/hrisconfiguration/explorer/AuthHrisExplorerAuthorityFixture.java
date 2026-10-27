package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.AuthorityRequest;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.CONTEXT;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.DECISION;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.NOW;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.SCOPE;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.SUBJECT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Shared setup and evidence builders for AuthHrisExplorerAuthorityEvidenceQueryTest. */
abstract class AuthHrisExplorerAuthorityFixture {
    protected static final String AUTH_URL = "http://auth.test";
    protected static final String PEOPLE_URL = "http://people.test";
    protected static final String RAW_SCOPE = "scope-" + "9".repeat(32);
    protected static final String RAW_SCOPE_ALIAS = "scope-" + "8".repeat(32);
    protected static final String RELATIONSHIP_REVISION = "relationship-v1";
    protected static final String POPULATION_REVISION = "population-v1";
    protected static final String DERIVED_SCOPE = HcmEligibilityScopeKey.derived(
            42, 84, "hcm.utility", RAW_SCOPE,
            RELATIONSHIP_REVISION, POPULATION_REVISION);
    protected static final String PAY_SCOPE =
            "scope-0b4a9f69ff6219b40b0c9c48e7576271";
    protected static final String PAYROLL_LEGAL_ENTITY_SCOPE =
            "scope-312eb5f15464a0a9e9ab4b6aaa4c1f19";
    protected static final String PAY_DERIVED_SCOPE =
            "hcm-scope-3b21f50dc0bc6974f09f6bcb2b987f677ce5b7a5";
    protected static final String PLATFORM_RECEIPT_SCOPE = ProductSurfaceScopeKey.key(
            42, 84, "hcm", "hcm.utility",
            "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
    protected static final String PLATFORM_RECEIPT_CONTEXT = "psc-" + "6".repeat(64);
    protected static final String PLATFORM_RECEIPT_DECISION =
            AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                    42, 84, "NORMAL", "auth-platform-receipt-v1",
                    "policy-platform-receipt-v1", null, null, "hcm.utility",
                    AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                    "route-grant:platform-command-receipt");
    protected static final String ORIGIN_RAW_SCOPE = "scope-" + "4".repeat(32);
    protected static final String ORIGIN_DERIVED_SCOPE = HcmEligibilityScopeKey.derived(
            42, 84, "hcm.management", ORIGIN_RAW_SCOPE,
            RELATIONSHIP_REVISION, POPULATION_REVISION);
    protected static final String ORIGIN_CONTEXT = "psc-" + "5".repeat(64);
    protected static final String ORIGIN_ROUTE =
            "route.hcm.configuration.configuration-version-publish.action";
    protected static final String CONTROLLED_TRANSFER_ROUTE =
            "route.hcm.management.controlled-transfer-create.action";
    protected static final String ORIGIN_DECISION =
            AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                    42, 84, "NORMAL", "auth-origin-v1", "policy-origin-v1",
                    RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.management",
                    ORIGIN_ROUTE, "route-grant:configuration-publish");

    protected static final Map<String, String> EXPECTED_ORIGINS = Map.ofEntries(
            Map.entry("configuration.version.create",
                    "route.hcm.configuration.configuration-version-create.action"),
            Map.entry("configuration.version.validate",
                    "route.hcm.configuration.configuration-version-validate.action"),
            Map.entry("configuration.version.simulate",
                    "route.hcm.configuration.configuration-version-simulate.action"),
            Map.entry("configuration.version.submit",
                    "route.hcm.configuration.configuration-version-submit.action"),
            Map.entry("configuration.version.approve",
                    "route.hcm.configuration.configuration-version-approve.action"),
            Map.entry("configuration.version.schedule",
                    "route.hcm.configuration.configuration-version-schedule.action"),
            Map.entry("configuration.version.publish",
                    "route.hcm.configuration.configuration-version-publish.action"),
            Map.entry("configuration.version.rollback",
                    "route.hcm.configuration.configuration-version-rollback.action"),
            Map.entry("configuration.version.retire",
                    "route.hcm.configuration.configuration-version-retire.action"),
            Map.entry("CREATE_DRAFT",
                    "route.hcm.configuration.navigation-policy-draft-create.action"),
            Map.entry("UPDATE_DRAFT",
                    "route.hcm.configuration.navigation-policy-draft-update.action"),
            Map.entry("VALIDATE",
                    "route.hcm.configuration.navigation-policy-draft-validate.action"),
            Map.entry("SUBMIT",
                    "route.hcm.configuration.navigation-policy-draft-submit.action"),
            Map.entry("APPROVE",
                    "route.hcm.configuration.navigation-policy-revision-approve.action"),
            Map.entry("PUBLISH",
                    "route.hcm.configuration.navigation-policy-revision-publish.action"),
            Map.entry("RESTORE",
                    "route.hcm.configuration.navigation-policy-revision-restore.action"),
            Map.entry("CONTROLLED_TRANSFER_CREATE", CONTROLLED_TRANSFER_ROUTE),
            Map.entry("hris.action.sys.post.platform.v1.admin.hris.automations",
                    "route.hcm.configuration.automation-definition-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.runs",
                    "route.hcm.configuration.automation-run-queue.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.retry",
                    "route.hcm.configuration.automation-run-retry.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions",
                    "route.hcm.configuration.automation-version-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.validate",
                    "route.hcm.configuration.automation-version-validate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.submit",
                    "route.hcm.configuration.automation-version-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.approve",
                    "route.hcm.configuration.automation-version-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.versions.resource.activate",
                    "route.hcm.configuration.automation-version-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automations.resource.schedules",
                    "route.hcm.configuration.automation-schedule-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.submit",
                    "route.hcm.configuration.automation-schedule-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.approve",
                    "route.hcm.configuration.automation-schedule-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.activate",
                    "route.hcm.configuration.automation-schedule-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-schedules.resource.suspend",
                    "route.hcm.configuration.automation-schedule-suspend.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.automation-runs.resource.cancel",
                    "route.hcm.configuration.automation-run-cancel.action"),
            Map.entry("hris.action.sys.post.platform.v1.admin.hris.connectors",
                    "route.hcm.configuration.connector-definition-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.test",
                    "route.hcm.configuration.connector-test.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.dry-runs",
                    "route.hcm.configuration.connector-mapping-dry-run.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.executions",
                    "route.hcm.configuration.connector-execution-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.validate",
                    "route.hcm.configuration.connector-definition-validate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.submit",
                    "route.hcm.configuration.connector-definition-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.approve",
                    "route.hcm.configuration.connector-definition-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.activate",
                    "route.hcm.configuration.connector-definition-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings",
                    "route.hcm.configuration.connector-mapping-version-create.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.validate",
                    "route.hcm.configuration.connector-mapping-version-validate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.submit",
                    "route.hcm.configuration.connector-mapping-version-submit.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.approve",
                    "route.hcm.configuration.connector-mapping-version-approve.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connectors.resource.mappings.resource.activate",
                    "route.hcm.configuration.connector-mapping-version-activate.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile",
                    "route.hcm.configuration.connector-execution-reconcile.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry",
                    "route.hcm.configuration.connector-execution-retry.action"),
            Map.entry(
                    "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel",
                    "route.hcm.configuration.connector-execution-cancel.action"));

    protected static void expectIdentity(MockRestServiceServer authServer) {
        authServer.expect(requestTo(AUTH_URL
                        + "/internal/identity/v1/tenants/42/users/84"))
                .andRespond(withSuccess(identity(), MediaType.APPLICATION_JSON));
    }

    protected static String identity() {
        return """
                {
                  "tenantId": 42,
                  "userId": 84,
                  "personPublicId": "%s",
                  "status": "ACTIVE",
                  "identityPlane": "TENANT"
                }
                """.formatted(SUBJECT);
    }

    protected static AuthHrisExplorerAuthorityEvidenceQuery.AuthorityResult
            payrollAuthority(boolean activeMutation) {
        java.time.OffsetDateTime readExpiry = java.time.OffsetDateTime.parse(
                "2026-10-15T06:00:01Z");
        java.time.OffsetDateTime mutationExpiry = java.time.OffsetDateTime.parse(
                "2026-10-15T06:00:00Z");
        AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant read =
                new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant(
                        "CAPABILITY", "hcm.payroll-foundation.view",
                        "HCM_PAYROLL_FOUNDATION_VIEW", "payroll-read-policy",
                        "decision:payroll-read", "PERMISSION_ONLY", List.of(),
                        null, null, List.of(PAY_SCOPE), true, true, "ACTIVE",
                        readExpiry);
        AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant mutation =
                new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveGrant(
                        "CAPABILITY", "hcm.payroll-foundation.manage",
                        "HCM_PAYROLL_FOUNDATION_MANAGE", "payroll-manage-policy",
                        "decision:payroll-manage", "PERMISSION_AND_RELATIONSHIP",
                        List.of("predicate:payroll"), "PAYROLL_ADMIN", null,
                        List.of(PAYROLL_LEGAL_ENTITY_SCOPE), true, false,
                        activeMutation ? "ACTIVE" : "INACTIVE", mutationExpiry);
        return new AuthHrisExplorerAuthorityEvidenceQuery.AuthorityResult(
                "ALLOWED", null, "auth-payroll-v1", "policy-payroll-v1",
                CONTEXT, "hcm", "hcm.operations", "operations", "NORMAL",
                "ASSIGNMENT", "hcm.payroll-foundation", List.of(read, mutation),
                List.of(
                        new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveScope(
                                PAY_SCOPE, "TARGET_POPULATION",
                                "Payroll population", true, true, readExpiry),
                        new AuthHrisExplorerAuthorityEvidenceQuery.EffectiveScope(
                                PAYROLL_LEGAL_ENTITY_SCOPE, "TARGET_POPULATION",
                                "Payroll population", true, false,
                                mutationExpiry)),
                "route-grant:payroll-foundation", false, true, mutationExpiry,
                null, null, null, NOW.plusMinutes(5), "auth-evidence-payroll");
    }

    protected static AuthHrisExplorerAuthorityEvidenceQuery.EligibilityResult
            payrollEligibility() {
        return new AuthHrisExplorerAuthorityEvidenceQuery.EligibilityResult(
                "ALLOWED", null, "relationship-payroll-v1",
                "population-payroll-v1", List.of(
                new AuthHrisExplorerAuthorityEvidenceQuery.EligibleScope(
                        PAY_SCOPE, PAY_DERIVED_SCOPE, "TARGET_POPULATION",
                        "Payroll population", true, false,
                        java.time.OffsetDateTime.parse("2026-10-15T06:00:01Z")),
                new AuthHrisExplorerAuthorityEvidenceQuery.EligibleScope(
                        PAYROLL_LEGAL_ENTITY_SCOPE, PAY_DERIVED_SCOPE,
                        "TARGET_POPULATION", "Payroll population", true, false,
                        java.time.OffsetDateTime.parse("2026-10-15T06:00:00Z"))),
                NOW.plusMinutes(5), "people-evidence-payroll");
    }

    protected static AuthorityRequest request() {
        String decision = AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                42, 84, "NORMAL", "auth-v1", "policy-v1",
                RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.utility",
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PAGE_ROUTE,
                "route-grant:explorer-page");
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", decision,
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PAGE_ROUTE,
                CONTEXT, DERIVED_SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.EXPLORER_PURPOSE,
                NOW.plusMinutes(5));
    }

    protected static AuthorityRequest receiptRequest() {
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", "psr-" + "7".repeat(64),
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                CONTEXT, DERIVED_SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_PURPOSE,
                NOW.plusMinutes(5));
    }

    protected static AuthorityRequest platformReceiptRequest() {
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", PLATFORM_RECEIPT_DECISION,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                PLATFORM_RECEIPT_CONTEXT, PLATFORM_RECEIPT_SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_PURPOSE,
                NOW.plusMinutes(5));
    }

    protected static AuthorityRequest dualReceiptRequest() {
        return new AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", DECISION,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_ROUTE,
                CONTEXT, SCOPE,
                AuthHrisExplorerAuthorityEvidenceQuery.RECEIPT_PURPOSE,
                NOW.plusMinutes(5));
    }

    protected static String allowedPlatformReceipt() {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "auth-platform-receipt-v1",
                  "policyRevision": "policy-platform-receipt-v1",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.utility",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.command-receipt.view",
                    "resolvedCapabilityCode": "DATA.HRIS_COMMAND_RECEIPT:VIEW",
                    "authorityMode": "PERMISSION_AND_RELATIONSHIP",
                    "scopeKeys": ["%s"],
                    "requiresProductEntitlement": false,
                    "readOnly": true,
                    "activationState": "ACTIVE"
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "TARGET_POPULATION",
                    "displayName": "Originating command population",
                    "isDefault": true,
                    "readOnly": true,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "routeGrantRef": "route-grant:platform-command-receipt",
                  "effectiveReadOnly": true,
                  "requiresProductEligibility": false,
                  "revalidateAt": "2026-10-15T04:10:00Z"
                }
                """.formatted(
                PLATFORM_RECEIPT_CONTEXT, PLATFORM_RECEIPT_SCOPE,
                PLATFORM_RECEIPT_SCOPE);
    }

    protected static String allowedManagementOrigin() {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "auth-origin-v1",
                  "policyRevision": "policy-origin-v1",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.management",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.reference-configuration.manage",
                    "resolvedCapabilityCode": "CONFIG.HRIS_POLICY:UPDATE",
                    "authorityMode": "PERMISSION_AND_RELATIONSHIP",
                    "scopeKeys": ["%s"],
                    "requiresProductEntitlement": true,
                    "readOnly": false,
                    "activationState": "ACTIVE"
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "RESOURCE_SET",
                    "displayName": "HCM configuration",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "routeGrantRef": "route-grant:configuration-publish",
                  "effectiveReadOnly": false,
                  "requiresProductEligibility": true,
                  "revalidateAt": "2026-10-15T04:20:00Z"
                }
                """.formatted(ORIGIN_CONTEXT, ORIGIN_RAW_SCOPE, ORIGIN_RAW_SCOPE);
    }

    protected static String allowedControlledTransferOrigin() {
        return allowedManagementOrigin()
                .replace("hcm.reference-configuration.manage",
                        "hcm.integration.transfer.create")
                .replace("CONFIG.HRIS_POLICY:UPDATE",
                        "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE")
                .replace("route-grant:configuration-publish",
                        "route-grant:controlled-transfer-create");
    }

    protected static HrisExplorerRepository.ReceiptAuthority
            controlledTransferStored() {
        long exactField = AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                "field-policy", "policy-origin-v1");
        long exactAuthorization =
                AuthHrisExplorerAuthorityEvidenceQuery.authorityRevision(
                        "authorization", "auth-origin-v1");
        String originDecision =
                AuthHrisExplorerAuthorityEvidenceQuery.decisionRevision(
                        42, 84, "NORMAL", "auth-origin-v1", "policy-origin-v1",
                        RELATIONSHIP_REVISION, POPULATION_REVISION, "hcm.management",
                        CONTROLLED_TRANSFER_ROUTE,
                        "route-grant:controlled-transfer-create");
        return new HrisExplorerRepository.ReceiptAuthority(
                SUBJECT, "CONTROLLED_TRANSFER_CREATE", "b".repeat(64),
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        ORIGIN_DERIVED_SCOPE),
                HrisExplorerAuthoritySeals.publicRevision(exactField), exactField,
                "HRIS_INTEGRATION_ADMIN",
                HrisExplorerAuthoritySeals.publicRevision(exactAuthorization),
                exactAuthorization, CONTROLLED_TRANSFER_ROUTE, ORIGIN_CONTEXT,
                ORIGIN_DERIVED_SCOPE, originDecision);
    }

    protected static String allowedManagementPeople() {
        return """
                {
                  "decision": "ALLOWED",
                  "productRelationshipRevision": "%s",
                  "targetPopulationRevision": "%s",
                  "scopes": [{
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "RESOURCE_SET",
                    "displayName": "HCM configuration",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T04:50:00Z"
                  }],
                  "revalidateAt": "2026-10-15T04:15:00Z"
                }
                """.formatted(
                RELATIONSHIP_REVISION, POPULATION_REVISION,
                ORIGIN_RAW_SCOPE, ORIGIN_DERIVED_SCOPE);
    }

    protected static String allowedPage(String authRevision, String policyRevision) {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "%s",
                  "policyRevision": "%s",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.utility",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.app.shell.view",
                    "resolvedCapabilityCode": "HCM_APP_SHELL_VIEW",
                    "scopeKeys": ["%s"],
                    "readOnly": true
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": true,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "routeGrantRef": "route-grant:explorer-page",
                  "effectiveReadOnly": true,
                  "requiresProductEligibility": true,
                  "revalidateAt": "2026-10-15T04:10:00Z"
                }
                """.formatted(
                authRevision, policyRevision, CONTEXT, RAW_SCOPE, RAW_SCOPE);
    }

    protected static String allowedPeople() {
        return """
                {
                  "decision": "ALLOWED",
                  "productRelationshipRevision": "%s",
                  "targetPopulationRevision": "%s",
                  "scopes": [{
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": true,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }],
                  "revalidateAt": "2026-10-15T04:10:00Z"
                }
                """.formatted(
                RELATIONSHIP_REVISION, POPULATION_REVISION,
                RAW_SCOPE, DERIVED_SCOPE);
    }

    protected static String allowedExplorerOriginAliases() {
        return """
                {
                  "decision": "ALLOWED",
                  "authRevision": "auth-v1",
                  "policyRevision": "policy-v1",
                  "contextKey": "%s",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.utility",
                  "accessMode": "NORMAL",
                  "effectiveGrants": [{
                    "grantKind": "CAPABILITY",
                    "capabilityContractKey": "hcm.app.shell.view",
                    "resolvedCapabilityCode": "HCM_APP_SHELL_VIEW",
                    "authorityMode": "PERMISSION_AND_RELATIONSHIP",
                    "scopeKeys": ["%s", "%s"],
                    "requiresProductEntitlement": true,
                    "readOnly": false,
                    "activationState": "ACTIVE"
                  }],
                  "scopes": [{
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T05:00:00Z"
                  }, {
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": false,
                    "readOnly": true,
                    "validUntil": "2026-10-15T04:50:00Z"
                  }],
                  "routeGrantRef": "route-grant:explorer-recent",
                  "effectiveReadOnly": false,
                  "requiresProductEligibility": true,
                  "revalidateAt": "2026-10-15T04:55:00Z"
                }
                """.formatted(
                CONTEXT, RAW_SCOPE, RAW_SCOPE_ALIAS,
                RAW_SCOPE, RAW_SCOPE_ALIAS);
    }

    protected static String allowedPeopleAliases() {
        return """
                {
                  "decision": "ALLOWED",
                  "productRelationshipRevision": "%s",
                  "targetPopulationRevision": "%s",
                  "scopes": [{
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T04:45:00Z"
                  }, {
                    "sourceScopeKey": "%s",
                    "key": "%s",
                    "kind": "POPULATION",
                    "displayName": "Current population",
                    "isDefault": true,
                    "readOnly": false,
                    "validUntil": "2026-10-15T04:50:00Z"
                  }],
                  "revalidateAt": "2026-10-15T04:55:00Z"
                }
                """.formatted(
                RELATIONSHIP_REVISION, POPULATION_REVISION,
                RAW_SCOPE, DERIVED_SCOPE, RAW_SCOPE_ALIAS, DERIVED_SCOPE);
    }

    protected static AuthHrisExplorerAuthorityEvidenceQuery query(
            RestClient auth, RestClient people) {
        return new AuthHrisExplorerAuthorityEvidenceQuery(
                auth, people, "product-token", "identity-token",
                "people-token",
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"),
                        ZoneOffset.UTC),
                new ObjectMapper().findAndRegisterModules()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    protected static String denied(String authRevision, String policyRevision) {
        return """
                {
                  "decision": "ROUTE_DENIED",
                  "authRevision": "%s",
                  "policyRevision": "%s"
                }
                """.formatted(authRevision, policyRevision);
    }
}
