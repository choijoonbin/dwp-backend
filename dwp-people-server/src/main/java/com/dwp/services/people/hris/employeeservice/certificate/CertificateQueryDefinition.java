package com.dwp.services.people.hris.employeeservice.certificate;

import java.util.List;
import java.util.Set;

/** Closed owner-query registry for IAQ-003 and IAQ-010. */
enum CertificateQueryDefinition {

    SELF_REQUESTS(
            "IAQ-003", "IAQ.OP.003", "REQUESTS",
            "/v1/hris/workbench/self-service/requests",
            "/api/people/v1/hris/workbench/self-service/requests",
            "/hr/me/requests",
            "route.hcm.personal.requests.data", "self-request-reader",
            "route.hcm.personal.requests.data.binding.01",
            "route.hcm.personal.requests.data.self-request-reader.projection.v1",
            "Hrm009SelfRequestsProjectionV1",
            "hrm009.iaq-003.fields.v1",
            "hcm.personal.requests.view", "EMPLOYEE_SELF_SERVICE",
            Set.of("predicate.self-person.v1"), Set.of("SELF"),
            List.of("REQUEST_SUMMARY", "REQUEST_STATUS", "COMMAND_RECEIPT"),
            Set.of(
                    "requestSummary:ASC", "requestSummary:DESC",
                    "requestStatus:ASC", "requestStatus:DESC",
                    "commandReceipt:ASC", "commandReceipt:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC"),
            List.of(
                    "hris.action.hrm.create-employee-change-request",
                    "hris.action.hrm.submit-employee-change-request",
                    "hris.action.hrm.request-certificate")),
    OPERATIONS_CERTIFICATES(
            "IAQ-010", "IAQ.OP.010", "CERTIFICATES_DOCUMENTS",
            "/v1/hris/workbench/operations/certificates/documents",
            "/api/people/v1/hris/workbench/operations/certificates/documents",
            "/hr/operations/people/certificates-documents",
            "route.hcm.operations.certificates-documents.data",
            "people-operations-reader",
            "route.hcm.operations.certificates-documents.data.binding.01",
            "route.hcm.operations.certificates-documents.data.people-operations-reader.projection.v1",
            "Hrm009CertificatesDocumentsProjectionV1",
            "hrm009.iaq-010.fields.v1",
            "hcm.operations.people.view", "HR_OPERATIONS",
            Set.of("predicate.hcm-workforce-visible-person.v1"),
            Set.of("TARGET_POPULATION"),
            List.of("CERTIFICATE_METADATA", "DOCUMENT_METADATA"),
            Set.of(
                    "certificateMetadata:ASC", "certificateMetadata:DESC",
                    "documentMetadata:ASC", "documentMetadata:DESC",
                    "itemPublicId:ASC", "itemPublicId:DESC"),
            List.of("hris.action.hrm.request-certificate"));

    private final String queryId;
    private final String operationId;
    private final String variantKey;
    private final String ownerPath;
    private final String browserPath;
    private final String deepLinkRoute;
    private final String routeContractKey;
    private final String profileKey;
    private final String bindingKey;
    private final String projectionPolicyKey;
    private final String responseSchemaKey;
    private final String fieldPolicyKey;
    private final String capability;
    private final String purpose;
    private final Set<String> predicatePolicyKeys;
    private final Set<String> targetBindingKinds;
    private final List<String> fieldGroups;
    private final Set<String> sorts;
    private final List<String> actionKeys;

    CertificateQueryDefinition(
            String queryId,
            String operationId,
            String variantKey,
            String ownerPath,
            String browserPath,
            String deepLinkRoute,
            String routeContractKey,
            String profileKey,
            String bindingKey,
            String projectionPolicyKey,
            String responseSchemaKey,
            String fieldPolicyKey,
            String capability,
            String purpose,
            Set<String> predicatePolicyKeys,
            Set<String> targetBindingKinds,
            List<String> fieldGroups,
            Set<String> sorts,
            List<String> actionKeys) {
        this.queryId = queryId;
        this.operationId = operationId;
        this.variantKey = variantKey;
        this.ownerPath = ownerPath;
        this.browserPath = browserPath;
        this.deepLinkRoute = deepLinkRoute;
        this.routeContractKey = routeContractKey;
        this.profileKey = profileKey;
        this.bindingKey = bindingKey;
        this.projectionPolicyKey = projectionPolicyKey;
        this.responseSchemaKey = responseSchemaKey;
        this.fieldPolicyKey = fieldPolicyKey;
        this.capability = capability;
        this.purpose = purpose;
        this.predicatePolicyKeys = Set.copyOf(predicatePolicyKeys);
        this.targetBindingKinds = Set.copyOf(targetBindingKinds);
        this.fieldGroups = List.copyOf(fieldGroups);
        this.sorts = Set.copyOf(sorts);
        this.actionKeys = List.copyOf(actionKeys);
    }

    String queryId() { return queryId; }
    String operationId() { return operationId; }
    String variantKey() { return variantKey; }
    String ownerPath() { return ownerPath; }
    String browserPath() { return browserPath; }
    String deepLinkRoute() { return deepLinkRoute; }
    String routeContractKey() { return routeContractKey; }
    String profileKey() { return profileKey; }
    String bindingKey() { return bindingKey; }
    String projectionPolicyKey() { return projectionPolicyKey; }
    String responseSchemaKey() { return responseSchemaKey; }
    String fieldPolicyKey() { return fieldPolicyKey; }
    String capability() { return capability; }
    String purpose() { return purpose; }
    Set<String> predicatePolicyKeys() { return predicatePolicyKeys; }
    Set<String> targetBindingKinds() { return targetBindingKinds; }
    List<String> fieldGroups() { return fieldGroups; }
    Set<String> sorts() { return sorts; }
    List<String> actionKeys() { return actionKeys; }
}
