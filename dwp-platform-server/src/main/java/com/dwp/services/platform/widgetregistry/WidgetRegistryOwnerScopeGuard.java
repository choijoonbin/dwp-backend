package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
final class WidgetRegistryOwnerScopeGuard {
    static final String OWNER_SCOPE_HEADER = "X-DWP-Widget-Owner-Product-Keys";
    private static final String CONTROL_PLANE_HEADER = "X-DWP-Control-Plane";
    private static final Pattern OWNER_KEY = Pattern.compile(
            "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");

    private final WidgetDefinitionRepository definitions;
    private final WidgetDefinitionVersionRepository versions;

    WidgetRegistryOwnerScopeGuard(
            WidgetDefinitionRepository definitions,
            WidgetDefinitionVersionRepository versions) {
        this.definitions = definitions;
        this.versions = versions;
    }

    Optional<Set<String>> currentProviderOwners() {
        HttpServletRequest request = request();
        if (request == null || !"PROVIDER".equalsIgnoreCase(
                request.getHeader("X-DWP-Identity-Plane"))) {
            return Optional.empty();
        }
        if (!"WIDGET_REGISTRY_PROVIDER".equals(request.getHeader(CONTROL_PLANE_HEADER))) {
            throw forbidden();
        }
        List<String> headers = request.getHeaders(OWNER_SCOPE_HEADER) == null
                ? List.of() : Collections.list(request.getHeaders(OWNER_SCOPE_HEADER));
        if (headers.size() != 1) throw forbidden();
        Set<String> values = Arrays.stream(headers.getFirst().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (values.isEmpty()
                || values.size() > 32
                || values.stream().anyMatch(value -> value.length() > 120
                        || !OWNER_KEY.matcher(value).matches())) {
            throw forbidden();
        }
        return Optional.of(values);
    }

    void requireOwner(String ownerProductKey) {
        currentProviderOwners().ifPresent(owners -> {
            if (!owners.contains(ownerProductKey)) throw forbidden();
        });
    }

    void requireVersion(WidgetDefinitionVersion version) {
        WidgetDefinition definition = definitions.findById(version.getDefinitionId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireOwner(definition.getOwnerProductKey());
    }

    boolean allowsControl(WidgetRuntimeControl control) {
        Optional<Set<String>> owners = currentProviderOwners();
        if (owners.isEmpty()) return true;
        String owner = switch (control.getTargetType()) {
            case "PROVIDER" -> control.getProviderProductKey();
            case "DEFINITION" -> ownerForDefinition(control.getTargetId());
            case "VERSION" -> ownerForVersion(control.getTargetId());
            case "ACTION" -> control.getProviderProductKey();
            default -> null;
        };
        return owner != null && owners.get().contains(owner);
    }

    void requireControl(WidgetRuntimeControl control) {
        if (!allowsControl(control)) throw forbidden();
    }

    void requireDisableTarget(WidgetRegistryDtos.RuntimeDisableRequest request) {
        if (currentProviderOwners().isEmpty()) return;
        String owner = switch (request.targetType()) {
            case "PROVIDER" -> request.providerProductKey();
            case "DEFINITION" -> ownerForDefinition(request.targetId());
            case "VERSION" -> ownerForVersion(request.targetId());
            case "ACTION" -> request.providerProductKey();
            default -> null;
        };
        if (owner == null) throw forbidden();
        requireOwner(owner);
    }

    void requireAllKnownOwners() {
        currentProviderOwners().ifPresent(owners -> {
            Set<String> knownOwners = definitions.findAll().stream()
                    .map(WidgetDefinition::getOwnerProductKey)
                    .collect(Collectors.toUnmodifiableSet());
            if (!owners.containsAll(knownOwners)) throw forbidden();
        });
    }

    private String ownerForDefinition(String value) {
        UUID id = uuid(value);
        if (id == null) return null;
        return definitions.findById(id).map(WidgetDefinition::getOwnerProductKey).orElse(null);
    }

    private String ownerForVersion(String value) {
        UUID id = uuid(value);
        if (id == null) return null;
        return versions.findById(id)
                .flatMap(version -> definitions.findById(version.getDefinitionId()))
                .map(WidgetDefinition::getOwnerProductKey)
                .orElse(null);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return null;
        }
    }

    private static HttpServletRequest request() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "Provider Widget Registry owner scope does not permit this resource.");
    }
}
