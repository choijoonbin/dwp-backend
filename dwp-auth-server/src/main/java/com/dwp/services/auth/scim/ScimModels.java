package com.dwp.services.auth.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;

public final class ScimModels {

    public static final String CORE_USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String CORE_GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";

    private ScimModels() {
    }

    public record UserRequest(
            List<String> schemas,
            String externalId,
            @NotBlank String userName,
            Boolean active,
            String displayName,
            Name name,
            String title,
            String locale,
            List<@Valid Email> emails) {
    }

    public record Name(String formatted, String familyName, String givenName) {
    }

    public record Email(String value, String type, Boolean primary) {
    }

    public record GroupRequest(
            List<String> schemas,
            String externalId,
            @NotBlank String displayName,
            List<@Valid Member> members) {
    }

    public record Member(@NotBlank String value, String display, String type) {
    }

    public record PatchRequest(
            @NotEmpty List<String> schemas,
            @JsonProperty("Operations") @NotEmpty List<@Valid PatchOperation> operations) {
    }

    public record PatchOperation(
            @NotBlank String op,
            String path,
            JsonNode value) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserResponse(
            List<String> schemas,
            String id,
            String externalId,
            String userName,
            boolean active,
            String displayName,
            Name name,
            String title,
            String locale,
            List<Email> emails,
            Meta meta) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupResponse(
            List<String> schemas,
            String id,
            String externalId,
            String displayName,
            List<Member> members,
            Meta meta) {
    }

    public record Meta(
            String resourceType,
            Instant created,
            Instant lastModified,
            String version,
            String location) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ListResponse<T>(
            List<String> schemas,
            long totalResults,
            int startIndex,
            int itemsPerPage,
            @JsonProperty("Resources") List<T> resources,
            String nextCursor) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            List<String> schemas,
            String status,
            String scimType,
            String detail) {
    }
}
