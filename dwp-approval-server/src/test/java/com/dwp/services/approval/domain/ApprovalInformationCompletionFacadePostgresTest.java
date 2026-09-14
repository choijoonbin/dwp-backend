package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.api.ApprovalController;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalOwnerPredicateEvaluator;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeInformationAdmission;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual Facade/Service advice and SQL; owner context/current source and private RSA admission are explicit fixtures, not installed PEP/Auth proof. */
@Testcontainers
class ApprovalInformationCompletionFacadePostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalInformationCompletionAdmissionsPostgresTest setup;
    ApprovalCommandRepository commands;
    ApprovalQueryRepository queries;
    ApprovalQueryRepository.TaskAccess task;
    ApprovalDtos.DecisionRequest decision;
    @BeforeAll static void keys() throws Exception {ApprovalWorkflowInformationAdmissionPostgresTest.keys();}
    @BeforeEach void initialize() throws Exception {
        setup=new ApprovalInformationCompletionAdmissionsPostgresTest();setup.initialize(PG);
        commands=spy(new ApprovalCommandRepository(new NamedParameterJdbcTemplate(setup.setup.f.jdbc),setup.mapper));queries=mock(ApprovalQueryRepository.class);
        doReturn(ApprovalWorkflowQuorumPostgresFixture.one(ApprovalWorkflowQuorum.Mode.ALL,null)).when(commands).quorumWorkflow(anyLong(),eq(setup.command.requestId()));
        var summary=new ApprovalDtos.TaskSummary(setup.command.taskId(),setup.command.requestId(),"QUORUM","Quorum","","Workflow","Workflow",
                "FINANCE","Finance",1,"Requester","","CLAIMED","NORMAL","INTERNAL",0,Instant.now(),Instant.now().plusSeconds(60),setup.command.expectedTaskVersion());
        task=new ApprovalQueryRepository.TaskAccess(summary,ApprovalWorkflowQuorumPostgresFixture.REQUESTER,100L,"FINANCE_REVIEWER",false,null);
        when(queries.taskDetail(any(),eq(setup.command.taskId()))).thenReturn(task);
        decision=setup.mapper.readValue(setup.request.getContentAsByteArray(),ApprovalDtos.DecisionRequest.class);
    }
    @AfterEach void clear() {setup.clear();}
    ApprovalWorkflowQuorumInformationFacade facade(ApprovalWorkflowInformationCompletionAdmissions admissions) {
        var f=setup.setup.f;var named=new NamedParameterJdbcTemplate(f.jdbc);var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("fixtureAuthority",f);
        var work=mock(ApprovalWorkAuthority.class);
        when(work.require(anyString(),anyBoolean())).thenAnswer(i->ApprovalRequestContext.require());
        when(work.requireCurrent(anyString())).thenAnswer(i->ApprovalRequestContext.require());
        return new ApprovalWorkflowQuorumInformationFacade(commands,queries,named,setup.mapper,f.tx.getTransactionManager(),
                beans.getBeanProvider(ApprovalWorkflowQuorumAuthority.class),(a,r,v,h,s,p,b,e)->p,setup.metadata,work,
                beans.getBeanProvider(ApprovalOwnerPredicateEvaluator.class),new AuditOutboxRecorder(named,setup.mapper,"dwp-approval-server","test","test"),admissions);
    }
    @Test void springSelectedRequiredConstructorActuallyStampsThroughTheRuntimeCompletionOverload() {
        var information=facade(setup.admissions);var actor=ApprovalRequestContext.require();
        var result=setup.setup.f.tx.execute(tx->information.request(actor,task,decision,setup.command.expectedQuorum(),()->{}));
        assertEquals("REQUEST_INFO",result.decision());assertEquals(1,setup.setup.f.count("apr_quorum_information_admissions"));
        assertEquals(1,setup.setup.f.count("apr_quorum_information_completion_transactions"));
    }
    @Test void actualServiceProxyCommitsOnlyUnknownBeforeControllerTranslatesALateCompletionFailure() {
        var failing=spy(setup.admissions);
        doAnswer(invocation->{
            invocation.callRealMethod();
            return (Consumer<ApprovalWorkflowQuorumInformationRuntime.Receipt>)receipt->{throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);};
        }).when(failing).request(any(),any());
        var f=setup.setup.f;var named=new NamedParameterJdbcTemplate(f.jdbc);var beans=new DefaultListableBeanFactory();beans.registerSingleton("fixtureAuthority",f);
        var information=facade(failing);
        var quorum=new ApprovalWorkflowQuorumFacade(commands,named,setup.mapper,f.tx.getTransactionManager(),
                beans.getBeanProvider(ApprovalWorkflowQuorumAuthority.class),mock(AuditOutboxRecorder.class),information);
        var target=new ApprovalService(queries,commands,mock(AuditOutboxRecorder.class),mock(ApprovalIdentityDirectory.class),null,null,quorum);
        var proxy=new ProxyFactory(target);proxy.setProxyTargetClass(true);
        var advice=new TransactionInterceptor();advice.setTransactionManager(f.tx.getTransactionManager());
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());proxy.addAdvice(advice);
        var controller=new ApprovalController((ApprovalService)proxy.getProxy(),mock(ApprovalDraftService.class),setup.metadata);
        var error=assertThrows(BaseException.class,()->controller.decide(setup.command.taskId(),decision,null));
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,error.getErrorCode());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1,f.count("apr_quorum_information_commands"));assertEquals("UNKNOWN",f.jdbc.queryForObject("SELECT status FROM apr_quorum_information_commands",String.class));
        assertEquals("IN_REVIEW",f.jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?",String.class,f.request));
        assertEquals(0,f.count("apr_quorum_information_rounds"));assertEquals(0,f.count("apr_quorum_information_admissions"));
        assertEquals(0,f.count("apr_quorum_information_completion_transactions"));
    }
    @Test void validPublicMetadataCannotBypassTheRequiredPrivateAdmissionInProductionConstructor() {
        setup.request.removeAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->facade(setup.admissions)
                .request(ApprovalRequestContext.require(),task,decision,setup.command.expectedQuorum(),()->{})).getErrorCode());
        assertEquals(0,setup.setup.f.count("apr_quorum_information_commands"));assertEquals(0,setup.setup.f.count("apr_quorum_information_admissions"));
    }
}
