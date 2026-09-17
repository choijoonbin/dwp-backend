package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceCommandDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceResourceCommandProvider.*;
import static com.dwp.services.platform.workplace.WorkplaceTypes.*;

@Service
public class WorkplaceResourceCommandService {
    private static final Duration PROVIDER_FRESHNESS = Duration.ofMinutes(10);
    private static final Duration PREVIEW_LIFETIME = Duration.ofMinutes(5);

    private final WorkplaceResourceCommandRepository repository;
    private final WorkplaceResourceCommandProvider provider;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public WorkplaceResourceCommandService(
            WorkplaceResourceCommandRepository repository,
            WorkplaceResourceCommandProvider provider,
            ObjectMapper mapper) {
        this(repository, provider, mapper, Clock.systemUTC());
    }

    WorkplaceResourceCommandService(
            WorkplaceResourceCommandRepository repository,
            WorkplaceResourceCommandProvider provider,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CommandContext context(long tenantId, long actorId, UUID bookingId) {
        requireIdentity(tenantId, actorId);
        var booking = requireBooking(tenantId, actorId, bookingId);
        OffsetDateTime now = now();
        List<CommandAction> actions = types(booking.resourceType()).stream()
                .map(type -> action(tenantId, booking, type, now)).toList();
        return new CommandContext(booking.bookingId(), booking.resourceId(),
                booking.resourceType(), booking.version(), actions, now);
    }

    @Transactional
    public CommandPreview preview(
            long tenantId,
            long actorId,
            UUID bookingId,
            PreviewRequest request) {
        requireIdentity(tenantId, actorId);
        Map<String, String> parameters = parameters(request.commandType(), request.parameters());
        var booking = requireBooking(tenantId, actorId, bookingId);
        if (!types(booking.resourceType()).contains(request.commandType())) {
            throw invalid("This command is not valid for the reserved resource type.");
        }
        OffsetDateTime now = now();
        CommandAction action = action(tenantId, booking, request.commandType(), now);
        List<String> limitations = new ArrayList<>();
        if (booking.version() != request.expectedBookingVersion()) {
            limitations.add("BOOKING_VERSION_CHANGED");
        }
        if (action.availability() != ResourceCommandAvailability.AVAILABLE
                && action.limitationCode() != null) {
            limitations.add(action.limitationCode());
        }
        ProviderSnapshot snapshot = providerSnapshot(tenantId,
                capability(request.commandType()), now);
        boolean eligible = limitations.isEmpty();
        var row = new WorkplaceResourceCommandRepository.PreviewRow(
                UUID.randomUUID(), tenantId, actorId, booking.bookingId(), booking.resourceId(),
                request.commandType(), request.expectedBookingVersion(), parameters,
                capability(request.commandType()), snapshot.providerCode(),
                snapshot.configurationVersion(), eligible, List.copyOf(limitations),
                now.plus(PREVIEW_LIFETIME), now);
        repository.insertPreview(row);
        return preview(row, snapshot.state());
    }

    @Transactional(readOnly = true)
    public CommandPreview preview(
            long tenantId, long actorId, UUID bookingId, UUID previewId) {
        requireIdentity(tenantId, actorId);
        var row = repository.preview(tenantId, actorId, previewId)
                .filter(value -> value.bookingId().equals(bookingId))
                .orElseThrow(() -> notFound("The resource-command preview was not found."));
        ResourceCommandProviderState state = providerSnapshot(
                tenantId, row.capability(), now()).state();
        return preview(row, state);
    }

    @Transactional
    public CommandReceipt execute(
            long tenantId,
            long actorId,
            UUID bookingId,
            String idempotencyKey,
            String correlationId,
            ExecuteRequest request) {
        requireIdentity(tenantId, actorId);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String key = bounded(idempotencyKey, 160, "A valid Idempotency-Key is required.");
        String correlation = optionalBounded(correlationId, 160);
        String reason = request.reason().trim();
        String fingerprint = fingerprint(bookingId, request);
        repository.lock(tenantId, actorId, key);
        var replay = repository.command(tenantId, actorId, key);
        if (replay.isPresent()) {
            verifyReplay(replay.orElseThrow(), bookingId, fingerprint);
            return receipt(replay.orElseThrow(), true);
        }
        OffsetDateTime now = now();
        var preview = repository.preview(tenantId, actorId, request.previewId())
                .filter(value -> value.bookingId().equals(bookingId))
                .orElseThrow(() -> notFound("The resource-command preview was not found."));
        if (!preview.eligible() || !preview.expiresAt().isAfter(now)
                || preview.expectedBookingVersion() != request.expectedBookingVersion()) {
            throw conflict("The resource-command preview is stale or ineligible.");
        }
        var booking = requireBooking(tenantId, actorId, bookingId);
        if (booking.version() != request.expectedBookingVersion() || !active(booking, now)) {
            throw conflict("The booking changed after the resource command was previewed.");
        }
        ProviderSnapshot truth = providerSnapshot(tenantId, preview.capability(), now);
        if (truth.state() != ResourceCommandProviderState.READY
                || !safeEquals(truth.providerCode(), preview.providerCode())
                || !safeEquals(truth.configurationVersion(),
                        preview.providerConfigurationVersion())
                || truth.binding() == null) {
            throw conflict("The resource-command provider changed after preview.");
        }
        UUID commandId = deterministicCommandId(tenantId, actorId, key);
        var pending = repository.insertCommand(commandId, tenantId, actorId, preview, key,
                fingerprint, truth.binding(), reason, correlation, now);
        ProviderOutcome outcome;
        try {
            outcome = provider.execute(providerCommand(pending));
        } catch (RuntimeException uncertain) {
            outcome = new ProviderOutcome(ResourceCommandState.RESULT_UNKNOWN,
                    "command:" + commandId, "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        }
        var completed = repository.applyOutcome(pending, normalized(outcome, commandId), now());
        repository.audit(completed, actorId, "workplace.resource.command.executed",
                correlation, now());
        return receipt(completed, false);
    }

    @Transactional(readOnly = true)
    public CommandReceipt receipt(
            long tenantId, long actorId, UUID bookingId, UUID commandId) {
        requireIdentity(tenantId, actorId);
        return receipt(repository.command(tenantId, actorId, bookingId, commandId)
                .orElseThrow(() -> notFound("The resource-command receipt was not found.")), false);
    }

    @Transactional
    public CommandReceipt reconcile(
            long tenantId,
            long actorId,
            UUID bookingId,
            UUID commandId,
            String idempotencyKey,
            String correlationId,
            ReconcileRequest request) {
        requireIdentity(tenantId, actorId);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String key = bounded(idempotencyKey, 160, "A valid Idempotency-Key is required.");
        String fingerprint = reconcileFingerprint(bookingId, commandId, request);
        repository.lock(tenantId, actorId, "reconcile:" + key);
        var replay = repository.reconciliation(tenantId, actorId, key);
        if (replay.isPresent()) {
            var value = replay.orElseThrow();
            if (!value.commandId().equals(commandId) || !MessageDigest.isEqual(
                    value.fingerprint().getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw conflict("The Idempotency-Key was already used for a different reconciliation.");
            }
            return replay(value.receipt());
        }
        var command = repository.command(tenantId, actorId, bookingId, commandId)
                .orElseThrow(() -> notFound("The resource-command receipt was not found."));
        var updated = command;
        if (command.state() == ResourceCommandState.RESULT_UNKNOWN) {
            ProviderBinding binding = new ProviderBinding(command.providerCode(),
                    command.providerConfigurationVersion(), command.credentialReference());
            ProviderOutcome outcome;
            try {
                outcome = provider.ready(binding)
                        ? provider.status(providerCommand(command))
                        : new ProviderOutcome(ResourceCommandState.RESULT_UNKNOWN,
                                command.providerOperationReference(),
                                "PROVIDER_STATUS_NOT_CONFIGURED");
            } catch (RuntimeException unavailable) {
                outcome = new ProviderOutcome(ResourceCommandState.RESULT_UNKNOWN,
                        command.providerOperationReference(), "PROVIDER_STATUS_UNAVAILABLE");
            }
            updated = repository.applyOutcome(command, normalized(outcome, commandId), now());
            repository.audit(updated, actorId, "workplace.resource.command.reconciled",
                    optionalBounded(correlationId, 160), now());
        }
        CommandReceipt result = receipt(updated, false);
        repository.insertReconciliation(tenantId, actorId, commandId, key, fingerprint,
                result, now());
        return result;
    }

    private CommandAction action(
            long tenantId,
            WorkplaceResourceCommandRepository.BookingRow booking,
            ResourceCommandType type,
            OffsetDateTime now) {
        ProviderSnapshot snapshot = providerSnapshot(tenantId, capability(type), now);
        if (!active(booking, now)) {
            return new CommandAction(type, capability(type), snapshot.state(),
                    ResourceCommandAvailability.BOOKING_INACTIVE, "BOOKING_INACTIVE", true);
        }
        if (snapshot.state() != ResourceCommandProviderState.READY) {
            return new CommandAction(type, capability(type), snapshot.state(),
                    ResourceCommandAvailability.PROVIDER_NOT_READY,
                    "PROVIDER_" + snapshot.state().name(), true);
        }
        return new CommandAction(type, capability(type), snapshot.state(),
                ResourceCommandAvailability.AVAILABLE, null, true);
    }

    private ProviderSnapshot providerSnapshot(
            long tenantId,
            ResourceCommandProviderCapability capability,
            OffsetDateTime now) {
        var truth = repository.providerTruth(tenantId, capability).orElse(null);
        if (truth == null || !truth.configured()) {
            return new ProviderSnapshot(ResourceCommandProviderState.NOT_CONFIGURED,
                    null, null, null);
        }
        ProviderBinding binding = provider.binding(
                truth.providerCode(), truth.configurationVersion()).orElse(null);
        if (truth.observedConfigurationVersion() == null || truth.reportedState() == null
                || truth.receivedAt() == null || binding == null || !provider.ready(binding)) {
            return new ProviderSnapshot(ResourceCommandProviderState.CONFIGURED_UNVERIFIED,
                    truth.providerCode(), truth.configurationVersion(), binding);
        }
        if (truth.observedConfigurationVersion() != truth.configurationVersion()) {
            return new ProviderSnapshot(ResourceCommandProviderState.CONFIGURED_UNVERIFIED,
                    truth.providerCode(), truth.configurationVersion(), binding);
        }
        if (truth.receivedAt().isBefore(now.minus(PROVIDER_FRESHNESS))) {
            return new ProviderSnapshot(ResourceCommandProviderState.STALE,
                    truth.providerCode(), truth.configurationVersion(), binding);
        }
        if (!"HEALTHY".equals(truth.reportedState()) || truth.errorCode() != null) {
            return new ProviderSnapshot(ResourceCommandProviderState.DEGRADED,
                    truth.providerCode(), truth.configurationVersion(), binding);
        }
        return new ProviderSnapshot(ResourceCommandProviderState.READY,
                truth.providerCode(), truth.configurationVersion(), binding);
    }

    private static List<ResourceCommandType> types(ResourceType type) {
        return switch (type) {
            case PARKING -> List.of(ResourceCommandType.PARKING_EXTEND,
                    ResourceCommandType.PARKING_EXIT_STATUS);
            case LOCKER -> List.of(ResourceCommandType.LOCKER_UNLOCK,
                    ResourceCommandType.NFC_KEY_RESEND);
            case ROOM -> List.of(ResourceCommandType.ROOM_PRE_ENTRY,
                    ResourceCommandType.NFC_KEY_RESEND);
            case DESK, FOCUS_POD, PHONE_BOOTH -> List.of(ResourceCommandType.NFC_KEY_RESEND);
            case EQUIPMENT -> List.of();
        };
    }

    private static ResourceCommandProviderCapability capability(ResourceCommandType type) {
        return switch (type) {
            case PARKING_EXTEND, PARKING_EXIT_STATUS ->
                    ResourceCommandProviderCapability.SPEED_GATE;
            case LOCKER_UNLOCK, NFC_KEY_RESEND, ROOM_PRE_ENTRY ->
                    ResourceCommandProviderCapability.NFC;
        };
    }

    private static Map<String, String> parameters(
            ResourceCommandType type, Map<String, String> input) {
        Map<String, String> value = input == null ? Map.of() : new LinkedHashMap<>(input);
        if (type == ResourceCommandType.PARKING_EXTEND) {
            if (!value.keySet().equals(java.util.Set.of("minutes"))) {
                throw invalid("Parking extension requires only the minutes parameter.");
            }
            int minutes;
            try {
                minutes = Integer.parseInt(value.get("minutes"));
            } catch (RuntimeException invalid) {
                throw invalid("Parking extension minutes must be between 15 and 240.");
            }
            if (minutes < 15 || minutes > 240 || minutes % 15 != 0) {
                throw invalid("Parking extension minutes must be a 15-minute increment up to 240.");
            }
            return Map.of("minutes", Integer.toString(minutes));
        }
        if (!value.isEmpty()) throw invalid("This resource command does not accept parameters.");
        return Map.of();
    }

    private static boolean active(
            WorkplaceResourceCommandRepository.BookingRow booking, OffsetDateTime now) {
        return (booking.status() == BookingStatus.RESERVED
                || booking.status() == BookingStatus.CHECKED_IN)
                && booking.endsAt().isAfter(now);
    }

    private WorkplaceResourceCommandRepository.BookingRow requireBooking(
            long tenantId, long actorId, UUID bookingId) {
        return repository.booking(tenantId, actorId, bookingId)
                .orElseThrow(() -> notFound("The owned Workplace booking was not found."));
    }

    private static CommandPreview preview(
            WorkplaceResourceCommandRepository.PreviewRow row,
            ResourceCommandProviderState providerState) {
        List<String> impact = switch (row.type()) {
            case PARKING_EXTEND -> List.of("PARKING_ACCESS_WINDOW_WILL_CHANGE");
            case PARKING_EXIT_STATUS -> List.of("PARKING_EXIT_STATUS_WILL_REFRESH");
            case LOCKER_UNLOCK -> List.of("LOCKER_WILL_UNLOCK_ONCE");
            case NFC_KEY_RESEND -> List.of("CURRENT_NFC_KEY_WILL_BE_REPLACED");
            case ROOM_PRE_ENTRY -> List.of("ROOM_ACCESS_WINDOW_WILL_OPEN_EARLY");
        };
        return new CommandPreview(row.previewId(), row.bookingId(), row.resourceId(), row.type(),
                row.expectedBookingVersion(), row.parameters(), row.capability(), providerState,
                row.providerCode(), row.providerConfigurationVersion(), row.eligible(), impact,
                row.limitations(), row.expiresAt(), row.createdAt());
    }

    private static ProviderCommand providerCommand(
            WorkplaceResourceCommandRepository.CommandRow command) {
        return new ProviderCommand(command.tenantId(), command.commandId(), command.bookingId(),
                command.resourceId(), command.type(), command.parameters(), command.correlationId(),
                new ProviderBinding(command.providerCode(), command.providerConfigurationVersion(),
                        command.credentialReference()));
    }

    private static ProviderOutcome normalized(ProviderOutcome value, UUID commandId) {
        if (value == null || value.state() == null) {
            return new ProviderOutcome(ResourceCommandState.RESULT_UNKNOWN,
                    "command:" + commandId, "PROVIDER_RESPONSE_INVALID");
        }
        String reference = optionalBounded(value.providerOperationReference(), 320);
        String code = optionalBounded(value.resultCode(), 120);
        if (value.state() == ResourceCommandState.SUCCEEDED && reference == null) {
            return new ProviderOutcome(ResourceCommandState.RESULT_UNKNOWN,
                    "command:" + commandId, "PROVIDER_RESPONSE_INVALID");
        }
        return new ProviderOutcome(value.state(), reference,
                code == null ? "PROVIDER_" + value.state().name() : code);
    }

    private static CommandReceipt receipt(
            WorkplaceResourceCommandRepository.CommandRow row, boolean replay) {
        return new CommandReceipt(row.commandId(), row.previewId(), row.bookingId(),
                row.resourceId(), row.type(), row.state(), row.resultCode(),
                row.providerOperationReference(), row.version(),
                "/v1/workplace/bookings/" + row.bookingId()
                        + "/resource-commands/" + row.commandId(),
                row.correlationId(), row.acceptedAt(), row.completedAt(), row.updatedAt(),
                row.state() == ResourceCommandState.RESULT_UNKNOWN, replay);
    }

    private static void verifyReplay(
            WorkplaceResourceCommandRepository.CommandRow row,
            UUID bookingId,
            String fingerprint) {
        if (!row.bookingId().equals(bookingId) || !MessageDigest.isEqual(
                row.fingerprint().getBytes(StandardCharsets.US_ASCII),
                fingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("The Idempotency-Key was already used for a different command.");
        }
    }

    private String fingerprint(UUID bookingId, ExecuteRequest request) {
        try {
            return sha256(mapper.writeValueAsString(List.of(
                    bookingId, request.previewId(), request.expectedBookingVersion(),
                    request.reason().trim(), request.explicitConfirmation())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to fingerprint resource command.", exception);
        }
    }

    private String reconcileFingerprint(
            UUID bookingId, UUID commandId, ReconcileRequest request) {
        try {
            return sha256(mapper.writeValueAsString(List.of(
                    bookingId, commandId, request.reason().trim(),
                    request.explicitConfirmation())));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Unable to fingerprint resource-command reconciliation.", exception);
        }
    }

    private static CommandReceipt replay(CommandReceipt value) {
        return new CommandReceipt(value.commandId(), value.previewId(), value.bookingId(),
                value.resourceId(), value.commandType(), value.state(), value.resultCode(),
                value.providerOperationReference(), value.version(), value.statusHref(),
                value.correlationId(), value.acceptedAt(), value.completedAt(), value.updatedAt(),
                value.requeryRequired(), true);
    }

    private static UUID deterministicCommandId(long tenantId, long actorId, String key) {
        return UUID.nameUUIDFromBytes(("workplace-resource-command:" + tenantId + ":" + actorId
                + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static void requireIdentity(long tenantId, long actorId) {
        if (tenantId < 1 || actorId < 1) throw invalid("A valid tenant and user are required.");
    }

    private static String bounded(String value, int maximum, String message) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw invalid(message);
        }
        return value;
    }

    private static String optionalBounded(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        if (trimmed.length() > maximum
                || trimmed.chars().anyMatch(character -> character < 32 || character > 126)) {
            throw invalid("The provider evidence value is invalid.");
        }
        return trimmed;
    }

    private static boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private record ProviderSnapshot(
            ResourceCommandProviderState state,
            String providerCode,
            Long configurationVersion,
            ProviderBinding binding) { }
}
