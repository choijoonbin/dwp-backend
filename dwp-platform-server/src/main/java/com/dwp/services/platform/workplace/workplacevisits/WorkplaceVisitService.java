package com.dwp.services.platform.workplace.workplacevisits;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.*;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitRepository.*;

@Service
public class WorkplaceVisitService {
    private final WorkplaceVisitRepository repository;
    private final Clock clock;
    private final List<WorkplaceVisitGuestRefVerificationPort> guestRefVerifiers;

    @Autowired
    public WorkplaceVisitService(
            WorkplaceVisitRepository repository,
            List<WorkplaceVisitGuestRefVerificationPort> guestRefVerifiers) {
        this(repository, Clock.systemUTC(), guestRefVerifiers);
    }

    WorkplaceVisitService(WorkplaceVisitRepository repository, Clock clock) {
        this(repository, clock, List.of());
    }

    WorkplaceVisitService(WorkplaceVisitRepository repository, Clock clock,
                          List<WorkplaceVisitGuestRefVerificationPort> guestRefVerifiers) {
        this.repository = repository;
        this.clock = clock;
        this.guestRefVerifiers = List.copyOf(guestRefVerifiers);
    }

    @Transactional
    public VisitPreview preview(long tenantId, long actorId, String key,
                                VisitPreviewRequest request, String correlationId) {
        requireKey(key);
        requireConfirmation(request.explicitConfirmation());
        String fingerprint = fingerprint("PREVIEW", request.reservation().authority(),
                request.reservation().id(), request.reservation().version(), request.visitType(),
                request.siteId(), request.startsAt(), request.endsAt(),
                request.zoneIds().stream().sorted().toList(), guestsFingerprint(request.guests()),
                request.reason());
        repository.lockCommand(tenantId, actorId, "PREVIEW", key);
        PreviewCommandRow replay = repository.previewCommand(tenantId, actorId, key).orElse(null);
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint)) throw conflict(
                    "The Idempotency-Key was already used for a different command.");
            return replay.response();
        }
        OffsetDateTime now = now();
        ReservationSnapshot reservation = repository.reservation(tenantId, actorId,
                request.reservation()).orElseThrow(WorkplaceVisitService::notFound);
        if (!reservation.active() || reservation.version() != request.reservation().version()) {
            throw conflict("The reservation changed or is no longer active.");
        }
        if (!reservation.siteId().equals(request.siteId())
                || request.startsAt().isBefore(reservation.startsAt())
                || request.endsAt().isAfter(reservation.endsAt())
                || !request.endsAt().isAfter(request.startsAt())) {
            throw invalid("The visit must remain within the governed reservation site and period.");
        }
        PolicyRow policy = repository.policy(tenantId, request.visitType())
                .orElseThrow(() -> conflict("No active visit policy exists for this visit type."));
        validateGuestRetention(request.guests(), policy, now);
        List<String> limitations = new ArrayList<>();
        boolean guestRefsVerified = verifyGuestReferences(tenantId, request.guests(), limitations);
        boolean zonesEligible = repository.zonesEligible(
                tenantId, request.siteId(), request.visitType(), request.zoneIds());
        if (!zonesEligible) limitations.add("ZONE_NOT_ELIGIBLE");
        if (request.startsAt().toLocalTime().isBefore(policy.allowedFrom())
                || request.endsAt().toLocalTime().isAfter(policy.allowedUntil())) {
            limitations.add("OUTSIDE_ALLOWED_HOURS");
        }
        ProviderTruth visitor = providerTruth(tenantId, ProviderKind.VISITOR, now);
        ProviderTruth access = providerTruth(tenantId, ProviderKind.ACCESS, now);
        providerLimitation(visitor, limitations);
        providerLimitation(access, limitations);
        boolean eligible = zonesEligible && guestRefsVerified
                && !limitations.contains("OUTSIDE_ALLOWED_HOURS");
        UUID previewId = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plusMinutes(15);
        repository.insertPreview(tenantId, actorId, previewId, request, policy,
                visitor, access, guestsFingerprint(request.guests()), eligible, limitations,
                expiresAt, now);
        VisitPreview response = new VisitPreview(previewId, 1, request.reservation(), request.visitType(),
                request.siteId(), request.startsAt(), request.endsAt(), List.copyOf(request.zoneIds()),
                request.guests().size(), policy.approvalRequired(), policy.ndaRequired(),
                policy.identityVerificationRequired(), policy.minimumCollectionFields(), visitor,
                access, eligible, List.copyOf(limitations), expiresAt, now);
        repository.insertPreviewCommand(tenantId, actorId, key, fingerprint,
                correlationId, response, now);
        repository.audit(tenantId, null, actorId, "workplace.visit.preview.generated",
                correlationId, Map.of("previewId", previewId, "guestCount", request.guests().size(),
                        "guestReferencesVerified", guestRefsVerified, "eligible", eligible), now);
        return response;
    }

    @Transactional
    public VisitCommandResult create(long tenantId, long actorId, String key,
                                     CreateVisitRequest request, String correlationId) {
        requireKey(key);
        requireConfirmation(request.explicitConfirmation());
        String fingerprint = fingerprint("CREATE", request.previewId(),
                request.expectedPreviewVersion(), guestsFingerprint(request.guests()),
                request.reason());
        repository.lockCommand(tenantId, actorId, "CREATE", key);
        CommandRow replay = repository.command(tenantId, actorId, "CREATE", key).orElse(null);
        if (replay != null) return requesterResult(tenantId, actorId,
                verify(replay, fingerprint), true);
        OffsetDateTime now = now();
        PreviewRow preview = repository.preview(tenantId, actorId, request.previewId())
                .orElseThrow(WorkplaceVisitService::notFound);
        if (preview.version() != request.expectedPreviewVersion() || now.isAfter(preview.expiresAt())) {
            throw conflict("The preview is stale. Create a new preview.");
        }
        if (!preview.eligible()) throw conflict("The preview contains blocking limitations.");
        if (!preview.guestFingerprint().equals(guestsFingerprint(request.guests()))) {
            throw conflict("Guest references differ from the governed preview.");
        }
        ReservationSnapshot reservation = repository.reservation(tenantId, actorId,
                new ReservationReference(preview.authority(), preview.reservationId(),
                        preview.reservationVersion())).orElseThrow(WorkplaceVisitService::notFound);
        if (!reservation.active() || reservation.version() != preview.reservationVersion()) {
            throw conflict("The reservation changed after preview.");
        }
        UUID visitId = UUID.randomUUID();
        repository.insertVisit(tenantId, actorId, visitId, preview, request.guests(), now);
        repository.timeline(tenantId, visitId, actorId, "VISIT_DRAFT_CREATED",
                VisitState.DRAFT, null, now);
        requireTransition(repository.transition(tenantId, visitId, 1,
                List.of(VisitState.DRAFT), VisitState.PREVIEWED, null, null, now));
        repository.timeline(tenantId, visitId, actorId, "VISIT_PREVIEW_ACCEPTED",
                VisitState.PREVIEWED, null, now);
        CommandRow receipt = repository.insertCommand(tenantId, actorId, visitId, "CREATE",
                key, fingerprint, CommandState.SUCCEEDED, correlationId, now);
        repository.audit(tenantId, visitId, actorId, "workplace.visit.created",
                correlationId, Map.of("guestCount", request.guests().size(),
                        "reservationAuthority", preview.authority().name()), now);
        return requesterResult(tenantId, actorId, receipt, false);
    }

    @Transactional(readOnly = true)
    public VisitPage<RequesterVisit> requesterVisits(
            long tenantId, long actorId, ReservationAuthority authority, UUID reservationId) {
        return new VisitPage<>(repository.requesterVisits(tenantId, actorId, authority, reservationId)
                .stream().map(row -> requesterProjection(tenantId, row)).toList(), now());
    }

    @Transactional
    public RequesterVisit requesterVisit(long tenantId, long actorId, UUID visitId,
                                         String correlationId) {
        VisitRow row = repository.requesterVisit(tenantId, actorId, visitId)
                .orElseThrow(WorkplaceVisitService::notFound);
        repository.audit(tenantId, visitId, actorId, "workplace.visit.guest_refs.viewed",
                correlationId, Map.of("projection", "REQUESTER", "guestCount",
                        repository.guests(tenantId, visitId).size()), now());
        return requesterProjection(tenantId, row);
    }

    @Transactional
    public VisitCommandResult sendInvitation(long tenantId, long actorId, UUID visitId,
                                             String key, VersionCommand request,
                                             String correlationId) {
        return userMutation(tenantId, actorId, visitId, key, "SEND_INVITATION", request,
                correlationId, (row, now) -> {
                    ProviderTruth truth = providerTruth(tenantId, ProviderKind.VISITOR, now);
                    if (truth.state() != ProviderTruthState.READY) {
                        throw conflict("Visitor provider is not ready; follow the manual procedure.");
                    }
                    requireTransition(repository.transition(tenantId, visitId, row.version(),
                            List.of(VisitState.PREVIEWED), VisitState.INVITED, null, null, now));
                    repository.timeline(tenantId, visitId, actorId, "INVITATION_QUEUED",
                            VisitState.INVITED, null, now);
                    repository.outbox(tenantId, visitId, "SEND_INVITATION",
                            visitId + ":invite:" + key, null, "PENDING", now);
                    return CommandState.ACCEPTED;
                });
    }

    @Transactional
    public VisitCommandResult requestAccess(long tenantId, long actorId, UUID visitId,
                                            String key, VersionCommand request,
                                            String correlationId) {
        return userMutation(tenantId, actorId, visitId, key, "REQUEST_ACCESS", request,
                correlationId, (row, now) -> accessTransition(
                        tenantId, actorId, row, key, now, false));
    }

    @Transactional
    public VisitCommandResult cancel(long tenantId, long actorId, UUID visitId,
                                     String key, VersionCommand request, String correlationId) {
        return userMutation(tenantId, actorId, visitId, key, "CANCEL", request,
                correlationId, (row, now) -> {
                    requireTransition(repository.transition(tenantId, visitId, row.version(),
                            List.of(VisitState.PREVIEWED, VisitState.INVITED,
                                    VisitState.APPROVAL_PENDING, VisitState.APPROVED,
                                    VisitState.ACCESS_PENDING, VisitState.READY,
                                    VisitState.RESULT_UNKNOWN, VisitState.ACCESS_FAILED),
                            VisitState.CANCELLED, null, null, now));
                    repository.timeline(tenantId, visitId, actorId, "VISIT_CANCELLED",
                            VisitState.CANCELLED, null, now);
                    repository.outbox(tenantId, visitId, "REVOKE_ACCESS",
                            visitId + ":revoke:cancel:" + row.version(), null, "PENDING", now);
                    return CommandState.SUCCEEDED;
                });
    }

    @Transactional(readOnly = true)
    public VisitPage<VisitException> exceptions(long tenantId, VisitState state) {
        return new VisitPage<>(repository.exceptionVisits(tenantId, state).stream()
                .map(row -> new VisitException(row.visitId(), exceptionKind(row), row.state(),
                        firstMaskedLabel(tenantId, row.visitId()), row.siteId(), row.startsAt(),
                        row.version(), row.limitationCode(), row.updatedAt())).toList(), now());
    }

    @Transactional
    public AdminVisit adminVisit(long tenantId, long actorId, UUID visitId,
                                 String correlationId) {
        VisitRow row = repository.adminVisit(tenantId, visitId)
                .orElseThrow(WorkplaceVisitService::notFound);
        repository.audit(tenantId, visitId, actorId, "workplace.visit.admin_detail.viewed",
                correlationId, Map.of("projection", "ADMIN", "guestCount",
                        repository.guests(tenantId, visitId).size()), now());
        return adminProjection(tenantId, row);
    }

    @Transactional
    public AdminVisitCommandResult approve(long tenantId, long actorId, UUID visitId,
                                            String key, ApprovalCommand request,
                                            String correlationId) {
        return adminMutation(tenantId, actorId, visitId, key, "APPROVE", request.expectedVersion(),
                request.reason(), request.explicitConfirmation(), correlationId, (row, now) -> {
                    requireReservationCurrent(tenantId, row);
                    VisitState target = request.approved() ? VisitState.APPROVED : VisitState.REJECTED;
                    requireTransition(repository.transition(tenantId, visitId, row.version(),
                            List.of(VisitState.APPROVAL_PENDING), target, null, null, now));
                    repository.timeline(tenantId, visitId, actorId,
                            request.approved() ? "VISIT_APPROVED" : "VISIT_REJECTED",
                            target, null, now);
                    if (!request.approved()) repository.outbox(tenantId, visitId, "REVOKE_ACCESS",
                            visitId + ":revoke:reject:" + row.version(), null, "PENDING", now);
                    return CommandState.SUCCEEDED;
                });
    }

    @Transactional
    public AdminVisitCommandResult retryAccess(long tenantId, long actorId, UUID visitId,
                                                String key, VersionCommand request,
                                                String correlationId) {
        return adminMutation(tenantId, actorId, visitId, key, "RETRY_ACCESS",
                request.expectedVersion(), request.reason(), request.explicitConfirmation(),
                correlationId, (row, now) -> {
                    requireReservationCurrent(tenantId, row);
                    if (row.state() != VisitState.RESULT_UNKNOWN) {
                        return accessTransition(tenantId, actorId, row, key, now, true);
                    }
                    ProviderTruth truth = providerTruth(tenantId, ProviderKind.ACCESS, now);
                    if (truth.state() != ProviderTruthState.READY) {
                        throw conflict("Access provider status lookup is not ready.");
                    }
                    OutboxRow original = repository.latestOutbox(
                            tenantId, visitId, "REQUEST_ACCESS")
                            .filter(value -> "RESULT_UNKNOWN".equals(value.deliveryState()))
                            .orElseThrow(() -> conflict(
                                    "No uncertain access operation exists to reconcile."));
                    requireTransition(repository.transition(tenantId, visitId, row.version(),
                            List.of(VisitState.RESULT_UNKNOWN), VisitState.RESULT_UNKNOWN,
                            row.evidenceReference(), "ACCESS_STATUS_LOOKUP_PENDING", now));
                    repository.timeline(tenantId, visitId, actorId,
                            "ACCESS_STATUS_LOOKUP_QUEUED", VisitState.RESULT_UNKNOWN, null, now);
                    repository.outbox(tenantId, visitId, "CHECK_PROVIDER_STATUS",
                            visitId + ":provider-status:" + original.id(), original.id(),
                            null, "PENDING", now);
                    return CommandState.ACCEPTED;
                });
    }

    @Transactional
    public AdminVisitCommandResult notifyHost(long tenantId, long actorId, UUID visitId,
                                               String key, VersionCommand request,
                                               String correlationId) {
        return adminMutation(tenantId, actorId, visitId, key, "NOTIFY_HOST",
                request.expectedVersion(), request.reason(), request.explicitConfirmation(),
                correlationId, (row, now) -> {
                    requireTransition(repository.transition(tenantId, visitId, row.version(),
                            List.of(row.state()), row.state(), null, row.limitationCode(), now));
                    repository.timeline(tenantId, visitId, actorId, "HOST_NOTIFIED",
                            row.state(), null, now);
                    repository.outbox(tenantId, visitId, "NOTIFY_HOST",
                            visitId + ":host:" + row.version(), null, "PENDING", now);
                    return CommandState.ACCEPTED;
                });
    }

    @Transactional
    public AdminVisitCommandResult confirmCheckout(long tenantId, long actorId, UUID visitId,
                                                    String key, VersionCommand request,
                                                    String correlationId) {
        return adminMutation(tenantId, actorId, visitId, key, "CONFIRM_CHECKOUT",
                request.expectedVersion(), request.reason(), request.explicitConfirmation(),
                correlationId, (row, now) -> checkoutTransition(tenantId, actorId, row, now));
    }


    private VisitCommandResult userMutation(
            long tenantId, long actorId, UUID visitId, String key, String scope,
            VersionCommand request, String correlationId, Mutation mutation) {
        requireKey(key);
        requireConfirmation(request.explicitConfirmation());
        String fp = fingerprint(scope, visitId, request.expectedVersion(), request.reason());
        repository.lockCommand(tenantId, actorId, scope, key);
        CommandRow replay = repository.command(tenantId, actorId, scope, key).orElse(null);
        if (replay != null) return requesterResult(tenantId, actorId, verify(replay, fp), true);
        VisitRow row = repository.requesterVisit(tenantId, actorId, visitId)
                .orElseThrow(WorkplaceVisitService::notFound);
        requireVersion(row.version(), request.expectedVersion());
        requireReservationCurrent(tenantId, row);
        CommandState state = mutation.apply(row, now());
        CommandRow receipt = repository.insertCommand(tenantId, actorId, visitId, scope,
                key, fp, state, correlationId, now());
        repository.audit(tenantId, visitId, actorId, "workplace.visit." + scope.toLowerCase(),
                correlationId, Map.of("result", state.name()), now());
        return requesterResult(tenantId, actorId, receipt, false);
    }

    private AdminVisitCommandResult adminMutation(
            long tenantId, long actorId, UUID visitId, String key, String scope,
            long expectedVersion, String reason, boolean confirmed, String correlationId,
            Mutation mutation) {
        requireKey(key);
        requireConfirmation(confirmed);
        String fp = fingerprint(scope, visitId, expectedVersion, reason);
        repository.lockCommand(tenantId, actorId, scope, key);
        CommandRow replay = repository.command(tenantId, actorId, scope, key).orElse(null);
        if (replay != null) {
            verify(replay, fp);
            return adminResult(tenantId, replay, true);
        }
        VisitRow row = repository.adminVisit(tenantId, visitId)
                .orElseThrow(WorkplaceVisitService::notFound);
        requireVersion(row.version(), expectedVersion);
        CommandState state = mutation.apply(row, now());
        CommandRow receipt = repository.insertCommand(tenantId, actorId, visitId, scope,
                key, fp, state, correlationId, now());
        repository.audit(tenantId, visitId, actorId, "workplace.visit.admin."
                + scope.toLowerCase(), correlationId, Map.of("result", state.name()), now());
        return adminResult(tenantId, receipt, false);
    }


    private CommandState accessTransition(long tenantId, long actorId, VisitRow row,
                                          String key, OffsetDateTime now, boolean retry) {
        List<VisitState> allowed = retry
                ? List.of(VisitState.ACCESS_FAILED) : List.of(VisitState.APPROVED);
        ProviderTruth truth = providerTruth(tenantId, ProviderKind.ACCESS, now);
        if (truth.state() != ProviderTruthState.READY) {
            String limitation = "ACCESS_PROVIDER_" + truth.state().name();
            requireTransition(repository.transition(tenantId, row.visitId(), row.version(), allowed,
                    VisitState.ACCESS_FAILED, null, limitation, now));
            repository.timeline(tenantId, row.visitId(), actorId, "ACCESS_FAILED",
                    VisitState.ACCESS_FAILED, limitation, now);
            return CommandState.FAILED;
        }
        requireTransition(repository.transition(tenantId, row.visitId(), row.version(), allowed,
                VisitState.ACCESS_PENDING, null, null, now));
        repository.timeline(tenantId, row.visitId(), actorId, "ACCESS_REQUEST_QUEUED",
                VisitState.ACCESS_PENDING, null, now);
        repository.outbox(tenantId, row.visitId(), "REQUEST_ACCESS",
                row.visitId() + ":access:" + key, null, "PENDING", now);
        return CommandState.ACCEPTED;
    }

    private CommandState checkoutTransition(long tenantId, long actorId, VisitRow row,
                                            OffsetDateTime now) {
        requireTransition(repository.transition(tenantId, row.visitId(), row.version(),
                List.of(VisitState.ARRIVED, VisitState.OVERSTAY), VisitState.CHECKED_OUT,
                null, null, now));
        repository.timeline(tenantId, row.visitId(), actorId, "VISITOR_CHECKED_OUT",
                VisitState.CHECKED_OUT, null, now);
        repository.outbox(tenantId, row.visitId(), "REVOKE_ACCESS",
                row.visitId() + ":revoke:checkout:" + row.version(), null, "PENDING", now);
        return CommandState.SUCCEEDED;
    }


    private ProviderTruth providerTruth(long tenantId, ProviderKind kind, OffsetDateTime now) {
        return repository.provider(tenantId, kind)
                .map(row -> WorkplaceVisitProviderTruth.evaluate(row, now))
                .orElseGet(() -> WorkplaceVisitProviderTruth.missing(kind));
    }

    private void validateGuestRetention(List<GuestRefInput> guests, PolicyRow policy,
                                        OffsetDateTime now) {
        OffsetDateTime maximum = now.plusDays(policy.retentionDays());
        for (GuestRefInput guest : guests) {
            if (!guest.fieldRetentionExpiresAt().keySet()
                    .containsAll(policy.minimumCollectionFields())) {
                throw invalid("Every guest must declare retention expiry for each required field.");
            }
            if (guest.fieldRetentionExpiresAt().values().stream()
                    .anyMatch(expiry -> !expiry.isAfter(now) || expiry.isAfter(maximum))) {
                throw invalid("Guest field retention must be future dated and within policy.");
            }
        }
    }

    private boolean verifyGuestReferences(long tenantId, List<GuestRefInput> guests,
                                          List<String> limitations) {
        boolean allVerified = true;
        for (GuestRefInput guest : guests) {
            List<WorkplaceVisitGuestRefVerificationPort> matching = guestRefVerifiers.stream()
                    .filter(candidate -> candidate.supports(guest.opaqueRef()))
                    .toList();
            if (matching.size() != 1) {
                if (matching.size() > 1) {
                    limitations.add("GUEST_REF_VERIFIER_AMBIGUOUS");
                } else {
                    limitations.add("GUEST_REF_VERIFIER_NOT_CONFIGURED");
                }
                allVerified = false;
                continue;
            }
            WorkplaceVisitGuestRefVerificationPort verifier = matching.getFirst();
            try {
                var result = verifier.verify(new WorkplaceVisitGuestRefVerificationPort
                        .VerificationRequest(tenantId, guest.opaqueRef(), guest.maskedLabel(),
                        guest.purpose(), Map.copyOf(guest.fieldRetentionExpiresAt())));
                if (result == null || !result.valid()) {
                    limitations.add(result == null || result.limitationCode() == null
                            ? "GUEST_REF_INVALID" : result.limitationCode());
                    allVerified = false;
                }
            } catch (RuntimeException unavailable) {
                limitations.add("GUEST_REF_VERIFICATION_UNAVAILABLE");
                allVerified = false;
            }
        }
        return allVerified;
    }

    private RequesterVisit requesterProjection(long tenantId, VisitRow row) {
        List<GuestRefView> guests = repository.guests(tenantId, row.visitId()).stream()
                .map(g -> new GuestRefView(null, g.maskedLabel(), g.purpose(), g.retention())).toList();
        return new RequesterVisit(row.visitId(), reference(row), row.visitType(), row.siteId(),
                row.startsAt(), row.endsAt(), repository.zones(tenantId, row.visitId()), guests,
                row.state(), row.version(), row.state() == VisitState.RESULT_UNKNOWN,
                "/v1/workplace/visits/" + row.visitId(), repository.timeline(tenantId, row.visitId()),
                row.updatedAt());
    }

    private AdminVisit adminProjection(long tenantId, VisitRow row) {
        List<GuestRefView> guests = repository.guests(tenantId, row.visitId()).stream()
                .map(g -> new GuestRefView(g.opaqueRef(), g.maskedLabel(), g.purpose(), g.retention()))
                .toList();
        return new AdminVisit(row.visitId(), row.requesterUserId(), reference(row), row.visitType(),
                row.siteId(), row.startsAt(), row.endsAt(), repository.zones(tenantId, row.visitId()),
                guests, row.state(), row.version(), row.evidenceReference(), row.limitationCode(),
                repository.timeline(tenantId, row.visitId()), row.updatedAt());
    }

    private VisitCommandResult requesterResult(long tenantId, long actorId,
                                                CommandRow receipt, boolean replayed) {
        VisitRow row = repository.requesterVisit(tenantId, actorId, receipt.visitId())
                .orElseThrow(WorkplaceVisitService::notFound);
        return new VisitCommandResult(requesterProjection(tenantId, row), receipt(receipt, replayed));
    }


    private AdminVisitCommandResult adminResult(long tenantId, CommandRow receipt,
                                                boolean replayed) {
        VisitRow row = repository.adminVisit(tenantId, receipt.visitId())
                .orElseThrow(WorkplaceVisitService::notFound);
        return new AdminVisitCommandResult(adminProjection(tenantId, row), receipt(receipt, replayed));
    }

    private CommandReceipt receipt(CommandRow row, boolean replayed) {
        return new CommandReceipt(row.commandId(), row.visitId(), row.state(), row.statusHref(),
                replayed, row.correlationId(), row.createdAt());
    }

    private static ReservationReference reference(VisitRow row) {
        return new ReservationReference(row.authority(), row.reservationId(), row.reservationVersion());
    }

    private void requireReservationCurrent(long tenantId, VisitRow row) {
        ReservationSourceState source = repository.reservationSource(
                tenantId, row.authority(), row.reservationId())
                .orElseThrow(WorkplaceVisitService::notFound);
        if (!source.active() || source.version() != row.reservationVersion()) {
            throw conflict("The reservation changed after the visit preview.");
        }
    }

    private static ExceptionKind exceptionKind(VisitRow row) {
        return switch (row.state()) {
            case APPROVAL_PENDING -> ExceptionKind.APPROVAL_PENDING;
            case ACCESS_FAILED -> ExceptionKind.ACCESS_FAILED;
            case OVERSTAY -> ExceptionKind.OVERSTAY;
            case RESULT_UNKNOWN -> ExceptionKind.RESULT_UNKNOWN;
            default -> ExceptionKind.HOST_UNRESPONSIVE;
        };
    }

    private String firstMaskedLabel(long tenantId, UUID visitId) {
        return repository.guests(tenantId, visitId).stream().findFirst()
                .map(GuestRow::maskedLabel).orElse("•••");
    }

    private static void providerLimitation(ProviderTruth truth, List<String> limitations) {
        if (truth.state() != ProviderTruthState.READY) {
            limitations.add(truth.kind().name() + "_" + truth.state().name());
        }
    }

    private static CommandRow verify(CommandRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) throw conflict(
                "The Idempotency-Key was already used for a different command.");
        return row;
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) throw conflict("The visit version changed. Refresh before retrying.");
    }

    private static void requireTransition(boolean changed) {
        if (!changed) throw conflict("The resource state or version changed. Refresh before retrying.");
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw invalid("Explicit confirmation is required.");
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[!-~]{1,160}")) {
            throw invalid("An opaque Idempotency-Key of 1-160 ASCII characters is required.");
        }
    }

    private static String requireIdentity(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "A valid kiosk device identity is required.");
        }
        return value;
    }

    private static String guestsFingerprint(List<GuestRefInput> guests) {
        List<String> guestFingerprints = guests.stream().map(guest -> fingerprint(
                        guest.opaqueRef(), guest.maskedLabel(),
                        guest.purpose(), guest.fieldRetentionExpiresAt().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(e -> e.getKey() + "=" + e.getValue()).toList()))
                .sorted().toList();
        return fingerprint(guestFingerprints);
    }

    private static String fingerprint(Object... values) {
        String joined = java.util.Arrays.stream(values).map(Objects::toString)
                .reduce("", (a, b) -> a + "\u001f" + b);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    private static BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "The visit resource was not found.");
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    @FunctionalInterface private interface Mutation {
        CommandState apply(VisitRow row, OffsetDateTime now);
    }
}
