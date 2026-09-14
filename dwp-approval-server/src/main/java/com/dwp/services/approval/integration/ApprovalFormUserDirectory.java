package com.dwp.services.approval.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Fixed, purpose-scoped tenant coworker directory; implementations must verify current source authority. */
public interface ApprovalFormUserDirectory {

    String SOURCE_POLICY = "APPROVAL_FORM_TENANT_PEOPLE_V1";
    int MAX_SIZE = 30;

    Result search(Authority authority, String query, int size);

    Result resolve(Authority authority, List<UUID> personPublicIds);

    @FunctionalInterface
    interface AuthorityProvider {
        Authority requireCurrent(FormBinding form);
    }

    /** Values are loaded by the owner service, never accepted as caller-granted authority. */
    record FormBinding(long tenantId, long actorId, UUID formId, UUID formVersionId, String schemaSha256) { }

    record Authority(FormBinding form, String sourcePolicyKey, String contextKey, String contextScopeKey,
            String decisionRevision, String routeContractKey, OffsetDateTime validUntil,
            String accessMode, String referencePurpose) {
        public Authority(FormBinding form, String sourcePolicyKey, String contextKey, String contextScopeKey,
                String decisionRevision, String routeContractKey, OffsetDateTime validUntil) {
            this(form, sourcePolicyKey, contextKey, contextScopeKey, decisionRevision, routeContractKey,
                    validUntil, "NORMAL", "CREATE_REFERENCE");
        }
    }

    /** Subject IDs stay internal; the public projection contains only personPublicId and displayName. */
    record Person(Long tenantId, Long subjectId, UUID personPublicId, String displayName,
            String identityPlane, String status) { }

    record Result(Authority authority, List<Person> people) {
        public Result { people = people == null ? List.of() : List.copyOf(people); }
    }
}
