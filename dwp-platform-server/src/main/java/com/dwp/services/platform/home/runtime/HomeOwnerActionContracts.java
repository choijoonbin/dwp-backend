package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contract.home.HomeWidgetProviderContract;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Closed owner declarations for Home command canaries. */
public final class HomeOwnerActionContracts {

    public static final Contract DISMISS_RECOMMENDATION = new Contract(
            "platform",
            "core.workspace",
            "core.workspace.daily-brief",
            "1.0.0",
            "9b7f48b7ea4ef429120db330a4972c3315ad682759fa86e49c212c42bdd02406",
            "dismiss-recommendation",
            "home.recommendation.dismiss",
            "APP.WORK:VIEW",
            Set.of("recommendationKey"));

    private static final Map<String, Contract> BY_ID = Map.of(
            DISMISS_RECOMMENDATION.contractId(), DISMISS_RECOMMENDATION);

    private HomeOwnerActionContracts() {
    }

    public static Optional<Contract> find(String contractId) {
        return Optional.ofNullable(BY_ID.get(contractId));
    }

    public static Optional<Contract> find(
            String definitionKey,
            String definitionVersion,
            String actionId) {
        return BY_ID.values().stream().filter(contract ->
                contract.definitionKey().equals(definitionKey)
                        && contract.definitionVersion().equals(definitionVersion)
                        && contract.actionId().equals(actionId)).findFirst();
    }

    public record Contract(
            String providerKey,
            String providerProductKey,
            String definitionKey,
            String definitionVersion,
            String definitionManifestHash,
            String actionId,
            String commandKey,
            String requiredAuthority,
            Set<String> parameterKeys) {

        public Contract {
            parameterKeys = Set.copyOf(parameterKeys);
        }

        public String contractId() {
            return providerProductKey + "|" + definitionKey + "|" + definitionVersion
                    + "|" + actionId + "|" + commandKey;
        }

        public HomeWidgetProviderContract.Action action(String resultVersion) {
            return new HomeWidgetProviderContract.Action(
                    actionId,
                    "home.action.dismissRecommendation",
                    HomeWidgetProviderContract.ActionKind.COMMAND,
                    null,
                    commandKey,
                    resultVersion,
                    true);
        }

        public void validate(
                HomeRuntimeContext context,
                HomeWidgetProviderContract.CommandRequest request) {
            if (!context.has(requiredAuthority)
                    || !definitionKey.equals(request.definitionKey())
                    || !definitionManifestHash.equals(request.definitionManifestHash())
                    || !actionId.equals(request.actionId())
                    || !commandKey.equals(request.commandKey())
                    || request.parameters() == null
                    || !parameterKeys.equals(request.parameters().keySet())) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "The exact Home owner action contract is not authorized.");
            }
        }
    }
}
