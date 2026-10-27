package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.security.SpaceRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpaceMembershipValidityPostgresTest extends SpaceMembershipPostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    static Stream<Arguments> roleWindows() {
        return Stream.of(Principal.values()).flatMap(principal ->
                Stream.of("OWNER", "MODERATOR").flatMap(role ->
                        Stream.of(MembershipWindow.values()).map(window ->
                                Arguments.of(principal, role, window))));
    }

    @ParameterizedTest(name = "{0} {1} {2}: management and moderation")
    @MethodSource("roleWindows")
    void managementAndModerationRequireACurrentlyEffectiveMatchingRole(
            Principal principal, String role, MembershipWindow window) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "OPEN");
            member(TENANT, spaceId, principal.type, principal.ref(actor), role, window);

            assertThat(queries.canManage(actor, spaceId))
                    .as("management requires a currently effective OWNER")
                    .isEqualTo(window.effective && "OWNER".equals(role));
            assertThat(queries.canModerate(actor, spaceId))
                    .as("moderation requires a currently effective OWNER or MODERATOR")
                    .isEqualTo(window.effective);
            assertThat(queries.hasActiveOwner(TENANT, spaceId))
                    .isEqualTo(window.effective && "OWNER".equals(role));
            assertThat(queries.space(actor, key(spaceId)).memberRole())
                    .isEqualTo(window.effective ? role : null);
        });
    }

    @ParameterizedTest(name = "{0}: summary counts and administration metrics")
    @EnumSource(MembershipWindow.class)
    void countsIncludeOnlyEffectiveMembershipsInTheCorrectSpaceAndTenant(MembershipWindow window) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "OPEN");
            member(TENANT, spaceId, "USER", "counted-user", "VIEWER", window);
            member(TENANT, spaceId, "GROUP", "counted-group", "CONTRIBUTOR", window);

            UUID otherSpace = space(TENANT, "OPEN");
            member(TENANT, otherSpace, "USER", "other-space-user", "OWNER", MembershipWindow.VALID);
            UUID foreignSpace = space(OTHER_TENANT, "OPEN");
            member(OTHER_TENANT, foreignSpace, "USER", "foreign-user", "OWNER", MembershipWindow.VALID);
            member(OTHER_TENANT, foreignSpace, "GROUP", "foreign-group", "VIEWER", MembershipWindow.VALID);

            int expected = window.effective ? 2 : 0;
            assertThat(queries.space(actor, key(spaceId)).memberCount()).isEqualTo(expected);
            assertThat(queries.adminSpace(actor, key(spaceId)).memberCount()).isEqualTo(expected);
            assertThat(queries.spaces(actor, "DISCOVER", "", 100))
                    .filteredOn(summary -> summary.spaceId().equals(spaceId))
                    .singleElement().satisfies(summary -> assertThat(summary.memberCount()).isEqualTo(expected));
            assertThat(queries.adminSpaces(actor, "", 100))
                    .filteredOn(summary -> summary.spaceId().equals(spaceId))
                    .singleElement().satisfies(summary -> assertThat(summary.memberCount()).isEqualTo(expected));
            assertThat(queries.adminMetrics(TENANT).activeMemberships()).isEqualTo(expected + 1);
            assertThat(queries.adminMetrics(OTHER_TENANT).activeMemberships()).isEqualTo(2);
        });
    }

    @ParameterizedTest(name = "{0} {1} {2}: hidden discovery and My")
    @MethodSource("roleWindows")
    void hiddenAndPrivateSpacesRequireEffectiveMembershipForDiscoveryMyAndDirectRead(
            Principal principal, String role, MembershipWindow window) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID hidden = space(TENANT, "HIDDEN");
            UUID privateSpace = space(TENANT, "PRIVATE");
            UUID open = space(TENANT, "OPEN");
            UUID request = space(TENANT, "REQUEST");
            member(TENANT, hidden, principal.type, principal.ref(actor), role, window);
            member(TENANT, privateSpace, principal.type, principal.ref(actor), role, window);

            // A valid role in another tenant must not disclose that tenant's hidden space either.
            UUID foreign = space(OTHER_TENANT, "HIDDEN");
            member(OTHER_TENANT, foreign, principal.type, principal.ref(actor), "OWNER", MembershipWindow.VALID);
            for (UUID id : List.of(hidden, privateSpace, open, request)) activity(TENANT, id, 0);
            activity(OTHER_TENANT, foreign, 0);
            activity(TENANT, hidden, 25);

            List<UUID> discoverable = new ArrayList<>(List.of(open, request));
            List<UUID> mine = window.effective ? List.of(hidden, privateSpace) : List.of();
            discoverable.addAll(mine);
            assertThat(queries.spaces(actor, "DISCOVER", "", 100))
                    .extracting(SpaceDtos.SpaceSummary::spaceId)
                    .containsExactlyInAnyOrderElementsOf(discoverable);
            assertThat(queries.spaces(actor, "MY", "", 100))
                    .extracting(SpaceDtos.SpaceSummary::spaceId)
                    .containsExactlyInAnyOrderElementsOf(mine);
            assertThat(queries.homeMetrics(actor).mySpaces()).isEqualTo(mine.size());
            assertThat(queries.homeMetrics(actor).unreadSignals())
                    .as("only recent signals in effective member spaces are visible")
                    .isEqualTo(mine.size());
            for (UUID id : List.of(hidden, privateSpace)) {
                if (window.effective) {
                    assertThat(queries.space(actor, key(id)).memberRole()).isEqualTo(role);
                } else {
                    assertThatThrownBy(() -> queries.space(actor, key(id)))
                            .isInstanceOfSatisfying(BaseException.class,
                                    error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
                }
            }
        });
    }

    @Test
    void ineffectiveHigherRolesCannotOverrideAValidLowerRole() {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "HIDDEN");
            member(TENANT, spaceId, "USER", Long.toString(actor.userId()), "OWNER",
                    MembershipWindow.EXPIRED_WINDOW);
            member(TENANT, spaceId, "USER", actor.personPublicId().toString(), "MODERATOR",
                    MembershipWindow.FUTURE);
            member(TENANT, spaceId, "GROUP", GROUP_REF, "VIEWER", MembershipWindow.STARTS_NOW);

            assertThat(queries.space(actor, key(spaceId)).memberRole()).isEqualTo("VIEWER");
            assertThat(queries.canManage(actor, spaceId)).isFalse();
            assertThat(queries.canModerate(actor, spaceId)).isFalse();
            assertThat(queries.hasActiveOwner(TENANT, spaceId)).isFalse();
            assertThat(queries.space(actor, key(spaceId)).memberCount()).isOne();
        });
    }

    @Test
    void matchingIdentityAndTenantAreRequiredEvenForValidOwners() {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "OPEN");
            member(TENANT, spaceId, "USER", "unrelated-user", "OWNER", MembershipWindow.VALID);
            member(TENANT, spaceId, "GROUP", "unrelated-group", "OWNER", MembershipWindow.VALID);
            UUID foreign = space(OTHER_TENANT, "HIDDEN");
            member(OTHER_TENANT, foreign, "USER", Long.toString(actor.userId()), "OWNER", MembershipWindow.VALID);
            member(OTHER_TENANT, foreign, "GROUP", GROUP_REF, "OWNER", MembershipWindow.VALID);

            assertThat(queries.canManage(actor, spaceId)).isFalse();
            assertThat(queries.canModerate(actor, spaceId)).isFalse();
            assertThat(queries.canManage(actor, foreign)).isFalse();
            assertThat(queries.canModerate(actor, foreign)).isFalse();
            assertThat(queries.space(actor, key(spaceId)).memberRole()).isNull();
            assertThat(queries.spaces(actor, "MY", "", 100)).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNER", "MODERATOR"})
    void emptyGroupsNeverAcquireTheSentinelGroupsRoleOrManagementFlags(String role) {
        inTransaction(() -> {
            var base = subject(TENANT);
            var noGroups = new SpaceRequestContext.Subject(base.userId(), TENANT, base.personPublicId(),
                    base.displayName(), base.roles(), base.permissions(), Set.of());
            var tenantAdmin = new SpaceRequestContext.Subject(base.userId(), TENANT, base.personPublicId(),
                    base.displayName(), Set.of("TENANT_ADMIN"), base.permissions(), Set.of());
            var actualGroupMember = new SpaceRequestContext.Subject(base.userId(), TENANT, base.personPublicId(),
                    base.displayName(), base.roles(), base.permissions(), Set.of("__NO_GROUP__"));
            UUID open = space(TENANT, "OPEN");
            UUID hidden = space(TENANT, "HIDDEN");
            UUID privateSpace = space(TENANT, "PRIVATE");
            for (UUID id : List.of(open, hidden, privateSpace)) {
                member(TENANT, id, "GROUP", "__NO_GROUP__", role, MembershipWindow.INDEFINITE);
                activity(TENANT, id, 0);
                assertThat(queries.canManage(noGroups, id)).isFalse();
                assertThat(queries.canModerate(noGroups, id)).isFalse();
                assertThat(queries.adminSpace(noGroups, key(id)).memberRole()).isNull();
                assertThat(queries.space(actualGroupMember, key(id)).memberRole()).isEqualTo(role);
                assertThat(queries.canManage(actualGroupMember, id)).isEqualTo("OWNER".equals(role));
                assertThat(queries.canModerate(actualGroupMember, id)).isTrue();
            }
            assertThat(queries.spaces(noGroups, "MY", "", 100)).isEmpty();
            assertThat(queries.spaces(noGroups, "DISCOVER", "", 100))
                    .extracting(SpaceDtos.SpaceSummary::spaceId).containsExactly(open);
            assertThat(queries.space(noGroups, key(open)).memberRole()).isNull();
            assertThat(queries.homeMetrics(noGroups).mySpaces()).isZero();
            assertThat(queries.homeMetrics(noGroups).unreadSignals()).isZero();
            for (UUID id : List.of(hidden, privateSpace)) {
                assertThatThrownBy(() -> queries.space(noGroups, key(id)))
                        .isInstanceOfSatisfying(BaseException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
            }

            // Tenant-wide listing visibility must not turn the sentinel into per-Space authority.
            SpaceRequestContext.set(tenantAdmin);
            var detail = service().space(key(hidden));
            assertThat(detail.space().memberRole()).isNull();
            assertThat(detail.canManage()).isFalse();
            assertThat(detail.canModerate()).isFalse();
            assertThat(detail.canContribute()).isFalse();
            assertThat(detail.featuredContent()).isEmpty();
            SpaceRequestContext.set(noGroups);
            assertThat(service().home().canAdminister()).isFalse();
        });
    }
}
