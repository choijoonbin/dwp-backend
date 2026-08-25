package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles the path and query constraints in the generated HCM People PEP. */
final class HcmPepBindingConstraints {

    private HcmPepBindingConstraints() {
    }

    record PathTemplate(Pattern pattern, String template) {
        static PathTemplate compile(String template, JsonNode constraints) {
            require(template != null && template.startsWith("/"),
                    "Generated HCM service path is invalid");
            StringBuilder expression = new StringBuilder("^");
            Matcher matcher = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}")
                    .matcher(template);
            List<String> parameters = new ArrayList<>();
            int offset = 0;
            while (matcher.find()) {
                expression.append(Pattern.quote(template.substring(offset, matcher.start())))
                        .append(parameterExpression(matcher.group(1), constraints));
                parameters.add(matcher.group(1));
                offset = matcher.end();
            }
            expression.append(Pattern.quote(template.substring(offset))).append('$');
            require(constraints == null || constraints.isObject(),
                    "Invalid generated HCM path constraints");
            if (constraints != null) constraints.fieldNames().forEachRemaining(name ->
                    require(parameters.contains(name),
                            "Unknown generated HCM path constraint " + name));
            return new PathTemplate(Pattern.compile(expression.toString()), template);
        }

        boolean matches(String path) {
            String normalized = path == null ? "" : path.toLowerCase(Locale.ROOT);
            return path != null && !normalized.contains("%2f") && !normalized.contains("%5c")
                    && !normalized.contains("%3b") && path.indexOf(';') < 0
                    && path.indexOf('\\') < 0 && !path.contains("//")
                    && !path.contains("/./") && !path.contains("/../")
                    && !path.endsWith("/.") && !path.endsWith("/..")
                    && pattern.matcher(path).matches();
        }

        boolean claims(String path) {
            return path != null && pattern.matcher(path).matches();
        }

        private static String parameterExpression(String parameter, JsonNode constraints) {
            JsonNode constraint = constraints == null ? null : constraints.get(parameter);
            if (constraint == null) return "([^/]+)";
            String kind = constraint.path("kind").asText();
            if ("FIXED".equals(kind)) {
                String value = constraint.path("value").asText();
                require(!value.isBlank(), "Empty generated HCM fixed path constraint");
                return "(" + Pattern.quote(value) + ")";
            }
            require("ALLOWLIST".equals(kind) && constraint.path("values").isArray()
                            && !constraint.path("values").isEmpty(),
                    "Invalid generated HCM path constraint");
            List<String> values = new ArrayList<>();
            constraint.path("values").forEach(value -> {
                require(value.isTextual() && !value.asText().isBlank(),
                        "Invalid generated HCM allowlist value");
                values.add(Pattern.quote(value.asText()));
            });
            return "(" + String.join("|", values) + ")";
        }
    }

    record QueryConstraints(Map<String, QueryConstraint> values) {
        static QueryConstraints compile(JsonNode constraints) {
            if (constraints == null || constraints.isMissingNode() || constraints.isNull()) {
                return new QueryConstraints(Map.of());
            }
            require(constraints.isObject(), "Invalid generated HCM query constraints");
            Map<String, QueryConstraint> result = new LinkedHashMap<>();
            constraints.properties().forEach(entry -> {
                String kind = entry.getValue().path("kind").asText();
                Set<String> allowed = new LinkedHashSet<>();
                if ("FIXED".equals(kind)) {
                    require(entry.getValue().path("value").isTextual(),
                            "Invalid HCM fixed query constraint");
                    allowed.add(entry.getValue().path("value").asText());
                } else if ("ALLOWLIST".equals(kind)) {
                    entry.getValue().path("values").forEach(value ->
                            require(value.isTextual() && allowed.add(value.asText()),
                                    "Invalid HCM query allowlist"));
                    require(!allowed.isEmpty(), "Empty HCM query allowlist");
                } else {
                    require("ABSENT".equals(kind), "Invalid HCM query constraint kind");
                }
                require(result.putIfAbsent(entry.getKey(),
                        new QueryConstraint(kind, Set.copyOf(allowed))) == null,
                        "Duplicate HCM query constraint");
            });
            return new QueryConstraints(Map.copyOf(result));
        }

        boolean matches(String rawQuery) {
            Map<String, List<String>> actual;
            try {
                actual = parse(rawQuery);
            } catch (RuntimeException exception) {
                return false;
            }
            for (Map.Entry<String, QueryConstraint> entry : values.entrySet()) {
                List<String> present = actual.getOrDefault(entry.getKey(), List.of());
                if ("ABSENT".equals(entry.getValue().kind())) {
                    if (!present.isEmpty()) return false;
                } else if (present.size() != 1
                        || !entry.getValue().allowed().contains(present.getFirst())) {
                    return false;
                }
            }
            return true;
        }

        private static Map<String, List<String>> parse(String rawQuery) {
            if (rawQuery == null || rawQuery.isBlank()) return Map.of();
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String pair : rawQuery.split("&", -1)) {
                require(!pair.isBlank(), "Empty HCM query element");
                int equals = pair.indexOf('=');
                String rawName = equals < 0 ? pair : pair.substring(0, equals);
                String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                require(!name.isBlank(), "Empty HCM query name");
                result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
            return result;
        }
    }

    private record QueryConstraint(String kind, Set<String> allowed) {
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
