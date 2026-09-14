package com.dwp.services.approval.domain;

import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Actual helper wiring for direct-constructor PG fixtures; absent providers stay disabled. */
public final class ApprovalAttachmentLifecycleTestWiring {
    private static final ValidatorFactory VALIDATION=Validation.buildDefaultValidatorFactory();
    private ApprovalAttachmentLifecycleTestWiring() { }

    public static void bindDefault(ApprovalCommandRepository commands,NamedParameterJdbcTemplate jdbc,
            ApprovalIdentityDirectory identities,ObjectMapper mapper) {
        var canonical=new ApprovalDocumentCanonical(mapper);
        var work=new ApprovalWorkAuthority(identities);
        var owners=new ApprovalDocumentOwnerRepository(jdbc);
        var authority=new ApprovalDocumentAuthority(work,identities,owners,canonical);
        var policies=new ApprovalAttachmentPolicyRepository(jdbc,canonical,VALIDATION.getValidator());
        var facade=new ApprovalAttachmentManifestFacade(jdbc,owners,authority,canonical);
        bind(commands,new ApprovalAttachmentLifecycleBinding(jdbc,work,owners,policies,facade,
                new ApprovalAttachmentProviderGate(Optional.empty(),Optional.empty()),canonical));
    }
    public static void bind(ApprovalCommandRepository commands,ApprovalAttachmentLifecycleBinding binding) {
        commands.bindAttachmentLifecycleBinding(binding);
    }
}
