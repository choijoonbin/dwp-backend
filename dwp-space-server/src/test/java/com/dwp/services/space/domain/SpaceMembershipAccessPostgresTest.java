package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpaceMembershipAccessPostgresTest extends SpaceMembershipPostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    static Stream<Arguments> accessWindows() {
        return Stream.of(Principal.values()).flatMap(principal ->
                Stream.of("OPEN", "REQUEST").flatMap(visibility ->
                        Stream.of(MembershipWindow.values()).map(window ->
                                Arguments.of(principal, visibility, window))));
    }

    @ParameterizedTest(name = "{0} {1} {2}: access requests")
    @MethodSource("accessWindows")
    void requestEligibilityUsesEffectiveUserAndGroupMemberships(
            Principal principal, String visibility, MembershipWindow window) {
        inTransaction(() -> {
            var actor = subject(TENANT);
            UUID spaceId = space(TENANT, visibility);
            member(TENANT, spaceId, principal.type, principal.ref(actor), "VIEWER", window);
            var input = new SpaceDtos.CreateAccessRequest("VIEWER", "Regression membership request.");
            if (window.effective) {
                var before = jdbc.queryForList("SELECT * FROM spc_memberships WHERE space_id = ?", spaceId);
                assertThatThrownBy(() -> commands.createAccessRequest(actor, spaceId, input))
                        .isInstanceOfSatisfying(BaseException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spc_access_requests WHERE space_id = ?",
                        Integer.class, spaceId)).isZero();
                assertThat(jdbc.queryForList("SELECT * FROM spc_memberships WHERE space_id = ?", spaceId))
                        .isEqualTo(before);
            } else {
                UUID requestId = commands.createAccessRequest(actor, spaceId, input);
                var request = jdbc.queryForMap("SELECT * FROM spc_access_requests WHERE access_request_id = ?", requestId);
                boolean autoApproved = "OPEN".equals(visibility);
                assertThat(request.get("status")).isEqualTo(autoApproved ? "APPROVED" : "PENDING");
                assertThat(request.get("decision_mode")).isEqualTo(autoApproved ? "AUTO" : "OWNER_REVIEW");
                assertThat(queries.space(actor, key(spaceId)).memberRole()).isEqualTo(autoApproved ? "VIEWER" : null);
                if (autoApproved) {
                    assertThat(request.get("decided_at")).as("finite JDBC timestamp binding").isNotNull();
                } else {
                    assertThat(request.get("decided_at")).isNull();
                }
            }
        });
    }

    @ParameterizedTest
    @CsvSource({"PRIVATE,ACTIVE", "HIDDEN,ACTIVE", "OPEN,ARCHIVED", "REQUEST,ARCHIVED",
            "OPEN,DRAFT", "REQUEST,DELETED"})
    void commandLayerRejectsRequestsToNonrequestableSpaces(String visibility, String lifecycle) {
        inTransaction(() -> {
            UUID spaceId = space(TENANT, visibility);
            jdbc.update("UPDATE spc_spaces SET lifecycle_state = ? WHERE space_id = ?", lifecycle, spaceId);

            assertThatThrownBy(() -> commands.createAccessRequest(subject(TENANT), spaceId,
                    new SpaceDtos.CreateAccessRequest("VIEWER", "This request must be rejected.")))
                    .isInstanceOfSatisfying(BaseException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spc_access_requests WHERE space_id = ?",
                    Integer.class, spaceId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spc_memberships WHERE space_id = ?",
                    Integer.class, spaceId)).isZero();
        });
    }
}
