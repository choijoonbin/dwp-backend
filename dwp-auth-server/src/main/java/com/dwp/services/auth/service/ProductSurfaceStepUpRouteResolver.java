package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceStepUpDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private static final java.util.Set<Long> STEP_UP_RUNTIME_BUNDLE_VERSIONS =
            java.util.Set.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L,
                    14L, 15L, 16L, 32L, 49L, 50L, 52L, 53L, 54L, 55L, 56L, 57L,
                    62L);
    private static final java.util.Set<Long> SEALED_RUNTIME_BUNDLE_VERSIONS =
            java.util.Set.of(9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 32L, 49L, 50L, 52L, 53L,
                    54L, 55L, 56L, 57L, 62L);
    private static final String HRM012_ACTION_ROUTE =
            "route.hcm.operations.workforce-ingestion.action";
    private static final String HRM009_SHARED_ACTION_ROUTE =
            "route.hcm.operations.certificate-issue.action";
    private static final String HRM009_OPERATIONS_CAPABILITY =
            "hcm.operations.certificate.issue";
    private static final String HRM009_SELF_CAPABILITY =
            "hcm.personal.certificate.request";
    private static final String HRM013_APPROVE_ROUTE =
            "route.approvals.work.hris-separation-waiver-task-approve.action";
    private static final String HRM013_APPROVE_CAPABILITY =
            "approvals.work.hris-separation-waiver-task.approve";
    private static final String HRM013_GATEWAY_PATH =
            "/api/approvals/v1/hris/separation-waiver-tasks/{taskId}/approve";
    private static final String HRM013_SERVICE_PATH =
            "/v1/hris/separation-waiver-tasks/{taskId}/approve";
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
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9._~:@-]{1,200}");

    private final ProductAuthorizationContractRepository repository;
    private final StoredDescriptorSeal seals;

    static boolean supportsSealedRuntimeBundle(long version) {
        return STEP_UP_RUNTIME_BUNDLE_VERSIONS.contains(version)
                && SEALED_RUNTIME_BUNDLE_VERSIONS.contains(version);
    }

    public ProductSurfaceStepUpRouteResolver(ProductAuthorizationContractRepository repository) {
        this.repository = repository;
        this.seals = null;
    }

    @Autowired
    public ProductSurfaceStepUpRouteResolver(ProductAuthorizationContractRepository repository,
            JdbcTemplate jdbc, ProductAuthorizationContractValidator validator, ObjectMapper mapper) {
        this.repository = repository;
        this.seals = new StoredDescriptorSeal(jdbc, repository, validator, mapper);
    }

    @Transactional(readOnly = true)
    public Resolution resolve(ProductSurfaceStepUpDtos.IssueRequest request) {
        return resolve(request, null);
    }

    @Transactional(readOnly = true)
    Resolution resolveForCapability(
            ProductSurfaceStepUpDtos.IssueRequest request,
            String selectedCapabilityContractKey) {
        if (blank(selectedCapabilityContractKey)) throw unavailable();
        return resolve(request, selectedCapabilityContractKey);
    }

    private Resolution resolve(
            ProductSurfaceStepUpDtos.IssueRequest request,
            String selectedCapabilityContractKey) {
        validateWire(request);
        ProductAuthorizationContractRepository.StoredBundle stored = repository
                .findActive(BUNDLE_KEY)
                .orElseThrow(this::unavailable);
        if (!STEP_UP_RUNTIME_BUNDLE_VERSIONS.contains(stored.version())) throw unavailable();
        ProductAuthorizationContractRepository.ActivePointer sealedPointer = null;
        ProductAuthorizationContractDtos.BundleContract contract;
        if (SEALED_RUNTIME_BUNDLE_VERSIONS.contains(stored.version())) {
            if (seals == null) throw unavailable();
            sealedPointer = repository.findActivePointer(BUNDLE_KEY).orElseThrow(this::unavailable);
            try {
                contract = seals.loadActive(stored, sealedPointer);
            } catch (IllegalArgumentException invalidSeal) {
                throw unavailable();
            }
        } else {
            contract = repository.loadContract(stored);
        }
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
        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities =
                safe(contract.capabilities()).stream().collect(Collectors.toUnmodifiableMap(
                        ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                        Function.identity()));
        ProductAuthorizationContractDtos.AccessProfile profile = selectedCapabilityContractKey == null
                ? capabilityProfile(resolved.route(), capabilities)
                : capabilityProfile(
                        resolved.route(), capabilities, selectedCapabilityContractKey);
        ProductAuthorizationContractDtos.CapabilityContract capability = capabilities.get(
                profile.requiredAccess().capabilityContractKey());
        String effectiveActivationPolicy = capability == null
                ? null : capability.activationPolicy();
        if (HRM013_APPROVE_ROUTE.equals(resolved.route().routeContractKey())) {
            effectiveActivationPolicy = validateHrm013ApproveRoute(
                    resolved, profile, capability);
        } else if (HRM009_SHARED_ACTION_ROUTE.equals(resolved.route().routeContractKey())) {
            effectiveActivationPolicy = validateHrm009SharedRoute(
                    request, resolved.route(), capabilities, capability);
        } else if (capability == null || !"ACTIVE".equals(capability.lifecycleState())
                || !java.util.Set.of("HIGH", "CRITICAL").contains(capability.riskTier())
                || blank(capability.activationPolicy())
                || blank(capability.scopeResolver())) {
            throw mismatch("The registered command is not an active high-risk capability.");
        }
        validateCommandBinding(request, resolved, capability);
        var currentPointer = repository.findActivePointer(BUNDLE_KEY)
                .filter(pointer -> pointer.bundleId().equals(stored.bundleId()))
                .orElseThrow(this::unavailable);
        if (sealedPointer != null && !sealedPointer.equals(currentPointer)) throw unavailable();
        long pointerRevision = currentPointer.revision();
        ProductAuthorizationContractDtos.StepUpCommandBinding stepUp = resolved.stepUp();
        return new Resolution(
                resolved.route().routeContractKey(), resolved.route().subject().productKey(),
                resolved.route().subject().surfaceKey(), capability.contractKey(),
                effectiveActivationPolicy, capability.scopeResolver(), stepUp.ownerServiceKey(),
                stepUp.audience(), stepUp.targetType(), stepUp.targetIdPathParameter(),
                safe(stepUp.targetIdBodyFields()),
                stepUp.expectedObjectVersionSource(), stepUp.expectedObjectVersionName(),
                stored.version(), stored.checksum(), pointerRevision);
    }

    private String validateHrm009SharedRoute(
            ProductSurfaceStepUpDtos.IssueRequest request,
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            ProductAuthorizationContractDtos.CapabilityContract selected) {
        if (blank(request.contextScopeKey()) || selected == null
                || !"ACTIVE".equals(selected.lifecycleState())
                || blank(selected.scopeResolver())
                || !java.util.Set.of(
                        HRM009_OPERATIONS_CAPABILITY,
                        HRM009_SELF_CAPABILITY).contains(selected.contractKey())) {
            throw mismatch("The shared certificate command requires one selected trusted scope.");
        }
        List<ProductAuthorizationContractDtos.AccessProfile> profiles = safe(route.accessProfiles());
        if (profiles.size() != 2
                || profiles.stream().map(ProductAuthorizationContractDtos.AccessProfile::profileKey)
                .collect(java.util.stream.Collectors.toSet()).size() != 2
                || profiles.stream().map(value -> value.requiredAccess().capabilityContractKey())
                .collect(java.util.stream.Collectors.toSet()).size() != 2) {
            throw unavailable();
        }
        ProductAuthorizationContractDtos.CapabilityContract high =
                capabilities.get(HRM009_OPERATIONS_CAPABILITY);
        ProductAuthorizationContractDtos.CapabilityContract self =
                capabilities.get(HRM009_SELF_CAPABILITY);
        if (high == null || self == null
                || !"HIGH".equals(high.riskTier())
                || !"STEPUP-MGMT-HIGH-V1".equals(high.activationPolicy())
                || !"MEDIUM".equals(self.riskTier())
                || self.activationPolicy() != null) {
            throw unavailable();
        }
        return high.activationPolicy();
    }

    /**
     * The separation-waiver approval command is intentionally a first-class route. It must
     * never inherit the generic task-decision descriptor because that would allow a caller to
     * obtain an APPROVE challenge for a different command path with the same task identifier.
     */
    private String validateHrm013ApproveRoute(
            ResolvedRoute resolved,
            ProductAuthorizationContractDtos.AccessProfile profile,
            ProductAuthorizationContractDtos.CapabilityContract capability) {
        return validateHrm013ApproveDescriptor(
                resolved.route(), profile, capability, resolved.gateway(), resolved.stepUp());
    }

    String validateHrm013ApproveDescriptor(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile,
            ProductAuthorizationContractDtos.CapabilityContract capability,
            ProductAuthorizationContractDtos.GatewayBinding gateway,
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp) {
        List<ProductAuthorizationContractDtos.AccessProfile> profiles =
                route == null ? List.of() : safe(route.accessProfiles());
        List<ProductAuthorizationContractDtos.GatewayBinding> gatewayBindings =
                route == null ? List.of() : safe(route.gatewayApiBindings());
        List<ProductAuthorizationContractDtos.ServicePepBinding> serviceBindings =
                route == null ? List.of() : safe(route.servicePepBindings());
        List<ProductAuthorizationContractDtos.StepUpCommandBinding> stepUpBindings =
                route == null ? List.of() : safe(route.stepUpCommandBindings());
        if (route == null || route.subject() == null || profile == null
                || capability == null || gateway == null || stepUp == null
                || !HRM013_APPROVE_ROUTE.equals(route.routeContractKey())
                || !"approvals.work".equals(route.navigationContextId())
                || !"PRODUCT".equals(route.subject().type())
                || !"approvals".equals(route.subject().productKey())
                || !"approvals.work".equals(route.subject().surfaceKey())
                || !"ACTION".equals(route.routeKind())
                || route.bindingMode() != null
                || route.purposeCode() != null
                || route.sideEffectFree() != null
                || route.uiRouteId() != null
                || route.uiRoutePattern() != null
                || route.authorizationEquivalenceKey() != null
                || route.consumedApiRouteContractKeys() != null
                || !"Approvals + Security".equals(route.owner())
                || route.policyVersion() != 1
                || !"ACTIVE".equals(route.lifecycleState())
                || profiles.size() != 1 || !profiles.getFirst().equals(profile)
                || gatewayBindings.size() != 1
                || !gatewayBindings.getFirst().equals(gateway)
                || serviceBindings.size() != 1
                || stepUpBindings.size() != 1
                || !stepUpBindings.getFirst().equals(stepUp)
                || !HRM013_APPROVE_CAPABILITY.equals(capability.contractKey())
                || !"ACTIVE".equals(capability.lifecycleState())
                || !"approvals".equals(capability.productKey())
                || !"approvals.work".equals(capability.surfaceKey())
                || !"ACTION.APPROVAL_TASK:APPROVE".equals(
                        capability.resolvedCapabilityCode())
                || !"ACTION.APPROVAL_TASK".equals(capability.resourceKey())
                || !"APPROVE".equals(capability.action())
                || !"PERMISSION".equals(capability.authorityMode())
                || !"NOT_REQUIRED".equals(capability.responsibilityRequirement())
                || capability.requiredResponsibilityCode() != null
                || !"DECIDABLE_TASK_SELF_APPROVAL_SOD".equals(
                        capability.scopeResolver())
                || !"HIGH".equals(capability.riskTier())
                || !"STEPUP-MGMT-HIGH-V1".equals(capability.activationPolicy())
                || capability.sodPolicyId() != null
                || !capability.requiresProductEntitlement()
                || !"Approvals + Security".equals(capability.owner())
                || capability.mappingVersion() != 1
                || capability.policyVersion() != 1
                || capability.sunsetAt() != null
                || capability.legacySource() != null
                || !safe(capability.routeContractKeys()).equals(List.of(HRM013_APPROVE_ROUTE))
                || !"full-work".equals(profile.profileKey())
                || profile.precedence() != 300
                || !safe(profile.activeAccessModes()).equals(List.of("NORMAL", "ELEVATED"))
                || profile.readOnly()
                || profile.requiredAccess() == null
                || !"CAPABILITY".equals(profile.requiredAccess().type())
                || !HRM013_APPROVE_CAPABILITY.equals(
                        profile.requiredAccess().capabilityContractKey())
                || profile.requiredAccess().accessPolicyKey() != null
                || profile.requiredAccess().mode() != null
                || profile.requiredAccess().capabilityContractKeys() != null
                || !safe(profile.targetBindingKinds()).equals(List.of("OBJECT"))
                || !safe(profile.predicatePolicyKeys()).equals(List.of(
                        "predicate.approval-task-decision.v1",
                        "predicate.approval.object-version.v1"))
                || profile.responseProjectionBindings() != null
                || !(HRM013_APPROVE_ROUTE + ".binding.01").equals(gateway.bindingKey())
                || !"POST".equals(gateway.method())
                || !HRM013_GATEWAY_PATH.equals(gateway.path())
                || gateway.pathParameterConstraints() == null
                || !gateway.pathParameterConstraints().isEmpty()
                || gateway.queryParameterConstraints() != null
                || !gateway.bindingKey().equals(serviceBindings.getFirst().bindingKey())
                || !"approval".equals(serviceBindings.getFirst().serviceKey())
                || !"POST".equals(serviceBindings.getFirst().method())
                || !HRM013_SERVICE_PATH.equals(serviceBindings.getFirst().path())
                || serviceBindings.getFirst().pathParameterConstraints() == null
                || !serviceBindings.getFirst().pathParameterConstraints().isEmpty()
                || serviceBindings.getFirst().queryParameterConstraints() != null
                || !gateway.bindingKey().equals(stepUp.bindingKey())
                || !"approval".equals(stepUp.ownerServiceKey())
                || !"dwp-approval-server".equals(stepUp.audience())
                || !"HRIS_SEPARATION_WAIVER_TASK".equals(stepUp.targetType())
                || !"taskId".equals(stepUp.targetIdPathParameter())
                || stepUp.targetIdBodyFields() != null
                || !"COMMAND_HEADER".equals(stepUp.expectedObjectVersionSource())
                || !"X-DWP-Expected-Object-Version".equals(
                        stepUp.expectedObjectVersionName())) {
            throw unavailable();
        }
        return capability.activationPolicy();
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

    ProductAuthorizationContractDtos.AccessProfile capabilityProfile(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities) {
        if (PayrollV49ElevatedAuthorityPolicy.COMMAND_ROUTES.contains(
                route.routeContractKey())) {
            if (!PayrollV49ElevatedAuthorityPolicy.isExactCommandRoute(route, capabilities)) {
                throw unavailable();
            }
            return route.accessProfiles().getFirst();
        }
        List<ProductAuthorizationContractDtos.AccessProfile> profiles =
                safe(route.accessProfiles()).stream()
                        .filter(value -> safe(value.activeAccessModes()).contains("NORMAL")
                                || hrm012NormalCandidate(route, value))
                        .filter(value -> value.requiredAccess() != null)
                        .filter(value -> "CAPABILITY".equals(value.requiredAccess().type()))
                        .toList();
        if (profiles.isEmpty()) throw unavailable();
        int precedence = profiles.stream()
                .mapToInt(ProductAuthorizationContractDtos.AccessProfile::precedence)
                .max().orElseThrow(this::unavailable);
        List<ProductAuthorizationContractDtos.AccessProfile> winners = profiles.stream()
                .filter(value -> value.precedence() == precedence)
                .toList();
        if (winners.size() != 1) throw unavailable();
        return winners.getFirst();
    }

    private ProductAuthorizationContractDtos.AccessProfile capabilityProfile(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            String selectedCapabilityContractKey) {
        List<ProductAuthorizationContractDtos.AccessProfile> matches =
                safe(route.accessProfiles()).stream()
                        .filter(value -> safe(value.activeAccessModes()).contains("NORMAL")
                                || hrm012NormalCandidate(route, value))
                        .filter(value -> value.requiredAccess() != null)
                        .filter(value -> "CAPABILITY".equals(value.requiredAccess().type()))
                        .filter(value -> selectedCapabilityContractKey.equals(
                                value.requiredAccess().capabilityContractKey()))
                        .filter(value -> capabilities.containsKey(
                                value.requiredAccess().capabilityContractKey()))
                        .toList();
        if (matches.size() != 1) throw unavailable();
        return matches.getFirst();
    }

    private boolean hrm012NormalCandidate(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile) {
        return HRM012_ACTION_ROUTE.equals(route.routeContractKey())
                && "workforce-ingestion-executor".equals(profile.profileKey())
                && safe(profile.activeAccessModes()).equals(List.of("ELEVATED"));
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
        String boundTarget = boundTarget(route, request.payload());
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
                || !hasExactlyOneTargetSource(stepUp)
                || blank(stepUp.expectedObjectVersionName())) {
            throw unavailable();
        }
    }

    private String boundTarget(ResolvedRoute route, JsonNode payload) {
        ProductAuthorizationContractDtos.StepUpCommandBinding stepUp = route.stepUp();
        if (!blank(stepUp.targetIdPathParameter())) {
            return route.parameters().get(stepUp.targetIdPathParameter());
        }
        List<String> fields = safe(stepUp.targetIdBodyFields());
        if (fields.isEmpty() || fields.size() != new java.util.LinkedHashSet<>(fields).size()) {
            return null;
        }
        List<String> values = new java.util.ArrayList<>();
        for (String field : fields) {
            JsonNode value = payload.get(field);
            if (blank(field) || value == null || !value.isTextual()
                    || blank(value.textValue()) || value.textValue().contains(":")
                    || value.textValue().indexOf('\n') >= 0
                    || value.textValue().indexOf('\r') >= 0) {
                return null;
            }
            values.add(value.textValue());
        }
        return String.join(":", values);
    }

    private boolean hasExactlyOneTargetSource(
            ProductAuthorizationContractDtos.StepUpCommandBinding stepUp) {
        boolean path = !blank(stepUp.targetIdPathParameter());
        boolean body = !safe(stepUp.targetIdBodyFields()).isEmpty();
        return path != body;
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
            List<String> targetIdBodyFields,
            String expectedObjectVersionSource,
            String expectedObjectVersionName,
            long bundleVersion,
            String bundleChecksum,
            long pointerRevision) {

        public Resolution(
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
            this(routeContractKey, productKey, surfaceKey, capabilityContractKey,
                    activationPolicy, scopeResolver, ownerServiceKey, audience, targetType,
                    targetIdPathParameter, List.of(), expectedObjectVersionSource,
                    expectedObjectVersionName, bundleVersion, bundleChecksum, pointerRevision);
        }
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
