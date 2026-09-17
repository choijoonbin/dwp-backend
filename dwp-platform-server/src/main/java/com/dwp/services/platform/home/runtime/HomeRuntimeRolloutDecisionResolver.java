package com.dwp.services.platform.home.runtime;

import com.dwp.services.platform.home.HomeModeV4ActivationGate;
import com.dwp.services.platform.widgetregistry.WidgetCatalogService;
import com.dwp.services.platform.widgetregistry.WidgetRegistryDtos;
import com.dwp.services.platform.widgetregistry.WidgetRegistryMutationGuard;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryActivationInterlock;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public final class HomeRuntimeRolloutDecisionResolver {

    private final HomeRuntimeProperties runtime;
    private final HomeRuntimeRolloutProperties rollout;
    private final HomeModeV4ActivationGate modeGate;
    private final WidgetRegistryMutationGuard controls;
    private final WidgetRegistryActivationInterlock registryInterlock;
    private final WidgetCatalogService catalog;

    public HomeRuntimeRolloutDecisionResolver(
            HomeRuntimeProperties runtime,
            HomeRuntimeRolloutProperties rollout,
            HomeModeV4ActivationGate modeGate,
            WidgetRegistryMutationGuard controls,
            WidgetRegistryActivationInterlock registryInterlock,
            WidgetCatalogService catalog) {
        this.runtime = runtime;
        this.rollout = rollout;
        this.modeGate = modeGate;
        this.controls = controls;
        this.registryInterlock = registryInterlock;
        this.catalog = catalog;
    }

    public HomeRuntimeRolloutDecision resolve(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trusted,
            String mode,
            WidgetCatalogService.RuntimeCatalog runtimeCatalog) {
        HomeRuntimeRolloutDecision.State state = trusted.state();
        if (!runtime.enabled() && !runtime.shadowEnabled()) {
            state = HomeRuntimeRolloutDecision.State.DISABLED;
        } else {
            state = minimum(state, rollout.ceiling());
            if (!runtime.enabled()) {
                state = minimum(state, HomeRuntimeRolloutDecision.State.SHADOW_COMPARE);
            }
            if (state.ordinal() > HomeRuntimeRolloutDecision.State.SHADOW_COMPARE.ordinal()
                    && (!rollout.activeRing(trusted.ring())
                    || !rollout.activeMode(mode)
                    || !"CLASSIC".equals(mode) && !modeGate.active())) {
                state = HomeRuntimeRolloutDecision.State.SHADOW_COMPARE;
            }
            if (state.ordinal() > HomeRuntimeRolloutDecision.State.SHADOW_COMPARE.ordinal()
                    && controls.runtimeDenied(
                    "RUNTIME_RENDER", context.tenantId(), null, null, null, mode, null)) {
                state = HomeRuntimeRolloutDecision.State.SHADOW_COMPARE;
            }
        }

        boolean registryAuthoritative = state.ordinal()
                >= HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE.ordinal()
                && registryInterlock.permitsAuthority(
                catalog.authorityEvidence(), trusted.revision());
        Set<String> allowedProviders = new LinkedHashSet<>();
        Set<String> allowedDefinitions = new LinkedHashSet<>();
        for (WidgetCatalogService.RuntimeDefinition definition : runtimeCatalog.definitions()) {
            if (definition.effectiveState() == WidgetRegistryDtos.EffectiveCatalogState.DENY
                    || (!registryAuthoritative && !catalog.isHomeRuntimeBaseline(definition))
                    || controls.runtimeDenied(
                    "RUNTIME_RENDER",
                    context.tenantId(),
                    definition.ownerProductKey(),
                    definition.definitionId(),
                    definition.versionId(),
                    mode,
                    null)) {
                continue;
            }
            allowedProviders.add(definition.ownerProductKey());
            allowedDefinitions.add(definition.definitionKey());
        }

        Set<String> allowedActions = allowedActions(
                context, state, mode, runtimeCatalog, allowedDefinitions);
        if (state == HomeRuntimeRolloutDecision.State.COMMAND_CANARY
                && allowedActions.isEmpty()) {
            state = HomeRuntimeRolloutDecision.State.READ_ONLY_ACTIVE;
        }
        String revision = revision(
                context, trusted, state, mode, runtimeCatalog, registryAuthoritative,
                allowedProviders, allowedDefinitions, allowedActions);
        return new HomeRuntimeRolloutDecision(
                state,
                mode,
                trusted.ring(),
                revision,
                registryAuthoritative,
                allowedProviders,
                allowedDefinitions,
                allowedActions,
                context.authorityRevalidateAt());
    }

    private Set<String> allowedActions(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.State state,
            String mode,
            WidgetCatalogService.RuntimeCatalog runtimeCatalog,
            Set<String> allowedDefinitions) {
        if (state != HomeRuntimeRolloutDecision.State.COMMAND_CANARY
                || !runtime.commandsEnabled()
                || controls.runtimeDenied(
                "RUNTIME_ACTION", context.tenantId(), null, null, null, mode, null)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String contractId : rollout.commandContracts()) {
            HomeOwnerActionContracts.Contract contract = HomeOwnerActionContracts.find(contractId)
                    .orElse(null);
            if (contract == null || !context.has(contract.requiredAuthority())) continue;
            WidgetCatalogService.RuntimeDefinition definition = runtimeCatalog.definitions().stream()
                    .filter(candidate -> contract.definitionKey().equals(candidate.definitionKey()))
                    .findFirst().orElse(null);
            if (definition == null
                    || !allowedDefinitions.contains(definition.definitionKey())
                    || !contract.definitionVersion().equals(definition.semanticVersion())
                    || !contract.definitionManifestHash().equals(definition.manifestHash())
                    || controls.runtimeDenied(
                    "RUNTIME_ACTION", context.tenantId(), contract.providerProductKey(),
                    definition.definitionId(), definition.versionId(), mode, contractId)
                    || !controls.runtimeActionApproved(
                    context.tenantId(), contract.providerProductKey(), contractId)) {
                continue;
            }
            result.add(contractId);
        }
        return Set.copyOf(result);
    }

    private HomeRuntimeRolloutDecision.State minimum(
            HomeRuntimeRolloutDecision.State first,
            HomeRuntimeRolloutDecision.State second) {
        return first.ordinal() <= second.ordinal() ? first : second;
    }

    private String revision(
            HomeRuntimeContext context,
            HomeRuntimeRolloutDecision.TrustedInput trusted,
            HomeRuntimeRolloutDecision.State state,
            String mode,
            WidgetCatalogService.RuntimeCatalog runtimeCatalog,
            boolean registryAuthoritative,
            Set<String> providers,
            Set<String> definitions,
            Set<String> actions) {
        String material = context.authorityDecisionRevision() + "\n" + trusted.revision() + "\n"
                + state + "\n" + mode + "\n" + trusted.ring() + "\n"
                + runtimeCatalog.catalogRevision() + "\n" + runtimeCatalog.bindingRevision() + "\n"
                + runtimeCatalog.policyRevision() + "\n" + runtimeCatalog.safetyRevision() + "\n"
                + registryAuthoritative + "\n"
                + String.join(",", providers.stream().sorted().toList()) + "\n"
                + String.join(",", definitions.stream().sorted().toList()) + "\n"
                + String.join(",", actions.stream().sorted().toList());
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
