package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.AccessSeal;

@Validated
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.controlled-transfer.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class ControlledTransferController {

    private static final String PATH = "/v1/admin/hris/file-transfers";
    private static final Set<String> QUERY_PARAMETERS = Set.of(
            "asOf", "fields", "freshness", "filter", "cursor", "limit");
    private static final int MAX_COMMAND_BODY_BYTES = 65_536;
    private final ControlledTransferService service;
    private final ObjectMapper strictJson;

    ControlledTransferController(
            ControlledTransferService service, ObjectMapper objectMapper) {
        this.service = service;
        this.strictJson = objectMapper.copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping(PATH)
    ResponseEntity<CatalogResponse> catalog(
            HttpServletRequest request,
            @RequestParam OffsetDateTime asOf,
            @RequestParam List<String> fields,
            @RequestParam Freshness freshness,
            @RequestParam(required = false) @Size(min = 1, max = 1000) String filter,
            @RequestParam(required = false) @Size(min = 16, max = 2048) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        requireClosedQuery(request);
        single(request, "X-Correlation-ID", true);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.catalog(access(request, false), asOf, fields, freshness,
                        filter, cursor, limit));
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Receipt> create(
            HttpServletRequest request,
            @RequestBody byte[] body) {
        requireNoQuery(request);
        FileTransferCommand command = strictCommand(body);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service.create(access(request, true), command));
    }

    private AccessSeal access(HttpServletRequest request, boolean mutation) {
        Object value = request.getAttribute(
                HcmPlatformPepRegistry.class.getName() + ".authority");
        if (!(value instanceof HcmPlatformPepRegistry.RouteAuthority authority)
                || !request.getMethod().equals(authority.method())
                || !PATH.equals(authority.servicePath())
                || !(mutation ? "ACTION".equals(authority.routeKind())
                : "DATA".equals(authority.routeKind()))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Exact controlled-transfer route authority is required");
        }
        long tenantId = positiveLong(single(request, "X-DWP-Tenant-ID", true));
        UUID subject = uuid(single(
                request, "X-DWP-Subject-Principal-Public-ID", true));
        String populationKind = single(request, "X-DWP-Population-Kind", true);
        if (!Set.of("CONNECTOR_SET", "LEGAL_ENTITY").contains(populationKind)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Verified controlled-transfer population is required");
        }
        String contextKey = single(request, "X-DWP-Context-Key", true);
        String contextScopeKey = single(
                request, "X-DWP-Context-Scope-Key", true);
        String decisionRevision = single(
                request, "X-DWP-Current-Decision-Revision", true);
        String purpose = single(request, "X-Purpose-Code", true);
        if (!ControlledTransferService.PURPOSE.equals(purpose)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Verified controlled-transfer purpose is required");
        }
        long fieldPolicyRevision = positiveLong(single(
                request, "X-DWP-Field-Policy-Revision", true));
        long authorizationRevision = positiveLong(single(
                request, "X-DWP-Authorization-Revision", true));
        OffsetDateTime freshUntil = instant(single(
                request, "X-DWP-Current-Revalidate-At", true));
        Set<String> fieldGroups = projectionFieldGroups(request, mutation);
        String populationDigest = ControlledTransferService.populationScopeDigest(
                tenantId, subject, populationKind, contextScopeKey, decisionRevision);
        String authorityDigest = ControlledTransferService.authorityDigest(
                tenantId, subject, authority.routeContractKey(), contextKey,
                contextScopeKey, populationKind, populationDigest, fieldGroups,
                fieldPolicyRevision, authorizationRevision, decisionRevision,
                purpose, freshUntil);
        String idempotencyKey = single(request, "Idempotency-Key", mutation);
        if (mutation && (idempotencyKey.length() < 16
                || idempotencyKey.length() > 128)) {
            throw invalid("Idempotency-Key must contain 16 through 128 characters");
        }
        return new AccessSeal(
                tenantId, subject, populationDigest, populationKind, fieldGroups,
                fieldPolicyRevision, authorizationRevision, authority.routeContractKey(),
                contextKey, contextScopeKey, decisionRevision, purpose,
                authorityDigest, freshUntil,
                uuid(single(request, "X-Correlation-ID", true)),
                idempotencyKey);
    }

    private void requireClosedQuery(HttpServletRequest request) {
        if (!QUERY_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            throw invalid("Unknown controlled-transfer query parameter");
        }
        for (String scalar : Set.of(
                "asOf", "freshness", "filter", "cursor", "limit")) {
            String[] values = request.getParameterMap().get(scalar);
            if (values != null && values.length != 1) {
                throw invalid(scalar + " must occur at most once");
            }
        }
        String[] fields = request.getParameterMap().get("fields");
        if (fields == null || fields.length == 0 || fields.length > 64) {
            throw invalid("fields is required and bounded");
        }
    }

    private void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw invalid("Controlled-transfer command does not accept query parameters");
        }
    }

    private FileTransferCommand strictCommand(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_COMMAND_BODY_BYTES) {
            throw invalid("Controlled-transfer command body is required and bounded");
        }
        try {
            return strictJson.readValue(body, FileTransferCommand.class);
        } catch (IOException | RuntimeException exception) {
            throw invalid("Controlled-transfer command body is invalid");
        }
    }

    private Set<String> projectionFieldGroups(
            HttpServletRequest request, boolean mutation) {
        Set<String> allowed = mutation
                ? ControlledTransferService.CREATE_FIELD_GROUPS
                : ControlledTransferService.READ_FIELD_GROUPS;
        Set<String> masked = tokens(
                request, "X-DWP-Masked-Field-Groups", false, allowed.size());
        Set<String> omitted = tokens(
                request, "X-DWP-Omitted-Field-Groups", false, allowed.size());
        if (!allowed.containsAll(masked) || !allowed.containsAll(omitted)
                || !Collections.disjoint(masked, omitted)
                || (mutation && (!masked.isEmpty() || !omitted.isEmpty()))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Verified controlled-transfer field projection is required");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(allowed);
        result.removeAll(omitted);
        return Set.copyOf(result);
    }

    private Set<String> tokens(
            HttpServletRequest request, String name, boolean required, int maximum) {
        String value = single(request, name, required);
        if (value == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.strip())
                    || !result.add(token) || result.size() > maximum) {
                throw new BaseException(ErrorCode.FORBIDDEN,
                        "Verified controlled-transfer field projection is malformed");
            }
        }
        return Set.copyOf(result);
    }

    private OffsetDateTime instant(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Verified controlled-transfer freshness is required");
        }
    }

    private String single(HttpServletRequest request, String name, boolean required) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.isEmpty() && !required) return null;
        if (values.size() != 1) throw invalid(name + " must occur exactly once");
        String value = values.getFirst();
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 2048 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            throw invalid(name + " is invalid");
        }
        return value;
    }

    private long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) {
        }
        throw new BaseException(ErrorCode.FORBIDDEN,
                "Verified controlled-transfer numeric authority is required");
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Verified controlled-transfer UUID authority is required");
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

}
