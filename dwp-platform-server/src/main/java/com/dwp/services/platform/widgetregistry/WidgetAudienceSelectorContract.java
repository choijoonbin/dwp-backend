package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed, expression-free audience selector shared by policy writes and catalog evaluation. */
final class WidgetAudienceSelectorContract {
    private static final Set<String> FIELDS =
            Set.of("schemaVersion", "mode", "roleCodes", "groupRefs");
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");

    private WidgetAudienceSelectorContract() {}

    static Audience requireValid(JsonNode value) {
        if (value == null || !value.isObject()
                || value.path("schemaVersion").asInt(-1) != 1
                || !FIELDS.equals(fieldNames(value))) {
            throw invalid();
        }
        String mode = value.path("mode").textValue();
        if (!Set.of("ALL_ENTITLED", "ANY_OF", "ALL_OF").contains(mode)) throw invalid();
        List<String> roles = strings(value.path("roleCodes"), true);
        List<String> groups = strings(value.path("groupRefs"), false);
        int count = roles.size() + groups.size();
        boolean validCount = switch (mode) {
            case "ALL_ENTITLED" -> count == 0;
            case "ANY_OF" -> count >= 1 && count <= 100;
            case "ALL_OF" -> count >= 1 && count <= 20;
            default -> false;
        };
        if (!validCount) throw invalid();
        return new Audience(mode, Set.copyOf(roles), Set.copyOf(groups));
    }

    static boolean matches(JsonNode value, Set<String> subjectRoles, Set<String> subjectGroups) {
        final Audience audience;
        try {
            audience = requireValid(value);
        } catch (BaseException exception) {
            return false;
        }
        return switch (audience.mode()) {
            case "ALL_ENTITLED" -> true;
            case "ANY_OF" -> audience.roles().stream().anyMatch(subjectRoles::contains)
                    || audience.groups().stream().anyMatch(subjectGroups::contains);
            case "ALL_OF" -> subjectRoles.containsAll(audience.roles())
                    && subjectGroups.containsAll(audience.groups());
            default -> false;
        };
    }

    private static List<String> strings(JsonNode node, boolean roles) {
        if (!node.isArray()) throw invalid();
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            String value = entry.textValue();
            if (value == null || value.isBlank() || !Normalizer.isNormalized(value, Normalizer.Form.NFC)
                    || (roles ? !ROLE.matcher(value).matches() : invalidGroup(value))) {
                throw invalid();
            }
            values.add(value);
        }
        if (!values.equals(values.stream().sorted().toList())
                || values.size() != new HashSet<>(values).size()) {
            throw invalid();
        }
        return List.copyOf(values);
    }

    private static boolean invalidGroup(String value) {
        if (value.length() > 128) return true;
        return value.codePoints().anyMatch(Character::isWhitespace)
                || value.codePoints().anyMatch(Character::isISOControl);
    }

    private static Set<String> fieldNames(JsonNode value) {
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "Audience selector must be canonical AudienceSelectorV1.");
    }

    record Audience(String mode, Set<String> roles, Set<String> groups) {}
}
