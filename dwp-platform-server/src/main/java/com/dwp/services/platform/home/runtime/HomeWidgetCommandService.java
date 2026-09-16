package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class HomeWidgetCommandService {

    private final Map<String, WidgetProviderPort> providers;
    private final HomeReadModelService readModels;
    private final HomeCommandReceiptService receipts;
    private final HomeCanonicalJson canonicalJson;
    private final ProviderResultValidator validator;
    private final HomeRuntimeProperties properties;

    public HomeWidgetCommandService(
            List<WidgetProviderPort> providers,
            HomeReadModelService readModels,
            HomeCommandReceiptService receipts,
            HomeCanonicalJson canonicalJson,
            ProviderResultValidator validator,
            HomeRuntimeProperties properties) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                WidgetProviderPort::providerKey, Function.identity()));
        this.readModels = readModels;
        this.receipts = receipts;
        this.canonicalJson = canonicalJson;
        this.validator = validator;
        this.properties = properties;
    }

    public HomeReadModelDtos.CommandReceipt execute(
            HomeRuntimeContext context,
            String requestedMode,
            String deviceClass,
            UUID commandId,
            HomeReadModelDtos.CommandRequest request) {
        if (commandId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key is required.");
        }
        validator.validateCommandParameters(request.parameters());
        String fingerprint = canonicalJson.fingerprint(Map.of(
                "authority", context.fingerprint(),
                "mode", requestedMode == null ? "" : requestedMode,
                "deviceClass", deviceClass,
                "request", request));
        String target = request.instanceId() + ":" + request.actionId();
        HomeReadModelDtos.CommandReceipt replay = receipts.replay(
                context.tenantId(), context.userId(), commandId,
                "HOME_WIDGET_ACTION", target, fingerprint,
                HomeReadModelDtos.CommandReceipt.class);
        if (replay != null) return replay;

        HomeReadModelDtos.HomeReadModel model = readModels
                .read(context, requestedMode, deviceClass).model();
        HomeReadModelDtos.Widget widget = model.widgets().stream()
                .filter(candidate -> candidate.instanceId().equals(request.instanceId()))
                .findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The Home widget instance is not available."));
        if (widget.state() != HomeWidgetProviderContract.State.AVAILABLE
                && widget.state() != HomeWidgetProviderContract.State.PARTIAL) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Home widget is not actionable in its current state.");
        }
        if (!request.expectedResultVersion().equals(widget.source().resultVersion())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Home widget result changed; refresh before executing the action.");
        }
        HomeWidgetProviderContract.Action action = widget.actions().stream()
                .filter(candidate -> candidate.actionId().equals(request.actionId()))
                .findFirst().orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The Home widget action is not available."));
        if (action.kind() != HomeWidgetProviderContract.ActionKind.COMMAND
                || !request.expectedResultVersion().equals(action.expectedResultVersion())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The Home widget action contract changed; refresh before retrying.");
        }
        String providerKey = WidgetRuntimeBroker.providerKey(
                widget.governance().sourceAppResourceKey());
        WidgetProviderPort provider = providers.get(providerKey);
        if (provider == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The Home widget command provider is not configured.");
        }
        HomeWidgetProviderContract.CommandRequest providerRequest =
                new HomeWidgetProviderContract.CommandRequest(
                        HomeWidgetProviderContract.SCHEMA_VERSION,
                        commandId,
                        widget.instanceId(),
                        widget.definitionKey(),
                        widget.definitionManifestHash(),
                        widget.rendererBindingRevision(),
                        action.actionId(),
                        action.commandKey(),
                        request.expectedResultVersion(),
                        request.parameters());
        OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(properties.providerTimeout());
        HomeWidgetProviderContract.CommandResponse response = provider.executeCommand(
                context, providerRequest, deadline);
        validate(response, context, providerRequest);
        HomeReadModelDtos.CommandReceipt receipt = new HomeReadModelDtos.CommandReceipt(
                response.receiptId(), response.commandId(), response.commandKey(),
                response.status().name(), response.sourceRoute(), response.acceptedAt(),
                response.resultVersion());
        receipts.record(context.tenantId(), context.userId(), commandId,
                "HOME_WIDGET_ACTION", target, fingerprint, receipt);
        return receipt;
    }

    private void validate(
            HomeWidgetProviderContract.CommandResponse response,
            HomeRuntimeContext context,
            HomeWidgetProviderContract.CommandRequest request) {
        if (response == null
                || response.schemaVersion() != HomeWidgetProviderContract.SCHEMA_VERSION
                || response.tenantId() != context.tenantId()
                || response.userId() != context.userId()
                || !context.authorityDecisionRevision().equals(
                        response.authorityDecisionRevision())
                || response.receiptId() == null
                || !request.commandId().equals(response.commandId())
                || !request.actionId().equals(response.actionId())
                || !request.commandKey().equals(response.commandKey())
                || response.status() == null
                || response.acceptedAt() == null
                || response.acceptedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30))
                || response.resultVersion() == null
                || response.resultVersion().isBlank()
                || response.resultVersion().length() > 160) {
            throw new WidgetProviderException(
                    WidgetProviderException.Kind.MALFORMED,
                    "PROVIDER_COMMAND_CONTRACT_INVALID",
                    "Home provider returned an invalid command receipt.");
        }
        if (response.sourceRoute() != null) {
            ProviderResultValidator.internalRoute(response.sourceRoute());
        }
    }
}
