package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;

/**
 * Owner-side port for a fresh, action-specific Meeting authority decision.
 *
 * <p>The Work workload assertion proves the caller and exact request bytes, but it never proves
 * the actor's current Meeting entitlement, scope, identity plane, or action capability. A safe
 * CREATE adapter therefore needs an Auth-owned decision bound to the tenant, actor, identity
 * plane, follow-up action, source object, authority/policy revisions, and a short validity window;
 * that evidence must be delivered directly by Auth or in an Auth-signed delegation that Work
 * cannot mint. REASSIGN additionally needs a People-owned current tenant-membership and
 * assignability decision for the target user.</p>
 */
public interface MeetingFollowupCurrentAuthority {

    Decision authorize(Request request);

    enum Denial {
        AUTHORITY_UNVERIFIED,
        AUTHORITY_REVOKED,
        SCOPE_FORBIDDEN,
        IDENTITY_PLANE_MISMATCH,
        ACTION_NOT_AUTHORIZED
    }

    record Decision(boolean allowed, Denial denial) {

        public Decision {
            if (allowed == (denial != null)) {
                throw new IllegalArgumentException(
                        "An authority decision must be either allowed or explicitly denied.");
            }
        }

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(Denial denial) {
            return new Decision(false, java.util.Objects.requireNonNull(denial));
        }
    }
}
