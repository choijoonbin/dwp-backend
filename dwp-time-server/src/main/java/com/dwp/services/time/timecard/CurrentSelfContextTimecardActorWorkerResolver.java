package com.dwp.services.time.timecard;

import com.dwp.platform.contracts.hris.identity.v1.SelfContextPortV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextPurposeV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextQueryV1;
import com.dwp.platform.contracts.hris.identity.v1.SelfContextResolutionV1;
import com.dwp.services.time.timecard.TimecardActorWorkerResolver.ActorWorkerEvidence;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail-closed adapter to the current HRIS identity self-context contract. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class CurrentSelfContextTimecardActorWorkerResolver
        implements TimecardActorWorkerResolver {

    private final ObjectProvider<SelfContextPortV1> ports;

    CurrentSelfContextTimecardActorWorkerResolver(ObjectProvider<SelfContextPortV1> ports) {
        this.ports = ports;
    }

    @Override
    public Optional<ActorWorkerEvidence> resolve(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Instant checkedAt) {
        SelfContextPortV1 port = ports.getIfUnique();
        if (port == null || tenantId <= 0 || actorId <= 0
                || subjectPrincipalPublicId == null || checkedAt == null) {
            return Optional.empty();
        }
        try {
            SelfContextResolutionV1 resolution = port.resolve(new SelfContextQueryV1(
                    SelfContextPurposeV1.SELF_ATTENDANCE_READ, checkedAt, null));
            if (resolution == null
                    || resolution.kind() != SelfContextResolutionV1.Kind.SELECTED
                    || resolution.selected() == null) {
                return Optional.empty();
            }
            var selected = resolution.selected();
            var binding = selected.binding();
            var authority = selected.authority();
            var worker = selected.context().worker();
            if (binding.tenantId() != tenantId || binding.userId() != actorId
                    || !subjectPrincipalPublicId.equals(binding.personPublicId())
                    || authority.tenantId() != tenantId || authority.userId() != actorId
                    || !subjectPrincipalPublicId.equals(authority.personPublicId())
                    || authority.audience() != SelfContextPurposeV1.Audience.HRIS_TIM
                    || authority.purpose() != SelfContextPurposeV1.SELF_ATTENDANCE_READ
                    || !authority.expiresAt().isAfter(checkedAt)
                    || !Objects.equals(binding.personPublicId(), worker.personPublicId())) {
                return Optional.empty();
            }
            return Optional.of(new ActorWorkerEvidence(
                    tenantId, actorId, subjectPrincipalPublicId, worker.publicId(),
                    selected.verifiedAt(), authority.expiresAt()));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
