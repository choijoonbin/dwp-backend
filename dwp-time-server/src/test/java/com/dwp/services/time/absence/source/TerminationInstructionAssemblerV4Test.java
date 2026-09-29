package com.dwp.services.time.absence.source;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.*;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.JSON;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4Test.*;
import static com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4Test.*;
import static com.dwp.services.time.absence.source.EntitlementInputAssemblerV4Test.*;
import static com.dwp.services.time.absence.source.TerminationInstructionAssemblerV4.EMPLOYMENT_END_OPERATION;
import static com.dwp.services.time.absence.source.TerminationInstructionAssemblerV4.EMPLOYMENT_END_PURPOSE;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.*;
import com.dwp.services.time.absence.source.EntitlementInputAssemblerV4.Mode;
import com.dwp.services.time.absence.source.TerminationInstructionAssemblerV4.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TerminationInstructionAssemblerV4Test {
    public record EndFixture(Fixture input,TerminationInstructionAssemblerV4 assembler,NewInstruction instruction,
            TerminationInstructionAssemblerV4.InsertReturning returning,
            com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2 authority) {}
    public static EndFixture setupEnd(int n,String treatment) throws Exception {
        Fixture f=setup(n,treatment,Mode.POST);ObjectNode old=wire(fixture("instructionsAB",n));
        var authority=proof(f.original(),EMPLOYMENT_END_OPERATION,EMPLOYMENT_END_PURPOSE);
        var endPolicy=policy(f.original(),authority,treatment);
        ObjectNode ended=seal((ObjectNode)old.get("employmentTermination"),"contentSha256");
        ended.put("asOf",NOW.toString());ended.put("validUntil",NOW.plusSeconds(200).toString());ended=seal(ended,"contentSha256");
        UUID snapshot=uuid(ended,"snapshotPublicId");ObjectNode fullEnd=ended;
        VerifiedTermination term=port().termination(q->fullEnd,new Lookup(snapshot,1,NOW,authority));
        ObjectNode ledger=seal((ObjectNode)old.get("ledgerSummary"),"summarySha256");
        UUID workflow=UUID.fromString("b0000000-0000-4000-8000-00000000007"+n);
        UUID intent=treatment.equals("OWNER_APPROVED_SETTLEMENT")?UUID.fromString("b0000000-0000-4000-8000-00000000008"+n):null;
        Parent corrective=treatment.equals("PRORATE_FINAL_PERIOD")
                ?new Parent("abs_entitlement_runs",41,321+n,UUID.fromString("b0000000-0000-4000-8000-00000000009"+n)):null;
        var source=new RealEmploymentEnd(workflow,f.enrollment(),term,endPolicy,ledger,corrective,intent,
                intent==null?null:SettlementKind.PAST_CONSUMPTION,authority);
        var assembler=new TerminationInstructionAssemblerV4(port(),engine(),CLOCK);
        NewInstruction instruction=assembler.prepare(source);
        var returned=new TerminationInstructionAssemblerV4.InsertReturning(
                new Parent("abs_enrollment_termination_workflows",41,341+n,workflow),
                intent==null?null:new Parent("abs_termination_settlement_intents",41,361+n,intent));
        return new EndFixture(f,assembler,instruction,returned,authority);
    }
    @ParameterizedTest @ValueSource(ints={0,1}) void actualAB27SettlementRecordsAndOwnDigestSlots(int n) throws Exception {
        EndFixture f=setupEnd(n,"OWNER_APPROVED_SETTLEMENT");PersistedTermination p=f.assembler.persist(f.instruction,f.returning);
        oracle(p.workflow());oracle(p.intent().orElseThrow());
        assertEquals("SETTLEMENT_PENDING",p.workflow().get("status").value());assertEquals("DELIVERY_PENDING",p.intent().orElseThrow().get("status").value());
        assertEquals(0L,p.workflow().get("row_version").value());assertEquals(Phase.LIFECYCLE_CONSTRUCTOR,p.workflow().get("row_version").phase());
        assertEquals(1L,p.intent().orElseThrow().get("revision").value());assertNull(p.intent().orElseThrow().get("owner_receipt_payload").value());
        assertEquals(p.workflow().get("termination_workflow_id").value(),p.intent().orElseThrow().get("termination_workflow_id").value());
        assertEquals(p.intent().orElseThrow().get("settlement_intent_id").value(),p.workflow().get("settlement_intent_id").value());
        assertEquals(LocalDate.of(2026,10,25),f.instruction.enrollmentEndExclusive());
        ObjectNode command=f.instruction.command().orElseThrow();
        assertEquals(text(command,"commandSha256"),ownSlotDigest(command,"commandSha256"));
        ObjectNode mutation=command.deepCopy();mutation.put("settlementKind","CLOSED_TIME_PERIOD");
        assertNotEquals(text(command,"commandSha256"),ownSlotDigest(mutation,"commandSha256"));
        System.out.println("J1_TERMINATION_AB_"+n+":"+JSON.writeValueAsString(Map.of("workflow",p.workflow().wireValues(),"intent",p.intent().orElseThrow().wireValues())));
    }
    @ParameterizedTest @ValueSource(strings={"STOP_FUTURE_ONLY","PRORATE_FINAL_PERIOD"})
    void nonSettlementHasNoPhantomIntent(String treatment) throws Exception {
        EndFixture f=setupEnd(0,treatment);PersistedTermination p=f.assembler.persist(f.instruction,f.returning);
        oracle(p.workflow());assertTrue(p.intent().isEmpty());assertTrue(f.instruction.command().isEmpty());
        assertEquals(treatment.equals("STOP_FUTURE_ONLY")?"STOPPED":"CORRECTION_QUEUED",p.workflow().get("status").value());
        assertThrows(IllegalArgumentException.class,()->f.assembler.persist(f.instruction,
                new TerminationInstructionAssemblerV4.InsertReturning(f.returning.workflow(),
                        new Parent("abs_termination_settlement_intents",41,361,ACTOR))));
    }
    @ParameterizedTest @ValueSource(strings={"STOP_ONLY","PRORATE","ownerSettlement","UNKNOWN"})
    void exactFrozenTreatmentEnumRejectsOldPseudoValues(String treatment) throws Exception {
        Fixture f=setup(0,"PRORATE_FINAL_PERIOD",Mode.POST);
        assertThrows(IllegalArgumentException.class,()->policy(f.original(),f.authority(),treatment));
    }
    @Test void entitlementAuthorityCannotAuthorizeEnrollmentCancelOperation() throws Exception {
        Fixture f=setup(0,"STOP_FUTURE_ONLY",Mode.POST);var assembler=new TerminationInstructionAssemblerV4(port(),engine(),CLOCK);
        assertThrows(IllegalArgumentException.class,()->assembler.ordinaryCancel(
                new CancelEnrollment(f.enrollment(),0,LocalDate.of(2026,10,20),"USER_REQUEST",1),f.authority()));
    }

    @Test void ordinaryCancelDoesNotRequireEmploymentEndOrAllocateWorkflow() throws Exception {
        Fixture f=setup(0,"STOP_FUTURE_ONLY",Mode.POST);var assembler=new TerminationInstructionAssemblerV4(port(),engine(),CLOCK);
        var cancelAuthority=proof(f.original(),"tim.leave.enrollment.cancel","TIM_ENROLLMENT_CANCEL");
        var result=assembler.ordinaryCancel(new CancelEnrollment(f.enrollment(),0,LocalDate.of(2026,10,20),"USER_REQUEST",1),cancelAuthority);
        assertEquals("CANCELLED",result.status());assertEquals(LocalDate.of(2026,10,20),result.effectiveTo());
        assertThrows(IllegalArgumentException.class,()->assembler.ordinaryCancel(
                new CancelEnrollment(f.enrollment(),0,LocalDate.of(2026,10,20),"USER_REQUEST",0),cancelAuthority));
        assertThrows(IllegalArgumentException.class,()->assembler.ordinaryCancel(
                new CancelEnrollment(f.enrollment(),0,LocalDate.of(2026,10,16),"USER_REQUEST",1),cancelAuthority));
        var e=f.enrollment();
        var bounded=new com.dwp.services.time.absence.source.EntitlementInputAssemblerV4.Enrollment(e.local(),e.planVersion(),e.revision(),
                e.worker(),e.assignment(),e.effectiveFrom(),LocalDate.of(2026,10,25),e.status());
        assertThrows(IllegalArgumentException.class,()->assembler.ordinaryCancel(
                new CancelEnrollment(bounded,0,LocalDate.of(2026,10,26),"USER_REQUEST",1),cancelAuthority));
        var foreign=new com.dwp.services.time.absence.source.EntitlementInputAssemblerV4.Enrollment(
                new Parent("tme_worker_plan_enrollments",41,71,e.local().publicId()),e.planVersion(),e.revision(),e.worker(),e.assignment(),
                e.effectiveFrom(),null,e.status());
        assertThrows(IllegalArgumentException.class,()->assembler.ordinaryCancel(
                new CancelEnrollment(foreign,0,LocalDate.of(2026,10,20),"USER_REQUEST",1),cancelAuthority));
    }
    @Test void terminalCasAndUnmatchedOrMissingReturnDeny() throws Exception {
        EndFixture f=setupEnd(0,"OWNER_APPROVED_SETTLEMENT");
        assertThrows(IllegalArgumentException.class,()->f.assembler.persist(f.instruction,null));
        assertThrows(IllegalArgumentException.class,()->f.assembler.persist(f.instruction,
                new TerminationInstructionAssemblerV4.InsertReturning(new Parent("abs_enrollment_termination_workflows",42,341,f.returning.workflow().publicId()),f.returning.intent())));
        var old=new ExistingWorkflow(f.returning.workflow(),2,"COMPLETED");
        var next=new ExistingWorkflow(f.returning.workflow(),3,"COMPLETED");
        assertThrows(IllegalArgumentException.class,()->f.assembler.observeCas(new WorkflowCas(old,2,next),f.authority));
        var pending=new ExistingWorkflow(f.returning.workflow(),2,"SETTLEMENT_PENDING");
        var success=new ExistingWorkflow(f.returning.workflow(),3,"COMPLETED");
        assertThrows(IllegalArgumentException.class,()->f.assembler.observeCas(new WorkflowCas(pending,2,success),f.authority));
    }
}
