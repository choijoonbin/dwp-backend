package com.dwp.services.platform.hrisconfiguration.integration;

import java.util.Map;
import java.util.Set;

/** Immutable reference to the reviewed SYS-008 G2 transport artifact. */
public final class Sys008ClosedTransportSchemaReference {

    public static final String SOURCE_REPOSITORY = "dwp-frontend";
    public static final String SOURCE_PATH =
            "docs/06-delivery/hris/recovery/hris-porting-blueprint-2026-09-09/"
                    + "session-evidence/sys/g2-transport-schemas.v1.json";
    public static final String SOURCE_SHA256 =
            "7067a7adad828d4918220fcde54fc0fa9a2add5db1d5745e0a4acb0a50698cf7";
    public static final long SOURCE_BYTES = 95_808L;
    public static final String SEMANTIC_SOURCE_PATH =
            "docs/06-delivery/hris/wave7-2026-10-17/"
                    + "r7-batch-05-module-start-r1/semantic-dispositions/"
                    + "base-tfr-sys-008.semantic-disposition.v1.json";
    public static final String SEMANTIC_JSON_POINTER =
            "/contractDecision/closedTransportSchemas";
    public static final String SEMANTIC_SECTION_SHA256 =
            "9f4cffe7a433445c5b054f10cec4573127cdcad52e94934956802d25cc9535f2";

    public static final Map<String, String> SEMANTIC_TO_G2_SCHEMA = Map.ofEntries(
            Map.entry("connectorCommand", "ConnectorCommand"),
            Map.entry("connectorTestCommand", "ConnectorTestCommand"),
            Map.entry("mappingDryRunCommand", "MappingDryRunCommand"),
            Map.entry("executionCommand", "ExecutionCommand"),
            Map.entry("definitionValidationCommand", "ConnectorValidationCommand"),
            Map.entry("mappingCommand", "ConnectorMappingCommand"),
            Map.entry("mappingValidationCommand", "MappingValidationCommand"),
            Map.entry("approvalRequest", "ApprovalRequestCommand"),
            Map.entry("approvalDecision", "ApprovalDecisionCommand"),
            Map.entry("activationCommand", "ActivationCommand"),
            Map.entry("reconcileCommand", "ExecutionReconcileCommand"),
            Map.entry("retryCommand", "FailedExecutionItemRetryCommand"),
            Map.entry("cancelCommand", "CancelCommand"),
            Map.entry("executionRead", "ConnectorExecutionReceiptResponse"));

    public static final Set<String> SEMANTIC_SCHEMAS =
            SEMANTIC_TO_G2_SCHEMA.keySet();

    public static final Set<Operation> OPERATIONS = Set.of(
            new Operation("SYS-API-021.GET", "GET",
                    "/api/platform/v1/admin/hris/connectors"),
            new Operation("SYS-API-021.POST", "POST",
                    "/api/platform/v1/admin/hris/connectors"),
            new Operation("SYS-API-022", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/test"),
            new Operation("SYS-API-023", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/dry-runs"),
            new Operation("SYS-API-024", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/executions"),
            new Operation("SYS-API-025", "GET",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}"),
            new Operation("SYS-API-057", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/validate"),
            new Operation("SYS-API-058", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/submit"),
            new Operation("SYS-API-059", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/approve"),
            new Operation("SYS-API-060", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/activate"),
            new Operation("SYS-API-061", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings"),
            new Operation("SYS-API-062", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/validate"),
            new Operation("SYS-API-063", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/submit"),
            new Operation("SYS-API-064", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/approve"),
            new Operation("SYS-API-065", "POST",
                    "/api/platform/v1/admin/hris/connectors/{connectorId}/mappings/{version}/activate"),
            new Operation("SYS-API-066", "POST",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}/reconcile"),
            new Operation("SYS-API-067", "POST",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}/retry"),
            new Operation("SYS-API-068", "POST",
                    "/api/platform/v1/admin/hris/connector-executions/{executionId}/cancel"));

    private Sys008ClosedTransportSchemaReference() {}

    public record Operation(String apiId, String method, String browserPath) {}
}
