package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

/** Reference-only outbox delivery entry point; production owner binding remains absent. */
@Service
class Per004OwnerFinalizeDelivery {
    private final OwnerPort owners;
    private final Per004OwnerEvidenceStore evidence;
    private final Per004ConfigurationRuntime runtime;
    private final TransactionTemplate transactions;
    Per004OwnerFinalizeDelivery(ObjectProvider<OwnerPort> owners, Per004OwnerEvidenceStore evidence,
            Per004ConfigurationRuntime runtime, PlatformTransactionManager transactionManager) {
        this.owners = owners.getIfAvailable(); this.evidence = evidence; this.runtime = runtime;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Optional<FinalizationAcknowledgement> deliver(long tenantId, UUID id, long version) {
        if (owners == null) return Optional.empty();
        FinalizeRequest request;
        try { request = transactions.execute(status -> evidence.pendingFinalization(tenantId, id, version)); }
        catch (RuntimeException unavailableOrNotDue) { return Optional.empty(); }
        try {
            OwnerReceipt receipt = owners.finalizeBinding(request);
            return Optional.ofNullable(transactions.execute(status -> runtime.acknowledge(request, receipt)));
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> evidence.failedDelivery(tenantId, id, version, OffsetDateTime.now(ZoneOffset.UTC)));
            return Optional.empty();
        }
    }
}
