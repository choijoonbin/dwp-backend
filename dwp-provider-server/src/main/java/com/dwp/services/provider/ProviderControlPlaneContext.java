package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.entitlement.Entitlement;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlement;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStepAttemptRepository;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

final class ProviderControlPlaneContext {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProviderTenantRepository tenantRepository;
    private final EntitlementRepository entitlementRepository;
    private final TenantEntitlementRepository tenantEntitlementRepository;
    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderOperationStepAttemptRepository attemptRepository;
    private final ProviderEstateRepository estateRepository;
    private final ObjectMapper objectMapper;

    ProviderControlPlaneContext(
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            TenantEntitlementRepository tenantEntitlementRepository,
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderOperationStepAttemptRepository attemptRepository,
            ProviderEstateRepository estateRepository,
            ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.entitlementRepository = entitlementRepository;
        this.tenantEntitlementRepository = tenantEntitlementRepository;
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.attemptRepository = attemptRepository;
        this.estateRepository = estateRepository;
        this.objectMapper = objectMapper;
    }

    List<Entitlement> requireEntitlements(List<String> requestedKeys) {
        Set<String> keys = requestedKeys.stream()
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Entitlement> available = entitlementRepository
                .findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE")
                .stream().collect(Collectors.toMap(Entitlement::getEntitlementKey, Function.identity()));
        if (!available.keySet().containsAll(keys)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An unknown entitlement was selected.");
        }
        return keys.stream().map(available::get)
                .sorted(Comparator.comparing(Entitlement::getEntitlementKey)).toList();
    }

    void requireRegion(String regionKey) {
        boolean active = estateRepository.regions().stream()
                .anyMatch(region -> region.regionKey().equals(regionKey)
                        && "ACTIVE".equals(region.lifecycleState()));
        if (!active) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown or inactive data region.");
    }

    void replaceTenantEntitlements(ProviderTenant tenant, List<Entitlement> entitlements) {
        Map<Long, TenantEntitlement> current = tenantEntitlementRepository
                .findByProviderTenantIdOrderByTenantEntitlementIdAsc(tenant.getProviderTenantId())
                .stream().collect(Collectors.toMap(TenantEntitlement::getEntitlementId, Function.identity()));
        Set<Long> requestedIds = entitlements.stream()
                .map(Entitlement::getEntitlementId).collect(Collectors.toSet());
        current.values().forEach(assignment -> assignment.setLifecycleState(
                requestedIds.contains(assignment.getEntitlementId()) ? "ACTIVE" : "RETIRED"));
        List<TenantEntitlement> additions = entitlements.stream()
                .filter(entitlement -> !current.containsKey(entitlement.getEntitlementId()))
                .map(entitlement -> TenantEntitlement.builder()
                        .providerTenantId(tenant.getProviderTenantId())
                        .entitlementId(entitlement.getEntitlementId())
                        .lifecycleState("ACTIVE")
                        .configuration("{}")
                        .build())
                .toList();
        tenantEntitlementRepository.saveAll(current.values());
        tenantEntitlementRepository.saveAll(additions);
    }

