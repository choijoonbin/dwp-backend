package com.dwp.services.meeting.security;

import com.dwp.core.security.ProductSurfaceScopeKey;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Owner-service PEP for the first immutable P-MEETINGS contract slice.
 *
 * <p>The v4 registry is a DRAFT candidate, so this policy is inert unless the
 * tenant rollout selects exact enforcement and the service readiness latch is
 * explicitly enabled. Scope keys are opaque selectors, not bearer grants: the
 * owner recomputes the actor/tenant-bound SELF key before accepting one.</p>
 */
@Component
public final class MeetingProductAccessPolicy {

    public static final String POLICY_ID = "P-MEETINGS";
    public static final String PRODUCT_ID = "meetings";
    public static final String SURFACE_KEY = "meetings.work";
    public static final String OWNER_SERVICE = "dwp-meeting-server";
    public static final String SERVICE_KEY = "meeting";

    private static final String SELF_SOURCE = "SELF";
    private static final String SELF_KIND = "SELF";
    private static final List<Binding> BINDINGS = List.of(
            new Binding(
                    "route.meetings.work.home.page",
                    RouteKind.PAGE,
                    "GET",
                    "/v1/home",
                    "meetings.work-access.v1",
                    "APP.MEETINGS:VIEW",
                    true),
            new Binding(
                    "route.meetings.work.meetings.data",
                    RouteKind.DATA,
                    "GET",
                    "/v1/meetings",
                    "meetings.work.meetings.read",
                    "APP.MEETINGS:VIEW",
                    true),
            new Binding(
                    "route.meetings.work.meeting-create.action",
                    RouteKind.ACTION,
                    "POST",
                    "/v1/meetings",
                    "meetings.work.meeting.create",
                    "APP.MEETINGS:CREATE",
                    false));

    public Decision authorize(RequestEvidence evidence) {
        if (evidence == null || evidence.tenantId() <= 0 || evidence.actorId() <= 0) {
            return Decision.denied("VERIFIED_SUBJECT_REQUIRED");
        }
        Binding binding = BINDINGS.stream()
                .filter(candidate -> candidate.method().equals(evidence.method()))
                .filter(candidate -> candidate.servicePath().equals(evidence.path()))
                .findFirst().orElse(null);
        if (binding == null) return Decision.denied("EXACT_SERVICE_BINDING_REQUIRED");
        if (!binding.routeContractKey().equals(evidence.routeContractKey())) {
            return Decision.denied("ROUTE_CONTRACT_MISMATCH");
        }
        if (!Set.of(ActiveAccessMode.NORMAL, ActiveAccessMode.ELEVATED)
                .contains(evidence.activeAccessMode()) || evidence.supportIdentity()) {
            return Decision.denied("WORKSPACE_ACTOR_REQUIRED");
        }
        String expectedScope = selfScope(evidence.tenantId(), evidence.actorId());
        if (!expectedScope.equals(evidence.scopeKey())) {
            return Decision.denied("TENANT_ACTOR_SCOPE_MISMATCH");
        }
        if (evidence.permissions() == null
                || !evidence.permissions().contains(binding.resolvedAuthority())) {
            return Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED");
        }
        return Decision.allowed(binding);
    }

    public boolean ownsCandidate(String method, String path) {
        return BINDINGS.stream().anyMatch(binding ->
                binding.method().equals(method) && binding.servicePath().equals(path));
    }

    public String selfScope(long tenantId, long actorId) {
        return ProductSurfaceScopeKey.key(
                tenantId, actorId, PRODUCT_ID, SURFACE_KEY, SELF_SOURCE, SELF_KIND);
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
                "/api/meetings" + binding.servicePath(),
                binding.servicePath(),
                binding.accessContractKey(),
                binding.resolvedAuthority(),
                SELF_KIND,
                binding.readOnly())).toList();
    }

    public enum RouteKind {
        PAGE,
        DATA,
        ACTION
    }

    public enum ActiveAccessMode {
        NORMAL,
        ELEVATED,
        PROVIDER_SUPPORT
    }

    public record RequestEvidence(
            long tenantId,
            long actorId,
            String method,
            String path,
            String routeContractKey,
            String scopeKey,
            ActiveAccessMode activeAccessMode,
            boolean supportIdentity,
            Set<String> permissions) {
    }

    public record Decision(boolean allowed, String reasonCode, Binding binding) {

        static Decision allowed(Binding binding) {
            return new Decision(true, null, binding);
        }

        static Decision denied(String reasonCode) {
            return new Decision(false, reasonCode, null);
        }
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
            String accessContractKey,
            String resolvedAuthority,
            String targetKind,
            boolean readOnly) {
    }

    public record Binding(
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String servicePath,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly) {
    }
}
