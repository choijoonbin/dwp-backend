package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dwp.services.auth.service.ProductAuthorizationContractRules.*;

/** Validates governed routes, their enforcement bindings, and response projections. */
final class ProductAuthorizationRouteContractValidator {

    void validateRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies,
            Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates,
            Map<String, Set<String>> capabilityRoutes,
            Map<String, Set<String>> policyRoutes,
            Map<String, Set<String>> predicateRoutes,
            long bundleVersion) {
        String key = route.routeContractKey();
        revision(route.owner(), route.policyVersion(), route.lifecycleState(), key);
        require(ROUTE_KINDS.contains(route.routeKind()), key + ": invalid route kind.");
        require(CONTEXT_PATTERN.matcher(route.navigationContextId()).matches()
                        && !route.navigationContextId().contains("_"),
                key + ": invalid navigation context.");
        require(route.subject() != null, key + ": subject is required.");
        if ("PRODUCT".equals(route.subject().type())) {
            require(text(route.subject().productKey()) && text(route.subject().surfaceKey())
                            && !key.startsWith("route.context."),
                    key + ": invalid product subject.");
        } else {
            require("GOVERNED_CONTEXT".equals(route.subject().type())
                            && route.subject().productKey() == null
                            && route.subject().surfaceKey() == null,
                    key + ": invalid governed context subject.");
            String token = route.navigationContextId().replace(".", "__");
            require(key.startsWith("route.context." + token + "."),
                    key + ": context token is not reversible.");
        }
        require("PAGE".equals(route.routeKind())
                        ? text(route.uiRouteId()) && text(route.uiRoutePattern())
                        : route.uiRouteId() == null && route.uiRoutePattern() == null,
                key + ": UI route fields violate the route kind.");
        if ("ACTION".equals(route.routeKind())) {
            require(route.sideEffectFree() == null,
                    key + ": ACTION cannot be side-effect-free DATA.");
        }
        validateBindings(route);
        validateProfiles(route, capabilities, policies, predicates,
                capabilityRoutes, policyRoutes, predicateRoutes, bundleVersion);
        ProductAuthorizationGateTopologyValidator.validateRoute(route, capabilities);
    }

    void validateAuthorityEndpoints(ProductAuthorizationContractDtos.BundleContract contract) {
        List<ProductAuthorizationContractDtos.AuthorityEndpoint> endpoints =
                nullSafe(contract.authorityEndpoints());
        if (contract.version() == 1) {
            require(endpoints.isEmpty(), "Authority endpoints are forbidden in registry v1.");
            return;
        }
        require(endpoints.size() == 1,
                "Every registry version after v1 requires the exact step-up authority endpoint.");
        ProductAuthorizationContractDtos.AuthorityEndpoint endpoint = endpoints.get(0);
        require("product-surface-step-up-challenge.issue".equals(endpoint.endpointKey())
                        && "POST".equals(endpoint.method())
                        && "/api/auth/product-surface-step-up-challenges".equals(
                                endpoint.publicPath())
                        && "auth".equals(endpoint.serviceKey())
                        && "/auth/product-surface-step-up-challenges".equals(endpoint.servicePath())
                        && endpoint.requiresAuthentication()
                        && endpoint.requiresCsrf()
                        && "X-DWP-Expected-Decision-Revision".equals(
                                endpoint.expectedDecisionRevisionHeader()),
                "Registry step-up authority endpoint drift.");
    }

    void validateApprovalProjectionSchemaCoverage(
            ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() < 2) return;
        Set<String> schemas = contract.routes().stream()
                .filter(route -> "PRODUCT".equals(route.subject().type())
                        && "approvals".equals(route.subject().productKey()))
                .flatMap(route -> route.accessProfiles().stream())
                .filter(profile -> Set.of("auditor", "legacy-oversight")
                        .contains(profile.profileKey()))
                .flatMap(profile -> nullSafe(profile.responseProjectionBindings()).stream())
                .map(ProductAuthorizationContractDtos.ResponseProjectionBinding::responseSchemaKey)
                .collect(Collectors.toSet());
        require(schemas.equals(APPROVAL_FIELD_MASK_SCHEMA_PROFILES.keySet()),
                "Approval projection schema coverage drift.");
    }

    private void validateBindings(ProductAuthorizationContractDtos.GovernedRoute route) {
        String key = route.routeContractKey();
        Map<String, ProductAuthorizationContractDtos.GatewayBinding> gateway = index(
                route.gatewayApiBindings(),
                ProductAuthorizationContractDtos.GatewayBinding::bindingKey,
                key + " gateway binding");
        Map<String, ProductAuthorizationContractDtos.ServicePepBinding> service = index(
                route.servicePepBindings(),
                ProductAuthorizationContractDtos.ServicePepBinding::bindingKey,
                key + " service binding");
        require(!gateway.isEmpty() && gateway.keySet().equals(service.keySet()),
                key + ": public/service binding pair mismatch.");
        gateway.forEach((bindingKey, publicBinding) -> {
            ProductAuthorizationContractDtos.ServicePepBinding pep = service.get(bindingKey);
            require(publicBinding.method().equals(pep.method()), bindingKey + ": method mismatch.");
            require(nullSafeMap(publicBinding.pathParameterConstraints())
                            .equals(nullSafeMap(pep.pathParameterConstraints())),
                    bindingKey + ": path constraint mismatch.");
            require(nullSafeMap(publicBinding.queryParameterConstraints())
                            .equals(nullSafeMap(pep.queryParameterConstraints())),
                    bindingKey + ": query constraint mismatch.");
            require(SERVICE_KEYS.contains(pep.serviceKey()), bindingKey + ": unknown service.");
            boolean validServicePath = "auth".equals(pep.serviceKey())
                    ? pep.path().startsWith("/auth/")
                    : pep.path().startsWith("/v1/")
                            || "platform".equals(pep.serviceKey())
                                    && pep.path().startsWith("/v2/");
            require(validServicePath && !pep.path().contains("/**")
                            && !publicBinding.path().contains("/**"),
                    bindingKey + ": invalid service path grammar.");
            validatePathConstraints(bindingKey, publicBinding.pathParameterConstraints());
            validateQueryConstraints(bindingKey, publicBinding.queryParameterConstraints());
        });
    }

    private void validatePathConstraints(
            String bindingKey,
            Map<String, ProductAuthorizationContractDtos.PathParameterConstraint> constraints) {
        nullSafeMap(constraints).forEach((parameter, constraint) -> {
            require(Set.of("FIXED", "ALLOWLIST").contains(constraint.kind()),
                    bindingKey + ": invalid path constraint kind.");
            if ("FIXED".equals(constraint.kind())) {
                require(text(constraint.value()) && nullSafe(constraint.values()).isEmpty(),
                        bindingKey + ": invalid fixed constraint.");
            } else {
                require(!nullSafe(constraint.values()).isEmpty()
                                && new HashSet<>(constraint.values()).size()
                                == constraint.values().size(),
                        bindingKey + ": invalid allowlist constraint.");
            }
        });
    }

    private void validateQueryConstraints(
            String bindingKey,
            Map<String, ProductAuthorizationContractDtos.QueryParameterConstraint> constraints) {
        nullSafeMap(constraints).forEach((parameter, constraint) -> {
            require(Set.of("FIXED", "ALLOWLIST", "ABSENT", "REQUIRED")
                            .contains(constraint.kind()),
                    bindingKey + ": invalid query constraint kind.");
            if ("FIXED".equals(constraint.kind())) {
                require(text(constraint.value()) && nullSafe(constraint.values()).isEmpty(),
                        bindingKey + ": invalid fixed query constraint.");
            } else if ("ALLOWLIST".equals(constraint.kind())) {
                require(!nullSafe(constraint.values()).isEmpty()
                                && new HashSet<>(constraint.values()).size()
                                == constraint.values().size(),
                        bindingKey + ": invalid query allowlist constraint.");
            } else {
                require(constraint.value() == null && nullSafe(constraint.values()).isEmpty(),
                        bindingKey + ("ABSENT".equals(constraint.kind())
                                ? ": invalid absent query constraint."
                                : ": invalid required query constraint."));
            }
        });
    }

    private void validateProfiles(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies,
            Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates,
            Map<String, Set<String>> capabilityRoutes,
            Map<String, Set<String>> policyRoutes,
            Map<String, Set<String>> predicateRoutes,
            long bundleVersion) {
        String routeKey = route.routeContractKey();
        require(!nullSafe(route.accessProfiles()).isEmpty(),
                routeKey + ": access profiles are required.");
        Set<String> profileKeys = new HashSet<>();
        Set<Integer> precedences = new HashSet<>();
        Set<String> bindingKeys = route.gatewayApiBindings().stream()
                .map(ProductAuthorizationContractDtos.GatewayBinding::bindingKey)
                .collect(Collectors.toSet());
        for (ProductAuthorizationContractDtos.AccessProfile profile : route.accessProfiles()) {
            String profileRef = routeKey + "/" + profile.profileKey();
            require(text(profile.profileKey()) && profileKeys.add(profile.profileKey()),
                    profileRef + ": duplicate profile key.");
            require(precedences.add(profile.precedence()),
                    profileRef + ": duplicate precedence.");
            require(nonEmptyUniqueSubset(profile.activeAccessModes(), ACCESS_MODES),
                    profileRef + ": invalid access modes.");
            validateRequiredAccess(profileRef, routeKey, profile.requiredAccess(), capabilities,
                    policies, capabilityRoutes, policyRoutes);
            validatePredicateBindings(profileRef, routeKey, profile, predicates, predicateRoutes);
            validateProjections(route, profile, bindingKeys, bundleVersion, profileRef);
        }
    }

    private void validateRequiredAccess(
            String profileRef,
            String routeKey,
            ProductAuthorizationContractDtos.RequiredAccess access,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies,
            Map<String, Set<String>> capabilityRoutes,
            Map<String, Set<String>> policyRoutes) {
        require(access != null, profileRef + ": required access is missing.");
        if ("CAPABILITY".equals(access.type())) {
            require(capabilities.containsKey(access.capabilityContractKey()),
                    profileRef + ": unknown capability.");
            capabilityRoutes.get(access.capabilityContractKey()).add(routeKey);
        } else if ("CAPABILITY_EXPRESSION".equals(access.type())) {
            require(Set.of("ANY", "ALL").contains(access.mode())
                            && !nullSafe(access.capabilityContractKeys()).isEmpty()
                            && access.capabilityContractKeys().stream()
                                    .allMatch(capabilities::containsKey),
                    profileRef + ": invalid capability expression.");
            access.capabilityContractKeys()
                    .forEach(key -> capabilityRoutes.get(key).add(routeKey));
        } else {
            require("POLICY".equals(access.type())
                            && policies.containsKey(access.accessPolicyKey()),
                    profileRef + ": unknown access policy.");
            policyRoutes.get(access.accessPolicyKey()).add(routeKey);
        }
    }

    private void validatePredicateBindings(
            String profileRef,
            String routeKey,
            ProductAuthorizationContractDtos.AccessProfile profile,
            Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates,
            Map<String, Set<String>> predicateRoutes) {
        List<String> targets = nullSafe(profile.targetBindingKinds());
        List<String> predicateKeys = nullSafe(profile.predicatePolicyKeys());
        require(new HashSet<>(targets).size() == targets.size()
                        && TARGET_KINDS.containsAll(targets),
                profileRef + ": invalid target bindings.");
        require(new HashSet<>(predicateKeys).size() == predicateKeys.size(),
                profileRef + ": duplicate predicates.");
        Set<String> covered = new HashSet<>();
        for (String predicateKey : predicateKeys) {
            ProductAuthorizationContractDtos.PredicatePolicy predicate =
                    predicates.get(predicateKey);
            require(predicate != null, profileRef + ": unknown predicate.");
            Set<String> effective = new HashSet<>(targets);
            effective.retainAll(predicate.targetBindingKinds());
            require(!effective.isEmpty(), profileRef + ": predicate target mismatch.");
            covered.addAll(effective);
            predicateRoutes.get(predicateKey).add(routeKey);
        }
        if (!predicateKeys.isEmpty()) {
            require(covered.equals(new HashSet<>(targets)),
                    profileRef + ": predicate union does not cover targets.");
        }
    }

    private void validateProjections(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile,
            Set<String> bindingKeys,
            long bundleVersion,
            String profileRef) {
        List<ProductAuthorizationContractDtos.ResponseProjectionBinding> projections =
                nullSafe(profile.responseProjectionBindings());
        if ("ACTION".equals(route.routeKind())) {
            require(projections.isEmpty(), profileRef + ": ACTION projection is forbidden.");
            return;
        }
        require(projections.stream()
                        .map(ProductAuthorizationContractDtos.ResponseProjectionBinding::apiBindingKey)
                        .collect(Collectors.toSet()).equals(bindingKeys)
                        && projections.size() == bindingKeys.size(),
                profileRef + ": incomplete response projection bindings.");
        require(projections.stream().allMatch(value ->
                        text(value.projectionPolicyKey()) && text(value.responseSchemaKey())),
                profileRef + ": projection keys are required.");
        boolean approvalFieldMaskProfile = bundleVersion >= 2
                && "PRODUCT".equals(route.subject().type())
                && "approvals".equals(route.subject().productKey())
                && Set.of("auditor", "legacy-oversight").contains(profile.profileKey());
        for (ProductAuthorizationContractDtos.ResponseProjectionBinding projection : projections) {
            validateProjection(
                    route, profile, projection, bundleVersion, approvalFieldMaskProfile, profileRef);
        }
    }

    private void validateProjection(
            ProductAuthorizationContractDtos.GovernedRoute route,
            ProductAuthorizationContractDtos.AccessProfile profile,
            ProductAuthorizationContractDtos.ResponseProjectionBinding projection,
            long bundleVersion,
            boolean approvalFieldMaskProfile,
            String profileRef) {
        if (bundleVersion >= 11 && ProductAuthorizationRecovery11ProjectionSchema
                .isRecovery11DataRoute(route.routeContractKey())) {
            require(ProductAuthorizationRecovery11ProjectionSchema.matches(
                            route, profile.profileKey(), projection),
                    profileRef + ": invalid exact recovery11 projection schema metadata.");
        } else if (bundleVersion >= 7 && ProductAuthorizationReleaseLineage
                .isWorkDataRoute(route.routeContractKey())) {
            require(ProductAuthorizationReleaseLineage.matchesWorkProjection(
                            route.routeContractKey(), profile.profileKey(), projection),
                    profileRef + ": invalid v7 work projection schema metadata.");
        } else if (bundleVersion >= 8 && ProductAuthorizationDocumentProjectionSchema
                .isDocumentDataRoute(route.routeContractKey())) {
            require(ProductAuthorizationDocumentProjectionSchema.matches(
                            route.routeContractKey(), profile.profileKey(), projection),
                    profileRef + ": invalid v8 document/source projection schema metadata.");
        } else if (bundleVersion >= 9 && ProductAuthorizationExtensionProjectionSchema
                .isExtensionDataRoute(route.routeContractKey())) {
            require(ProductAuthorizationExtensionProjectionSchema.matches(
                            route, profile.profileKey(), projection),
                    profileRef + ": invalid exact v9 extension projection schema metadata.");
        } else if (bundleVersion >= 10 && ProductAuthorizationRelease10ProjectionSchema
                .isRelease10DataRoute(route.routeContractKey())) {
            require(ProductAuthorizationRelease10ProjectionSchema.matches(
                            route, profile.profileKey(), projection),
                    profileRef + ": invalid exact release10 projection schema metadata.");
        } else if (approvalFieldMaskProfile) {
            require(profile.profileKey().equals(
                            APPROVAL_FIELD_MASK_SCHEMA_PROFILES.get(projection.responseSchemaKey()))
                            && Integer.valueOf(1).equals(projection.schemaVersion())
                            && text(projection.openApiSchemaSha256())
                            && CHECKSUM_PATTERN.matcher(
                                    projection.openApiSchemaSha256()).matches()
                            && Boolean.FALSE.equals(projection.additionalProperties()),
                    profileRef + ": invalid Approval projection schema metadata.");
        } else {
            require(projection.schemaVersion() == null
                            && projection.openApiSchemaSha256() == null
                            && projection.additionalProperties() == null,
                    profileRef + ": projection schema metadata is forbidden.");
        }
    }
}