    ProviderDtos.TenantSummary tenantSummary(ProviderTenant tenant) {
        ProviderDtos.OrganizationSummary organization = estateRepository
                .organization(tenant.getOrganizationId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Map<Long, Entitlement> catalog = entitlementRepository.findAll().stream()
                .collect(Collectors.toMap(Entitlement::getEntitlementId, Function.identity()));
        List<ProviderDtos.EntitlementSummary> entitlements = tenantEntitlementRepository
                .findByProviderTenantIdOrderByTenantEntitlementIdAsc(tenant.getProviderTenantId())
                .stream()
                .filter(assignment -> catalog.containsKey(assignment.getEntitlementId()))
                .map(assignment -> entitlementSummary(catalog.get(assignment.getEntitlementId()), assignment))
                .toList();
        return new ProviderDtos.TenantSummary(
                tenant.getProviderTenantId(),
                tenant.getOrganizationId(),
                organization.organizationKey(),
                organization.displayName(),
                tenant.getTenantKey(),
                tenant.getDisplayName(),
                tenant.getEnvironmentKey(),
                tenant.getServiceTier(),
                tenant.getDataRegion(),
                tenant.getIsolationModel(),
                tenant.getDefaultLocale(),
                tenant.getTimeZone(),
                tenant.getLifecycleState(),
                tenant.getOnboardingState(),
                tenant.getAuthTenantId(),
                tenant.getSchemaVersion(),
                tenant.getConfiguration(),
                valueOrZero(tenant.getVersion()),
                tenant.getCreatedAt() == null
                        ? null
                        : tenant.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                tenant.getUpdatedAt() == null
                        ? null
                        : tenant.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                estateRepository.currentSubscription(tenant.getOrganizationId()).orElse(null),
                entitlements,
                estateRepository.serviceInstances(tenant.getProviderTenantId()),
                estateRepository.domains(tenant.getProviderTenantId()),
                estateRepository.administratorPosture(tenant.getProviderTenantId()));
    }

    ProviderDtos.EntitlementSummary entitlementSummary(
            Entitlement entitlement,
            TenantEntitlement assignment) {
        return new ProviderDtos.EntitlementSummary(
                entitlement.getEntitlementId(), entitlement.getEntitlementKey(),
                entitlement.getName(), entitlement.getEntitlementType(),
                assignment == null ? entitlement.getLifecycleState() : assignment.getLifecycleState(),
                assignment == null ? "{}" : assignment.getConfiguration(),
                assignment == null ? 0 : valueOrZero(assignment.getVersion()));
    }

    ProviderDtos.OperationSummary operationSummary(ProviderOperation operation) {
        List<ProviderDtos.OperationStep> steps = stepRepository
                .findByOperationIdOrderByStepOrderAsc(operation.getOperationId())
                .stream().map(step -> new ProviderDtos.OperationStep(
                        step.getOperationStepId(),
                        step.getStepOrder(),
                        step.getStepKey(),
                        step.getLifecycleState(),
                        step.getTargetService(),
                        step.getExternalReference(),
                        step.getRedactedResult(),
                        step.getAttemptCount(),
                        step.getLastErrorCode(),
                        step.getLastErrorMessage(),
                        step.getNextRetryAt(),
                        step.getStartedAt(),
                        step.getCompletedAt(),
                        attemptRepository
                                .findByOperationStepIdOrderByAttemptNumberAsc(
                                        step.getOperationStepId())
                                .stream()
                                .map(attempt -> new ProviderDtos.OperationStepAttempt(
                                        attempt.getOperationStepAttemptId(),
                                        attempt.getAttemptNumber(),
                                        attempt.getLifecycleState(),
                                        attempt.getRequestFingerprint(),
                                        attempt.getRedactedResult(),
                                        attempt.getErrorCode(),
                                        attempt.getErrorMessage(),
                                        attempt.getStartedAt(),
                                        attempt.getCompletedAt()))
                                .toList()))
                .toList();
        return new ProviderDtos.OperationSummary(
                operation.getOperationId(), operation.getProviderTenantId(), operation.getOperationType(),
                operation.getLifecycleState(), operation.getRiskTier(), operation.getPlanHash(),
                operation.getPlan(), operation.getFailureCode(), operation.getFailureMessage(),
                operation.getStartedAt(), operation.getCompletedAt(), operation.getCreatedAt(),
                valueOrZero(operation.getVersion()), steps);
    }

    ProviderTenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    void requireOnboardingReady(ProviderTenant tenant) {
        if (!"READY".equals(tenant.getOnboardingState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Tenant onboarding must be ready before provider mutations can run.");
        }
    }

    void requireSupportReadyTenant(ProviderTenant tenant) {
        if (!"ACTIVE".equals(tenant.getLifecycleState())
                || !"READY".equals(tenant.getOnboardingState())
                || tenant.getAuthTenantId() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The target tenant must be ACTIVE, READY, and linked to auth before support activation.");
        }
    }

    ProviderOperation requireOperation(UUID operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > 160 || !normalized.matches("[A-Za-z0-9:._-]+")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key format is invalid.");
        }
        return normalized;
    }

    void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(valueOrZero(actual), expected)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Provider state changed after it was loaded. Refresh and try again.");
        }
    }

    List<String> dnsTxtRecords(String recordName) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "2500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            Attributes attributes = new InitialDirContext(environment)
                    .getAttributes(recordName, new String[]{"TXT"});
            if (attributes.get("TXT") == null) return List.of();
            List<String> values = new ArrayList<>();
            for (int index = 0; index < attributes.get("TXT").size(); index++) {
                values.add(String.valueOf(attributes.get("TXT").get(index)));
            }
            return values;
        } catch (NamingException exception) {
            return List.of();
        }
    }

    String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    boolean constantTimeEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider plan serialization failed.", exception);
        }
    }

    String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
