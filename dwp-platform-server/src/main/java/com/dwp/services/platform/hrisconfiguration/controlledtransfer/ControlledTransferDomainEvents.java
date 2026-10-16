package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.transfer.v1.ControlledTransferStateChangedV1;
import com.dwp.services.platform.hrisconfiguration.contract.ControlledTransferEventContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.PreparedTransfer;

@Component
final class ControlledTransferDomainEvents {

    static final String TYPE = ControlledTransferEventContract.TYPE;
    private static final String SOURCE = ControlledTransferEventContract.SOURCE;
    private static final String AGGREGATE =
            ControlledTransferEventContract.AGGREGATE_TYPE;

    private final DomainEventRecorder recorder;
    private final ObjectMapper json;

    ControlledTransferDomainEvents(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper json) {
        this.recorder = recorder;
        this.json = json;
        contracts.register(TYPE, 1, 1);
    }

    UUID requested(PreparedTransfer transfer, String receiptDigest, OffsetDateTime occurredAt) {
        ControlledTransferStateChangedV1 event =
                new ControlledTransferStateChangedV1(
                        transfer.transferPublicId(),
                        ControlledTransferStateChangedV1.OwnerModule.valueOf(
                                transfer.ownerModule().name()),
                        ControlledTransferStateChangedV1.TransferKind.valueOf(
                                transfer.transferKind().name()),
                        transfer.access().purposeCode(),
                        ControlledTransferStateChangedV1.State.REQUESTED,
                        transfer.objectVersion(), transfer.objectDigest(),
                        transfer.byteSize(), transfer.mediaType(),
                        transfer.metadataSnapshotDigest(),
                        transfer.egressPolicyRevision(), receiptDigest,
                        occurredAt.toInstant());
        UUID eventId = UUID.nameUUIDFromBytes(String.join("\u001f",
                SOURCE, TYPE, Long.toString(transfer.access().tenantId()),
                transfer.transferPublicId().toString(), "1")
                .getBytes(StandardCharsets.UTF_8));
        return recorder.record(new DomainEventEnvelope(
                "1.0", eventId, SOURCE, TYPE, 1, occurredAt.toInstant(),
                AGGREGATE + "/" + transfer.transferPublicId(),
                transfer.access().tenantId(), AGGREGATE,
                transfer.transferPublicId().toString(), 1,
                transfer.access().correlationId().toString(), null, null,
                json.valueToTree(event), Map.of()));
    }
}
