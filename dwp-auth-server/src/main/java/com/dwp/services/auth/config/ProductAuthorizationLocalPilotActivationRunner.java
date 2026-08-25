package com.dwp.services.auth.config;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.service.ProductAuthorizationContractService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Explicit local-only bootstrap for the immutable CORE-006 v3 authorization bundle.
 * Production and shared environments must use the normal approval workflow instead.
 */
@Component
@Order(110)
public class ProductAuthorizationLocalPilotActivationRunner implements ApplicationRunner {

    static final String BUNDLE_KEY = "product-surfaces";
    static final long PILOT_VERSION = 3L;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductAuthorizationLocalPilotActivationRunner.class);

    private final boolean enabled;
    private final String approvalActorRef;
    private final String activationActorRef;
    private final ProductAuthorizationContractService service;

    public ProductAuthorizationLocalPilotActivationRunner(
            @Value("${dwp.product-authorization.local-pilot-activation.enabled:false}")
            boolean enabled,
            @Value("${dwp.product-authorization.local-pilot-activation.approval-actor-ref:local-core006-bundle-approver}")
            String approvalActorRef,
            @Value("${dwp.product-authorization.local-pilot-activation.activation-actor-ref:local-core006-bundle-activator}")
            String activationActorRef,
            Environment environment,
            ProductAuthorizationContractService service) {
        this.enabled = enabled;
        this.approvalActorRef = normalizedActor(approvalActorRef, "approval");
        this.activationActorRef = normalizedActor(activationActorRef, "activation");
        this.service = service;
        if (enabled && !local(environment)) {
            throw new IllegalStateException(
                    "CORE-006 local pilot activation is forbidden outside DWP_ENVIRONMENT=local.");
        }
        if (enabled && this.approvalActorRef.equals(this.activationActorRef)) {
            throw new IllegalStateException(
                    "CORE-006 local pilot approval and activation actor references must differ.");
        }
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!enabled) {
            LOGGER.info("CORE-006 local pilot authorization activation is disabled.");
            return;
        }

        ProductAuthorizationContractDtos.BundleView target =
                service.version(BUNDLE_KEY, PILOT_VERSION);
        if ("DRAFT".equals(target.bundleStatus())) {
            target = service.approve(BUNDLE_KEY, PILOT_VERSION, approvalActorRef);
        }
        if (!"APPROVED".equals(target.bundleStatus())
                && !"ACTIVE".equals(target.bundleStatus())) {
            throw new IllegalStateException(
                    "CORE-006 local pilot v3 must be DRAFT, APPROVED or ACTIVE before activation.");
        }

        Optional<ProductAuthorizationContractDtos.BundleView> activeBefore = active();
        if (isTarget(activeBefore, target.checksum())) {
            LOGGER.info(
                    "CORE-006 local pilot authorization bundle is already active: version={} revision={}",
                    PILOT_VERSION, activeBefore.orElseThrow().activeRevision());
            return;
        }
        activeBefore.ifPresent(active -> {
            if (active.version() > PILOT_VERSION) {
                throw new IllegalStateException(
                        "CORE-006 local pilot bootstrap will not replace a newer active bundle.");
            }
        });
        long expectedRevision = activeBefore
                .map(ProductAuthorizationContractDtos.BundleView::activeRevision)
                .orElse(0L);

        try {
            ProductAuthorizationContractDtos.ActivationResult activated = service.activate(
                    BUNDLE_KEY, PILOT_VERSION, activationActorRef, expectedRevision);
            if (activated.version() != PILOT_VERSION
                    || !activated.checksum().equals(target.checksum())) {
                throw new IllegalStateException(
                        "CORE-006 local pilot activation returned a different immutable bundle.");
            }
            LOGGER.info(
                    "Activated CORE-006 local pilot authorization bundle: version={} revision={}",
                    activated.version(), activated.revision());
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.RESOURCE_CONFLICT
                    && isTarget(active(), target.checksum())) {
                LOGGER.info(
                        "CORE-006 local pilot authorization activation converged after a CAS race.");
                return;
            }
            throw exception;
        }
    }

    private Optional<ProductAuthorizationContractDtos.BundleView> active() {
        try {
            return Optional.of(service.active(BUNDLE_KEY));
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.NOT_FOUND) return Optional.empty();
            throw exception;
        }
    }

    private static boolean isTarget(
            Optional<ProductAuthorizationContractDtos.BundleView> active,
            String expectedChecksum) {
        return active.filter(value -> value.version() == PILOT_VERSION)
                .filter(value -> "ACTIVE".equals(value.bundleStatus()))
                .filter(value -> value.activeRevision() > 0)
                .filter(value -> expectedChecksum.equals(value.checksum()))
                .isPresent();
    }

    private static String normalizedActor(String value, String purpose) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalStateException(
                    "CORE-006 local pilot " + purpose + " actor reference is required.");
        }
        return normalized;
    }

    private static boolean local(Environment environment) {
        String value = environment.getProperty("DWP_ENVIRONMENT", "");
        return "local".equals(value.trim().toLowerCase(Locale.ROOT));
    }
}
