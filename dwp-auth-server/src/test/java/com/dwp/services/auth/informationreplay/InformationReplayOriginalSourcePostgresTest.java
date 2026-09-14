package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.dwp.services.auth.service.WorkflowRuntimeActualAuthHarness;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson;
import com.dwp.services.auth.workflowruntime.WorkflowRuntimeSourceRepository;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/** Actual current-source primitive only: this does not install a fabricated receipt DATA route or v9 authority. */
class InformationReplayOriginalSourcePostgresTest {
    @Test void revokedOriginalPrincipalIsNeverHealedByAnUnrelatedEligiblePrincipal() throws Exception {
        var fixture = new InformationReplayProofFixture();
        try (var auth = new WorkflowRuntimeActualAuthHarness(fixture.owner, fixture.transport, fixture.signer)) {
            long requester = auth.jdbc().queryForObject("SELECT max(user_id)+1 FROM com_users", Long.class);
            long principal = requester + 1, unrelated = requester + 2, delegate = requester + 3;
            for (long user : List.of(requester, principal, unrelated, delegate)) auth.subject(user, java.util.UUID.fromString(InformationReplayProofFixture.id(user)));
            for (long user : List.of(principal, unrelated, delegate)) {
                auth.grant(user, "APP.APPROVALS", "VIEW", requester);
                auth.grant(user, "ACTION.APPROVAL_TASK", "VIEW", requester);
                auth.grant(user, "ACTION.APPROVAL_TASK", "APPROVE", requester);
            }
            long role = auth.role("RECEIPT_REVIEWER", List.of(principal, unrelated));
            var contextField = WorkflowRuntimeActualAuthHarness.class.getDeclaredField("context"); contextField.setAccessible(true);
            var context = (AnnotationConfigApplicationContext) contextField.get(auth);
            var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(context.getBean(jakarta.persistence.EntityManagerFactory.class)));
            var json = new WorkflowRuntimeJson(fixture.mapper);
            var sources = new WorkflowRuntimeSourceRepository(new NamedParameterJdbcTemplate(auth.jdbc()), repositories.getRepository(RoleMemberRepository.class), json);
            var currentRole = sources.currentRole(auth.tenantId(), "RECEIPT_REVIEWER");
            assertEquals(role, currentRole.roleId());
            var originalUsers = Set.of(requester, principal, delegate);
            var before = sources.snapshot(auth.tenantId(), originalUsers);
            assertTrue(before.subjects().get(principal).roles().contains(role));
            assertTrue(before.subjects().get(principal).canApprove());
            assertEquals(1, auth.jdbc().update("DELETE FROM com_role_members WHERE tenant_id=? AND user_id=? AND role_id=?", auth.tenantId(), principal, role));
            var after = sources.snapshot(auth.tenantId(), originalUsers);
            assertEquals(originalUsers, after.subjects().keySet());
            assertFalse(after.subjects().get(principal).roles().contains(role));
            assertTrue(after.subjects().get(principal).canApprove());
            assertNotEquals(json.canonical(before.vector()), json.canonical(after.vector()));
            var alternate = sources.snapshot(auth.tenantId(), Set.of(unrelated)).subjects().get(unrelated);
            assertTrue(alternate.roles().contains(role)); assertTrue(alternate.canApprove());
            assertEquals(currentRole, sources.currentRole(auth.tenantId(), "RECEIPT_REVIEWER"));
            assertEquals(0, auth.jdbc().queryForObject("SELECT count(*) FROM com_role_members WHERE tenant_id=? AND user_id=? AND role_id=?", Integer.class, auth.tenantId(), principal, role));
        }
    }
}
