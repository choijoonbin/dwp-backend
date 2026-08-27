package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationModels.ActionResult;
import com.dwp.services.notification.domain.NotificationModels.BulkActionRequest;
import com.dwp.services.notification.domain.NotificationModels.BulkResult;
import com.dwp.services.notification.domain.NotificationModels.Capabilities;
import com.dwp.services.notification.domain.NotificationModels.DeliveryProfile;
import com.dwp.services.notification.domain.NotificationModels.DeliveryProfileUpdate;
import com.dwp.services.notification.domain.NotificationModels.Detail;
import com.dwp.services.notification.domain.NotificationModels.EffectiveSettings;
import com.dwp.services.notification.domain.NotificationModels.InboxPage;
import com.dwp.services.notification.domain.NotificationModels.SnoozeRequest;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRule;
import com.dwp.services.notification.domain.NotificationModels.SubscriptionRuleUpdate;
import com.dwp.services.notification.domain.NotificationModels.Summary;
import com.dwp.services.notification.domain.NotificationModels.SyncResponse;
import com.dwp.services.notification.domain.NotificationModels.TargetResolution;
import com.dwp.services.notification.domain.NotificationModels.VersionRequest;
import com.dwp.services.notification.domain.NotificationService;
import com.dwp.services.notification.realtime.NotificationStreamService;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@Validated
@RestController
@RequestMapping("/v1")
public class NotificationController {

    private final NotificationService service;
    private final NotificationStreamService streamService;

    public NotificationController(
            NotificationService service,
            NotificationStreamService streamService) {
        this.service = service;
        this.streamService = streamService;
    }

    @GetMapping("/summary")
    public ApiResponse<Summary> summary() {
        return ApiResponse.success(service.summary(actor()));
    }

    @GetMapping("/capabilities")
    public ApiResponse<Capabilities> capabilities() {
        return ApiResponse.success(new Capabilities(
                List.of("IN_APP"),
                List.of("EMAIL", "WEB_PUSH", "MOBILE_PUSH", "TEAMS", "SLACK"),
                "POSTGRESQL",
                "SSE_HINT_WITH_DURABLE_SYNC",
                "DISABLED",
                Instant.now()));
    }

    @GetMapping("/inbox")
    public ApiResponse<InboxPage> inbox(
            @RequestParam(defaultValue = "PRIORITY") String view,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String appKey,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String readState,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(service.inbox(
                actor(), view, limit, cursor, query, appKey, priority, readState, reason, from, to));
    }

    @GetMapping("/inbox/{notificationId}")
    public ApiResponse<Detail> detail(@PathVariable UUID notificationId) {
        return ApiResponse.success(service.detail(actor(), notificationId));
    }

    @GetMapping("/inbox/{notificationId}/target")
    public ApiResponse<TargetResolution> target(@PathVariable UUID notificationId) {
        return ApiResponse.success(service.resolveTarget(actor(), notificationId));
    }

