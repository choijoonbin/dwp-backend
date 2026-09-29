package com.dwp.services.people.hris.identity.v2;

import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.TargetKind;
import com.dwp.platform.contracts.hris.identity.v2.NativeHrisTargetReadEvidencePortsV2.CurrentAuthRoleEvidenceProvider;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisUserPolicyAdmissionPilotV2Test.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.*;
import static com.dwp.services.people.hris.identity.v2.NativeHrisTargetPolicyInputsV2.Code.*;

/** Native People only; Gateway/Auth-role/date proofs are explicit MOCK ONLY. No production PEP. */
@Testcontainers
class NativeHrisTargetPolicyQueryReaderV2PostgresTest {
    private static final String RUNTIME="people_native_target_read_test";
    private static final String PASSWORD=UUID.randomUUID().toString();
    @Container private static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE","postgres:16-alpine"));
    private static JdbcTemplate owner;
    private static PGSimpleDataSource runtime;
    private Fixture f;
    private NativeHrisTargetPolicyQueryReaderV2 reader;
    private long personId,workerId,relationshipId,assignmentId,orgAId,orgBId;
    private UUID tenantPolicy;
    private final AtomicInteger nativeCalls=new AtomicInteger();

    @BeforeAll static void actualFortyNineMigrationsAndSelectOnlyNativeOwnerTables() throws SQLException {
        var admin=source(POSTGRES.getUsername(),POSTGRES.getPassword()); owner=new JdbcTemplate(admin);
        var flyway=Flyway.configure().dataSource(admin).locations("filesystem:src/main/resources/db/migration").target("49")
                .validateOnMigrate(true).outOfOrder(false).load();flyway.migrate();
        assertEquals("49",flyway.info().current().getVersion().getVersion());assertEquals(49,flyway.info().applied().length);
        try(Connection connection=admin.getConnection()) {
            try(var s=connection.prepareStatement("SELECT set_config('dwp.test_password', ?, false)")) {s.setString(1,PASSWORD);s.execute();}
            try(var s=connection.createStatement()) {s.execute("""
                    DO $role$ BEGIN EXECUTE format('CREATE ROLE people_native_target_read_test LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD %L',
                    current_setting('dwp.test_password')); END $role$;
                    """);}
        }
        String db=POSTGRES.getDatabaseName();assertTrue(db.matches("[a-zA-Z0-9_]+"));
        owner.execute("REVOKE CREATE,TEMPORARY ON DATABASE "+db+" FROM PUBLIC");
        owner.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT CONNECT ON DATABASE "+db+" TO "+RUNTIME);
        owner.execute("GRANT USAGE ON SCHEMA public TO "+RUNTIME);
        owner.execute("GRANT SELECT(tenant_id,person_id,public_id,version,lifecycle_state) ON public.ppl_persons TO "+RUNTIME);
        owner.execute("GRANT SELECT(tenant_id,worker_id,person_id,public_id,version,worker_status) ON public.ppl_workers TO "+RUNTIME);
        owner.execute("GRANT SELECT(tenant_id,work_relationship_id,worker_id,public_id,version,start_date,end_date) ON public.ppl_work_relationships TO "+RUNTIME);
        owner.execute("GRANT SELECT(tenant_id,work_relationship_id,public_id,version,organization_id,assignment_key,effective_start_date,effective_end_date,effective_sequence,assignment_status) ON public.ppl_assignments TO "+RUNTIME);
        owner.execute("GRANT SELECT(tenant_id,organization_id,parent_organization_id,public_id,version,lifecycle_state) ON public.ppl_organizations TO "+RUNTIME);
        owner.execute("GRANT SELECT(tenant_id,workforce_access_policy_id,subject_type,subject_ref,population_type,organization_public_id,field_groups,action_codes,valid_from,valid_to,lifecycle_state,version) ON public.ppl_workforce_access_policies TO "+RUNTIME);
        runtime=source(RUNTIME,PASSWORD);
    }
    @BeforeEach void nativeRowsNeverNeedTargetAuthOrLocation() {
        f=new Fixture();nativeCalls.set(0);
        owner.update("UPDATE ppl_workforce_access_policies SET lifecycle_state='REVOKED',version=version+1 WHERE tenant_id=? AND lifecycle_state='ACTIVE'",TENANT);
        personId=owner.queryForObject("INSERT INTO ppl_persons(tenant_id,public_id,person_key,display_name) VALUES(?,?,?,?) RETURNING person_id",
                Long.class,TENANT,f.person,"target:"+f.person,"Owned native target");
        orgAId=organization(TENANT,f.orgA,null);orgBId=organization(TENANT,f.orgB,null);
        workerId=owner.queryForObject("INSERT INTO ppl_workers(tenant_id,public_id,person_id,worker_number,worker_type) VALUES(?,?,?,?,'EMPLOYEE') RETURNING worker_id",
                Long.class,TENANT,f.worker,personId,"worker:"+f.worker);
        long employer=owner.queryForObject("INSERT INTO ppl_legal_employers(tenant_id,employer_key,legal_name) VALUES(?,?,?) RETURNING legal_employer_id",
                Long.class,TENANT,"employer:"+f.relationship,"Owned employer");
        relationshipId=owner.queryForObject("INSERT INTO ppl_work_relationships(tenant_id,public_id,relationship_key,worker_id,legal_employer_id,relationship_type,start_date,end_date) VALUES(?,?,?,?,?,'EMPLOYEE',DATE '2020-01-01',DATE '2026-09-14') RETURNING work_relationship_id",
                Long.class,TENANT,f.relationship,"relationship:"+f.relationship,workerId,employer);
        assignmentId=owner.queryForObject("INSERT INTO ppl_assignments(tenant_id,public_id,assignment_key,work_relationship_id,organization_id,effective_start_date,effective_end_date) VALUES(?,?,?,?,?,DATE '2020-01-01',DATE '2026-09-14') RETURNING assignment_id",
                Long.class,TENANT,f.assignment,"assignment:"+f.assignment,relationshipId,orgBId);
        tenantPolicy=policy("USER","9","TENANT",null,"DIRECTORY",null,null);
        reader=new NativeHrisTargetPolicyQueryReaderV2(runtime);
    }
    private long organization(long tenant,UUID publicId,Long parent) {
        owner.update("INSERT INTO ppl_organization_type_catalog(tenant_id,type_key,display_name) VALUES(?,'DEPARTMENT','Owned department type') ON CONFLICT(tenant_id,type_key) DO NOTHING",tenant);
        return owner.queryForObject("INSERT INTO ppl_organizations(tenant_id,public_id,organization_key,name,organization_type,parent_organization_id) VALUES(?,?,?,?,?,?) RETURNING organization_id",
                Long.class,tenant,publicId,"org:"+publicId,"Owned organization","DEPARTMENT",parent);
    }
    private UUID policy(String type,String subject,String population,UUID org,String fields,Instant from,Instant to) {
        UUID id=UUID.randomUUID();owner.update("INSERT INTO ppl_workforce_access_policies(workforce_access_policy_id,tenant_id,subject_type,subject_ref,population_type,organization_public_id,field_groups,action_codes,justification,valid_from,valid_to) VALUES(?,?,?,?,?,?,ARRAY[?]::varchar[],ARRAY['READ']::varchar[],?,?,?)",
                id,TENANT,type,subject,population,org,fields,"Owned test policy",from==null?null:java.sql.Timestamp.from(from),to==null?null:java.sql.Timestamp.from(to));return id;
    }
    private void revoke(UUID id) {assertEquals(1,owner.update("UPDATE ppl_workforce_access_policies SET lifecycle_state='REVOKED',version=version+1 WHERE workforce_access_policy_id=? AND version=0",id));}
    private NativeHrisUserPolicyAdmissionPilotV2 pilot(InputsProvider inputs,CurrentAuthRoleEvidenceProvider roles) {
        return new NativeHrisUserPolicyAdmissionPilotV2(f.operation(),f::gateway,roles,f::date,inputs,f.clock);
    }
    private ReadAdmission read() {return pilot((q,r,a,d)->{nativeCalls.incrementAndGet();return reader.read(q,r,a,d);},f::roles).read(f.lookup());}
    private void denied(Code code,Runnable call) {var failure=assertThrows(Rejected.class,call::run);assertEquals(code,failure.code());assertNull(failure.getCause());}

