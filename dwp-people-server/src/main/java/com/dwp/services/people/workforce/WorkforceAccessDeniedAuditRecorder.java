package com.dwp.services.people.workforce;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkforceAccessDeniedAuditRecorder {

    private final AuditOutboxRecorder audit;

    public WorkforceAccessDeniedAuditRecorder(AuditOutboxRecorder audit) {
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event) {
        audit.record(event);
    }
}
