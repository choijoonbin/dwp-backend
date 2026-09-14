package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumRuntimeStore.conflict;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding.Intent;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding.Target;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only the locked, server-owned information context can prepare a same-transaction attachment reply. */
@Component
public final class ApprovalWorkflowInformationAttachments {
    static final class PreparedReply {
        private final ApprovalAttachmentLifecycleBinding binding;
        private final ApprovalAttachmentLifecycleBinding.Pin pin;
        private final ApprovalRequestContext.Actor actor;
        private final Target source;
        private boolean sealed;

        private PreparedReply(ApprovalAttachmentLifecycleBinding binding,ApprovalAttachmentLifecycleBinding.Pin pin,
                ApprovalRequestContext.Actor actor,Target source) {
            this.binding=binding;this.pin=pin;this.actor=actor;this.source=source;
        }
        boolean materialChange() {return pin.materialChange();}
        void seal(int revision,String payloadSha256) {
            transaction();
            if(!actor.equals(ApprovalRequestContext.require())) throw new BaseException(ErrorCode.FORBIDDEN);
            if(sealed || payloadSha256==null || !payloadSha256.matches("[a-f0-9]{64}")) throw conflict();
            boolean material=pin.materialChange() || !source.payloadSha256().equals(payloadSha256);
            final int expected;
            try {expected=material?Math.addExact(source.payloadRevision(),1):source.payloadRevision();}
            catch(ArithmeticException overflow) {throw conflict();}
            if(revision!=expected) throw conflict();
            binding.seal(pin,new Target(source.formVersionId(),source.workflowVersionId(),source.resourceSetKey(),
                    source.formSchemaSha256(),revision,payloadSha256));
            sealed=true;
        }
    }

    private final ApprovalAttachmentLifecycleBinding binding;
    public ApprovalWorkflowInformationAttachments(ApprovalAttachmentLifecycleBinding binding) {this.binding=binding;}

    PreparedReply prepare(UUID request,ApprovalWorkflowQuorumInformationContext.Bound bound) {
        transaction();
        if(request==null || bound==null) throw conflict();
        var actor=ApprovalRequestContext.require();var context=bound.context();
        if(actor.personPublicId()==null || actor.tenantId()!=context.pins().tenantId() || actor.userId()!=context.requesterUserId()
                || !actor.personPublicId().equals(context.requesterPersonId())) throw new BaseException(ErrorCode.FORBIDDEN);
        var expected=new Target(context.formVersionId(),context.pins().workflowVersionId(),bound.resourceSet(),
                context.pins().formSchemaSha256(),context.payloadRevision(),context.payloadSha256());
        var pin=binding.prepare(request,bound.requestVersion(),Intent.INFO_RESPONSE);
        if(pin==null || pin.sourcePayloadRevision()!=expected.payloadRevision()
                || !expected.payloadSha256().equals(pin.sourcePayloadSha256()) || !expected.equals(binding.target(request))) throw conflict();
        return new PreparedReply(binding,pin,actor,expected);
    }
    private static void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw conflict();
    }
}
