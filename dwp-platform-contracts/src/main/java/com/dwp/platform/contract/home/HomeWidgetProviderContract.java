package com.dwp.platform.contract.home;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provider-neutral contract used by the Home Runtime Broker and application owners.
 *
 * <p>The contract contains only declarative data. Providers must never return HTML, script,
 * credentials, or an arbitrary external URL. Recipient identity and authorization are carried on
 * the mutually authenticated service request, not accepted from this payload.</p>
 */
public final class HomeWidgetProviderContract {

    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_WIDGETS_PER_BATCH = 100;
    public static final int MAX_ACTIONS_PER_WIDGET = 8;
    public static final int MAX_ITEM_LIMIT = 50;
    public static final String BATCH_PATH = "/internal/home/v1/widget-data:batch";
    public static final String COMMAND_PATH = "/internal/home/v1/widget-actions:execute";
    public static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    public static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    public static final String AUTHORITY_REVISION_HEADER = "X-DWP-Current-Decision-Revision";

    private HomeWidgetProviderContract() {
    }

    public enum State {
        AVAILABLE,
        EMPTY,
        PARTIAL,
        FORBIDDEN,
        UNAVAILABLE,
        STALE
    }

    public enum ActionKind {
        SOURCE_ROUTE,
        COMMAND
    }

    public enum CommandStatus {
        ACCEPTED,
        COMPLETED
    }

    public record BatchRequest(
            int schemaVersion,
            List<WidgetRequest> widgets) {

        public BatchRequest {
            widgets = widgets == null ? List.of() : List.copyOf(widgets);
        }
    }

    public record WidgetRequest(
            UUID instanceId,
            String definitionKey,
            String definitionVersion,
            String definitionManifestHash,
            String rendererBindingRevision,
            Map<String, Object> configuration,
            int itemLimit) {

        public WidgetRequest {
            configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        }
    }

    public record BatchResponse(
            int schemaVersion,
            long tenantId,
            long userId,
            String authorityDecisionRevision,
            List<WidgetResult> results) {

        public BatchResponse {
            results = results == null ? null : List.copyOf(results);
        }
    }

    public record WidgetResult(
            UUID instanceId,
            String definitionKey,
            String definitionManifestHash,
            String rendererBindingRevision,
            State state,
            SourceState source,
            Map<String, Object> payload,
            List<Action> actions,
            List<String> redactions) {

        public WidgetResult {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            actions = actions == null ? List.of() : List.copyOf(actions);
            redactions = redactions == null ? List.of() : List.copyOf(redactions);
        }
    }

    public record SourceState(
            String sourceKey,
            OffsetDateTime generatedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime lastSuccessAt,
            String reasonCode,
            boolean retryable,
            String resultVersion) {
    }

    public record Action(
            String actionId,
            String labelKey,
            ActionKind kind,
            String sourceRoute,
            String commandKey,
            String expectedResultVersion,
            boolean requiresConfirmation) {
    }

    public record CommandRequest(
            int schemaVersion,
            UUID commandId,
            UUID instanceId,
            String definitionKey,
            String definitionManifestHash,
            String rendererBindingRevision,
            String actionId,
            String commandKey,
            String expectedResultVersion,
            Map<String, Object> parameters) {

        public CommandRequest {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public record CommandResponse(
            int schemaVersion,
            long tenantId,
            long userId,
            String authorityDecisionRevision,
            UUID receiptId,
            UUID commandId,
            String actionId,
            String commandKey,
            CommandStatus status,
            String sourceRoute,
            OffsetDateTime acceptedAt,
            String resultVersion) {
    }
}
