package com.dwp.services.people.hr.performance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PerformanceCycleAuthorization {

    static final String RESOURCE = "DATA.HR_TALENT";
    static final String VIEW = "VIEW";
    static final String CREATE_DRAFT = "CREATE_DRAFT";
    static final String UPDATE_DRAFT = "UPDATE_DRAFT";
    static final String PREVIEW_PARTICIPANTS = "PREVIEW_PARTICIPANTS";
    static final String PUBLISH = "PUBLISH";

    private PerformanceCycleAuthorization() {
    }

    static void requireRead(PeopleRequestContext.Actor actor) {
        require(actor, "VIEW", "MANAGE");
    }

    static void requireCreate(PeopleRequestContext.Actor actor) {
        require(actor, "CREATE", "MANAGE");
    }

    static void requireUpdate(PeopleRequestContext.Actor actor) {
        require(actor, "UPDATE", "MANAGE");
    }

    static void requirePreview(PeopleRequestContext.Actor actor) {
        require(actor, "UPDATE", "MANAGE");
    }

    static void requirePublish(PeopleRequestContext.Actor actor) {
        require(actor, "APPROVE", "MANAGE");
    }

    static UUID requireSubject(PeopleRequestContext.Actor actor) {
        if (actor.personPublicId() == null) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A governed subject principal is required for performance commands.");
        }
        return actor.personPublicId();
    }

    static List<String> allowedActions(
            PeopleRequestContext.Actor actor,
            String lifecycleState,
            Long currentVersionAuthorId) {
        List<String> actions = new ArrayList<>();
        if (actor.hasPermission(RESOURCE, "VIEW", "MANAGE")) {
            actions.add(VIEW);
        }
        if (actor.hasPermission(RESOURCE, "CREATE", "MANAGE")) {
            actions.add(CREATE_DRAFT);
        }
        if (lifecycleState != null && !"RETIRED".equals(lifecycleState)
                && actor.hasPermission(RESOURCE, "UPDATE", "MANAGE")) {
            actions.add(UPDATE_DRAFT);
        }
        if (lifecycleState != null
                && List.of("DRAFT", "VALIDATED").contains(lifecycleState)
                && actor.hasPermission(RESOURCE, "UPDATE", "MANAGE")) {
            actions.add(PREVIEW_PARTICIPANTS);
        }
        if ("VALIDATED".equals(lifecycleState)
                && !actor.userId().equals(currentVersionAuthorId)
                && actor.hasPermission(RESOURCE, "APPROVE", "MANAGE")) {
            actions.add(PUBLISH);
        }
        return List.copyOf(actions);
    }

    private static void require(PeopleRequestContext.Actor actor, String... actions) {
        if (!actor.hasPermission(RESOURCE, actions)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The required performance-cycle permission is not granted.");
        }
    }
}
