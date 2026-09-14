package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalWorkflowPlanningSelectionSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

@Component
public final class WorkflowPlanningSelectionFacade {
    private final boolean enabled;
    private final WorkflowPlanningSelectionContext installed=new WorkflowPlanningSelectionContext();
    private final ApprovalWorkflowPlanningSelectionSource source;
    private final ApprovalWorkAuthority work;
    public WorkflowPlanningSelectionFacade(@Value("${dwp.approval.workflow-planning.enabled:false}") boolean enabled,
            NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager,ApprovalWorkAuthority work) {
        this.enabled=enabled;source=new ApprovalWorkflowPlanningSelectionSource(jdbc,mapper,manager);this.work=work;
    }
    public WorkflowPlanningSelection select(HttpServletRequest request,UUID workflowId,UUID formId) {
        if(!enabled) throw unavailable();
        var owner=installed.capture(request,workflowId,formId);
        return source.evaluate(owner,()->{
            installed.unchanged(owner,request);if(work==null) throw unavailable();
            if(!owner.actor.equals(work.requireCurrent(WorkflowPlanningProtocol.PERMISSION))
                    || !owner.actor.equals(work.requireCurrent("ACTION.APPROVAL_FORM:VIEW"))) throw denied();
        });
    }
}
