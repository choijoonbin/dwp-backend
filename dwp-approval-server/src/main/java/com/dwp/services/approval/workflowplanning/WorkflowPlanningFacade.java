package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalWorkflowStudioSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only planning is never a requester/voter authorization or a durable stage snapshot. */
public final class WorkflowPlanningFacade {
    private final boolean enabled;
    private final Supplier<WorkflowPlanningRuntime> runtime;
    private final WorkflowPlanningInstalledContext installed;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager manager;
    private final ApprovalWorkAuthority work;
    public WorkflowPlanningFacade(boolean enabled,Supplier<WorkflowPlanningRuntime> runtime,WorkflowPlanningInstalledContext installed,
            NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager,ApprovalWorkAuthority work) {
        this.enabled=enabled;this.runtime=runtime;this.installed=installed;this.jdbc=jdbc;this.mapper=mapper;this.manager=manager;this.work=work;
    }
    public WorkflowPlanningResult simulate(HttpServletRequest request,UUID workflowId,UUID versionId,byte[] raw) {
        if(!enabled) throw unavailable();
        final WorkflowPlanningRuntime exchange;
        try {exchange=runtime.get();} catch(org.springframework.beans.BeansException missing) {throw unavailable();}
        if(exchange==null) throw unavailable();
        var owner=installed.capture(request,workflowId,versionId);var body=WorkflowPlanningBody.parse(raw);var selection=body.selection(workflowId,versionId);
        if(!owner.scope.resourceSetKey().equals(selection.managementResourceSetKey())) throw denied();
        var source=new ApprovalWorkflowStudioSource(jdbc,mapper,new TransactionTemplate(manager),(actor,chosen,snapshot,now)->{
            installed.unchanged(owner,request);
            if(!owner.actor.equals(actor) || !owner.scope.resourceSetKey().equals(chosen.managementResourceSetKey()) || work==null) throw unavailable();
            if(!actor.equals(work.requireCurrent(PERMISSION)) || !actor.equals(work.requireCurrent("ACTION.APPROVAL_FORM:VIEW"))) throw denied();
        });
        return source.evaluate(owner.actor,selection,snapshot->{
            var first=exchange.client().evaluate(exchange.issuer().issue(owner,snapshot));installed.unchanged(owner,request);
            var second=exchange.client().evaluate(exchange.issuer().issue(owner,snapshot));first.requireSameCurrent(second);installed.unchanged(owner,request);
            return second.result(snapshot);
        });
    }
}
