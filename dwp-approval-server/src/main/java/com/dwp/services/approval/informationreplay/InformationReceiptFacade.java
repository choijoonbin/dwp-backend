package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import com.dwp.services.approval.domain.ApprovalInformationReceiptSource;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads an existing completed receipt; every database transaction is read-only and freshly revalidated. */
public final class InformationReceiptFacade {
    private final boolean enabled;
    private final Supplier<InformationReplayRuntime> runtime;
    private final InformationReceiptInstalledContext installed;
    private final ApprovalInformationReceiptSource source;
    private final TransactionTemplate initial,current;
    private final Clock clock;
    public InformationReceiptFacade(boolean enabled,Supplier<InformationReplayRuntime> runtime,InformationReceiptInstalledContext installed,
            ApprovalInformationReceiptSource source,PlatformTransactionManager transactions,Clock clock) {
        this.enabled=enabled;this.runtime=runtime;this.installed=installed;this.source=source;this.clock=clock;
        initial=reads(transactions,TransactionDefinition.ISOLATION_REPEATABLE_READ);
        current=reads(transactions,TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public InformationCommandReceipt read(HttpServletRequest request,UUID requestId,String originalKey,byte[] body) {
        if(!enabled) throw unavailable();
        final InformationReplayRuntime components;
        try {components=runtime.get();} catch(org.springframework.beans.BeansException unavailableKeys) {throw unavailable();}
        if(components==null) throw unavailable();
        // Installed DATA authority and dedicated credentials precede the first SQL read.
        var owner=installed.capture(request,requestId,originalKey);var lookup=InformationReceiptBody.parse(body);
        var original=initial.execute(tx->source.capture(owner,lookup));if(original==null) throw unavailable();
        Instant deadline=min(original.deadline(),clock.instant().plusSeconds(30));
        recheck(original,owner,request,lookup,deadline);
        var before=components.client().evaluate(components.issuer().issue(original,deadline));
        deadline=min(deadline,before.expiresAt());recheck(original,owner,request,lookup,deadline);
        var after=components.client().evaluate(components.issuer().issue(original,deadline));before.requireSameCurrent(after);
        deadline=min(deadline,after.expiresAt());recheck(original,owner,request,lookup,deadline);
        return InformationCommandReceipt.from(original.receipt());
    }
    private void recheck(ApprovalInformationReceiptSource.Seal expected,InformationReceiptInstalledContext.Seal owner,
            HttpServletRequest request,InformationReceiptBody lookup,Instant deadline) {
        if(!deadline.isAfter(clock.instant())) throw unavailable();installed.unchanged(owner,request);
        var latest=current.execute(tx->source.capture(owner,lookup));expected.requireSame(latest);
        installed.unchanged(owner,request);if(!deadline.isAfter(clock.instant())) throw unavailable();
    }
    private static Instant min(Instant first,Instant second) {return first.isBefore(second)?first:second;}
    private static TransactionTemplate reads(PlatformTransactionManager transactions,int isolation) {
        var template=new TransactionTemplate(transactions);template.setReadOnly(true);template.setIsolationLevel(isolation);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);template.setTimeout(5);return template;
    }
}
