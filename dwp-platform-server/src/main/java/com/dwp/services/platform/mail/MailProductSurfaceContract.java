package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Immutable owner-service projection for the Mail v4 PAGE/DATA/ACTION draft. */
@Component
public final class MailProductSurfaceContract {

    public static final String POLICY_ID = "P-MAIL";
    public static final String PRODUCT_ID = "mail";
    public static final String SURFACE_KEY = "mail.work";
    public static final String ACCESS_POLICY_KEY = "mail.work-access.v1";
    public static final String MESSAGE_CREATE_CAPABILITY_KEY = "mail.work.message.create";
    public static final String OWNER_SERVICE = "dwp-platform-server";
    public static final String SERVICE_KEY = "platform";

    public static final String HOME_PAGE_ROUTE = "route.mail.work.home.page";
    public static final String THREADS_DATA_ROUTE = "route.mail.work.threads.data";
    public static final String MESSAGE_CREATE_ACTION_ROUTE =
            "route.mail.work.message-create.action";

    private static final List<Binding> BINDINGS = List.of(
            new Binding(
                    HOME_PAGE_ROUTE,
                    RouteKind.PAGE,
                    "GET",
                    "/api/platform/v1/mail/home",
                    "/v1/mail/home",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.MAIL:VIEW",
                    true),
            new Binding(
                    THREADS_DATA_ROUTE,
                    RouteKind.DATA,
                    "GET",
                    "/api/platform/v1/mail/threads",
                    "/v1/mail/threads",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.MAIL:VIEW",
                    true),
            new Binding(
                    MESSAGE_CREATE_ACTION_ROUTE,
                    RouteKind.ACTION,
                    "POST",
                    "/api/platform/v1/mail/messages",
                    "/v1/mail/messages",
                    AccessContractType.CAPABILITY,
                    MESSAGE_CREATE_CAPABILITY_KEY,
                    "APP.MAIL:CREATE",
                    false));

    public Optional<Binding> resolveOwner(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        return BINDINGS.stream()
                .filter(binding -> binding.method().equals(method))
                .filter(binding -> binding.servicePath().equals(path))
                .findFirst();
    }

    public List<BindingContract> bindingContracts() {
        return BINDINGS.stream().map(binding -> new BindingContract(
                POLICY_ID,
                PRODUCT_ID,
                SURFACE_KEY,
                OWNER_SERVICE,
                SERVICE_KEY,
                binding.routeContractKey(),
                binding.routeKind(),
                binding.method(),
                binding.gatewayPath(),
                binding.servicePath(),
                binding.accessContractType(),
                binding.accessContractKey(),
                binding.resolvedAuthority(),
                binding.readOnly())).toList();
    }

    public enum RouteKind {
        PAGE,
        DATA,
        ACTION
    }

    public enum AccessContractType {
        POLICY,
        CAPABILITY
    }

    public record Binding(
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            AccessContractType accessContractType,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly) {
    }

    public record BindingContract(
            String policyId,
            String productId,
            String surfaceKey,
            String ownerService,
            String serviceKey,
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            AccessContractType accessContractType,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly) {
    }
}
