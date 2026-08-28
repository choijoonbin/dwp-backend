package com.dwp.services.space.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owner-service consumer for the proposed Spaces v4 DRAFT route projection. */
@Component
public final class SpaceProductSurfacePepRegistry {

    public static final String POLICY_ID = "P-SPACES";
    public static final String PRODUCT_ID = "spaces";
    public static final String OWNER_SERVICE = "dwp-space-server";
    public static final String SERVICE_KEY = "space";

    static final String RESOURCE =
            "product-authorization/spaces-space-pep-v4.draft.json";

    private final List<Binding> bindings;

    public SpaceProductSurfacePepRegistry(ObjectMapper objectMapper) {
        JsonNode projection = read(objectMapper);
        validateEnvelope(projection);
        this.bindings = compile(projection);
        validateClosure();
    }

    public boolean ownsOwner(String method, String path) {
        return method != null && path != null && bindings.stream()
                .anyMatch(binding -> binding.method().equals(method)
                        && binding.pathPattern().matcher(path).matches());
    }

    public Decision authorize(
            String trustedRouteContractKey,
            String method,
            String path,
            Set<String> permissions) {
        List<Binding> matches = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(trustedRouteContractKey))
                .filter(binding -> binding.method().equals(method))
                .filter(binding -> binding.pathPattern().matcher(path).matches())
                .toList();
        if (matches.size() != 1) return Decision.denied();
        Binding binding = matches.getFirst();
        if (binding.permissions().stream().noneMatch(permissions::contains)) {
            return Decision.denied();
        }
        return Decision.allowed(binding);
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                POLICY_ID, PRODUCT_ID, binding.surfaceKey(),
                binding.authorityType(), binding.authorityKey(),
                OWNER_SERVICE, SERVICE_KEY,
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                "/api/spaces" + binding.servicePath(), binding.servicePath(),
                binding.permissions(), !"ACTION".equals(binding.routeKind()))).toList();
    }

    private JsonNode read(ObjectMapper objectMapper) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Spaces v4 DRAFT PEP projection is absent.");
            }
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Spaces v4 DRAFT PEP projection cannot be read.", exception);
        }
    }

    private void validateEnvelope(JsonNode projection) {
        require(projection.path("schemaVersion").asInt() == 1,
                "Unsupported Spaces PEP projection schema.");
        require("spaces-space-pep-v4-draft".equals(
                        projection.path("projectionKey").asText()),
                "Unexpected Spaces PEP projection key.");
        JsonNode registry = projection.path("registryRef");
        require("product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 4
                        && "DRAFT".equals(registry.path("bundleStatus").asText()),
                "Spaces PEP must remain bound to the v4 DRAFT contract.");
        require("space".equals(projection.path("ownerServiceKey").asText())
                        && "spaces".equals(projection.path("productKey").asText()),
                "Spaces PEP owner or product changed.");
    }

    private List<Binding> compile(JsonNode projection) {
        List<Binding> compiled = new ArrayList<>();
        for (JsonNode route : projection.path("routes")) {
            String routeKey = requiredText(route, "routeContractKey");
            String routeKind = requiredText(route, "routeKind");
            String surfaceKey = requiredText(route, "surfaceKey");
            JsonNode requiredAccess = route.path("requiredAccess");
            String authorityType = requiredText(requiredAccess, "type");
            String authorityKey = requiredText(requiredAccess, "key");
            require(Set.of("PAGE", "DATA", "ACTION").contains(routeKind),
                    routeKey + ": unsupported route kind.");
            require(Set.of("spaces.work", "spaces.management").contains(surfaceKey),
                    routeKey + ": unsupported surface.");
            require(("ACTION".equals(routeKind) && "CAPABILITY".equals(authorityType))
                            || (!"ACTION".equals(routeKind)
                            && "POLICY".equals(authorityType)),
                    routeKey + ": required access type does not match route kind.");
            for (JsonNode binding : route.path("bindings")) {
                String method = requiredText(binding, "method");
                String servicePath = requiredText(binding, "servicePath");
                String permission = requiredText(binding, "permission");
                require(servicePath.startsWith("/v1/"),
                        routeKey + ": binding escaped Spaces ownership.");
                compiled.add(new Binding(
                        routeKey, routeKind, surfaceKey, authorityType, authorityKey,
                        method, servicePath,
                        pathPattern(servicePath), Set.of(permission.split("\\|"))));
            }
        }
        return List.copyOf(compiled);
    }

    private void validateClosure() {
        require(!bindings.isEmpty(), "Spaces PEP projection has no bindings.");
        require(bindings.stream().map(Binding::routeContractKey).collect(
                        java.util.stream.Collectors.toSet()).equals(Set.of(
                        "route.spaces.work.home.page",
                        "route.spaces.work.content.data",
                        "route.spaces.work.request-create.action")),
                "Spaces PEP route set drifted from the v4 DRAFT contract.");
        require(bindings.stream().map(Binding::routeKind).collect(
                        java.util.stream.Collectors.toSet())
                        .equals(Set.of("PAGE", "DATA", "ACTION")),
                "Spaces PEP must close PAGE, DATA and ACTION.");
        Set<String> methodPaths = new LinkedHashSet<>();
        for (Binding binding : bindings) {
            require(expectedAuthority(binding).equals(binding.authorityKey()),
                    "Spaces PEP authority drifted for " + binding.routeContractKey());
            require(methodPaths.add(binding.method() + " " + binding.servicePath()),
                    "Duplicate Spaces binding " + binding.method() + " "
                            + binding.servicePath());
        }
    }

    private String expectedAuthority(Binding binding) {
        return "ACTION".equals(binding.routeKind())
                ? "spaces.request.create"
                : "spaces.work-access.v1";
    }

    private Pattern pathPattern(String template) {
        Matcher matcher = Pattern.compile("\\{[A-Za-z][A-Za-z0-9]*}").matcher(template);
        StringBuilder expression = new StringBuilder("^");
        int offset = 0;
        while (matcher.find()) {
            expression.append(Pattern.quote(template.substring(offset, matcher.start())))
                    .append("[A-Za-z0-9_-]+");
            offset = matcher.end();
        }
        expression.append(Pattern.quote(template.substring(offset))).append('$');
        return Pattern.compile(expression.toString());
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        require(value != null && !value.isBlank() && value.equals(value.trim()),
                "Spaces PEP field " + field + " is missing or non-canonical.");
        return value;
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Binding(
            String routeContractKey,
            String routeKind,
            String surfaceKey,
            String authorityType,
            String authorityKey,
            String method,
            String servicePath,
            Pattern pathPattern,
            Set<String> permissions) {
    }

    public record BindingContract(
            String policyId,
            String productId,
            String surfaceKey,
            String authorityType,
            String authorityKey,
            String ownerService,
            String serviceKey,
            String routeContractKey,
            String routeKind,
            String method,
            String publicPath,
            String servicePath,
            Set<String> resolvedAuthorities,
            boolean readOnly) {
    }

    public record Decision(boolean allowed, Binding binding) {
        static Decision allowed(Binding binding) {
            return new Decision(true, binding);
        }

        static Decision denied() {
            return new Decision(false, null);
        }
    }
}
