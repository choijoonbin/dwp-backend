package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.personalization.HomeCanonicalJson;
import com.dwp.services.platform.home.personalization.HomeCommandReceiptService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final int LOCK_STRIPES = 256;

    private final Map<String, WidgetProviderPort> providers;
    private final HomeReadModelService readModels;
    private final HomeCommandReceiptService receipts;
    private final HomeCanonicalJson canonicalJson;
    private final ProviderResultValidator validator;
    private final HomeRuntimeProperties properties;
    private final PlatformAuditService audit;
    private final WidgetRegistryMutationGuard controls;
    private final Object[] commandLocks = new Object[LOCK_STRIPES];

    @Autowired
    public HomeWidgetCommandService(
            List<WidgetProviderPort> providers,
            HomeReadModelService readModels,
            HomeCommandReceiptService receipts,
            HomeCanonicalJson canonicalJson,
            ProviderResultValidator validator,
            HomeRuntimeProperties properties,
            PlatformAuditService audit,
            WidgetRegistryMutationGuard controls) {
        this.providers = providers.stream().collect(Collectors.toUnmodifiableMap(
                WidgetProviderPort::providerKey, Function.identity()));
        this.readModels = readModels;
        this.receipts = receipts;
        this.canonicalJson = canonicalJson;
        this.validator = validator;
        this.properties = properties;
        this.audit = audit;
        this.controls = controls;
        for (int index = 0; index < commandLocks.length; index++) {
            commandLocks[index] = new Object();
        }
    }

    HomeWidgetCommandService(
            List<WidgetProviderPort> providers,
            HomeReadModelService readModels,
            HomeCommandReceiptService receipts,
            HomeCanonicalJson canonicalJson,
            ProviderResultValidator validator,
            HomeRuntimeProperties properties,
            PlatformAuditService audit) {
        this(providers, readModels, receipts, canonicalJson, validator, properties, audit, null);
    }

    public HomeReadModelDtos.CommandReceipt execute(
            HomeRuntimeContext context,
            String requestedMode,
            String deviceClass,
            UUID commandId,
            HomeReadModelDtos.CommandRequest request) {
        return executeInternal(
                context, null, requestedMode, deviceClass, commandId, request, false).receipt();
    }

    public ExecutionResult execute(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trustedRollout,
            String requestedMode,
            String deviceClass,
            UUID commandId,
            HomeReadModelDtos.CommandRequest request) {
        return executeInternal(
                context, trustedRollout, requestedMode, deviceClass, commandId, request, true);
    }

    private ExecutionResult executeInternal(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trustedRollout,
            String requestedMode,
            String deviceClass,
            UUID commandId,
            HomeReadModelDtos.CommandRequest request,
            boolean enforceRollout) {
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
        audit(context, commandId, target, "attempted", "SUCCESS");
        if (!properties.commandsEnabled()) {
            audit(context, commandId, target, "denied", "DENIED");
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime commands are disabled until the owner idempotency gate is promoted.");
        }
        Object lock = commandLocks[Math.floorMod(
                java.util.Objects.hash(context.tenantId(), context.userId(), commandId),
                commandLocks.length)];
        try {
            synchronized (lock) {
                return executeOnce(
                        context, trustedRollout, requestedMode, deviceClass, commandId, request,
                        fingerprint, target, enforceRollout);
            }
        } catch (RuntimeException failure) {
            boolean denied = failure instanceof BaseException
                    || failure instanceof WidgetProviderException providerFailure
                    && providerFailure.kind() == WidgetProviderException.Kind.FORBIDDEN;
            audit(context, commandId, target,
                    denied ? "denied" : "failed",
                    denied ? "DENIED" : "FAILED");
            throw failure;
        }
    }

    private ExecutionResult executeOnce(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trustedRollout,
            String requestedMode,
            String deviceClass,
            UUID commandId,
            HomeReadModelDtos.CommandRequest request,
            String fingerprint,
            String target,
            boolean enforceRollout) {
        if (!enforceRollout) {
            HomeReadModelDtos.CommandReceipt replay = receipts.replay(
                    context.tenantId(), context.userId(), commandId,
                    "HOME_WIDGET_ACTION", target, fingerprint,
                    HomeReadModelDtos.CommandReceipt.class);
            if (replay != null) {
                audit(context, commandId, target, "replayed", "SUCCESS");
                return new ExecutionResult(replay, null);
            }
        }
        HomeReadModelDtos.ReadResult readResult = enforceRollout
                ? readModels.read(context, trustedRollout, requestedMode, deviceClass)
                : readModels.read(context, requestedMode, deviceClass);
        HomeReadModelDtos.HomeReadModel model = readResult.model();
        HomeRuntimeRolloutDecision decision = readResult.decision();
        if (enforceRollout && (decision == null || !decision.commandsEnabled())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "Home Runtime commands are disabled by the current rollout decision.");
        }
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
        HomeOwnerActionContracts.Contract contract = HomeOwnerActionContracts.find(
                        widget.definitionKey(), widget.definitionVersion(), action.actionId())
                .filter(candidate -> candidate.commandKey().equals(action.commandKey()))
                .filter(candidate -> candidate.definitionManifestHash().equals(
                        widget.definitionManifestHash()))
                .orElse(null);
        if (enforceRollout && contract == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The Home widget action is not an approved owner contract.");
        }
        if (enforceRollout && (!decision.actionAllowed(contract.contractId())
                || controls == null
                || controls.runtimeDenied(
                "RUNTIME_ACTION", context.tenantId(), contract.providerProductKey(),
                null, null, decision.mode(), contract.contractId())
                || !controls.runtimeActionApproved(
                context.tenantId(), contract.providerProductKey(), contract.contractId()))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The Home owner action is disabled by the current runtime control.");
        }
        if (enforceRollout) {
            HomeReadModelDtos.CommandReceipt replay = receipts.replay(
                    context.tenantId(), context.userId(), commandId,
                    "HOME_WIDGET_ACTION", target, fingerprint,
                    HomeReadModelDtos.CommandReceipt.class);
            if (replay != null) {
                audit(context, commandId, target, "replayed", "SUCCESS");
                return new ExecutionResult(replay, decision);
            }
        }
        String providerKey = WidgetRuntimeBroker.providerKey(
                widget.governance().sourceAppResourceKey());
        if (contract != null && !contract.providerKey().equals(providerKey)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The Home owner action provider contract is inconsistent.");
        }
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
        if (contract != null) contract.validate(context, providerRequest);
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
        audit(context, commandId, target, "accepted", "SUCCESS");
        return new ExecutionResult(receipt, decision);
    }

    private void audit(
            HomeRuntimeContext context,
            UUID commandId,
            String target,
            String stage,
            String outcome) {
        audit.event(
                context.tenantId(), context.userId(),
                "home.widget.command." + stage,
                "HOME_WIDGET_ACTION", target, commandId.toString(), outcome);
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

    public record ExecutionResult(
            HomeReadModelDtos.CommandReceipt receipt,
            HomeRuntimeRolloutDecision decision) {
    }
}
