package com.dwp.services.auth.scim;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/scim/v2")
public class ScimController {

    private static final MediaType SCIM_JSON = MediaType.parseMediaType("application/scim+json");
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final ScimUserService userService;
    private final ScimGroupService groupService;

    public ScimController(ScimUserService userService, ScimGroupService groupService) {
        this.userService = userService;
        this.groupService = groupService;
    }

    @GetMapping("/ServiceProviderConfig")
    public ResponseEntity<Map<String, Object>> serviceProviderConfig() {
        return scim(Map.of(
                "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"),
                "patch", Map.of("supported", true),
                "bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
                "filter", Map.of("supported", true, "maxResults", 100),
                "changePassword", Map.of("supported", false),
                "sort", Map.of("supported", false),
                "etag", Map.of("supported", true),
                "authenticationSchemes", List.of(Map.of(
                        "type", "oauthbearertoken",
                        "name", "DWP SCIM bearer token",
                        "description", "Rotatable connector credential issued by DWP Control Center",
                        "specUri", "https://www.rfc-editor.org/rfc/rfc6750"))));
    }

    @GetMapping("/ResourceTypes")
    public ResponseEntity<Map<String, Object>> resourceTypes() {
        List<Map<String, Object>> resources = List.of(
                Map.of(
                        "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
                        "id", "User", "name", "User", "endpoint", "/Users",
                        "schema", ScimModels.CORE_USER),
                Map.of(
                        "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
                        "id", "Group", "name", "Group", "endpoint", "/Groups",
                        "schema", ScimModels.CORE_GROUP));
        return scim(Map.of(
                "schemas", List.of(ScimModels.LIST_RESPONSE),
                "totalResults", resources.size(),
                "startIndex", 1,
                "itemsPerPage", resources.size(),
                "Resources", resources));
    }

    @GetMapping("/Schemas")
    public ResponseEntity<Map<String, Object>> schemas() {
        List<Map<String, Object>> schemas = List.of(
                Map.of(
                        "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Schema"),
                        "id", ScimModels.CORE_USER,
                        "name", "User",
                        "description", "DWP identity projection for a person",
                        "attributes", List.of()),
                Map.of(
                        "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:Schema"),
                        "id", ScimModels.CORE_GROUP,
                        "name", "Group",
                        "description", "DWP provisioning group",
                        "attributes", List.of()));
        return scim(Map.of(
                "schemas", List.of(ScimModels.LIST_RESPONSE),
                "totalResults", schemas.size(),
                "startIndex", 1,
                "itemsPerPage", schemas.size(),
                "Resources", schemas));
    }

    @GetMapping("/Users")
    public ResponseEntity<ScimModels.ListResponse<ScimModels.UserResponse>> users(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().contentType(SCIM_JSON)
                .body(userService.search(filter, startIndex, count, cursor));
    }

    @GetMapping("/Users/{id}")
    public ResponseEntity<ScimModels.UserResponse> user(@PathVariable UUID id) {
        return entity(userService.get(id));
    }

    @PostMapping("/Users")
    public ResponseEntity<ScimModels.UserResponse> createUser(
            @Valid @RequestBody ScimModels.UserRequest request,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        ScimUserService.MutationResult result = userService.create(request, correlationId);
        return mutate(result.response(), result.created());
    }

    @PutMapping("/Users/{id}")
    public ResponseEntity<ScimModels.UserResponse> replaceUser(
            @PathVariable UUID id,
            @Valid @RequestBody ScimModels.UserRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return entity(userService.replace(id, request, ifMatch, correlationId));
    }

    @PatchMapping("/Users/{id}")
    public ResponseEntity<ScimModels.UserResponse> patchUser(
            @PathVariable UUID id,
            @Valid @RequestBody ScimModels.PatchRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return entity(userService.patch(id, request, ifMatch, correlationId));
    }

    @DeleteMapping("/Users/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        userService.deactivate(id, ifMatch, correlationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/Groups")
    public ResponseEntity<ScimModels.ListResponse<ScimModels.GroupResponse>> groups(
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) Integer startIndex,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().contentType(SCIM_JSON)
                .body(groupService.search(filter, startIndex, count, cursor));
    }

    @GetMapping("/Groups/{id}")
    public ResponseEntity<ScimModels.GroupResponse> group(@PathVariable UUID id) {
        return entity(groupService.get(id));
    }

    @PostMapping("/Groups")
    public ResponseEntity<ScimModels.GroupResponse> createGroup(
            @Valid @RequestBody ScimModels.GroupRequest request,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        ScimGroupService.MutationResult result = groupService.create(request, correlationId);
        return mutate(result.response(), result.created());
    }

    @PutMapping("/Groups/{id}")
    public ResponseEntity<ScimModels.GroupResponse> replaceGroup(
            @PathVariable UUID id,
            @Valid @RequestBody ScimModels.GroupRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return entity(groupService.replace(id, request, ifMatch, correlationId));
    }

    @PatchMapping("/Groups/{id}")
    public ResponseEntity<ScimModels.GroupResponse> patchGroup(
            @PathVariable UUID id,
            @Valid @RequestBody ScimModels.PatchRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        return entity(groupService.patch(id, request, ifMatch, correlationId));
    }

    @DeleteMapping("/Groups/{id}")
    public ResponseEntity<Void> deleteGroup(
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId) {
        groupService.deactivate(id, ifMatch, correlationId);
        return ResponseEntity.noContent().build();
    }

    private <T> ResponseEntity<T> mutate(T body, boolean created) {
        ScimModels.Meta meta = body instanceof ScimModels.UserResponse user
                ? user.meta()
                : ((ScimModels.GroupResponse) body).meta();
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .contentType(SCIM_JSON)
                .header(HttpHeaders.ETAG, meta.version())
                .header(HttpHeaders.LOCATION, meta.location())
                .body(body);
    }

    private <T> ResponseEntity<T> entity(T body) {
        ScimModels.Meta meta = body instanceof ScimModels.UserResponse user
                ? user.meta()
                : ((ScimModels.GroupResponse) body).meta();
        return ResponseEntity.ok()
                .contentType(SCIM_JSON)
                .header(HttpHeaders.ETAG, meta.version())
                .header(HttpHeaders.LOCATION, meta.location())
                .body(body);
    }

    private ResponseEntity<Map<String, Object>> scim(Map<String, Object> body) {
        return ResponseEntity.ok().contentType(SCIM_JSON).body(body);
    }
}
