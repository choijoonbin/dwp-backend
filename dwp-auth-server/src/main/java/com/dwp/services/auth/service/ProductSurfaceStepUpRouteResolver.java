package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProductSurfaceStepUpRouteResolver {

    private static final String BUNDLE_KEY = "product-surfaces";
    private static final long W1A_APPROVALS_BUNDLE_VERSION = 2L;
    private static final String AUTHORITY_ENDPOINT_KEY =
            "product-surface-step-up-challenge.issue";
    private static final String AUTHORITY_PUBLIC_PATH =
            "/api/auth/product-surface-step-up-challenges";
    private static final String AUTHORITY_SERVICE_PATH =
            "/auth/product-surface-step-up-challenges";
    private static final String DECISION_REVISION_HEADER =
            "X-DWP-Expected-Decision-Revision";
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final Pattern COMMAND_METHOD = Pattern.compile("POST|PUT|PATCH|DELETE");
    private static final Pattern TARGET_TYPE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9._~-]{1,200}");

    private final ProductAuthorizationContractRepository repository;

    public ProductSurfaceStepUpRouteResolver(ProductAuthorizationContractRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Resolution resolve(ProductSurfaceStepUpDtos.IssueRequest request) {
        validateWire(request);
        ProductAuthorizationContractRepository.StoredBundle stored = repository
                .findActive(BUNDLE_KEY)
                .orElseThrow(this::unavailable);
        if (stored.version() != W1A_APPROVALS_BUNDLE_VERSION) throw unavailable();
        ProductAuthorizationContractDtos.BundleContract contract = repository.loadContract(stored);
        validateAuthorityEndpoint(contract);

        List<ResolvedRoute> matches = safe(contract.routes()).stream()
                .filter(route -> "ACTIVE".equals(route.lifecycleState()))
                .filter(route -> "ACTION".equals(route.routeKind()))
                .flatMap(route -> safe(route.stepUpCommandBindings()).stream()
                        .flatMap(stepUp -> matchingBinding(route, stepUp).stream())
                        .map(binding -> match(route, binding.gateway(), binding.stepUp(),
                                request.commandPath())))
                .flatMap(Optional::stream)
                .filter(value -> request.commandMethod().equals(value.gateway().method()))
                .toList();
        if (matches.size() != 1) {
            throw mismatch("Command method/path is not uniquely registered for step-up.");
        }
        ResolvedRoute resolved = matches.getFirst();
        ProductAuthorizationContractDtos.AccessProfile profile = capabilityProfile(resolved.route());
        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities =
                safe(contract.capabilities()).stream().collect(Collectors.toUnmodifiableMap(
                        ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                        Function.identity()));
        ProductAuthorizationContractDtos.CapabilityContract capability = capabilities.get(
                profile.requiredAccess().capabilityContractKey());
        if (capability == null || !"ACTIVE".equals(capability.lifecycleState())
                || !"HIGH".equals(capability.riskTier())
                || blank(capability.activationPolicy())
                || blank(capability.scopeResolver())) {
            throw mismatch("The registered command is not an active high-risk capability.");
        }
        validateCommandBinding(request, resolved, capability);
        long pointerRevision = repository.findActivePointer(BUNDLE_KEY)
                .filter(pointer -> pointer.bundleId().equals(stored.bundleId()))
                .map(ProductAuthorizationContractRepository.ActivePointer::revision)
                .orElseThrow(this::unavailable);
        ProductAuthorizationContractDtos.StepUpCommandBinding stepUp = resolved.stepUp();
        return new Resolution(
                resolved.route().routeContractKey(), resolved.route().subject().productKey(),
                resolved.route().subject().surfaceKey(), capability.contractKey(),
                capability.activationPolicy(), capability.scopeResolver(), stepUp.ownerServiceKey(),
                stepUp.audience(), stepUp.targetType(), stepUp.targetIdPathParameter(),
                stepUp.expectedObjectVersionSource(), stepUp.expectedObjectVersionName(),
                stored.version(), stored.checksum(), pointerRevision);
    }

    private void validateAuthorityEndpoint(
            ProductAuthorizationContractDtos.BundleContract contract) {
        List<ProductAuthorizationContractDtos.AuthorityEndpoint> endpoints =
                safe(contract.authorityEndpoints()).stream()
                        .filter(value -> AUTHORITY_ENDPOINT_KEY.equals(value.endpointKey()))
                        .toList();
        if (endpoints.size() != 1) throw unavailable();
        ProductAuthorizationContractDtos.AuthorityEndpoint endpoint = endpoints.getFirst();
        if (!"POST".equals(endpoint.method())
                || !AUTHORITY_PUBLIC_PATH.equals(endpoint.publicPath())
                || !"auth".equals(endpoint.serviceKey())
                || !AUTHORITY_SERVICE_PATH.equals(endpoint.servicePath())
                || !endpoint.requiresAuthentication()
                || !endpoint.requiresCsrf()
                || !DECISION_REVISION_HEADER.equals(endpoint.expectedDecisionRevisionHeader())) {
            throw unavailable();
        }
    }

    private Optional<ResolvedBinding> matchingBinding(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp) {
        List<ProductAuthorizationContractDtos.GatewayBinding> bindings =
                safe(route.gatewayApiBindings()).stream()
                        .filter(value -> value.bindingKey().equals(stepUp.bindingKey()))
                        .toList();
        if (bindings.size() != 1) return Optional.empty();
        return Optional.of(new ResolvedBinding(bindings.getFirst(), stepUp));
    }

    private Optional<ResolvedRoute> match(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.GatewayBinding gateway,
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp,
            String path) {
        StringBuilder regex = new StringBuilder("^");
        Matcher placeholders = PLACEHOLDER.matcher(gateway.path());
        List<String> names = new java.util.ArrayList<>();
        int start = 0;
        while (placeholders.find()) {
            regex.append(Pattern.quote(gateway.path().substring(start, placeholders.start())))
                    .append("([A-Za-z0-9._~-]{1,200})");
            names.add(placeholders.group(1));
            start = placeholders.end();
        }
        regex.append(Pattern.quote(gateway.path().substring(start))).append('$');
        Matcher matcher = Pattern.compile(regex.toString()).matcher(path);
        if (!matcher.matches()) return Optional.empty();
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            if (parameters.put(names.get(index), matcher.group(index + 1)) != null) {
                throw unavailable();
            }
        }
        return Optional.of(new ResolvedRoute(route, gateway, stepUp, Map.copyOf(parameters)));
    }

    private ProductAuthorizationContractDtos.AccessProfile capabilityProfile(
            ProductAuthorizationContractDtos.GovernedRoute route) {
        List<ProductAuthorizationContractDtos.AccessProfile> profiles =
                safe(route.accessProfiles()).stream()
                        .filter(value -> safe(value.activeAccessModes()).contains("NORMAL"))
                        .filter(value -> value.requiredAccess() != null)
                        .filter(value -> "CAPABILITY".equals(value.requiredAccess().type()))
                        .toList();
        int precedence = profiles.stream()
                .mapToInt(ProductAuthorizationContractDtos.AccessProfile::precedence)
                .max()
                .orElseThrow(this::unavailable);
        List<ProductAuthorizationContractDtos.AccessProfile> winners = profiles.stream()
                .filter(value -> value.precedence() == precedence)
                .toList();
        if (winners.size() != 1) throw unavailable();
        return winners.getFirst();
    }

    private void validateWire(ProductSurfaceStepUpDtos.IssueRequest request) {
        if (request == null || blank(request.commandMethod())
                || !COMMAND_METHOD.matcher(request.commandMethod()).matches()
                || blank(request.commandPath()) || request.commandPath().length() > 500
                || request.commandPath().contains("?") || request.commandPath().contains("#")
                || !request.commandPath().startsWith("/api/")
                || blank(request.targetType())
                || !TARGET_TYPE.matcher(request.targetType()).matches()
                || blank(request.targetId()) || !TARGET_ID.matcher(request.targetId()).matches()
                || request.expectedObjectVersion() == null
                || request.expectedObjectVersion() < 0
                || blank(request.idempotencyKey()) || request.idempotencyKey().length() > 200
                || tooLong(request.contextKey(), 500) || tooLong(request.contextScopeKey(), 500)
                || tooLong(request.providerKey(), 100) || tooLong(request.returnTo(), 500)
                || request.payload() == null || !request.payload().isObject()) {
            throw mismatch("The step-up command binding is invalid.");
        }
        try {
            URI uri = URI.create(request.commandPath());
            if (uri.isAbsolute() || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !request.commandPath().equals(uri.getRawPath())) {
                throw mismatch("Invalid path.");
            }
        } catch (IllegalArgumentException exception) {
            throw mismatch("The step-up target or path is invalid.");
        }
    }

    private void validateCommandBinding(
            ProductSurfaceStepUpDtos.IssueRequest request,
            ResolvedRoute route,
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        ProductAuthorizationContractDtos.StepUpCommandBinding stepUp = route.stepUp();
        String boundTarget = route.parameters().get(stepUp.targetIdPathParameter());
        if (!stepUp.targetType().equals(request.targetType())
                || boundTarget == null || !request.targetId().equals(boundTarget)) {
            throw mismatch("The target does not match the registered command binding.");
        }
        if ("COMMAND_BODY".equals(stepUp.expectedObjectVersionSource())) {
            JsonNode payloadVersion = request.payload().get(stepUp.expectedObjectVersionName());
            if (payloadVersion == null || !payloadVersion.isIntegralNumber()
                    || !payloadVersion.canConvertToLong()
                    || payloadVersion.longValue() != request.expectedObjectVersion()) {
                throw mismatch("The payload version does not match the registered command binding.");
            }
        } else if ("COMMAND_HEADER".equals(stepUp.expectedObjectVersionSource())) {
            if (request.payload().has(stepUp.expectedObjectVersionName())) {
                throw mismatch("A header-bound version cannot be supplied in the command payload.");
            }
        } else {
            throw unavailable();
        }
        if (!safe(capability.routeContractKeys()).contains(route.route().routeContractKey())
                || blank(stepUp.ownerServiceKey()) || blank(stepUp.audience())
                || blank(stepUp.targetIdPathParameter())
                || blank(stepUp.expectedObjectVersionName())) {
            throw unavailable();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private boolean tooLong(String value, int maximum) {
        return value != null && value.length() > maximum;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private BaseException mismatch(String message) {
        return new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH, message);
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    public record Resolution(
            String routeContractKey,
            String productKey,
            String surfaceKey,
            String capabilityContractKey,
            String activationPolicy,
            String scopeResolver,
            String ownerServiceKey,
            String audience,
            String targetType,
            String targetIdPathParameter,
            String expectedObjectVersionSource,
            String expectedObjectVersionName,
            long bundleVersion,
            String bundleChecksum,
            long pointerRevision) {
    }

    private record ResolvedBinding(
            ProductAuthorizationContractDtos.GatewayBinding gateway,
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp) {
    }

    private record ResolvedRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.GatewayBinding gateway,
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp,
            Map<String, String> parameters) {
    }
}
