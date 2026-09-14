package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.LocalTenantMediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class WorkplaceExperienceCollaborationPostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static ObjectMapper mapper;
    private static TransactionTemplate transaction;
    private static WorkplaceExperienceCollaborationRepository repository;
    private static WorkplaceExperienceCollaborationService service;
    private static WorkplaceExperienceCollaborationGovernanceService reviews;
    private static WorkplaceRuntimeGovernance runtime;
    private static WorkplaceSpatialGovernanceRepository governanceRepository;
    private static final long OWNER = 91001;
    private static final long VIEWER = 91002;
    private static final LocalDate DATE = LocalDate.now().plusDays(2);
    @TempDir Path mediaRoot;

    @BeforeAll static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration",
                "filesystem:../dwp-core/src/main/resources/db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        mapper = new ObjectMapper().findAndRegisterModules();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        governanceRepository = new WorkplaceSpatialGovernanceRepository(jdbc, mapper);
        WorkplaceSpatialGovernanceService governance = new WorkplaceSpatialGovernanceService(governanceRepository, mapper);
        runtime = new WorkplaceRuntimeGovernance(governance);
        repository = new WorkplaceExperienceCollaborationRepository(jdbc);
        service = new WorkplaceExperienceCollaborationService(repository, runtime, governanceRepository, mapper);
        reviews = new WorkplaceExperienceCollaborationGovernanceService(governance, service, mapper);
    }

    @Test void persistedPlansRespectGranularityAndRevocationDoesNotReshareHistoricalPlans() {
        Fixture f = fixture(9247001);
        enableSharing(f, Visibility.RESOURCE);
        WorkPlan saved = write(() -> service.savePlan(f.tenant(), OWNER, f.group().toString(), "plan",
                new WorkPlanRequest(DATE, PlanMode.OFFICE, f.site(), f.floor(), f.resource(), f.group(), Visibility.RESOURCE, null)));
        CollaborationOverview before = overview(f, VIEWER, f.group().toString());
        assertThat(before.shareableGroups()).extracting(ShareableGroup::groupRef).containsExactly(f.group());
        assertThat(before.sharedPlans()).singleElement().satisfies(plan -> {
            assertThat(plan.resourceId()).isEqualTo(f.resource());
            assertThat(plan.source()).isEqualTo("WORK_PLAN");
            assertThat(plan.displayName()).isNull();
        });
        write(() -> service.savePolicy(f.tenant(), OWNER, new SharingPolicyRequest(true, Visibility.SITE, 1L, "Minimize precision", true), "policy"));
        assertThat(overview(f, VIEWER, f.group().toString()).sharedPlans()).singleElement().satisfies(plan -> {
            assertThat(plan.siteId()).isEqualTo(f.site()); assertThat(plan.floorId()).isNull(); assertThat(plan.resourceId()).isNull();
        });
        write(() -> service.revokePreference(f.tenant(), OWNER, 1, "revoke"));
        assertThat(overview(f, VIEWER, f.group().toString()).sharedPlans()).isEmpty();
        assertThat(repository.ownPlanOnDate(f.tenant(), OWNER, DATE).orElseThrow()).satisfies(plan -> {
            assertThat(plan.visibility()).isEqualTo(Visibility.PRIVATE); assertThat(plan.version()).isGreaterThan(saved.version());
        });
        write(() -> service.savePreference(f.tenant(), OWNER, new SharingPreferenceRequest(true, Visibility.SITE, 2L), "re-enable"));
        assertThat(overview(f, VIEWER, f.group().toString()).sharedPlans()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT snapshot ->> 'reason' FROM wp_audit_events WHERE tenant_id = ? AND action = 'workplace.experience.sharing.policy.updated' ORDER BY occurred_at DESC LIMIT 1", String.class, f.tenant())).isEqualTo("Minimize precision");
    }

    @Test void membershipTenantAndCurrentSiteAuthorityFailClosed() {
        Fixture f = fixture(9247002); enableSharing(f, Visibility.SITE);
        write(() -> service.savePlan(f.tenant(), OWNER, f.group().toString(), null,
                new WorkPlanRequest(DATE, PlanMode.OFFICE, f.site(), null, null, f.group(), Visibility.SITE, null)));
        assertThat(overview(f, VIEWER, null).sharedPlans()).isEmpty();
        assertThatThrownBy(() -> service.overview(f.tenant(), VIEWER, null, DATE, DATE, f.group())).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(service.overview(9247099, VIEWER, f.group().toString(), DATE, DATE, null).sharedPlans()).isEmpty();
        jdbc.update("UPDATE wp_site_access_rules SET lifecycle_state = 'INACTIVE', version = version + 1 WHERE tenant_id = ? AND subject_user_id = ?", f.tenant(), VIEWER);
        assertThat(overview(f, VIEWER, f.group().toString()).sharedPlans()).isEmpty();
        assertThatThrownBy(() -> write(() -> service.savePlan(f.tenant(), VIEWER, f.group().toString(), null,
                new WorkPlanRequest(DATE, PlanMode.OFFICE, f.site(), null, null, null, Visibility.PRIVATE, null))))
                .isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test void unprovisionedNativePolicyNeverProjectsZeroRetentionDays() {
        Fixture f = fixture(9247098);
        jdbc.update("DELETE FROM wp_tenant_policies WHERE tenant_id = ?", f.tenant());
        assertThatThrownBy(() -> service.governanceOverview(f.tenant()))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(error.getMessage()).contains("has not been provisioned");
                });
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id, booking_retention_days) VALUES (?, ?)", f.tenant(), 30);
        assertThat(service.governanceOverview(f.tenant()).privacy().bookingRetentionDays()).isEqualTo(30);
    }

    @Test void ownPlanVersionAndDeleteAreScopedAndPersisted() {
        Fixture f = fixture(9247003);
        WorkPlan saved = write(() -> service.savePlan(f.tenant(), OWNER, null, null,
                new WorkPlanRequest(DATE, PlanMode.REMOTE, null, null, null, null, Visibility.PRIVATE, null)));
        assertThatThrownBy(() -> write(() -> service.deletePlan(f.tenant(), VIEWER, saved.planId(), saved.version(), null))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> write(() -> service.deletePlan(f.tenant(), OWNER, saved.planId(), 99, null))).isInstanceOf(BaseException.class);
        assertThat(repository.ownPlanOnDate(f.tenant(), OWNER, DATE)).isPresent();
        write(() -> service.deletePlan(f.tenant(), OWNER, saved.planId(), saved.version(), "delete"));
        assertThat(repository.ownPlanOnDate(f.tenant(), OWNER, DATE)).isEmpty();
        jdbc.update("UPDATE wp_tenant_policies SET booking_retention_days = 30 WHERE tenant_id = ?", f.tenant());
        LocalDate oldDate = LocalDate.now().minusDays(40);
        write(() -> service.savePlan(f.tenant(), OWNER, null, null,
                new WorkPlanRequest(oldDate, PlanMode.REMOTE, null, null, null, null, Visibility.PRIVATE, null)));
        assertThat(repository.ownPlanOnDate(f.tenant(), OWNER, oldDate)).isPresent();
        assertThat(write(() -> repository.deleteExpiredWorkPlans(500))).isEqualTo(1);
        assertThat(repository.ownPlanOnDate(f.tenant(), OWNER, oldDate)).isEmpty();
    }

    @Test void ruleAndDelegationReasonAuditAreAtomicAndReviewsNeverSave() {
        Fixture f = fixture(9247004);
        SiteAccessRuleRequest proposed = new SiteAccessRuleRequest(AccessSubjectType.USER, 91003L, null,
                AccessPermission.BOOK, AccessEffect.ALLOW, null, null, RuleState.ACTIVE, null);
        AccessRuleChangeRequest request = new AccessRuleChangeRequest(proposed, "Approved site access", true);
        GovernanceChangeReview preview = reviews.reviewRule(f.tenant(), OWNER, null, f.site(), null, request);
        assertThat(preview.current().isNull()).isTrue();
        assertThat(countRules(f)).isEqualTo(2);
        assertThatThrownBy(() -> write(() -> reviews.changeRule(f.tenant(), OWNER, null, f.site(), null,
                new AccessRuleChangeRequest(proposed, "Missing confirmation", false), null))).isInstanceOf(BaseException.class);
        SiteAccessRule saved = write(() -> reviews.changeRule(f.tenant(), OWNER, null, f.site(), null, request, "reason"));
        assertThat(reason(f, "workplace.governance.access.rule.reviewed")).isEqualTo("Approved site access");
        SiteAccessRuleRequest update = new SiteAccessRuleRequest(AccessSubjectType.USER, 91003L, null,
                AccessPermission.BOOK, AccessEffect.DENY, null, null, RuleState.ACTIVE, saved.version());
        assertThatThrownBy(() -> write(() -> {
            reviews.changeRule(f.tenant(), OWNER, null, f.site(), saved.accessRuleId(), new AccessRuleChangeRequest(update, "Must roll back", true), null);
            throw new IllegalStateException("Rollback proof");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(governanceRepository.accessRules(f.tenant(), f.site()).stream().filter(row -> row.accessRuleId().equals(saved.accessRuleId())).findFirst().orElseThrow().effect()).isEqualTo(AccessEffect.ALLOW);
        assertThat(reason(f, "workplace.governance.access.rule.reviewed")).isEqualTo("Approved site access");
        SiteAccessRule committed = write(() -> reviews.changeRule(f.tenant(), OWNER, null, f.site(), saved.accessRuleId(), new AccessRuleChangeRequest(update, "Revoke booking", true), null));
        assertThatThrownBy(() -> reviews.reviewRule(f.tenant(), OWNER, null, f.site(), saved.accessRuleId(), new AccessRuleChangeRequest(update, "Stale", true))).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThat(committed.version()).isGreaterThan(saved.version());
        DelegatedAdminScopeRequest delegation = new DelegatedAdminScopeRequest(DelegateType.USER, VIEWER, null,
                DelegatedScopeType.SITE, f.site(), null, List.of(DelegatedPermission.CATALOG_VIEW), null, null, DelegationState.ACTIVE, null);
        write(() -> reviews.changeDelegation(f.tenant(), OWNER, null, new DelegationChangeRequest(delegation, "Read-only site operator", true), null));
        assertThat(reason(f, "workplace.governance.delegation.reviewed")).isEqualTo("Read-only site operator");
    }

    @Test void connectorMetadataAndRealPhotosNeverClaimLiveEmployees() throws Exception {
        Fixture f = fixture(9247005);
        ConnectorStatus status = write(() -> service.saveConnector(f.tenant(), OWNER, ConnectorKind.ACTUAL_PRESENCE,
                new ConnectorRequest("badge-adapter", true, "tenant-config/badge", 0L, "Configure adapter", true), null));
        assertThat(status.status()).isEqualTo(ConnectionState.CONFIGURED_UNVERIFIED); assertThat(status.lastVerifiedAt()).isNull();
        assertThat(overview(f, VIEWER, null).actualPresence().configurationReference()).isNull();
        assertThatThrownBy(() -> write(() -> service.saveConnector(f.tenant(), OWNER, ConnectorKind.CALENDAR,
                new ConnectorRequest("bad provider", true, "http://127.0.0.1:9000", 0L, "Bad", true), null))).isInstanceOf(BaseException.class);
        var storage = new LocalTenantMediaStorage(mediaRoot.toString());
        var photos = new WorkplaceExperienceCollaborationMediaService(repository, runtime, service, storage,
                new WorkplaceMediaCleanupRepository(jdbc), new WorkplaceFloorPlanValidator(10_485_760));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        MockMultipartFile file = new MockMultipartFile("file", "registered.png", "image/png", bytes.toByteArray());
        ResourcePhoto first = write(() -> photos.upload(f.tenant(), OWNER, f.resource(), 0, "Register real photo", "Workspace entrance", file, null));
        assertThat(photos.content(f.tenant(), VIEWER, null, f.resource()).resource().getInputStream().readAllBytes()).isEqualTo(bytes.toByteArray());
        assertThat(first.version()).isPositive();
        assertThat(first.url()).contains("/v1/admin/workplace/");
        assertThat(photos.metadata(f.tenant(), VIEWER, null, f.resource()).url()).doesNotContain("/v1/admin/");
        assertThat(photos.adminContent(f.tenant(), f.resource()).metadata().url()).contains("/v1/admin/workplace/");
        ResourcePhoto second = write(() -> photos.upload(f.tenant(), OWNER, f.resource(), first.version(), "Replace photo", "Updated entrance", file, null));
        assertThat(second.version()).isGreaterThan(first.version());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_tenant_media_cleanup_outbox WHERE tenant_id = ? AND cleanup_reason = 'WORKPLACE_RESOURCE_PHOTO_REPLACED'", Long.class, f.tenant())).isEqualTo(1);
        assertThatThrownBy(() -> photos.metadata(f.tenant(), 91009, null, f.resource())).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> write(() -> photos.upload(f.tenant(), OWNER, f.resource(), second.version(), "Invalid media", "Bad", new MockMultipartFile("file", "x.svg", "image/svg+xml", "<svg/>".getBytes()), null))).isInstanceOf(BaseException.class);
        write(() -> photos.delete(f.tenant(), OWNER, f.resource(), second.version(), "Remove photograph", null));
        assertThatThrownBy(() -> photos.adminMetadata(f.tenant(), f.resource())).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void nativeBookingPolicyAndReasonCommitAndRollbackTogether() {
        Fixture f = fixture(9247006);
        WorkplaceService facade = mock(WorkplaceService.class);
        WorkplaceCatalogAdminService nativeOwner = new WorkplaceCatalogAdminService(new WorkplaceCatalogRepository(jdbc, mapper),
                new WorkplaceBookingRepository(jdbc, mapper), null, null, null, null, null, null);
        when(facade.policy(f.tenant())).thenAnswer(invocation -> nativeOwner.policy(f.tenant()));
        when(facade.updatePolicy(eq(f.tenant()), eq(OWNER), nullable(String.class), any()))
                .thenAnswer(invocation -> nativeOwner.updatePolicy(f.tenant(), OWNER, invocation.getArgument(2), invocation.getArgument(3)));
        var policies = new WorkplaceExperienceCollaborationPolicyGovernanceService(facade, null, service, mapper);
        long version = nativeOwner.policy(f.tenant()).version();
        var proposed = new WorkplaceDtos.PolicyRequest(45, 20, 30, 480, 5, LocalTime.of(7, 0), LocalTime.of(20, 0),
                false, true, 60, 30, false, false, 365, version);
        assertThat(policies.reviewBookingPolicy(f.tenant(), new BookingPolicyChangeRequest(proposed, "", false)).current()).isNotNull();
        assertThatThrownBy(() -> write(() -> policies.changeBookingPolicy(f.tenant(), OWNER,
                new BookingPolicyChangeRequest(proposed, "", true), null))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> write(() -> {
            policies.changeBookingPolicy(f.tenant(), OWNER, new BookingPolicyChangeRequest(proposed, "Rollback policy proof", true), null);
            throw new IllegalStateException("Rollback proof");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(nativeOwner.policy(f.tenant()).version()).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wp_audit_events WHERE tenant_id = ? AND action = 'workplace.governance.booking.policy.reviewed'", Long.class, f.tenant())).isZero();
        WorkplaceDtos.Policy saved = write(() -> policies.changeBookingPolicy(f.tenant(), OWNER,
                new BookingPolicyChangeRequest(proposed, "45 day booking window approved", true), "policy-review"));
        assertThat(saved.bookingWindowDays()).isEqualTo(45);
        assertThat(reason(f, "workplace.governance.booking.policy.reviewed")).isEqualTo("45 day booking window approved");
        assertThatThrownBy(() -> policies.reviewBookingPolicy(f.tenant(), new BookingPolicyChangeRequest(proposed, null, false)))
                .isInstanceOfSatisfying(BaseException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
    }

    @Test void scopedPolicyOverrideUsesNativeOwnerAndPersistsReviewReason() {
        Fixture f = fixture(9247007);
        var governance = new WorkplaceSpatialGovernanceService(governanceRepository, mapper);
        var policies = new WorkplaceExperienceCollaborationPolicyGovernanceService(null, governance, service, mapper);
        var proposed = new PolicyOverrideRequest(PolicyScopeType.SITE, f.site(), mapper.createObjectNode().put("bookingWindowDays", 14), RuleState.ACTIVE, null);
        assertThat(policies.reviewPolicyOverride(f.tenant(), null, PolicyScopeType.SITE, f.site(),
                new PolicyOverrideChangeRequest(proposed, null, false)).current().isNull()).isTrue();
        PolicyOverride saved = write(() -> policies.changePolicyOverride(f.tenant(), OWNER, null, PolicyScopeType.SITE, f.site(),
                new PolicyOverrideChangeRequest(proposed, "Site window approved", true), null));
        assertThat(reason(f, "workplace.governance.policy.override.reviewed")).isEqualTo("Site window approved");
        assertThat(governance.previewPolicy(f.tenant(), PolicyScopeType.SITE, f.site()).effectivePolicy().get("bookingWindowDays").asInt()).isEqualTo(14);
        var update = new PolicyOverrideRequest(PolicyScopeType.SITE, f.site(), mapper.createObjectNode().put("bookingWindowDays", 7), RuleState.ACTIVE, saved.version());
        assertThatThrownBy(() -> write(() -> {
            policies.changePolicyOverride(f.tenant(), OWNER, saved.policyOverrideId(), PolicyScopeType.SITE, f.site(),
                    new PolicyOverrideChangeRequest(update, "Must roll back override", true), null);
            throw new IllegalStateException("Rollback proof");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(governance.previewPolicy(f.tenant(), PolicyScopeType.SITE, f.site()).effectivePolicy().get("bookingWindowDays").asInt()).isEqualTo(14);
        assertThatThrownBy(() -> policies.reviewPolicyOverride(f.tenant(), saved.policyOverrideId(), PolicyScopeType.SITE, UUID.randomUUID(),
                new PolicyOverrideChangeRequest(update, "Mismatch", true))).isInstanceOf(BaseException.class);
    }

    private Fixture fixture(long tenant) {
        UUID site = UUID.randomUUID(), floor = UUID.randomUUID(), resource = UUID.randomUUID(), group = UUID.randomUUID();
        jdbc.update("INSERT INTO sys_service_tenants (provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,data_region,isolation_model,created_by,updated_by) VALUES (?, ?, ?, 'Collaboration test', 'ACTIVE', 'kr', 'POOL', ?, ?)", UUID.randomUUID(), tenant, "collaboration-" + tenant, OWNER, OWNER);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (?)", tenant);
        jdbc.update("INSERT INTO wp_sites (site_id,tenant_id,site_code,name_ko,name_en) VALUES (?, ?, ?, '테스트', 'Test')", site, tenant, "SITE_" + tenant);
        jdbc.update("INSERT INTO wp_floors (floor_id,tenant_id,site_id,floor_number,name_ko,name_en) VALUES (?, ?, ?, 10, '10층', '10F')", floor, tenant, site);
        jdbc.update("INSERT INTO wp_resources (resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,resource_type) VALUES (?, ?, ?, ?, '좌석', 'Desk', 'DESK')", resource, tenant, floor, "DESK_" + tenant);
        for (long user : List.of(OWNER, VIEWER)) jdbc.update("INSERT INTO wp_site_access_rules (tenant_id,site_id,subject_type,subject_user_id,permission_code,effect,lifecycle_state) VALUES (?, ?, 'USER', ?, 'MANAGE', 'ALLOW', 'ACTIVE')", tenant, site, user);
        return new Fixture(tenant, site, floor, resource, group);
    }

    private void enableSharing(Fixture f, Visibility visibility) {
        write(() -> service.savePolicy(f.tenant(), OWNER, new SharingPolicyRequest(true, visibility, 0L, "Enable consent-based planning", true), null));
        write(() -> service.savePreference(f.tenant(), OWNER, new SharingPreferenceRequest(true, visibility, 0L), null));
    }
    private CollaborationOverview overview(Fixture f, long user, String groups) { return service.overview(f.tenant(), user, groups, DATE, DATE, null); }
    private long countRules(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM wp_site_access_rules WHERE tenant_id = ?", Long.class, f.tenant()); }
    private String reason(Fixture f, String action) { return jdbc.queryForObject("SELECT snapshot ->> 'reason' FROM wp_audit_events WHERE tenant_id = ? AND action = ? ORDER BY occurred_at DESC LIMIT 1", String.class, f.tenant(), action); }
    private <T> T write(Supplier<T> action) { return transaction.execute(status -> action.get()); }
    private record Fixture(long tenant, UUID site, UUID floor, UUID resource, UUID group) {}
}
