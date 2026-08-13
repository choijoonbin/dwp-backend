package com.dwp.services.platform.search;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SearchAuditService {

    private static final Set<String> SOURCES = Set.of(
            "APPS", "WORK", "PEOPLE", "ORGANIZATIONS", "TENANT_AUDIT",
            "TENANT_CATALOG", "PROVIDER_TENANTS", "PROVIDER_AUDIT", "PROVIDER_CATALOG");
    private static final Set<String> KINDS = Set.of(
            "APP", "WORK", "PERSON", "ORGANIZATION", "AUDIT", "TENANT", "CATALOG", "ASK");

    private final AuditOutboxRecorder outboxRecorder;

    public SearchAuditService(AuditOutboxRecorder outboxRecorder) {
        this.outboxRecorder = outboxRecorder;
    }

    @Transactional
    public SearchAuditDtos.AuditReceipt record(
            Long tenantId,
            Long actorId,
            String rolesHeader,
            String correlationId,
            SearchAuditDtos.AuditRequest request) {
        String phase = request.phase().trim().toUpperCase(Locale.ROOT);
        String query = request.query().trim();
        if (query.length() < 2) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Search query is too short.");
        }
        List<String> sources = normalizeSources(request.sources());
        String selectedKind = normalizeCode(request.selectedKind());
        String selectedId = trimOptional(request.selectedId());
        if ("SELECTION".equals(phase)
                && (selectedKind == null || !KINDS.contains(selectedKind) || selectedId == null)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A supported result kind and selected result are required.");
        }

        String digest = sha256(query.toLowerCase(Locale.ROOT));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("queryDigest", digest);
        metadata.put("queryLength", query.codePointCount(0, query.length()));
        metadata.put("sources", sources);
        metadata.put("resultCount", request.resultCount() == null ? 0 : request.resultCount());
        if (selectedKind != null) metadata.put("selectedKind", selectedKind);

        UUID eventId = outboxRecorder.record(AuditEvent.builder()
                .tenantId(tenantId)
                .category("DATA_ACCESS")
                .action("QUERY".equals(phase)
                        ? "workspace.search.executed"
                        : "workspace.search.result-selected")
                .outcome("SUCCESS")
                .actorType("USER")
                .actorId(actorId.toString())
                .actorRoles(parseRoles(rolesHeader))
                .sourceService("dwp-platform-server")
                .sourceModule("global-search")
                .targetType("QUERY".equals(phase) ? "GLOBAL_SEARCH_QUERY" : selectedKind)
                .targetId("QUERY".equals(phase) ? digest : selectedId)
                .correlationId(trimOptional(correlationId))
                .metadata(metadata)
                .retentionClass("STANDARD")
                .build());
        return new SearchAuditDtos.AuditReceipt(eventId.toString(), digest);
    }

    private List<String> normalizeSources(List<String> values) {
        if (values == null) return List.of();
        List<String> normalized = values.stream()
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (!SOURCES.containsAll(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Search source is not supported.");
        }
        return normalized;
    }

    private List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) return List.of();
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
