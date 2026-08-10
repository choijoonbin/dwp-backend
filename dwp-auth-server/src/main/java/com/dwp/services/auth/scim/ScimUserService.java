package com.dwp.services.auth.scim;

import com.dwp.core.identity.EmailAddressNormalizer;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.IdentityAccountService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScimUserService {

    private static final String SCIM = "SCIM";
    private static final Pattern FILTER = Pattern.compile(
            "^(userName|externalId)\\s+eq\\s+\"([^\"]+)\"$",
            Pattern.CASE_INSENSITIVE);

    private final UserRepository userRepository;
    private final IdentityAccountService identityAccountService;
    private final ScimProvisioningAuditService auditService;
    private final ScimCursorCodec cursorCodec;
    private final String baseUrl;

    public ScimUserService(
            UserRepository userRepository,
            IdentityAccountService identityAccountService,
            ScimProvisioningAuditService auditService,
            ScimCursorCodec cursorCodec,
            @Value("${dwp.scim.base-url:http://localhost:8080/scim/v2}") String baseUrl) {
        this.userRepository = userRepository;
        this.identityAccountService = identityAccountService;
        this.auditService = auditService;
        this.cursorCodec = cursorCodec;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @Transactional(readOnly = true)
    public ScimModels.ListResponse<ScimModels.UserResponse> search(
            String filter,
            Integer requestedStartIndex,
            Integer requestedCount,
            String cursor) {
        Long tenantId = ScimConnectorContext.require().tenantId();
        int count = Math.min(100, Math.max(0, requestedCount == null ? 50 : requestedCount));
        int offset = cursorCodec.decode(cursor, "Users", tenantId, count, filter);
        if (cursor == null || cursor.isBlank()) {
            offset = Math.max(0, (requestedStartIndex == null ? 1 : requestedStartIndex) - 1);
        }
        Specification<User> specification = specification(tenantId, filter);
        long total = userRepository.count(specification);
        if (count == 0 || offset >= total) {
            return new ScimModels.ListResponse<>(
                    List.of(ScimModels.LIST_RESPONSE), total, offset + 1, 0, List.of(), null);
        }
        Page<User> page = userRepository.findAll(
                specification,
                new ScimOffsetPageRequest(offset, count, Sort.by("userId").ascending()));
        int nextOffset = offset + page.getNumberOfElements();
        String nextCursor = nextOffset < total
                ? cursorCodec.encode("Users", tenantId, nextOffset, count, filter)
                : null;
        return new ScimModels.ListResponse<>(
                List.of(ScimModels.LIST_RESPONSE),
                total,
                offset + 1,
                page.getNumberOfElements(),
                page.stream().map(this::response).toList(),
                nextCursor);
    }

    @Transactional(readOnly = true)
    public ScimModels.UserResponse get(UUID publicId) {
        User user = require(publicId);
        return response(user);
    }

    @Transactional
    public MutationResult create(ScimModels.UserRequest request, String correlationId) {
        requireSchema(request.schemas(), ScimModels.CORE_USER);
        Long tenantId = ScimConnectorContext.require().tenantId();
        User user = findIdempotent(tenantId, request);
        boolean created = user == null;
        if (created) {
            user = User.builder()
                    .publicId(UUID.randomUUID())
                    .tenantId(tenantId)
                    .sourceType(SCIM)
                    .accessRevision(0L)
                    .mfaEnabled(false)
                    .build();
        } else if (!SCIM.equals(user.getSourceType())) {
            throw ScimException.conflict("The requested userName belongs to a non-SCIM identity.");
        }
        apply(user, request, true);
        user = save(user);
        identityAccountService.synchronizeManagedUser(user);
        auditService.success(
                created ? "CREATE" : "REPLACE", "USER",
                user.getPublicId().toString(), user.getExternalId(), correlationId);
        return new MutationResult(response(user), created);
    }

    @Transactional
    public ScimModels.UserResponse replace(
            UUID publicId,
            ScimModels.UserRequest request,
            String ifMatch,
            String correlationId) {
        requireSchema(request.schemas(), ScimModels.CORE_USER);
        User user = require(publicId);
        requireScimOwned(user);
        ScimVersionPrecondition.verify(ifMatch, user.getVersion());
        apply(user, request, true);
        user = save(user);
        identityAccountService.synchronizeManagedUser(user);
        auditService.success("REPLACE", "USER", publicId.toString(), user.getExternalId(), correlationId);
        return response(user);
    }

    @Transactional
    public ScimModels.UserResponse patch(
            UUID publicId,
            ScimModels.PatchRequest request,
            String ifMatch,
            String correlationId) {
        requireSchema(request.schemas(), ScimModels.PATCH_OP);
        User user = require(publicId);
        requireScimOwned(user);
        ScimVersionPrecondition.verify(ifMatch, user.getVersion());
        for (ScimModels.PatchOperation operation : request.operations()) {
            applyPatch(user, operation);
        }
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
        user = save(user);
        identityAccountService.synchronizeManagedUser(user);
        auditService.success("PATCH", "USER", publicId.toString(), user.getExternalId(), correlationId);
        return response(user);
    }

    @Transactional
    public void deactivate(UUID publicId, String ifMatch, String correlationId) {
        User user = require(publicId);
        requireScimOwned(user);
        ScimVersionPrecondition.verify(ifMatch, user.getVersion());
        user.setStatus("INACTIVE");
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
        user = save(user);
        identityAccountService.synchronizeManagedUser(user);
        auditService.success("DELETE", "USER", publicId.toString(), user.getExternalId(), correlationId);
    }

    private User findIdempotent(Long tenantId, ScimModels.UserRequest request) {
        if (request.externalId() != null && !request.externalId().isBlank()) {
            User byExternal = userRepository.findByTenantIdAndSourceTypeAndExternalId(
                    tenantId, SCIM, request.externalId().trim()).orElse(null);
            if (byExternal != null) return byExternal;
        }
        return userRepository.findByTenantIdAndScimUserName(
                tenantId, normalizeUserName(request.userName())).orElse(null);
    }

    private void apply(User user, ScimModels.UserRequest request, boolean replace) {
        String userName = normalizeUserName(request.userName());
        user.setScimUserName(userName);
        user.setExternalId(trimToNull(request.externalId()));
        user.setDisplayName(displayName(request));
        user.setEmail(primaryEmail(request.emails()));
        user.setJobTitle(trimToNull(request.title()));
        user.setPreferredLocale(trimToNull(request.locale()));
        user.setGivenName(request.name() == null ? null : trimToNull(request.name().givenName()));
        user.setFamilyName(request.name() == null ? null : trimToNull(request.name().familyName()));
        user.setStatus(Boolean.FALSE.equals(request.active()) ? "INACTIVE" : "ACTIVE");
        user.setSourceType(SCIM);
        user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L);
    }

    private void applyPatch(User user, ScimModels.PatchOperation operation) {
        String op = operation.op().trim().toLowerCase(Locale.ROOT);
        if (!List.of("add", "replace", "remove").contains(op)) {
            throw ScimException.invalidValue("Unsupported SCIM patch operation: " + operation.op());
        }
        String path = operation.path() == null ? "" : operation.path().trim().toLowerCase(Locale.ROOT);
        JsonNode value = operation.value();
        if (path.isEmpty() && value != null && value.isObject()) {
            if (value.has("active")) user.setStatus(value.path("active").asBoolean() ? "ACTIVE" : "INACTIVE");
            if (value.has("displayName")) user.setDisplayName(value.path("displayName").asText());
            if (value.has("title")) user.setJobTitle(trimToNull(value.path("title").asText()));
            if (value.has("locale")) user.setPreferredLocale(trimToNull(value.path("locale").asText()));
            return;
        }
        if (path.equals("active")) {
            if ("remove".equals(op)) throw ScimException.invalidValue("active cannot be removed.");
            user.setStatus(booleanValue(value) ? "ACTIVE" : "INACTIVE");
        } else if (path.equals("displayname")) {
            user.setDisplayName("remove".equals(op) ? user.getScimUserName() : textValue(value));
        } else if (path.equals("username")) {
            if ("remove".equals(op)) throw ScimException.invalidValue("userName cannot be removed.");
            user.setScimUserName(normalizeUserName(textValue(value)));
        } else if (path.equals("title")) {
            user.setJobTitle("remove".equals(op) ? null : trimToNull(textValue(value)));
        } else if (path.equals("locale")) {
            user.setPreferredLocale("remove".equals(op) ? null : trimToNull(textValue(value)));
        } else if (path.startsWith("emails")) {
            user.setEmail("remove".equals(op) ? null : emailValue(value));
        } else {
            throw ScimException.invalidValue("Unsupported SCIM user patch path: " + operation.path());
        }
    }

    private User save(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw ScimException.conflict("The SCIM userName, externalId, or email is already in use.");
        }
    }

    private User require(UUID publicId) {
        return userRepository.findByPublicIdAndTenantId(
                        publicId, ScimConnectorContext.require().tenantId())
                .orElseThrow(ScimException::notFound);
    }

    private void requireScimOwned(User user) {
        if (!SCIM.equals(user.getSourceType())) {
            throw new ScimException(403, "mutability", "The user is not managed by this SCIM boundary.");
        }
    }

    private Specification<User> specification(Long tenantId, String filter) {
        Specification<User> specification = (root, ignored, builder) ->
                builder.equal(root.get("tenantId"), tenantId);
        if (filter == null || filter.isBlank()) return specification;
        Matcher matcher = FILTER.matcher(filter.trim());
        if (!matcher.matches()) {
            throw ScimException.invalidFilter("Only userName eq and externalId eq filters are supported.");
        }
        String attribute = matcher.group(1).toLowerCase(Locale.ROOT);
        String value = matcher.group(2);
        return specification.and((root, ignored, builder) -> attribute.equals("username")
                ? builder.equal(root.get("scimUserName"), normalizeUserName(value))
                : builder.equal(root.get("externalId"), value));
    }

    private ScimModels.UserResponse response(User user) {
        String id = user.getPublicId().toString();
        List<ScimModels.Email> emails = user.getEmail() == null
                ? List.of()
                : List.of(new ScimModels.Email(user.getEmail(), "work", true));
        return new ScimModels.UserResponse(
                List.of(ScimModels.CORE_USER), id, user.getExternalId(),
                user.getScimUserName() == null ? user.getEmail() : user.getScimUserName(),
                "ACTIVE".equals(user.getStatus()), user.getDisplayName(),
                new ScimModels.Name(user.getDisplayName(), user.getFamilyName(), user.getGivenName()),
                user.getJobTitle(), user.getPreferredLocale(), emails,
                new ScimModels.Meta(
                        "User", instant(user.getCreatedAt()), instant(user.getUpdatedAt()),
                        version(user.getVersion()), baseUrl + "/Users/" + id));
    }

    private String displayName(ScimModels.UserRequest request) {
        String display = trimToNull(request.displayName());
        if (display != null) return display;
        if (request.name() != null && trimToNull(request.name().formatted()) != null) {
            return request.name().formatted().trim();
        }
        return request.userName().trim();
    }

    private String primaryEmail(List<ScimModels.Email> emails) {
        if (emails == null || emails.isEmpty()) return null;
        return normalizeEmail(emails.stream().filter(email -> Boolean.TRUE.equals(email.primary()))
                .findFirst().orElse(emails.get(0)).value());
    }

    private String emailValue(JsonNode value) {
        if (value == null) throw ScimException.invalidValue("An email value is required.");
        if (value.isTextual()) return normalizeEmail(value.asText());
        if (value.isArray() && !value.isEmpty()) {
            return normalizeEmail(value.get(0).path("value").asText());
        }
        if (value.isObject()) return normalizeEmail(value.path("value").asText());
        throw ScimException.invalidValue("The email patch value is invalid.");
    }

    private String normalizeEmail(String value) {
        try {
            return EmailAddressNormalizer.requireValid(value);
        } catch (IllegalArgumentException exception) {
            throw ScimException.invalidValue("The work email is invalid.");
        }
    }

    private boolean booleanValue(JsonNode value) {
        if (value == null || !value.isBoolean()) throw ScimException.invalidValue("A boolean value is required.");
        return value.asBoolean();
    }

    private String textValue(JsonNode value) {
        if (value == null || !value.isValueNode()) throw ScimException.invalidValue("A text value is required.");
        return value.asText();
    }

    private String normalizeUserName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 255) {
            throw ScimException.invalidValue("userName is required and must be at most 255 characters.");
        }
        return normalized;
    }

    private void requireSchema(List<String> schemas, String expected) {
        if (schemas == null || !schemas.contains(expected)) {
            throw ScimException.invalidValue("The required SCIM schema is missing: " + expected);
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private Instant instant(java.time.LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private String version(Long value) {
        return "W/\"" + valueOrZero(value) + "\"";
    }

    public record MutationResult(ScimModels.UserResponse response, boolean created) {
    }
}
