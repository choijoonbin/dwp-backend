package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpaceMembershipOwnerGuardPostgresTest extends SpaceMembershipPostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    static Stream<Arguments> ownerMutations() {
        return Stream.of(Principal.values()).flatMap(principal ->
                Stream.of(Mutation.values()).flatMap(mutation ->
                        Stream.of(MembershipWindow.values()).map(window ->
                                Arguments.of(principal, mutation, window))));
    }

    @ParameterizedTest(name = "{0} {1}: other owner is {2}")
    @MethodSource("ownerMutations")
    void removalOrDemotionRequiresAnotherEffectiveOwnerInTheSameSpaceAndTenant(
            Principal principal, Mutation mutation, MembershipWindow otherOwnerWindow) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "HIDDEN");
            String targetRef = principal.ref(actor);
            UUID target = member(TENANT, spaceId, principal.type, targetRef, "OWNER", MembershipWindow.INDEFINITE);
            member(TENANT, spaceId, "GROUP", "other-owner", "OWNER", otherOwnerWindow);
            // Neither a lower role nor owners in other spaces/tenants can satisfy the guard.
            member(TENANT, spaceId, "GROUP", "moderator-only", "MODERATOR", MembershipWindow.VALID);
            UUID otherSpace = space(TENANT, "HIDDEN");
            member(TENANT, otherSpace, "USER", "other-space-owner", "OWNER", MembershipWindow.VALID);
            UUID foreign = space(OTHER_TENANT, "HIDDEN");
            member(OTHER_TENANT, foreign, "USER", targetRef, "OWNER", MembershipWindow.VALID);

            Map<String, Object> before = membership(target);
            if (otherOwnerWindow.effective) {
                mutate(mutation, spaceId, target, principal.type, targetRef);
                assertThat(membership(target).get("version")).isEqualTo(1L);
                assertThat(membership(target).get(mutation == Mutation.REVOKE ? "lifecycle_state" : "member_role"))
                        .isEqualTo(mutation == Mutation.REVOKE ? "REVOKED" : "VIEWER");
                assertThat(queries.hasActiveOwner(TENANT, spaceId)).isTrue();
            } else {
                assertThatThrownBy(() -> mutate(mutation, spaceId, target, principal.type, targetRef))
                        .isInstanceOfSatisfying(BaseException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
                assertThat(membership(target)).as("rejection preserves the whole membership").isEqualTo(before);
                assertThat(queries.hasActiveOwner(TENANT, spaceId)).isTrue();
            }
        });
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void finalOwnerCannotBeRemovedWhenThereAreNoOtherOwnerRows(Mutation mutation) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "HIDDEN");
            String ref = Long.toString(actor.userId());
            UUID target = member(TENANT, spaceId, "USER", ref, "OWNER", MembershipWindow.VALID);
            Map<String, Object> before = membership(target);
            assertThatThrownBy(() -> mutate(mutation, spaceId, target, "USER", ref))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
            assertThat(membership(target)).isEqualTo(before);
        });
    }

    @ParameterizedTest
    @EnumSource(Principal.class)
    void finiteExpiryRoundTripsThroughInsertUpdateAndSaveUpsert(Principal principal) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "OPEN");
            String ref = principal.ref(actor);
            Timestamp firstExpiry = jdbc.queryForObject(
                    "SELECT CURRENT_TIMESTAMP + INTERVAL '2 days'", Timestamp.class);
            commands.saveMember(actor, spaceId,
                    new SpaceDtos.SaveMemberRequest(principal.type, ref, "VIEWER", firstExpiry.toInstant()));
            UUID membershipId = jdbc.queryForObject("""
                    SELECT membership_id FROM spc_memberships
                     WHERE tenant_id = ? AND space_id = ? AND principal_type = ? AND principal_ref = ?
                    """, UUID.class, TENANT, spaceId, principal.type, ref);
            assertThat(membership(membershipId).get("valid_until")).isEqualTo(firstExpiry);
            assertThat(queries.space(actor, key(spaceId)).memberRole()).isEqualTo("VIEWER");

            Timestamp secondExpiry = jdbc.queryForObject(
                    "SELECT CURRENT_TIMESTAMP + INTERVAL '3 days'", Timestamp.class);
            commands.updateMember(actor, spaceId, membershipId,
                    new SpaceDtos.UpdateMemberRequest("MODERATOR", secondExpiry.toInstant(), 0L));
            assertThat(membership(membershipId).get("valid_until")).isEqualTo(secondExpiry);
            assertThat(membership(membershipId).get("version")).isEqualTo(1L);
            assertThat(queries.canModerate(actor, spaceId)).isTrue();

            commands.saveMember(actor, spaceId,
                    new SpaceDtos.SaveMemberRequest(principal.type, ref, "VIEWER", firstExpiry.toInstant()));
            assertThat(membership(membershipId).get("valid_until")).isEqualTo(firstExpiry);
            assertThat(membership(membershipId).get("version")).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spc_memberships WHERE space_id = ?",
                    Integer.class, spaceId)).isOne();
        });
    }

    static Stream<Arguments> futureRevocations() {
        return Stream.of(Principal.values()).flatMap(principal -> Stream.of(false, true)
                .map(finite -> Arguments.of(principal, finite)));
    }

    @ParameterizedTest(name = "{0}: revoke future owner, finite expiry={1}")
    @MethodSource("futureRevocations")
    void futureOwnerCanBeRevokedWithoutViolatingTheMembershipWindow(Principal principal, boolean finite) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "HIDDEN");
            UUID membershipId = member(TENANT, spaceId, principal.type, principal.ref(actor), "OWNER",
                    MembershipWindow.FUTURE);
            if (finite) {
                jdbc.update("""
                        UPDATE spc_memberships SET valid_until = valid_from + INTERVAL '1 day'
                         WHERE membership_id = ?
                        """, membershipId);
            }
            Map<String, Object> before = membership(membershipId);

            commands.revokeMember(actor, spaceId, membershipId);

            Map<String, Object> after = membership(membershipId);
            assertThat(after.get("lifecycle_state")).isEqualTo("REVOKED");
            assertThat(after.get("version")).isEqualTo(1L);
            assertThat(after.get("valid_from")).isEqualTo(before.get("valid_from"));
            assertThat(after.get("valid_until")).isEqualTo(before.get("valid_until"));
            assertThat(queries.canManage(actor, spaceId)).isFalse();
            assertThat(queries.hasActiveOwner(TENANT, spaceId)).isFalse();
        });
    }

    @ParameterizedTest
    @EnumSource(value = MembershipWindow.class, names = {"EXPIRED_WINDOW", "ENDS_NOW", "FUTURE", "REVOKED"})
    void ineffectiveOwnerCanBeDemotedWithoutRequiringAnotherOwner(MembershipWindow window) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, "OPEN");
            UUID id = member(TENANT, spaceId, "USER", "ineffective-owner", "OWNER", window);
            commands.updateMember(actor, spaceId, id, new SpaceDtos.UpdateMemberRequest("VIEWER", null, 0L));
            assertThat(membership(id).get("member_role")).isEqualTo("VIEWER");
            assertThat(queries.hasActiveOwner(TENANT, spaceId)).isFalse();
        });
    }

    static Stream<Arguments> invalidUpdateExpiries() {
        return Stream.of(
                Arguments.of(MembershipWindow.VALID, "CURRENT_TIMESTAMP"),
                Arguments.of(MembershipWindow.VALID, "CURRENT_TIMESTAMP - INTERVAL '1 hour'"),
                Arguments.of(MembershipWindow.FUTURE, "CURRENT_TIMESTAMP + INTERVAL '12 hours'"),
                Arguments.of(MembershipWindow.FUTURE, "CURRENT_TIMESTAMP + INTERVAL '1 day'"));
    }

    @ParameterizedTest
    @MethodSource("invalidUpdateExpiries")
    void updateRejectsExpiryAtOrBeforeTransactionTimeOrMembershipStart(
            MembershipWindow window, String expirySql) {
        inTransaction(() -> {
            UUID spaceId = space(TENANT, "OPEN");
            UUID id = member(TENANT, spaceId, "USER", "expiry-target", "VIEWER", window);
            Map<String, Object> before = membership(id);
            Timestamp invalidExpiry = jdbc.queryForObject("SELECT " + expirySql, Timestamp.class);

            assertThatThrownBy(() -> commands.updateMember(subject(TENANT), spaceId, id,
                    new SpaceDtos.UpdateMemberRequest("VIEWER", invalidExpiry.toInstant(), 0L)))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
            assertThat(membership(id)).isEqualTo(before);
        });
    }

    Map<String, Object> membership(UUID membershipId) {
        return jdbc.queryForMap("SELECT * FROM spc_memberships WHERE membership_id = ?", membershipId);
    }

    void mutate(Mutation mutation, UUID spaceId, UUID membershipId, String principalType, String principalRef) {
        switch (mutation) {
            case REVOKE -> commands.revokeMember(subject(TENANT), spaceId, membershipId);
            case UPDATE -> commands.updateMember(subject(TENANT), spaceId, membershipId,
                    new SpaceDtos.UpdateMemberRequest("VIEWER", null, 0L));
            case SAVE -> commands.saveMember(subject(TENANT), spaceId,
                    new SpaceDtos.SaveMemberRequest(principalType, principalRef, "VIEWER", null));
        }
    }

    enum Mutation { REVOKE, UPDATE, SAVE }
}
