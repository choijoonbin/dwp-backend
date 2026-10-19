package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;

interface Per004OwnerEvidenceStore {
    void checkCreateEligible(long tenantId, PopulationScope scope, UUID configurationId, String schemaKey);
    void admitCreate(CreateIdentity identity);
    void recordPreparation(CreateIdentity identity, long version, OwnerReceipt receipt,
                           UUID platformCreateReceiptId, PopulationScope scope, OffsetDateTime now);
    Optional<OwnerReceipt> receipt(long tenantId, UUID configurationId, long version, ReceiptKind kind);
    void transition(VersionDetail detail, PopulationScope scope, OwnerReceipt receipt,
                    OffsetDateTime now);
    FinalizeRequest pendingFinalization(long tenantId, UUID configurationId, long version);
    FinalizationAcknowledgement acknowledge(FinalizeRequest request, OwnerReceipt receipt, OffsetDateTime now);
    void failedDelivery(long tenantId, UUID configurationId, long version, OffsetDateTime now);
    CreateLookupResponse lookup(CreateIdentity identity, OffsetDateTime now);
    List<OwnerProjection> project(long tenantId, OwnerProjectionRequest request);
}
