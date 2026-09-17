package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@RestController
public class WorkplaceAssistantController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERSON = "X-DWP-Person-Public-ID";
    static final String DISPLAY = "X-DWP-Display-Name-B64";
    static final String GROUPS = "X-DWP-Group-Refs";
    static final String PERMISSIONS = "X-DWP-Permissions";
    static final String ACCESS_MODE = "X-DWP-Active-Access-Mode";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String CORRELATION = "X-Correlation-ID";
    static final String LOCALE = "Accept-Language";
    static final String VIEW = "APP.WORKPLACE:VIEW";
    static final String UPDATE = "APP.WORKPLACE:UPDATE";
    static final String ADMIN_VIEW = "ADMIN.WORKPLACE:VIEW";
    static final String ADMIN_MANAGE = "ADMIN.WORKPLACE:MANAGE";

    private final WorkplaceAssistantService service;

    public WorkplaceAssistantController(WorkplaceAssistantService service) {
        this.service = service;
    }

    @PostMapping("/v1/workplace/assistant/requests")
    public ResponseEntity<ApiResponse<AssistantCommandResult>> create(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = PERSON, required = false) UUID actorPersonPublicId,
            @RequestHeader(value = DISPLAY, required = false) String encodedDisplayName,
            @RequestHeader(value = GROUPS, required = false) String verifiedGroupRefs,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody CreateAssistantRequest request) {
        requirePermission(permissions, UPDATE);
        AssistantCommandResult result = service.create(tenantId, actorId,
                actorPersonPublicId, decodeDisplayName(encodedDisplayName), verifiedGroupRefs, locale,
                idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/v1/workplace/assistant/requests/{requestId}")
    public ApiResponse<AssistantRequest> request(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID requestId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.request(tenantId, actorId, requestId));
    }

    @PostMapping("/v1/workplace/assistant/requests/{requestId}:validate")
    public ApiResponse<AssistantCommandResult> validate(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = PERSON, required = false) UUID actorPersonPublicId,
            @RequestHeader(value = DISPLAY, required = false) String encodedDisplayName,
            @RequestHeader(value = GROUPS, required = false) String verifiedGroupRefs,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ValidateAssistantRequest request,
            HttpServletResponse response) {
        requirePermission(permissions, UPDATE);
        noStore(response);
        return ApiResponse.success(service.validate(tenantId, actorId,
                actorPersonPublicId, decodeDisplayName(encodedDisplayName), verifiedGroupRefs, locale,
                requestId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/v1/workplace/assistant/requests/{requestId}:confirm")
    public ResponseEntity<ApiResponse<AssistantCommandResult>> confirm(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = GROUPS, required = false) String verifiedGroupRefs,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody ConfirmAssistantRequest request) {
        requirePermission(permissions, UPDATE);
        AssistantCommandResult result = service.confirm(tenantId, actorId,
                verifiedGroupRefs, locale, requestId, idempotencyKey, correlationId, request);
        String href = "/v1/workplace/assistant/requests/" + requestId + "/execution";
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(href)).body(ApiResponse.success(result));
    }

    @GetMapping("/v1/workplace/assistant/requests/{requestId}/execution")
    public ApiResponse<AssistantExecution> execution(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @PathVariable UUID requestId,
            HttpServletResponse response) {
        requirePermission(permissions, VIEW);
        noStore(response);
        return ApiResponse.success(service.execution(tenantId, actorId, requestId));
    }

    @PostMapping("/v1/workplace/assistant/requests/{requestId}:feedback")
    public ResponseEntity<ApiResponse<FeedbackReceipt>> feedback(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody FeedbackRequest request) {
        requirePermission(permissions, UPDATE);
        FeedbackReceipt result = service.feedback(tenantId, actorId, requestId,
                idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create("/v1/workplace/assistant/requests/" + requestId))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/v1/admin/workplace/assistant/governance")
    public ApiResponse<AssistantGovernance> governance(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.governance(tenantId));
    }

    @PutMapping("/v1/admin/workplace/assistant/governance")
    public ResponseEntity<ApiResponse<GovernanceCommandResult>> updateGovernance(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestHeader(ACCESS_MODE) String accessMode,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody GovernanceUpdateRequest request) {
        requirePermission(permissions, ADMIN_MANAGE);
        requireElevated(accessMode);
        GovernanceCommandResult result = service.updateGovernance(
                tenantId, actorId, idempotencyKey, correlationId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.receipt().statusHref()))
                .body(ApiResponse.success(result));
    }

    @GetMapping("/v1/admin/workplace/assistant/audit-events")
    public ApiResponse<AuditEvents> auditEvents(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(PERMISSIONS) String permissions,
            @RequestParam(required = false) UUID requestId,
            @RequestParam(required = false) Integer limit,
            HttpServletResponse response) {
        requirePermission(permissions, ADMIN_VIEW);
        noStore(response);
        return ApiResponse.success(service.auditEvents(tenantId, requestId, limit));
    }

    static void requirePermission(String raw, String required) {
        Set<String> values = Arrays.stream(raw == null ? new String[0] : raw.split("[,\\s]+"))
                .filter(value -> !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
        if (!values.contains(required)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The required Workplace Assistant permission is missing.");
        }
    }

    static void requireElevated(String accessMode) {
        if (!"ELEVATED".equalsIgnoreCase(accessMode)) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "Active elevated access is required for Assistant governance changes.");
        }
    }

    static String decodeDisplayName(String encoded) {
        if (encoded == null) return null;
        String value = encoded.trim();
        if (value.isEmpty() || value.length() > 512) {
            throw invalidDisplayName();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (bytes.length == 0 || bytes.length > 480) throw invalidDisplayName();
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().trim();
            if (decoded.isEmpty() || decoded.codePointCount(0, decoded.length()) > 160
                    || decoded.codePoints().anyMatch(Character::isISOControl)) {
                throw invalidDisplayName();
            }
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw invalidDisplayName();
        }
    }

    private static BaseException invalidDisplayName() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "The trusted display-name header is invalid.");
    }

    static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }
}