    @Test void personWithoutWorkerUsesTenantPolicyAndNoTargetAuthOrTim() {
        UUID person=UUID.randomUUID();f.person=person;
        owner.update("INSERT INTO ppl_persons(tenant_id,public_id,person_key,display_name) VALUES(?,?,?,?)",TENANT,person,"no-worker:"+person,"No worker");
        var result=read();assertEquals(person,result.target().personPublicId());assertNull(result.target().workerPublicId());
        assertEquals(0,result.target().personVersion());assertEquals(2,nativeCalls.get());
        assertTrue(owner.queryForObject("SELECT to_regclass('public.com_users') IS NULL AND to_regclass('public.tim_hris_schedules') IS NULL",Boolean.class));
    }
    @Test void allNativePublicUuidsAndVersionsArePreservedWithoutLocationFallback() {
        f.kind=TargetKind.EMPLOYMENT;var result=read();assertEquals(f.worker,result.target().workerPublicId());
        assertEquals(f.relationship,result.target().workRelationshipPublicId());assertEquals(f.assignment,result.target().assignmentPublicId());
        assertEquals(0L,result.target().workerVersion());assertEquals(0L,result.target().workRelationshipVersion());assertEquals(0L,result.target().assignmentVersion());
        assertEquals(f.orgB,result.nativeInputs().organizationPublicId());assertTrue(owner.queryForObject("SELECT location_id IS NULL FROM ppl_assignments WHERE assignment_id=?",Boolean.class,assignmentId));
    }
    @Test void orgAJobGradeCannotCrossJoinOrgBDirectoryButOrgBDirectoryIsAllowed() {
        revoke(tenantPolicy);f.kind=TargetKind.EMPLOYMENT;
        policy("USER","9","ORG_UNIT",f.orgA,"JOB_GRADE",null,null);
        UUID directory=policy("USER","9","ORG_UNIT",f.orgB,"DIRECTORY",null,null);
        f.field="employment.jobGrade";denied(POLICY_DENIED,this::read);
        f.field="person.publicId";var result=read();assertEquals(1,result.matchedPolicies().size());assertEquals(directory,result.matchedPolicies().getFirst().publicId());
    }
    @Test void matchingRolePolicyCannotBeDiscardedInFavorOfBroadUserPolicy() {
        f.negative=Negative.MATCHING_ROLE_POLICY;policy("ROLE","HR_OPERATOR","ORG_UNIT",f.orgB,"JOB_GRADE",null,null);
        denied(UNSUPPORTED_POLICY,this::read);assertEquals(1,nativeCalls.get());
    }
    @Test void missingCurrentRoleAdapterMakesZeroGatewayAndNativeCalls() {
        var lookup=f.lookup();denied(UNAVAILABLE,()->pilot((q,r,a,d)->{nativeCalls.incrementAndGet();return reader.read(q,r,a,d);},null).read(lookup));
        assertEquals(0,nativeCalls.get());assertEquals(0,f.gatewayCalls.get());
    }
    @Test void policyVersionCasChangesBetweenNativeReadsAreDenied() {
        InputsProvider replay=(q,r,a,d)->{var result=reader.read(q,r,a,d);if(nativeCalls.incrementAndGet()==1)
            assertEquals(1,owner.update("UPDATE ppl_workforce_access_policies SET version=version+1 WHERE workforce_access_policy_id=? AND version=0",tenantPolicy));return result;};
        denied(SOURCE_CHANGED,()->pilot(replay,f::roles).read(f.lookup()));assertEquals(2,nativeCalls.get());
    }
    @Test void policyRevokeBetweenNativeReadsCannotReuseFirstAdmission() {
        InputsProvider replay=(q,r,a,d)->{var result=reader.read(q,r,a,d);if(nativeCalls.incrementAndGet()==1)revoke(tenantPolicy);return result;};
        denied(SOURCE_CHANGED,()->pilot(replay,f::roles).read(f.lookup()));
    }
    @Test void policyExpiryIsExclusiveAndFuturePolicyDoesNotGrant() {
        revoke(tenantPolicy);policy("USER","9","TENANT",null,"DIRECTORY",NOW.minusSeconds(1),NOW);
        denied(POLICY_DENIED,this::read);
        owner.update("UPDATE ppl_workforce_access_policies SET lifecycle_state='REVOKED',version=version+1 WHERE tenant_id=? AND lifecycle_state='ACTIVE'",TENANT);
        policy("USER","9","TENANT",null,"DIRECTORY",NOW.plusNanos(1000),NOW.plusSeconds(20));denied(POLICY_DENIED,this::read);
    }
    @Test void foreignTargetTenantAndWrongEmploymentParentCannotBeRelabeled() {
        UUID foreign=UUID.randomUUID();owner.update("INSERT INTO ppl_persons(tenant_id,public_id,person_key,display_name) VALUES(?,?,?,?)",42,foreign,"foreign:"+foreign,"Foreign");
        f.person=foreign;denied(TARGET_NOT_FOUND,this::read);
        f.person=owner.queryForObject("SELECT public_id FROM ppl_persons WHERE person_id=?",UUID.class,personId);
        f.kind=TargetKind.EMPLOYMENT;f.worker=UUID.randomUUID();denied(TARGET_NOT_FOUND,this::read);
    }
    @Test void globallyValidButForeignTenantPolicyOrganizationFailsClosed() {
        revoke(tenantPolicy);UUID foreign=UUID.randomUUID();organization(42,foreign,null);
        policy("USER","9","ORG_UNIT",foreign,"DIRECTORY",null,null);f.kind=TargetKind.EMPLOYMENT;denied(NATIVE_INVALID,this::read);
    }
    @Test void mergedNativePersonCannotBeAdmitted() {
        owner.update("UPDATE ppl_persons SET lifecycle_state='MERGED',version=version+1 WHERE person_id=?",personId);denied(NATIVE_INVALID,this::read);
    }
    @Test void nativeTargetVersionChangeRequiresRefetchAndRejectsStaleSnapshot() {
        InputsProvider replay=(q,r,a,d)->{var result=reader.read(q,r,a,d);if(nativeCalls.incrementAndGet()==1)
            owner.update("UPDATE ppl_persons SET version=version+1 WHERE person_id=?",personId);return result;};
        denied(SOURCE_CHANGED,()->pilot(replay,f::roles).read(f.lookup()));
    }
    @Test void inclusiveNativeDatesRequireExplicitPurposeBoundDateAdapter() {
        f.kind=TargetKind.EMPLOYMENT;assertEquals(f.assignment,read().target().assignmentPublicId());
        owner.update("UPDATE ppl_work_relationships SET end_date=DATE '2026-09-13',version=version+1 WHERE work_relationship_id=?",relationshipId);denied(NATIVE_INVALID,this::read);
    }
    @Test void missingDateAdapterCannotFallBackToCurrentDateUtcOrLocation() {
        f.kind=TargetKind.EMPLOYMENT;var pilot=new NativeHrisUserPolicyAdmissionPilotV2(f.operation(),f::gateway,f::roles,null,
                (q,r,a,d)->{nativeCalls.incrementAndGet();return reader.read(q,r,a,d);},f.clock);
        denied(DATE_POLICY_UNAVAILABLE,()->pilot.read(f.lookup()));assertEquals(0,nativeCalls.get());assertEquals(0,f.gatewayCalls.get());
    }
    @Test void latestSameDayCorrectionSelectsActualNewUuidAndRejectsOldUuid() {
        f.kind=TargetKind.EMPLOYMENT;UUID correction=UUID.randomUUID();
        owner.update("INSERT INTO ppl_assignments(tenant_id,public_id,assignment_key,work_relationship_id,organization_id,effective_start_date,effective_end_date,effective_sequence,version) VALUES(?,?,?,?,?,DATE '2020-01-01',DATE '2026-09-14',2,3)",
                TENANT,correction,"assignment:"+f.assignment,relationshipId,orgBId);
        denied(TARGET_NOT_FOUND,this::read);f.assignment=correction;var result=read();assertEquals(correction,result.target().assignmentPublicId());assertEquals(3L,result.target().assignmentVersion());
    }
    @Test void overlappingDifferentDaySlicesDoNotCollapseToPrimaryLimitOne() {
        f.kind=TargetKind.EMPLOYMENT;
        owner.update("INSERT INTO ppl_assignments(tenant_id,assignment_key,work_relationship_id,organization_id,effective_start_date,effective_end_date,effective_sequence) VALUES(?,?,?,?,DATE '2021-01-01',DATE '2026-09-14',1)",
                TENANT,"assignment:"+f.assignment,relationshipId,orgBId);denied(NATIVE_INVALID,this::read);
    }
    @Test void organizationTreeAncestorsAreTenantBoundVersionedAndNotFieldUnions() {
        revoke(tenantPolicy);f.kind=TargetKind.EMPLOYMENT;
        owner.update("UPDATE ppl_organizations SET parent_organization_id=?,version=version+1 WHERE organization_id=?",orgAId,orgBId);
        policy("USER","9","ORG_TREE",f.orgA,"DIRECTORY",null,null);var result=read();assertEquals(2,result.nativeInputs().ancestors().size());
        assertTrue(result.nativeInputs().ancestors().stream().anyMatch(o->o.publicId().equals(f.orgB)&&o.version()==1));
    }
    @Test void runtimeHasNoDdlWriteHistoryOrPersonPrivatePayloadPrivileges() throws SQLException {
        try(var connection=runtime.getConnection()) {
            for(String sql:Set.of("CREATE TABLE public.unapproved_target_test(id int)","CREATE TEMP TABLE unapproved_temp(id int)",
                    "UPDATE public.ppl_persons SET version=version+1","SELECT * FROM public.flyway_schema_history","SELECT * FROM public.ppl_person_private"))
                try(var statement=connection.createStatement()) {assertThrows(SQLException.class,()->statement.execute(sql));}
        }
    }
    @Test void sqlFailureIsGenericWithoutNativeExceptionOrCredentialCause() {
        owner.execute("REVOKE SELECT(tenant_id,workforce_access_policy_id,subject_type,subject_ref,population_type,organization_public_id,field_groups,action_codes,valid_from,valid_to,lifecycle_state,version) ON public.ppl_workforce_access_policies FROM "+RUNTIME);
        try{var failure=assertThrows(Rejected.class,this::read);assertEquals(UNAVAILABLE,failure.code());assertNull(failure.getCause());assertFalse(failure.getMessage().contains(PASSWORD));}
        finally{owner.execute("GRANT SELECT(tenant_id,workforce_access_policy_id,subject_type,subject_ref,population_type,organization_public_id,field_groups,action_codes,valid_from,valid_to,lifecycle_state,version) ON public.ppl_workforce_access_policies TO "+RUNTIME);}
    }
    private static PGSimpleDataSource source(String user,String password){var ds=new PGSimpleDataSource();ds.setURL(POSTGRES.getJdbcUrl());ds.setUser(user);ds.setPassword(password);return ds;}
}
