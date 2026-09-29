package com.dwp.services.people.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v1.AuthPersonBindingV1;
import com.dwp.platform.contracts.hris.identity.v2.*;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationPortsV2.PeopleLookup;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.*;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisTargetReadEvidencePortsV2.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.Code.*;

/** Explicit MOCK Gateway/Auth-role/date/inputs. No native Auth producer, transport or final PEP. */
class NativeHrisUserPolicyAdmissionPilotV2Test {
    static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    static final long TENANT = 41, USER = 9;
    enum Positive { PERSON_WITHOUT_EMPLOYMENT, EMPLOYMENT_ALL_FOUR_ZERO_VERSIONS, INCLUSIVE_END_DATE, PROGRESSING_CLOCK }
    @ParameterizedTest(name="{index} positive={0}") @EnumSource(Positive.class)
    void nativeReadSubsetWitness(Positive kind) {
        Fixture f = new Fixture();
        if (kind != Positive.PERSON_WITHOUT_EMPLOYMENT) f.kind = TargetKind.EMPLOYMENT;
        f.progress = kind == Positive.PROGRESSING_CLOCK;
        var result = f.pilot().read(f.lookup());
        assertEquals(f.person, result.target().personPublicId());
        assertEquals(0, result.target().personVersion());
        assertEquals(Set.of("person.publicId"), result.fieldPaths());
        assertEquals(1, result.matchedPolicies().size());
        if (f.kind == TargetKind.EMPLOYMENT) {
            assertEquals(f.worker, result.target().workerPublicId());
            assertEquals(f.relationship, result.target().workRelationshipPublicId());
            assertEquals(f.assignment, result.target().assignmentPublicId());
            assertEquals(0L, result.target().assignmentVersion());
        } else assertNull(result.target().workerPublicId());
        assertEquals(f.time.get(), result.capturedAt()); assertTrue(result.expiresAt().isAfter(result.capturedAt()));
        f.calls(2,2, f.kind == TargetKind.EMPLOYMENT ? 2 : 0,2);
    }
    enum Negative { MISSING_GATEWAY, MISSING_ROLES, MISSING_INPUTS, MISSING_CLOCK, MISSING_DATE,
        WRONG_SCOPE, WRONG_GATEWAY_USER, WRONG_GATEWAY_REVISION, WRONG_ROLE_AUTH_REVISION,
        INCOMPLETE_ROLES, WRONG_ROLE_USER, WRONG_ROLE_NATIVE_VERSION, ROLE_ID_COLLISION,
        GROUP_MISSING_NATIVE_VERSION, EXPIRED_ROLE_SOURCE, MATCHING_ROLE_POLICY,
        WRONG_PERSON_PARENT, FOREIGN_TARGET_TENANT, MERGED_PERSON, EXPIRED_RELATIONSHIP,
        EXPIRED_ASSIGNMENT, FOREIGN_POLICY_TENANT, WRONG_POLICY_USER, FOREIGN_POLICY_ORG,
        WRONG_POLICY_ACTION, NO_POLICY, FIELD_SCOPE_CROSS_JOIN, PERSON_ORG_POLICY,
        POLICY_VERSION_CHANGED, TARGET_VERSION_CHANGED, ROLE_SOURCE_CHANGED, GATEWAY_CHANGED,
        EXPIRED_FIRST_PROOF, EXPIRED_POLICY_DURING_READ, BACKWARDS_CLOCK, PROVIDER_SECRET, CLOCK_SECRET, BAD_DATE_PURPOSE, DATE_CHANGED,
        SECOND_GATEWAY_SHORT_LEASE, SECOND_ROLE_SHORT_LEASE, SECOND_DATE_SHORT_LEASE,
        GROUP_VALID_TO_DURING_LAST_INPUT, PRIVILEGED_VALID_TO_DURING_LAST_INPUT,
        ROLE_SOURCE_ID_COLLISION, ROLE_CODE_COLLISION, DIRECT_HAS_NONEXISTENT_WINDOW }
    @ParameterizedTest(name="{index} negative={0}") @EnumSource(Negative.class)
    void typedRejectNeverTreatsMissingAuthorityOrConjunctionAsAllow(Negative kind) {
        Fixture f = new Fixture(); f.negative = kind;
        if (Set.of(Negative.MISSING_DATE,Negative.WRONG_PERSON_PARENT,Negative.EXPIRED_RELATIONSHIP,
                Negative.EXPIRED_ASSIGNMENT,Negative.FIELD_SCOPE_CROSS_JOIN,Negative.BAD_DATE_PURPOSE,Negative.DATE_CHANGED,
                Negative.SECOND_DATE_SHORT_LEASE).contains(kind)) f.kind=TargetKind.EMPLOYMENT;
        if (kind==Negative.FIELD_SCOPE_CROSS_JOIN) f.field="employment.jobGrade";
        var failure=assertThrows(Rejected.class,()->f.pilot().read(f.lookup()));
        assertEquals(expected(kind),failure.code()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private-sentinel"));
        if (Set.of(Negative.MISSING_GATEWAY,Negative.MISSING_ROLES,Negative.MISSING_INPUTS,
                Negative.MISSING_CLOCK,Negative.MISSING_DATE,Negative.CLOCK_SECRET).contains(kind)) f.calls(0,0,0,0);
        if (Set.of(Negative.WRONG_SCOPE,Negative.WRONG_GATEWAY_USER,Negative.WRONG_GATEWAY_REVISION).contains(kind)) f.calls(1,0,0,0);
        if (Set.of(Negative.WRONG_ROLE_AUTH_REVISION,Negative.INCOMPLETE_ROLES,Negative.WRONG_ROLE_USER,
                Negative.WRONG_ROLE_NATIVE_VERSION,Negative.ROLE_ID_COLLISION,Negative.GROUP_MISSING_NATIVE_VERSION,
                Negative.EXPIRED_ROLE_SOURCE,Negative.ROLE_SOURCE_ID_COLLISION,Negative.ROLE_CODE_COLLISION,
                Negative.DIRECT_HAS_NONEXISTENT_WINDOW).contains(kind)) f.calls(1,1,0,0);
        if(Set.of(Negative.SECOND_GATEWAY_SHORT_LEASE,Negative.SECOND_ROLE_SHORT_LEASE,
                Negative.GROUP_VALID_TO_DURING_LAST_INPUT,Negative.PRIVILEGED_VALID_TO_DURING_LAST_INPUT).contains(kind)) f.calls(2,2,0,2);
        if(kind==Negative.SECOND_DATE_SHORT_LEASE)f.calls(2,2,2,2);
    }
    static Code expected(Negative n) {
        return switch(n) {
            case MISSING_GATEWAY,MISSING_ROLES,MISSING_INPUTS,MISSING_CLOCK,PROVIDER_SECRET -> UNAVAILABLE;
            case MISSING_DATE -> DATE_POLICY_UNAVAILABLE;
            case WRONG_SCOPE,WRONG_GATEWAY_USER,WRONG_GATEWAY_REVISION,GATEWAY_CHANGED -> CONTEXT_INVALID;
            case WRONG_ROLE_AUTH_REVISION,INCOMPLETE_ROLES,WRONG_ROLE_USER,WRONG_ROLE_NATIVE_VERSION,
                ROLE_ID_COLLISION,GROUP_MISSING_NATIVE_VERSION,EXPIRED_ROLE_SOURCE,ROLE_SOURCE_ID_COLLISION,
                ROLE_CODE_COLLISION,DIRECT_HAS_NONEXISTENT_WINDOW -> ROLE_EVIDENCE_INVALID;
            case MATCHING_ROLE_POLICY -> UNSUPPORTED_POLICY;
            case WRONG_POLICY_ACTION,NO_POLICY,FIELD_SCOPE_CROSS_JOIN,PERSON_ORG_POLICY -> POLICY_DENIED;
            case POLICY_VERSION_CHANGED,TARGET_VERSION_CHANGED,ROLE_SOURCE_CHANGED,DATE_CHANGED -> SOURCE_CHANGED;
            case EXPIRED_FIRST_PROOF,EXPIRED_POLICY_DURING_READ,SECOND_GATEWAY_SHORT_LEASE,SECOND_ROLE_SHORT_LEASE,
                SECOND_DATE_SHORT_LEASE,GROUP_VALID_TO_DURING_LAST_INPUT,PRIVILEGED_VALID_TO_DURING_LAST_INPUT -> STALE;
            case BACKWARDS_CLOCK,CLOCK_SECRET -> CLOCK_INVALID;
            case BAD_DATE_PURPOSE -> DATE_POLICY_INVALID;
            default -> NATIVE_INVALID;
        };
    }

    /** Shared with isolated-PG tests. This acquires a private lookup through the real v2
     * guard, stops at the MOCK owner callback and never mints a fake verified final result. */
    static final class Fixture {
        UUID principal=UUID.randomUUID(),person=UUID.randomUUID(),worker=UUID.randomUUID(),relationship=UUID.randomUUID(),assignment=UUID.randomUUID();
        UUID orgA=UUID.randomUUID(),orgB=UUID.randomUUID(),policy=UUID.randomUUID();
        TargetKind kind=TargetKind.PERSON; String field="person.publicId"; Negative negative; boolean progress;
        final AtomicInteger gatewayCalls=new AtomicInteger(),roleCalls=new AtomicInteger(),dateCalls=new AtomicInteger(),inputCalls=new AtomicInteger();
        final AtomicReference<Instant> time=new AtomicReference<>(NOW);
        final Clock clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){if(negative==Negative.CLOCK_SECRET)throw new IllegalStateException("private-sentinel");return time.get();}
        };
        void advance(){if(progress)time.set(time.get().plusSeconds(1));}
        Requirements requirements(){return new Requirements("candidate.native.people.user.read","hcm","hcm.operations",
                "route.hcm.operations.person-detail.data","NATIVE_USER_POLICY_READ","dwp-people-server",Plane.MANAGEMENT,
                AccessMode.NORMAL,Selection.TARGET_POPULATION,kind,Set.of("APP.HRIS:VIEW"),Set.of(),Set.of(field),false);}
        ReadOperation operation(){return new ReadOperation(requirements(),Map.of(field,field.equals("person.publicId")?"DIRECTORY":"JOB_GRADE"),Set.of("ACTIVE"));}
        PeopleLookup lookup(){
            var result=new AtomicReference<PeopleLookup>();
            var auth=new AuthPersonBindingV1(TENANT,USER,principal,null,AuthPersonBindingV1.IdentityPlane.TENANT,
                    AuthPersonBindingV1.Status.ACTIVE,7,3,NOW,NOW.plusSeconds(20));
            var guard=new GuardedCurrentHrisAuthorizationPortV2(requirements(),
                    ()->new Invocation(TENANT,USER,principal,null,7,3,requirements().operationId(),"verified-session",NOW,NOW.plusSeconds(20)),
                    q->new DwpAuthoritySnapshot(requirements(),auth,Decision.ALLOWED,new AuthRevision("auth-"+"a".repeat(64)),
                            new PolicyRevision("policy-6-7-"+"b".repeat(64)),new ContextKey("psc-"+"c".repeat(64)),
                            new DecisionRevision("psr-"+"d".repeat(64)),"selected.native-scope",Set.of("APP.HRIS:VIEW"),Set.of(),true,true,NOW,NOW.plusSeconds(20)),
                    q->{result.set(q);return null;},Clock.fixed(NOW,ZoneOffset.UTC));
            assertThrows(CurrentHrisAuthorizationExceptionV2.class,()->guard.authorize(new TargetSelector(kind,person,
                    kind==TargetKind.EMPLOYMENT?worker:null,kind==TargetKind.EMPLOYMENT?relationship:null,kind==TargetKind.EMPLOYMENT?assignment:null)));
            assertNotNull(result.get());return result.get();
        }
        GatewayContext gateway(PeopleLookup q){int call=gatewayCalls.incrementAndGet();advance();
            if(negative==Negative.PROVIDER_SECRET)throw new IllegalStateException("private-sentinel");
            var a=q.authority();return new GatewayContext(requirements(),TENANT,negative==Negative.WRONG_GATEWAY_USER?10:USER,
                    negative==Negative.WRONG_GATEWAY_REVISION?"auth-foreign":a.authRevision().value(),a.policyRevision().value(),
                    a.contextKey().value(),negative==Negative.GATEWAY_CHANGED&&call==2?"psr-changed":a.decisionRevision().value(),
                    negative==Negative.WRONG_SCOPE?"foreign-scope":a.selectedScopeKey(),time.get(),time.get().plusSeconds(negative==Negative.SECOND_GATEWAY_SHORT_LEASE&&call==2?1:20));}
        NativeHrisCurrentRoleEvidenceV2 roles(PeopleLookup q){int call=roleCalls.incrementAndGet();advance();
            var rows=new ArrayList<NativeHrisCurrentRoleEvidenceV2.RoleSource>();
            if(negative!=null && Set.of(Negative.ROLE_ID_COLLISION,Negative.GROUP_MISSING_NATIVE_VERSION,Negative.EXPIRED_ROLE_SOURCE,
                    Negative.ROLE_SOURCE_CHANGED,Negative.MATCHING_ROLE_POLICY,Negative.ROLE_SOURCE_ID_COLLISION,
                    Negative.ROLE_CODE_COLLISION,Negative.DIRECT_HAS_NONEXISTENT_WINDOW,
                    Negative.GROUP_VALID_TO_DURING_LAST_INPUT,Negative.PRIVILEGED_VALID_TO_DURING_LAST_INPUT).contains(negative)) {
                boolean groupExpiry=negative==Negative.GROUP_VALID_TO_DURING_LAST_INPUT;
                boolean privileged=negative==Negative.PRIVILEGED_VALID_TO_DURING_LAST_INPUT;
                rows.add(new NativeHrisCurrentRoleEvidenceV2.RoleSource(5,"HR_OPERATOR",call==2&&negative==Negative.ROLE_SOURCE_CHANGED?1:0,
                        privileged?NativeHrisCurrentRoleEvidenceV2.SourceKind.PRIVILEGED:groupExpiry||negative==Negative.GROUP_MISSING_NATIVE_VERSION?NativeHrisCurrentRoleEvidenceV2.SourceKind.GROUP:NativeHrisCurrentRoleEvidenceV2.SourceKind.DIRECT,
                        privileged?null:8L,privileged?UUID.nameUUIDFromBytes(principal.toString().getBytes()):null,groupExpiry?0L:null,NOW.minusSeconds(10),
                        groupExpiry?6L:null,groupExpiry?0L:null,groupExpiry?NOW.minusSeconds(10):null,
                        privileged||negative==Negative.DIRECT_HAS_NONEXISTENT_WINDOW?NOW.minusSeconds(10):null,
                        negative==Negative.EXPIRED_ROLE_SOURCE?NOW:groupExpiry||privileged||negative==Negative.DIRECT_HAS_NONEXISTENT_WINDOW?NOW.plusSeconds(1):null));
            }
            if(negative==Negative.ROLE_ID_COLLISION)rows.add(new NativeHrisCurrentRoleEvidenceV2.RoleSource(5,"OTHER_ROLE",0,
                    NativeHrisCurrentRoleEvidenceV2.SourceKind.DIRECT,9L,null,null,NOW.minusSeconds(10),null,null,null,null,null));
            if(negative==Negative.ROLE_SOURCE_ID_COLLISION||negative==Negative.ROLE_CODE_COLLISION)
                rows.add(new NativeHrisCurrentRoleEvidenceV2.RoleSource(6,negative==Negative.ROLE_CODE_COLLISION?"HR_OPERATOR":"OTHER_ROLE",0,
                        NativeHrisCurrentRoleEvidenceV2.SourceKind.DIRECT,negative==Negative.ROLE_SOURCE_ID_COLLISION?8L:9L,null,null,NOW.minusSeconds(10),null,null,null,null,null));
            return new NativeHrisCurrentRoleEvidenceV2(TENANT,negative==Negative.WRONG_ROLE_USER?10:USER,principal,
                    negative==Negative.WRONG_ROLE_NATIVE_VERSION?8:7,3,negative==Negative.WRONG_ROLE_AUTH_REVISION?"auth-foreign":q.authority().authRevision().value(),
                    negative!=Negative.INCOMPLETE_ROLES,rows,time.get(),time.get().plusSeconds(negative==Negative.SECOND_ROLE_SHORT_LEASE&&call==2?1:20));}
        EmploymentDatePolicy date(PeopleLookup q,Instant asOf){int call=dateCalls.incrementAndGet();advance();return new EmploymentDatePolicy(requirements().operationId(),
                negative==Negative.BAD_DATE_PURPOSE?"OTHER_PURPOSE":requirements().purpose(),requirements().audience(),"MOCK-owner-date-policy","MOCK-date-revision",
                asOf,LocalDate.of(2026,9,14).plusDays(negative==Negative.DATE_CHANGED&&call==2?1:0),time.get(),time.get().plusSeconds(negative==Negative.SECOND_DATE_SHORT_LEASE&&call==2?1:20));}
        Inputs inputs(PeopleLookup q,Set<String> roles,Instant asOf,LocalDate date){int call=inputCalls.incrementAndGet();advance();
            if(negative==Negative.BACKWARDS_CLOCK)time.set(NOW.minusSeconds(1));
            if(negative==Negative.EXPIRED_FIRST_PROOF&&call==2)time.set(NOW.plusSeconds(20));
            if(negative==Negative.EXPIRED_POLICY_DURING_READ&&call==2)time.set(NOW.plusSeconds(1));
            if(negative!=null&&Set.of(Negative.SECOND_GATEWAY_SHORT_LEASE,Negative.SECOND_ROLE_SHORT_LEASE,Negative.SECOND_DATE_SHORT_LEASE,
                    Negative.GROUP_VALID_TO_DURING_LAST_INPUT,Negative.PRIVILEGED_VALID_TO_DURING_LAST_INPUT).contains(negative)&&call==2)time.set(NOW.plusSeconds(1));
            boolean emp=kind==TargetKind.EMPLOYMENT;
            var target=new TargetSnapshot(kind,negative==Negative.FOREIGN_TARGET_TENANT?42:TENANT,person,
                    negative==Negative.TARGET_VERSION_CHANGED&&call==2?1:0,negative==Negative.MERGED_PERSON?"MERGED":"ACTIVE",
                    emp?worker:null,emp?(negative==Negative.WRONG_PERSON_PARENT?UUID.randomUUID():person):null,emp?0L:null,
                    emp?relationship:null,emp?worker:null,emp?0L:null,emp?assignment:null,emp?relationship:null,emp?0L:null);
            var policies=new ArrayList<Policy>();
            policies.add(new Policy(policy,negative==Negative.FOREIGN_POLICY_TENANT?42:TENANT,"USER",negative==Negative.WRONG_POLICY_USER?"10":"9",
                    negative==Negative.PERSON_ORG_POLICY||negative==Negative.FOREIGN_POLICY_ORG||negative==Negative.FIELD_SCOPE_CROSS_JOIN?"ORG_UNIT":"TENANT",
                    negative==Negative.PERSON_ORG_POLICY||negative==Negative.FOREIGN_POLICY_ORG||negative==Negative.FIELD_SCOPE_CROSS_JOIN?orgA:null,
                    negative!=Negative.FOREIGN_POLICY_ORG,Set.of(negative==Negative.FIELD_SCOPE_CROSS_JOIN?"JOB_GRADE":"DIRECTORY"),
                    Set.of(negative==Negative.WRONG_POLICY_ACTION?"EXPORT":"READ"),null,negative==Negative.EXPIRED_POLICY_DURING_READ?NOW.plusSeconds(1):null,
                    "ACTIVE",negative==Negative.POLICY_VERSION_CHANGED&&call==2?1:0));
            if(negative==Negative.FIELD_SCOPE_CROSS_JOIN)policies.add(new Policy(UUID.nameUUIDFromBytes("stable-second-policy".getBytes()),TENANT,"USER","9","ORG_UNIT",orgB,true,Set.of("DIRECTORY"),Set.of("READ"),null,null,"ACTIVE",0));
            if(negative==Negative.MATCHING_ROLE_POLICY)policies.add(new Policy(UUID.nameUUIDFromBytes("stable-role-policy".getBytes()),TENANT,"ROLE","HR_OPERATOR","TENANT",null,false,Set.of("DIRECTORY"),Set.of("READ"),null,null,"ACTIVE",0));
            if(negative==Negative.NO_POLICY)policies.clear();
            return new Inputs(target,emp?"ACTIVE":null,emp?LocalDate.of(2020,1,1):null,
                    emp?(negative==Negative.EXPIRED_RELATIONSHIP?LocalDate.of(2026,9,13):LocalDate.of(2026,9,14)):null,
                    emp?"ACTIVE":null,emp?LocalDate.of(2020,1,1):null,
                    emp?(negative==Negative.EXPIRED_ASSIGNMENT?LocalDate.of(2026,9,13):LocalDate.of(2026,9,14)):null,
                    emp?"native-assignment":null,0,emp?orgB:null,emp?List.of(new Organization(orgB,0,null,"ACTIVE")):List.of(),policies);}
        NativeHrisUserPolicyAdmissionPilotV2 pilot(){return new NativeHrisUserPolicyAdmissionPilotV2(operation(),negative==Negative.MISSING_GATEWAY?null:this::gateway,
                negative==Negative.MISSING_ROLES?null:this::roles,negative==Negative.MISSING_DATE?null:this::date,
                negative==Negative.MISSING_INPUTS?null:this::inputs,negative==Negative.MISSING_CLOCK?null:clock);}
        void calls(int g,int r,int d,int i){assertEquals(g,gatewayCalls.get());assertEquals(r,roleCalls.get());assertEquals(d,dateCalls.get());assertEquals(i,inputCalls.get());}
    }
}
