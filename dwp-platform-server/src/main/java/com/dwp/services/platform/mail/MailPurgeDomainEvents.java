package com.dwp.services.platform.mail;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Records purge completion in the shared durable domain-event outbox. */
@Component
class MailPurgeDomainEvents implements MailPurgeDomainEventPort {

    static final String SOURCE = "urn:dwp:platform:mail";
    static final String TYPE = "mail.purge.completed.v1";

    private final DomainEventRecorder recorder;
    private final ObjectMapper json;

    MailPurgeDomainEvents(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper json) {
        this.recorder = recorder;
        this.json = json;
        contracts.register(TYPE, 1, 1);
    }

    @Override
    public UUID record(
            AdminMailCompletionRepository.PurgeLeaseRow job,
            AdminMailCompletionRepository.DeleteCounts counts) {
        UUID eventId = UUID.nameUUIDFromBytes(String.join("\u001f",
                SOURCE, TYPE, Long.toString(job.tenantId()), job.id().toString())
                .getBytes(StandardCharsets.UTF_8));
        ObjectNode data = json.createObjectNode()
                .put("jobId", job.id().toString())
                .put("candidateSnapshotId", job.snapshotId().toString())
                .put("actorUserId", job.actorId())
                .put("deletedThreads", counts.threads())
                .put("deletedMessages", counts.messages());
        return recorder.record(new DomainEventEnvelope(
                "1.0", eventId, SOURCE, TYPE, 1, job.startedAt().toInstant(),
                "MAIL_PURGE_JOB/" + job.id(), job.tenantId(), "MAIL_PURGE_JOB",
                job.id().toString(), 1, "mail-purge:" + job.id(), null, null,
                data, Map.of()));
    }
}
