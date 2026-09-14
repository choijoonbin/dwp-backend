package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Testcontainers
class WorkplaceDelegatedFloorScopePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static WorkplaceSpatialGovernanceService governance;
    static WorkplaceDelegatedAdminScopeGuard guard;
    static WorkplaceScopedSpatialGovernanceService scoped;
    static WorkplaceExperienceCollaborationGovernanceService reviews;
    static ObjectMapper mapper;
    static final long ADMIN = 9253001, ACTOR = 9253002;

    @BeforeAll static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(source); mapper = new ObjectMapper().findAndRegisterModules();
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        var repository = new WorkplaceSpatialGovernanceRepository(jdbc, mapper);
        governance = new WorkplaceSpatialGovernanceService(repository, mapper);
        guard = new WorkplaceDelegatedAdminScopeGuard(new WorkplaceDelegatedAdminScopeRepository(new NamedParameterJdbcTemplate(source)));
        scoped = new WorkplaceScopedSpatialGovernanceService(governance, repository, mapper, guard);
        var collaboration = new WorkplaceExperienceCollaborationService(new WorkplaceExperienceCollaborationRepository(jdbc),
                new WorkplaceRuntimeGovernance(governance), repository, mapper);
        reviews = new WorkplaceExperienceCollaborationGovernanceService(governance, collaboration, mapper);
    }

    @Test void legacyOmittedScopeRemainsWholeSiteAndLegacySubjectSiteUpdateIsPreserved() {
        Fixture f = fixture(9253101);
        var saved = save(f, request(f, ACTOR, null, null, DelegationState.ACTIVE));
        assertThat(saved.floorIds()).isNull();
        assertThat(governance.effectiveDelegatedScopes(f.tenant(), ACTOR, null)).singleElement()
                .satisfies(value -> assertThat(value.floorIds()).isNull());
        var actual = guard.scope(actor(f, null), f.site(), DelegatedPermission.CATALOG_MANAGE);
        write(() -> { guard.revalidate(actual).requireSiteWide(); guard.revalidate(actual).requireFloor(f.second()); return null; });
        var moved = new DelegatedAdminScopeRequest(DelegateType.USER, ACTOR + 1, null, DelegatedScopeType.SITE,
                f.otherSite(), null, saved.permissions(), null, null, DelegationState.ACTIVE, saved.version());
        var updated = write(() -> governance.saveDelegatedScope(f.tenant(), ADMIN, saved.delegationId(), "legacy-update", moved));
        assertThat(updated.siteId()).isEqualTo(f.otherSite()); assertThat(updated.delegateUserId()).isEqualTo(ACTOR + 1);
        assertThat(updated.floorIds()).isNull();
    }

    @Test void actualRestrictedFloorsPersistSortedAndReasonAuditAndEffectiveScopeAreNative() {
        Fixture f = fixture(9253102);
        var proposed = request(f, ACTOR, List.of(f.second(), f.floor()), null, DelegationState.ACTIVE);
        var review = reviews.reviewDelegation(f.tenant(), null, new DelegationChangeRequest(proposed, "", false));
        assertThat(review.proposed().get("floorIds").size()).isEqualTo(2);
        var saved = write(() -> reviews.changeDelegation(f.tenant(), ADMIN, null,
                new DelegationChangeRequest(proposed, "Manage only selected floors", true), "restricted"));
        assertThat(saved.floorIds()).containsExactlyElementsOf(List.of(f.floor(), f.second()).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList());
        assertThat(governance.effectiveDelegatedScopes(f.tenant(), ACTOR, null)).singleElement()
                .satisfies(value -> assertThat(value.floorIds()).containsExactlyElementsOf(saved.floorIds()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_delegated_admin_scope_floors WHERE tenant_id=?", Long.class, f.tenant())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT snapshot->>'reason' FROM wp_audit_events WHERE tenant_id=? AND action='workplace.governance.delegation.reviewed'", String.class, f.tenant()))
                .isEqualTo("Manage only selected floors");
    }

    @Test void emptyDuplicateNullForeignMissingAndMismatchedFloorsFailWithoutStoredGrant() {
        Fixture f = fixture(9253103), foreign = fixture(9253193);
        for (List<UUID> invalid : List.of(List.<UUID>of(), List.of(f.floor(), f.floor()),
                java.util.Arrays.asList(f.floor(), null), List.of(foreign.floor()), List.of(f.otherFloor()), List.of(UUID.randomUUID()))) {
            assertThatThrownBy(() -> save(f, request(f, ACTOR, invalid, null, DelegationState.ACTIVE))).isInstanceOf(BaseException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_delegated_admin_scopes WHERE tenant_id=?", Long.class, f.tenant())).isZero();
    }

    @Test void databaseCanonicalFkDeferredNonemptyAndUnrestrictedChildInvariantAreEnforcedAtCommit() {
        Fixture f = fixture(9253104), foreign = fixture(9253194);
        assertThatThrownBy(() -> write(() -> {
            insertParent(f, UUID.randomUUID(), true); return null;
        })).isInstanceOfAny(DataIntegrityViolationException.class, TransactionSystemException.class);
        assertThatThrownBy(() -> write(() -> {
            UUID id = UUID.randomUUID(); insertParent(f, id, false); insertFloor(f, id, f.floor()); return null;
        })).isInstanceOfAny(DataIntegrityViolationException.class, TransactionSystemException.class);
        for (UUID invalid : List.of(foreign.floor(), f.otherFloor())) {
            assertThatThrownBy(() -> write(() -> {
                UUID id = UUID.randomUUID(); insertParent(f, id, true); insertFloor(f, id, invalid); return null;
            })).isInstanceOfAny(DataIntegrityViolationException.class, TransactionSystemException.class);
        }
        var saved = save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        assertThatThrownBy(() -> jdbc.update("UPDATE wp_floors SET site_id=? WHERE tenant_id=? AND floor_id=?", f.otherSite(), f.tenant(), f.floor()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> write(() -> { jdbc.update("DELETE FROM wp_delegated_admin_scope_floors WHERE delegation_id=?", saved.delegationId()); return null; }))
                .isInstanceOfAny(DataIntegrityViolationException.class, TransactionSystemException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_delegated_admin_scope_floors WHERE delegation_id=?", Long.class, saved.delegationId())).isEqualTo(1);
    }

    @Test void restrictedScopeCannotChangeSubjectSiteFloorSetOrBePromotedByAnOldClient() {
        Fixture f = fixture(9253105);
        var saved = save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        for (var changed : List.of(request(f, ACTOR, null, saved.version(), DelegationState.ACTIVE),
                request(f, ACTOR, List.of(f.second()), saved.version(), DelegationState.ACTIVE),
                request(f, ACTOR + 1, saved.floorIds(), saved.version(), DelegationState.ACTIVE),
                new DelegatedAdminScopeRequest(DelegateType.USER, ACTOR, null, DelegatedScopeType.SITE, f.otherSite(), null,
                        saved.permissions(), null, null, DelegationState.ACTIVE, saved.version(), List.of(f.otherFloor())))) {
            assertThatThrownBy(() -> write(() -> reviews.changeDelegation(f.tenant(), ADMIN, saved.delegationId(),
                    new DelegationChangeRequest(changed, "Reject immutable scope change", true), "immutable"))).isInstanceOf(BaseException.class);
        }
        assertThat(governance.delegatedScopes(f.tenant())).singleElement().satisfies(value -> {
            assertThat(value.floorIds()).containsExactly(f.floor()); assertThat(value.version()).isEqualTo(saved.version());
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_audit_events WHERE tenant_id=?", Long.class, f.tenant())).isEqualTo(1);
    }

    @Test void wholeToRestrictedPromotionIsRejectedButReorderedSavedFloorsAndVersionedRevocationWork() {
        Fixture f = fixture(9253106);
        var whole = save(f, request(f, ACTOR + 1, null, null, DelegationState.ACTIVE));
        assertThatThrownBy(() -> write(() -> governance.saveDelegatedScope(f.tenant(), ADMIN, whole.delegationId(), null,
                request(f, ACTOR + 1, List.of(f.floor()), whole.version(), DelegationState.ACTIVE)))).isInstanceOf(BaseException.class);
        var saved = save(f, request(f, ACTOR, List.of(f.floor(), f.second()), null, DelegationState.ACTIVE));
        var reversed = request(f, ACTOR, saved.floorIds().stream().sorted(java.util.Comparator.reverseOrder()).toList(), saved.version(), DelegationState.REVOKED);
        var revoked = write(() -> reviews.changeDelegation(f.tenant(), ADMIN, saved.delegationId(), new DelegationChangeRequest(reversed, "Revoke exact saved floors", true), null));
        assertThat(revoked.state()).isEqualTo(DelegationState.REVOKED); assertThat(revoked.version()).isEqualTo(saved.version() + 1);
        assertThat(governance.effectiveDelegatedScopes(f.tenant(), ACTOR, null)).isEmpty();
    }

    @Test void permissionScopedUnionDoesNotMultiplyUnrelatedGrantsAndUnknownGroupsHaveNoAuthority() {
        Fixture f = fixture(9253107); UUID group = UUID.randomUUID();
        save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        save(f, new DelegatedAdminScopeRequest(DelegateType.GROUP_REF, null, group, DelegatedScopeType.SITE, f.site(), null,
                List.of(DelegatedPermission.CATALOG_VIEW), null, null, DelegationState.ACTIVE, null, List.of(f.second())));
        var actor = actor(f, group.toString());
        var manage = guard.scope(actor, f.site(), DelegatedPermission.CATALOG_MANAGE);
        var view = guard.scope(actor, f.site(), DelegatedPermission.CATALOG_VIEW);
        assertThat(write(() -> guard.revalidate(manage)).floorIds()).containsExactly(f.floor());
        assertThat(write(() -> guard.revalidate(view)).floorIds()).containsExactlyInAnyOrder(f.floor(), f.second());
        assertThat(guard.scope(actor(f, UUID.randomUUID().toString()), f.site(), DelegatedPermission.CATALOG_VIEW).floorIds()).containsExactly(f.floor());
        assertThatThrownBy(() -> guard.scopeForTarget(actor, WorkplaceDelegatedAdminTargetType.FLOOR, f.second(), DelegatedPermission.CATALOG_MANAGE)).isInstanceOf(BaseException.class);
        assertThatThrownBy(manage::requireSiteWide).isInstanceOf(BaseException.class);
    }

    @Test void wholeGrantOnlyDominatesForTheSamePermissionAndNoApplicationOrRoomsEntitlementIsCreated() {
        Fixture f = fixture(9253108); UUID group = UUID.randomUUID();
        save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        save(f, new DelegatedAdminScopeRequest(DelegateType.GROUP_REF, null, group, DelegatedScopeType.SITE, f.site(), null,
                List.of(DelegatedPermission.CATALOG_VIEW), null, null, DelegationState.ACTIVE, null));
        assertThat(guard.scope(actor(f, group.toString()), f.site(), DelegatedPermission.CATALOG_VIEW).floorIds()).isNull();
        assertThat(guard.scope(actor(f, group.toString()), f.site(), DelegatedPermission.CATALOG_MANAGE).floorIds()).containsExactly(f.floor());
        var denied = actor(f, null); denied.setMethod("GET"); denied.setRequestURI("/v1/admin/workplace/bookings");
        assertThatThrownBy(() -> guard.authorize(denied)).isInstanceOf(BaseException.class);
        denied.setRequestURI("/v1/admin/rooms/bookings/pending");
        assertThatThrownBy(() -> guard.authorize(denied)).isInstanceOf(BaseException.class);
    }

    @Test void limitedAccessListsAndOptionsHideSiteAndOtherFloorSubjectsButBaselineRemainsReadonly() {
        Fixture f = fixture(9253109);
        var delegate = new DelegatedAdminScopeRequest(DelegateType.USER, ACTOR, null, DelegatedScopeType.SITE, f.site(), null,
                List.of(DelegatedPermission.ACCESS_MANAGE), null, null, DelegationState.ACTIVE, null, List.of(f.floor()));
        save(f, delegate);
        for (UUID floor : java.util.Arrays.asList(null, f.floor(), f.second())) write(() -> governance.saveAccessRule(f.tenant(), ADMIN, f.site(), null, null,
                new SiteAccessRuleRequest(AccessSubjectType.USER, ACTOR, null, AccessPermission.MANAGE, AccessEffect.ALLOW, null, null, RuleState.ACTIVE, null, floor)));
        var requested = guard.scope(actor(f, null), f.site(), DelegatedPermission.ACCESS_MANAGE);
        var rules = write(() -> scoped.accessRules(f.tenant(), f.site(), requested));
        assertThat(rules).singleElement().satisfies(value -> assertThat(value.floorId()).isEqualTo(f.floor()));
        var preview = write(() -> scoped.accessPreview(f.tenant(), ACTOR, null, f.site(), AccessPermission.VIEW, requested));
        assertThat(preview.allowed()).isTrue(); assertThat(preview.availableFloors()).singleElement()
                .satisfies(value -> assertThat(value.floorId()).isEqualTo(f.floor()));
        assertThatThrownBy(() -> write(() -> scoped.rule(f.tenant(), f.site(), null,
                new SiteAccessRuleRequest(AccessSubjectType.USER, ADMIN, null, AccessPermission.VIEW, AccessEffect.ALLOW, null, null, RuleState.ACTIVE, null), requested, () -> null)))
                .isInstanceOf(BaseException.class);
    }

    @Test void expiredAuthorityAndReadOnlyOrMissingTransactionNeverYieldWholeSiteFallback() {
        Fixture f = fixture(9253110);
        var saved = save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        var requested = guard.scope(actor(f, null), f.site(), DelegatedPermission.CATALOG_MANAGE);
        assertThatThrownBy(() -> guard.revalidate(requested)).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager()); readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status -> guard.revalidate(requested))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE wp_delegated_admin_scopes SET valid_until=clock_timestamp()-interval '1 second' WHERE delegation_id=?", saved.delegationId());
        assertThatThrownBy(() -> write(() -> guard.revalidate(requested))).isInstanceOfSatisfying(BaseException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> write(() -> guard.revalidate(null))).isInstanceOf(BaseException.class);
    }

    @Test void revocationCommittedAfterPrehandleMakesTheOwnerCommandForbiddenAndRollsBack() throws Exception {
        Fixture f = fixture(9253111);
        var saved = save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        var requested = guard.scopeForTarget(actor(f, null), WorkplaceDelegatedAdminTargetType.FLOOR, f.floor(), DelegatedPermission.CATALOG_MANAGE);
        CountDownLatch updated = new CountDownLatch(1), release = new CountDownLatch(1), commandStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var revoke = pool.submit(() -> write(() -> {
                jdbc.update("UPDATE wp_delegated_admin_scopes SET lifecycle_state='REVOKED',version=version+1 WHERE delegation_id=?", saved.delegationId());
                updated.countDown(); await(release); return null;
            }));
            assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
            var command = pool.submit(() -> write(() -> {
                commandStarted.countDown();
                return scoped.target(requested, DelegatedPermission.CATALOG_MANAGE, WorkplaceDelegatedAdminTargetType.FLOOR, f.floor(), () -> {
                    jdbc.update("UPDATE wp_floors SET name_en='Forbidden write' WHERE floor_id=?", f.floor()); return true;
                });
            }));
            assertThat(commandStarted.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown(); revoke.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> command.get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(BaseException.class);
            assertThat(jdbc.queryForObject("SELECT name_en FROM wp_floors WHERE floor_id=?", String.class, f.floor())).isEqualTo("11F");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void lockedNativeCommandSerializesRevocationAndAuditFailureRollsBackBothScopeAndChildren() throws Exception {
        Fixture f = fixture(9253112);
        var saved = save(f, request(f, ACTOR, List.of(f.floor()), null, DelegationState.ACTIVE));
        var requested = guard.scope(actor(f, null), f.site(), DelegatedPermission.CATALOG_MANAGE);
        CountDownLatch authorized = new CountDownLatch(1), release = new CountDownLatch(1), revokeStarted = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var command = pool.submit(() -> write(() -> {
                var fresh = guard.revalidate(requested); fresh.requireFloor(f.floor()); authorized.countDown(); await(release);
                jdbc.update("UPDATE wp_floors SET name_en='Authorized write' WHERE floor_id=?", f.floor()); return null;
            }));
            assertThat(authorized.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> write(() -> {
                revokeStarted.countDown(); jdbc.update("UPDATE wp_delegated_admin_scopes SET lifecycle_state='REVOKED' WHERE delegation_id=?", saved.delegationId()); return null;
            }));
            assertThat(revokeStarted.await(5, TimeUnit.SECONDS)).isTrue(); assertThat(revoke.isDone()).isFalse();
            release.countDown(); command.get(10, TimeUnit.SECONDS); revoke.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT name_en FROM wp_floors WHERE floor_id=?", String.class, f.floor())).isEqualTo("Authorized write");
        } finally { release.countDown(); pool.shutdownNow(); }
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM wp_delegated_admin_scopes WHERE tenant_id=?", Long.class, f.tenant());
        jdbc.execute("ALTER TABLE wp_audit_events ADD CONSTRAINT reject_delegated_test_audit CHECK (tenant_id <> 9253112 OR action <> 'workplace.governance.delegation.reviewed')");
        try {
            assertThatThrownBy(() -> write(() -> reviews.changeDelegation(f.tenant(), ADMIN, null,
                    new DelegationChangeRequest(request(f, ACTOR + 3, List.of(f.second()), null, DelegationState.ACTIVE),
                            "Atomic audit rollback", true), "audit-failure"))).isInstanceOf(DataIntegrityViolationException.class);
        } finally { jdbc.execute("ALTER TABLE wp_audit_events DROP CONSTRAINT reject_delegated_test_audit"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_delegated_admin_scopes WHERE tenant_id=?", Long.class, f.tenant())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_delegated_admin_scope_floors WHERE tenant_id=?", Long.class, f.tenant())).isEqualTo(1);
    }

    static Fixture fixture(long tenant) {
        UUID site = UUID.randomUUID(), floor = UUID.randomUUID(), second = UUID.randomUUID(), otherSite = UUID.randomUUID(), otherFloor = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants(provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES(?,?,?,'Delegated floors','ACTIVE','kr','POOL',?,?)", UUID.randomUUID(), tenant, "delegated-floor-" + tenant, ADMIN, ADMIN);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenant);
        jdbc.update("INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en) VALUES(?,?,'MAIN','본관','Main'),(?,?,'OTHER','별관','Other')", site, tenant, otherSite, tenant);
        jdbc.update("INSERT INTO wp_floors(floor_id,tenant_id,site_id,floor_number,name_ko,name_en) VALUES(?,?,?,11,'11층','11F'),(?,?,?,12,'12층','12F'),(?,?,?,1,'기타','Other')", floor, tenant, site, second, tenant, site, otherFloor, tenant, otherSite);
        return new Fixture(tenant, site, floor, second, otherSite, otherFloor);
    }
    static DelegatedAdminScopeRequest request(Fixture f, long subject, List<UUID> floors, Long version, DelegationState state) {
        return new DelegatedAdminScopeRequest(DelegateType.USER, subject, null, DelegatedScopeType.SITE, f.site(), null,
                List.of(DelegatedPermission.CATALOG_MANAGE), null, null, state, version, floors);
    }
    static DelegatedAdminScope save(Fixture f, DelegatedAdminScopeRequest request) { return write(() -> governance.saveDelegatedScope(f.tenant(), ADMIN, null, null, request)); }
    static MockHttpServletRequest actor(Fixture f, String groups) {
        var request = new MockHttpServletRequest(); request.addHeader("X-DWP-Tenant-ID", f.tenant()); request.addHeader("X-DWP-User-ID", ACTOR);
        request.addHeader("X-DWP-Roles", "WORKPLACE_DELEGATE"); if (groups != null) request.addHeader("X-DWP-Group-Refs", groups); return request;
    }
    static void insertParent(Fixture f, UUID id, boolean restricted) { jdbc.update("INSERT INTO wp_delegated_admin_scopes(delegation_id,tenant_id,delegate_type,delegate_user_id,scope_type,site_id,permission_codes,floor_scope_restricted) VALUES(?,?,'USER',?,'SITE',?,ARRAY['CATALOG_MANAGE'],?)", id, f.tenant(), ACTOR, f.site(), restricted); }
    static void insertFloor(Fixture f, UUID id, UUID floor) { jdbc.update("INSERT INTO wp_delegated_admin_scope_floors(tenant_id,delegation_id,site_id,floor_id) VALUES(?,?,?,?)", f.tenant(), id, f.site(), floor); }
    static <T> T write(Supplier<T> operation) { return tx.execute(status -> operation.get()); }
    static void await(CountDownLatch latch) { try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    record Fixture(long tenant, UUID site, UUID floor, UUID second, UUID otherSite, UUID otherFloor) { }
}
