package com.dwp.services.notification.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact public-to-owner bindings for the Notifications v4 Product Surface candidate. */
@Component
public final class NotificationProductSurfaceContract {

    public static final String PRODUCT_KEY = "notifications";
    public static final String SURFACE_KEY = "notifications.work";
    public static final String ACCESS_POLICY_KEY = "notifications.work-access.v1";
    public static final String ACTION_CAPABILITY_KEY = "notifications.work.inbox.use";
    public static final String OWNER_SERVICE = "dwp-notification-server";
    public static final String OWNER_SERVICE_KEY = "notification";

    public static final String CENTER_PAGE_ROUTE =
            "route.notifications.work.center.page";
    public static final String SUMMARY_DATA_ROUTE =
            "route.notifications.work.summary.data";
    public static final String READ_ACTION_ROUTE =
            "route.notifications.work.read.action";

    private static final String UUID_CANDIDATE_EXPRESSION = "([^/]+)";
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final List<Binding> bindings = List.of(
            new Binding(
                    CENTER_PAGE_ROUTE,
                    "PAGE",
                    "GET",
                    "/api/notifications/v1/inbox",
                    "/v1/inbox",
                    Pattern.compile("^/api/notifications/v1/inbox$"),
                    Pattern.compile("^/v1/inbox$"),
                    false),
            new Binding(
                    SUMMARY_DATA_ROUTE,
                    "DATA",
                    "GET",
                    "/api/notifications/v1/summary/by-app",
                    "/v1/summary/by-app",
                    Pattern.compile("^/api/notifications/v1/summary/by-app$"),
                    Pattern.compile("^/v1/summary/by-app$"),
                    false),
            new Binding(
                    READ_ACTION_ROUTE,
                    "ACTION",
                    "POST",
                    "/api/notifications/v1/inbox/{notificationId}/read",
                    "/v1/inbox/{notificationId}/read",
                    Pattern.compile("^/api/notifications/v1/inbox/"
                            + UUID_CANDIDATE_EXPRESSION + "/read$"),
                    Pattern.compile("^/v1/inbox/"
                            + UUID_CANDIDATE_EXPRESSION + "/read$"),
                    true));

    public Optional<ResolvedBinding> resolveOwner(String method, String path) {
        return resolve(method, path, false);
    }

    public Optional<ResolvedBinding> resolvePublic(String method, String path) {
        return resolve(method, path, true);
    }

    public boolean ownsOwnerCandidate(String method, String path) {
        return candidate(method, path, false).isPresent();
    }

    public Optional<String> ownerPathForPublicCandidate(String method, String path) {
        return candidate(method, path, true).map(candidate -> candidate.binding().notificationScoped()
                ? candidate.binding().ownerPath().replace(
                        "{notificationId}", candidate.routeParameter())
                : candidate.binding().ownerPath());
    }

    public List<BindingDescriptor> descriptors() {
        return bindings.stream().map(binding -> new BindingDescriptor(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.publicPath(), binding.ownerPath(), OWNER_SERVICE_KEY)).toList();
    }

    private Optional<ResolvedBinding> resolve(String method, String path, boolean publicRoute) {
        Optional<CandidateBinding> candidate = candidate(method, path, publicRoute);
        if (candidate.isEmpty()) return Optional.empty();
        CandidateBinding match = candidate.orElseThrow();
        if (!match.binding().notificationScoped()) {
            return Optional.of(new ResolvedBinding(match.binding(), null, null));
        }
        Optional<UUID> notificationId = canonicalUuid(match.routeParameter());
        return notificationId.map(uuid -> new ResolvedBinding(
                match.binding(), uuid, match.routeParameter()));
    }

    private Optional<CandidateBinding> candidate(
            String method,
            String path,
            boolean publicRoute) {
        if (method == null || path == null) return Optional.empty();
        for (Binding binding : bindings) {
            if (!binding.method().equals(method)) continue;
            Matcher matcher = (publicRoute ? binding.publicPattern() : binding.ownerPattern())
                    .matcher(path);
            if (!matcher.matches()) continue;
            String routeParameter = binding.notificationScoped() ? matcher.group(1) : null;
            return Optional.of(new CandidateBinding(binding, routeParameter));
        }
        return Optional.empty();
    }

    private Optional<UUID> canonicalUuid(String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value)
                    ? Optional.of(parsed) : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private record Binding(
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String ownerPath,
            Pattern publicPattern,
            Pattern ownerPattern,
            boolean notificationScoped) {
    }

    private record CandidateBinding(Binding binding, String routeParameter) {
    }

    public record ResolvedBinding(
            Binding binding,
            UUID notificationId,
            String routeParameter) {
        public String routeContractKey() {
            return binding.routeContractKey();
        }

        public String routeKind() {
            return binding.routeKind();
        }

        public String ownerPath() {
            return binding.notificationScoped()
                    ? binding.ownerPath().replace(
                            "{notificationId}", routeParameter)
                    : binding.ownerPath();
        }
    }

    public record BindingDescriptor(
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String ownerPath,
            String serviceKey) {
    }
}
