package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

@Validated
@RestController
@ConditionalOnProperty(name = "dwp.hris.explorer.enabled", havingValue = "true")
final class HrisExplorerController {

    private static final String IDEMPOTENCY = "Idempotency-Key";
    private static final String IF_MATCH = "If-Match";
    private static final String CORRELATION = "X-Correlation-ID";

    private final HrisExplorerService service;

    HrisExplorerController(HrisExplorerService service) {
        this.service = service;
    }

    @GetMapping("/v1/hris/explorer")
    ResponseEntity<HrisExplorerResponse> explorer(
            HttpServletRequest request,
            @RequestParam OffsetDateTime asOf,
            @RequestParam Freshness freshness,
            @RequestParam(required = false) @Size(min = 1, max = 200) String filter,
            @RequestParam(required = false) @Size(min = 1, max = 2048) String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        HrisExplorerService.ExplorerResult result = service.explorer(
                snapshot(request), asOf, freshness, filter, cursor, limit);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, result.etag())
                .body(result.response());
    }

    @PutMapping("/v1/hris/explorer/preferences")
    ResponseEntity<HrisExplorerReceipt> replacePreferences(
            HttpServletRequest request,
            @Valid @RequestBody ExplorerPreferenceCommand command) {
        HrisExplorerService.CommandResult result = service.replacePreferences(
                snapshot(request), idempotency(request), single(request, IF_MATCH, true),
                correlation(request), command);
        ResponseEntity.BodyBuilder response = ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore());
        if (result.etag() != null) response.header(HttpHeaders.ETAG, result.etag());
        return response.body(result.receipt());
    }

    @PostMapping("/v1/hris/explorer/recents")
    ResponseEntity<HrisExplorerReceipt> recordRecent(
            HttpServletRequest request,
            @Valid @RequestBody ExplorerRecentCommand command) {
        HrisExplorerService.CommandResult result = service.recordRecent(
                snapshot(request), idempotency(request), correlation(request), command);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(result.receipt());
    }

    private AuthoritySnapshot snapshot(HttpServletRequest request) {
        Object value = request.getAttribute(
                HrisExplorerOwnerEvidenceAdapter.SNAPSHOT_ATTRIBUTE);
        if (value instanceof AuthoritySnapshot snapshot) return snapshot;
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Verified Explorer owner evidence is required");
    }

    private String idempotency(HttpServletRequest request) {
        String value = single(request, IDEMPOTENCY, true);
        if (value.length() < 16 || value.length() > 128) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Idempotency-Key must contain 16 through 128 characters");
        }
        return value;
    }

    private UUID correlation(HttpServletRequest request) {
        String value = single(request, CORRELATION, false);
        if (value == null) return UUID.randomUUID();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "X-Correlation-ID must be a UUID");
        }
    }

    private String single(HttpServletRequest request, String name, boolean required) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    name + " must occur exactly once");
        }
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 2048 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    name + " is invalid");
        }
        return value;
    }

}
