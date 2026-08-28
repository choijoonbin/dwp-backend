package com.dwp.services.messaging.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact public-to-owner bindings for the Messaging v4 Product Surface candidate. */
@Component
public final class MessagingProductSurfaceContract {

    public static final String PRODUCT_KEY = "messaging";
    public static final String SURFACE_KEY = "messaging.work";
    public static final String ACCESS_POLICY_KEY = "messaging.work-access.v1";
    public static final String MESSAGE_CREATE_CAPABILITY_KEY =
            "messaging.work.message.create";
    public static final String OWNER_SERVICE_KEY = "messaging";

    public static final String HOME_PAGE_ROUTE = "route.messaging.work.home.page";
    public static final String CONVERSATION_MESSAGES_DATA_ROUTE =
            "route.messaging.work.conversation-messages.data";
    public static final String MESSAGE_SEND_ACTION_ROUTE =
            "route.messaging.work.message-send.action";

    private static final String UUID_EXPRESSION =
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{12})";
    private static final Pattern OWNER_CONVERSATION_MESSAGES_CANDIDATE =
            Pattern.compile("^/v1/conversations/[^/]+/messages$");

    private final List<Binding> bindings = List.of(
            new Binding(
                    HOME_PAGE_ROUTE,
                    "PAGE",
                    "GET",
                    "/api/messaging/v1/home",
                    "/v1/home",
                    Pattern.compile("^/api/messaging/v1/home$"),
                    Pattern.compile("^/v1/home$"),
                    "VIEW",
                    false),
            new Binding(
                    CONVERSATION_MESSAGES_DATA_ROUTE,
                    "DATA",
                    "GET",
                    "/api/messaging/v1/conversations/{conversationId}/messages",
                    "/v1/conversations/{conversationId}/messages",
                    Pattern.compile("^/api/messaging/v1/conversations/" + UUID_EXPRESSION
                            + "/messages$"),
                    Pattern.compile("^/v1/conversations/" + UUID_EXPRESSION + "/messages$"),
                    "VIEW",
                    true),
            new Binding(
                    MESSAGE_SEND_ACTION_ROUTE,
                    "ACTION",
                    "POST",
                    "/api/messaging/v1/conversations/{conversationId}/messages",
                    "/v1/conversations/{conversationId}/messages",
                    Pattern.compile("^/api/messaging/v1/conversations/" + UUID_EXPRESSION
                            + "/messages$"),
                    Pattern.compile("^/v1/conversations/" + UUID_EXPRESSION + "/messages$"),
                    "CREATE",
                    true));

    public Optional<ResolvedBinding> resolveOwner(String method, String path) {
        return resolve(method, path, false);
    }

    public Optional<ResolvedBinding> resolvePublic(String method, String path) {
        return resolve(method, path, true);
    }

    public boolean ownsOwner(String method, String path) {
        if (method == null || path == null) return false;
        if ("GET".equals(method) && "/v1/home".equals(path)) return true;
        return ("GET".equals(method) || "POST".equals(method))
                && OWNER_CONVERSATION_MESSAGES_CANDIDATE.matcher(path).matches();
    }

    public List<BindingDescriptor> descriptors() {
        return bindings.stream().map(binding -> new BindingDescriptor(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.publicPath(), binding.ownerPath(), binding.requiredAction())).toList();
    }

    private Optional<ResolvedBinding> resolve(String method, String path, boolean publicRoute) {
        if (method == null || path == null) return Optional.empty();
        for (Binding binding : bindings) {
            if (!binding.method().equals(method)) continue;
            Matcher matcher = (publicRoute ? binding.publicPattern() : binding.ownerPattern())
                    .matcher(path);
            if (!matcher.matches()) continue;
            UUID conversationId = binding.conversationScoped()
                    ? UUID.fromString(matcher.group(1)) : null;
            return Optional.of(new ResolvedBinding(
                    binding.routeContractKey(),
                    binding.routeKind(),
                    binding.requiredAction(),
                    conversationId,
                    binding.ownerPath(),
                    binding.conversationScoped()));
        }
        return Optional.empty();
    }

    record Binding(
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String ownerPath,
            Pattern publicPattern,
            Pattern ownerPattern,
            String requiredAction,
            boolean conversationScoped) {
    }

    public record ResolvedBinding(
            String routeContractKey,
            String routeKind,
            String requiredAction,
            UUID conversationId,
            String ownerPathTemplate,
            boolean conversationScoped) {
        public String ownerPath() {
            return conversationScoped
                    ? ownerPathTemplate.replace("{conversationId}", conversationId.toString())
                    : ownerPathTemplate;
        }
    }

    public record BindingDescriptor(
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String ownerPath,
            String requiredAction) {
    }
}
