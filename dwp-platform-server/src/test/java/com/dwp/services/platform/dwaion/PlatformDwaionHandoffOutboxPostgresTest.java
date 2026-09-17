package com.dwp.services.platform.dwaion;

import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff.Binding;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff.Effect;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff.Identity;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoffOutboxRepository.ObservationSnapshot;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlatformDwaionHandoffOutboxPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static PlatformDwaionHandoffOutboxRepository repository;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new PlatformDwaionHandoffOutboxRepository(jdbc);
    }

    @Test
    void commitsReplaysLeasesAndCompletesWithImmutableEvidence() {
        UUID handoffId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        Binding binding = new Binding(
                1, handoffId, proposalId, "MAIL.DRAFT.CREATE", 1);
        Identity identity = new Identity(
                "session-1", UUID.randomUUID(), "WORKSPACE_MEMBER", "APP.ASK:VIEW");
        Effect effect = Effect.forBinding(binding, threadId, 0, "DRAFT");

        repository.committed(81, 19, binding, identity, effect, "correlation-1");
        repository.committed(81, 19, binding, identity, effect, "correlation-1");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_dwaion_proposal_handoffs
                 WHERE handoff_id = ?
                """, Integer.class, handoffId)).isEqualTo(1);
        var claimed = repository.claim(10, "worker-1").getFirst();
        var running = repository.advance(
                claimed, "worker-1",
                observation(claimed, "HANDED_OFF", 2, null)).orElseThrow();
        var completing = repository.advance(
                running, "worker-1",
                observation(running, "RUNNING", 3, null)).orElseThrow();
        UUID receiptId = UUID.randomUUID();

        assertThat(repository.advance(
                completing, "worker-1",
                observation(completing, "COMPLETED", 4, receiptId))).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state || '|' || handoff_version || '|' || receipt_id
                  FROM platform_dwaion_proposal_handoffs WHERE handoff_id = ?
                """, String.class, handoffId))
                .isEqualTo("COMPLETED|4|" + receiptId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM platform_dwaion_proposal_handoff_events e
                  JOIN platform_dwaion_proposal_handoffs h USING (binding_id, tenant_id)
                 WHERE h.handoff_id = ?
                """, Integer.class, handoffId)).isEqualTo(5);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE platform_dwaion_proposal_handoff_events SET attempt_count = 99
                 WHERE binding_id = (SELECT binding_id
                   FROM platform_dwaion_proposal_handoffs WHERE handoff_id = ?)
                """, handoffId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void replayWithADifferentCommittedEffectFailsClosed() {
        Binding binding = new Binding(
                1, UUID.randomUUID(), UUID.randomUUID(), "SERVICE.REQUEST.CREATE", 2);
        Identity identity = new Identity("session-2", null, "WORKSPACE_MEMBER", "APP.ASK:VIEW");
        Effect first = Effect.forBinding(binding, UUID.randomUUID(), 0, "DRAFT");
        repository.committed(82, 20, binding, identity, first, "correlation-2");

        assertThatThrownBy(() -> repository.committed(
                82, 20, binding, identity,
                Effect.forBinding(binding, UUID.randomUUID(), 0, "DRAFT"),
                "correlation-2"))
                .isInstanceOf(BaseException.class);
    }

    private static ObservationSnapshot observation(
            PlatformDwaionHandoffOutboxRepository.Delivery delivery,
            String state,
            long version,
            UUID receiptId) {
        return new ObservationSnapshot(
                delivery.handoffId(), delivery.proposalId(), delivery.actionKey(),
                state, version, receiptId);
    }
}
