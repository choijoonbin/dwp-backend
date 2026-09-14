package com.dwp.services.auth.dto;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class ApprovalFormUserDirectoryDtos {
    private ApprovalFormUserDirectoryDtos() { }

    @Schema(name = "ApprovalFormUserDirectorySearchRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record SearchRequest(String sourceProof, String query, Integer size) {
        public SearchRequest {
            if (sourceProof == null || sourceProof.isBlank() || sourceProof.length() > 16384
                    || query == null || !query.equals(query.strip()) || query.length() < 2
                    || query.length() > 100 || query.codePoints().anyMatch(Character::isISOControl)
                    || size == null || size < 1 || size > 30) throw invalid();
        }
    }

    @Schema(name = "ApprovalFormUserDirectoryResolveRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ResolveRequest(String sourceProof, List<UUID> personPublicIds) {
        public ResolveRequest {
            if (sourceProof == null || sourceProof.isBlank() || sourceProof.length() > 16384
                    || personPublicIds == null || personPublicIds.isEmpty() || personPublicIds.size() > 30
                    || personPublicIds.stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(personPublicIds).size() != personPublicIds.size()) {
                throw invalid();
            }
            personPublicIds = List.copyOf(personPublicIds);
        }
    }

    @Schema(name = "ApprovalFormUserDirectoryPerson", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Person(UUID personPublicId, String displayName) { }

    /** Security bindings are returned only through the dedicated service endpoint. */
    @Schema(name = "ApprovalFormUserDirectoryResolvedPerson", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record ResolvedPerson(Long tenantId, Long subjectId, UUID personPublicId, String displayName,
            String identityPlane, String status) {
        public Person publicProjection() { return new Person(personPublicId, displayName); }
    }

    @Schema(name = "ApprovalFormUserDirectoryResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Response(List<ResolvedPerson> people, UUID proofId, String requestDigest,
            String authRevision, String policyRevision) {
        public Response { people = List.copyOf(people); }
    }

    private static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An exact bounded approval person lookup is required.");
    }
}
