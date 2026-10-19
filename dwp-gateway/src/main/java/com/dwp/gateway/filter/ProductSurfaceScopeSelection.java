package com.dwp.gateway.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Mechanical extraction of the existing selector parser; retained query bytes are unchanged. */
final class ProductSurfaceScopeSelection {
    static final String SCOPE_QUERY_PARAMETER = "contextScopeKey";
    private ProductSurfaceScopeSelection() { }

    static ScopeSelection scopeSelection(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return new ScopeSelection(false, true, null, null);
        }
        List<String> retained = new ArrayList<>();
        String selected = null;
        int occurrences = 0;
        try {
            for (String pair : rawQuery.split("&", -1)) {
                if (pair.isEmpty()) return ScopeSelection.invalid();
                int separator = pair.indexOf('=');
                String key = URLDecoder.decode(
                        separator < 0 ? pair : pair.substring(0, separator),
                        StandardCharsets.UTF_8);
                String value = URLDecoder.decode(
                        separator < 0 ? "" : pair.substring(separator + 1),
                        StandardCharsets.UTF_8);
                if (!SCOPE_QUERY_PARAMETER.equals(key)) {
                    retained.add(pair);
                    continue;
                }
                occurrences++;
                selected = value;
            }
        } catch (IllegalArgumentException exception) {
            return ScopeSelection.invalid();
        }
        if (occurrences == 0) {
            return new ScopeSelection(false, true, null, rawQuery);
        }
        if (occurrences != 1 || selected == null || selected.isBlank()
                || selected.length() > 200 || !selected.equals(selected.trim())
                || !selected.matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,199}")) {
            return ScopeSelection.invalid();
        }
        return new ScopeSelection(
                true, true, selected, retained.isEmpty() ? null : String.join("&", retained));
    }

    static ServerHttpRequest withoutScopeSelection(
            ServerHttpRequest request,
            ScopeSelection selection) {
        if (!selection.present()) return request;
        URI uri = request.getURI();
        String raw = uri.toString();
        int fragment = raw.indexOf('#');
        String suffix = fragment < 0 ? "" : raw.substring(fragment);
        String withoutFragment = fragment < 0 ? raw : raw.substring(0, fragment);
        int query = withoutFragment.indexOf('?');
        String base = query < 0 ? withoutFragment : withoutFragment.substring(0, query);
        String rebuilt = base + (selection.retainedRawQuery() == null
                ? "" : "?" + selection.retainedRawQuery()) + suffix;
        return request.mutate().uri(URI.create(rebuilt)).build();
    }

    record ScopeSelection(
            boolean present,
            boolean valid,
            String value,
            String retainedRawQuery) {

        static ScopeSelection invalid() {
            return new ScopeSelection(true, false, null, null);
        }
    }
}
