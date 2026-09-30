package com.dwp.services.auth.tenantsettingregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TenantSettingRegistryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TenantSettingRegistryRepository repository;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        repository = new TenantSettingRegistryRepository(
                jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void migratesTypedOwnersAndPersistsTheThreeActorPublicationReceipt() {
        var owner = repository.requireOwner("identity.defaultLocale");
        var adapter = new LocaleTenantSettingOwnerAdapter(jdbc);
        var before = adapter.read(1L);
        var after = TextNode.valueOf(before.textValue().equals("ko") ? "en-US" : "ko");
        Instant now = Instant.parse("2026-09-29T08:00:00Z");

        var draft = repository.insert(
                1L, owner, "VALUE", before, after, "a".repeat(64), "b".repeat(64),
                repository.activePrincipalCount(1L), now,
                "Require an independently approved MFA policy change.", 1L);
        var submitted = repository.submit(1L, draft.changeId(), draft.version(), 1L, now);
        var approved = repository.decide(
                1L, draft.changeId(), submitted.version(), "APPROVED",
                "Independent owner review completed successfully.", 2L, now.plusSeconds(1));
        adapter.apply(1L, after, 3L);
        UUID receipt = UUID.randomUUID();
        var published = repository.publish(
                1L, draft.changeId(), approved.version(), 3L, now.plusSeconds(2), receipt);

        assertThat(repository.owners()).extracting(
                TenantSettingRegistryRepository.OwnerRow::settingKey)
                .containsExactlyInAnyOrder(
                        "identity.defaultLocale",
                        "authentication.defaultLoginType",
                        "authentication.requireMfa",
                        "authentication.tokenTtlSec");
        assertThat(published.lifecycleState()).isEqualTo("PUBLISHED");
        assertThat(published.publishReceiptId()).isEqualTo(receipt);
        assertThat(adapter.read(1L)).isEqualTo(after);
        assertThat(new AuthDefaultLoginTypeTenantSettingOwnerAdapter(jdbc).read(1L).textValue())
                .isEqualTo("LOCAL");
        assertThat(new AuthTokenTtlTenantSettingOwnerAdapter(jdbc).read(1L).intValue())
                .isEqualTo(28800);
        assertThat(repository.requireOwner("authentication.requireMfa").overridePolicy())
                .isEqualTo("OWNER_LOCKED");
        assertThat(repository.requireOwner("authentication.defaultLoginType").overridePolicy())
                .isEqualTo("OWNER_LOCKED");
        assertThat(repository.requireOwner("authentication.tokenTtlSec").overridePolicy())
                .isEqualTo("OWNER_LOCKED");
        var mfaAdapter = new AuthMfaTenantSettingOwnerAdapter(jdbc);
        assertThat(mfaAdapter.tenantEditable()).isFalse();
        assertThatThrownBy(() -> mfaAdapter.apply(1L, BooleanNode.TRUE, 3L))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void doesNotReuseAnActorsPermissionAcrossTenantBoundary() {
        long otherTenantId = jdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES ('s11-cross-tenant', 'S11 cross tenant', 'ACTIVE')
                RETURNING tenant_id
                """, Long.class);
        TenantSettingRegistryAuthorization authorization =
                new TenantSettingRegistryAuthorization(jdbc);

        assertThat(authorization.can(otherTenantId, 1L, "VIEW")).isFalse();
        assertThat(authorization.can(otherTenantId, 1L, "MANAGE")).isFalse();
        assertThat(authorization.can(otherTenantId, 1L, "APPROVE")).isFalse();
        assertThat(authorization.can(otherTenantId, 1L, "PUBLISH")).isFalse();
    }

    @Test
    void readsCanonicalAuthPolicyPublicationInsteadOfClaimingARegistryWrite() {
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_setting_change_sets (
                    change_set_id, tenant_id, owner_type, owner_ref, lifecycle_state,
                    before_state, proposed_state, before_hash, proposed_hash,
                    impact_confidence, impact_count, impact_coverage, impact_observed_at,
                    impact_exclusions, justification, requested_by, submitted_at,
                    decided_by, decided_at, decision_reason, published_by, published_at,
                    publish_receipt_id, version)
                VALUES (?, 1, 'AUTH_POLICY', 'tenant-authentication', 'PUBLISHED',
                        '{"defaultLoginType":"LOCAL","requireMfa":true,"tokenTtlSec":28800}'::jsonb,
                        '{"defaultLoginType":"LOCAL","requireMfa":false,"tokenTtlSec":28800}'::jsonb,
                        ?, ?, 'EXACT', 1, 'INTERNAL_AUTH_ACTIVE_IDENTITIES', CURRENT_TIMESTAMP,
                        '[]'::jsonb, 'Canonical S06 publication provenance fixture.',
                        1, CURRENT_TIMESTAMP, 2, CURRENT_TIMESTAMP,
                        'Independent canonical policy approval.', 3, CURRENT_TIMESTAMP, ?, 3)
                """, UUID.randomUUID(), "a".repeat(64), "b".repeat(64), receiptId);

        var publication = repository.latestCanonicalAuthPolicyPublication(1L).orElseThrow();

        assertThat(publication.proposedState().get("requireMfa").booleanValue()).isFalse();
        assertThat(publication.receiptId()).isEqualTo(receiptId);
        assertThat(publication.publishedAt()).isNotNull();
    }
}