    @GetMapping("/sync")
    public ApiResponse<SyncResponse> sync(
            @RequestParam(required = false) String after,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.sync(actor(), after, limit));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) UUID clientId) {
        NotificationRequestContext.Actor actor = actor();
        String cursor = after == null || after.isBlank() ? lastEventId : after;
        service.validateSyncCursor(actor, cursor);
        return streamService.open(
                actor,
                cursor,
                clientId,
                (pageAfter, limit) -> service.sync(actor, pageAfter, limit));
    }

    @PostMapping("/inbox/{notificationId}/read")
    public ApiResponse<ActionResult> read(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        return action(notificationId, "READ", positive(
                request.expectedVersion(), "expectedVersion"), null, idempotencyKey);
    }

    @PostMapping("/inbox/{notificationId}/unread")
    public ApiResponse<ActionResult> unread(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        return action(notificationId, "UNREAD", positive(
                request.expectedVersion(), "expectedVersion"), null, idempotencyKey);
    }

    @PostMapping("/inbox/{notificationId}/save")
    public ApiResponse<ActionResult> save(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        return action(notificationId, "SAVE", positive(
                request.expectedVersion(), "expectedVersion"), null, idempotencyKey);
    }

    @PostMapping("/inbox/{notificationId}/complete")
    public ApiResponse<ActionResult> complete(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        return action(notificationId, "COMPLETE", positive(
                request.expectedVersion(), "expectedVersion"), null, idempotencyKey);
    }

    @PostMapping("/inbox/{notificationId}/unsave")
    public ApiResponse<ActionResult> unsave(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        return action(notificationId, "UNSAVE", positive(
                request.expectedVersion(), "expectedVersion"), null, idempotencyKey);
    }

    @PostMapping("/inbox/{notificationId}/restore")
    public ApiResponse<ActionResult> restore(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody VersionRequest request) {
        return action(notificationId, "RESTORE", positive(
                request.expectedVersion(), "expectedVersion"), null, idempotencyKey);
    }

    @PostMapping("/inbox/{notificationId}/snooze")
    public ApiResponse<ActionResult> snooze(
            @PathVariable UUID notificationId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SnoozeRequest request) {
        return action(
                notificationId,
                "SNOOZE",
                positive(request.expectedVersion(), "expectedVersion"),
                request.snoozedUntil(),
                idempotencyKey);
    }

    @PostMapping("/inbox/bulk-actions")
    public ApiResponse<BulkResult> bulkActions(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody BulkActionRequest request) {
        return ApiResponse.success(service.bulkMutate(actor(), request, idempotencyKey));
    }

    @PostMapping("/inbox/bulk-actions/{undoToken}/undo")
    public ApiResponse<BulkResult> undoBulkAction(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID undoToken) {
        return ApiResponse.success(service.undoBulk(actor(), undoToken, idempotencyKey));
    }

    @GetMapping("/me/delivery-profile")
    public ApiResponse<DeliveryProfile> deliveryProfile() {
        return ApiResponse.success(service.profile(actor()));
    }

    @PutMapping("/me/delivery-profile")
    public ApiResponse<DeliveryProfile> updateDeliveryProfile(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DeliveryProfileUpdate request) {
        return ApiResponse.success(service.updateProfile(actor(), request, idempotencyKey));
    }

    @GetMapping("/me/effective-settings")
    public ApiResponse<EffectiveSettings> effectiveSettings() {
        return ApiResponse.success(service.effectiveSettings(actor()));
    }

    @GetMapping("/me/subscription-rules")
    public ApiResponse<List<SubscriptionRule>> subscriptionRules() {
        return ApiResponse.success(service.rules(actor()));
    }

    @PutMapping("/me/subscription-rules/{ruleId}")
    public ApiResponse<SubscriptionRule> putSubscriptionRule(
            @PathVariable UUID ruleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubscriptionRuleUpdate request) {
        return ApiResponse.success(service.putRule(actor(), ruleId, request, idempotencyKey));
    }

    @DeleteMapping("/me/subscription-rules/{ruleId}")
    public ApiResponse<Void> deleteSubscriptionRule(
            @PathVariable UUID ruleId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam(name = "expectedVersion", required = false) String expectedVersion,
            @RequestParam(name = "version", required = false) String version) {
        String externalVersion = expectedVersion == null ? version : expectedVersion;
        if (externalVersion == null) {
            throw new IllegalArgumentException("A valid expectedVersion is required.");
        }
        long targetVersion = positive(externalVersion, "expectedVersion");
        service.deleteRule(actor(), ruleId, targetVersion, idempotencyKey);
        return ApiResponse.success(null);
    }

    private ApiResponse<ActionResult> action(
            UUID notificationId,
            String action,
            long version,
            Instant until,
            String idempotencyKey) {
        return ApiResponse.success(service.mutate(
                actor(), notificationId, action, version, until, idempotencyKey));
    }

    private NotificationRequestContext.Actor actor() {
        return NotificationRequestContext.requireActor();
    }
}
