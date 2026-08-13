package com.dwp.services.auth.scim;

import com.dwp.services.auth.entity.DirectoryGroup;
import com.dwp.services.auth.entity.DirectoryGroupMember;
import com.dwp.services.auth.entity.AuthSession;
import com.dwp.services.auth.entity.User;
import com.dwp.services.auth.repository.AuthSessionRepository;
import com.dwp.services.auth.repository.DirectoryGroupMemberRepository;
import com.dwp.services.auth.repository.DirectoryGroupRepository;
import com.dwp.services.auth.repository.UserRepository;
import com.dwp.services.auth.service.GroupRoleConflictGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ScimGroupService {

    private static final String SCIM = "SCIM";
    private static final Pattern FILTER = Pattern.compile(
            "^(displayName|externalId)\\s+eq\\s+\"([^\"]+)\"$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MEMBER_FILTER = Pattern.compile(
            "^members\\[value\\s+eq\\s+\"([^\"]+)\"\\]$",
            Pattern.CASE_INSENSITIVE);

    private final DirectoryGroupRepository groupRepository;
    private final DirectoryGroupMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final AuthSessionRepository sessionRepository;
    private final ScimProvisioningAuditService auditService;
    private final GroupRoleConflictGuard groupRoleConflictGuard;
    private final ScimCursorCodec cursorCodec;
    private final String baseUrl;

    public ScimGroupService(
            DirectoryGroupRepository groupRepository,
            DirectoryGroupMemberRepository memberRepository,
            UserRepository userRepository,
            AuthSessionRepository sessionRepository,
            ScimProvisioningAuditService auditService,
            GroupRoleConflictGuard groupRoleConflictGuard,
            ScimCursorCodec cursorCodec,
            @Value("${dwp.scim.base-url:http://localhost:8080/scim/v2}") String baseUrl) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.auditService = auditService;
        this.groupRoleConflictGuard = groupRoleConflictGuard;
        this.cursorCodec = cursorCodec;
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    @Transactional(readOnly = true)
    public ScimModels.ListResponse<ScimModels.GroupResponse> search(
            String filter,
            Integer requestedStartIndex,
            Integer requestedCount,
            String cursor) {
        Long tenantId = ScimConnectorContext.require().tenantId();
        int count = Math.min(100, Math.max(0, requestedCount == null ? 50 : requestedCount));
        int offset = cursorCodec.decode(cursor, "Groups", tenantId, count, filter);
        if (cursor == null || cursor.isBlank()) {
            offset = Math.max(0, (requestedStartIndex == null ? 1 : requestedStartIndex) - 1);
        }
        Specification<DirectoryGroup> specification = specification(tenantId, filter);
        long total = groupRepository.count(specification);
        if (count == 0 || offset >= total) {
            return new ScimModels.ListResponse<>(
                    List.of(ScimModels.LIST_RESPONSE), total, offset + 1, 0, List.of(), null);
        }
        Page<DirectoryGroup> page = groupRepository.findAll(
                specification,
                new ScimOffsetPageRequest(offset, count, Sort.by("groupId").ascending()));
        Map<Long, List<ScimModels.Member>> members = membersByGroup(tenantId, page.getContent());
        int nextOffset = offset + page.getNumberOfElements();
        String nextCursor = nextOffset < total
                ? cursorCodec.encode("Groups", tenantId, nextOffset, count, filter)
                : null;
        return new ScimModels.ListResponse<>(
                List.of(ScimModels.LIST_RESPONSE), total, offset + 1,
                page.getNumberOfElements(),
                page.stream().map(group -> response(
                        group, members.getOrDefault(group.getGroupId(), List.of()))).toList(),
                nextCursor);
    }

    @Transactional(readOnly = true)
    public ScimModels.GroupResponse get(UUID publicId) {
        DirectoryGroup group = require(publicId);
        return response(group, members(group));
    }

    @Transactional
    public MutationResult create(ScimModels.GroupRequest request, String correlationId) {
        requireSchema(request.schemas(), ScimModels.CORE_GROUP);
        Long tenantId = ScimConnectorContext.require().tenantId();
        DirectoryGroup group = findIdempotent(tenantId, request);
        boolean created = group == null;
        if (created) {
            group = DirectoryGroup.builder()
                    .publicId(UUID.randomUUID())
                    .tenantId(tenantId)
                    .groupKey(groupKey(request))
                    .sourceType(SCIM)
                    .status("ACTIVE")
                    .revision(1L)
                    .build();
        } else if (!SCIM.equals(group.getSourceType())) {
            throw ScimException.conflict("The requested group belongs to a non-SCIM source.");
        } else {
            group = groupRepository.findByGroupIdAndTenantIdForUpdate(
                            group.getGroupId(), tenantId)
                    .orElseThrow(ScimException::notFound);
        }
        group.setDisplayName(request.displayName().trim());
        group.setExternalId(trimToNull(request.externalId()));
        group.setStatus("ACTIVE");
        group.setRevision(valueOrZero(group.getRevision()) + (created ? 0 : 1));
        group = save(group);
        replaceMembers(
                group, request.members(), created ? "CREATE" : "REPLACE", correlationId);
        auditService.success(
                created ? "CREATE" : "REPLACE", "GROUP",
                group.getPublicId().toString(), group.getExternalId(), correlationId);
        return new MutationResult(response(group, members(group)), created);
    }

    @Transactional
    public ScimModels.GroupResponse replace(
            UUID publicId,
            ScimModels.GroupRequest request,
            String ifMatch,
            String correlationId) {
        requireSchema(request.schemas(), ScimModels.CORE_GROUP);
        DirectoryGroup group = requireForUpdate(publicId);
        requireScimOwned(group);
        ScimVersionPrecondition.verify(ifMatch, group.getVersion());
        group.setDisplayName(request.displayName().trim());
        group.setExternalId(trimToNull(request.externalId()));
        group.setStatus("ACTIVE");
        group.setRevision(valueOrZero(group.getRevision()) + 1L);
        group = save(group);
        replaceMembers(group, request.members(), "REPLACE", correlationId);
        auditService.success("REPLACE", "GROUP", publicId.toString(), group.getExternalId(), correlationId);
        return response(group, members(group));
    }

    @Transactional
    public ScimModels.GroupResponse patch(
            UUID publicId,
            ScimModels.PatchRequest request,
            String ifMatch,
            String correlationId) {
        requireSchema(request.schemas(), ScimModels.PATCH_OP);
        DirectoryGroup group = requireForUpdate(publicId);
        requireScimOwned(group);
        ScimVersionPrecondition.verify(ifMatch, group.getVersion());
        LinkedHashSet<UUID> memberIds = members(group).stream()
                .map(member -> parseUuid(member.value()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (ScimModels.PatchOperation operation : request.operations()) {
            applyPatch(group, memberIds, operation);
        }
        group.setRevision(valueOrZero(group.getRevision()) + 1L);
        group = save(group);
        replaceMemberIds(group, memberIds, "PATCH", correlationId);
        auditService.success("PATCH", "GROUP", publicId.toString(), group.getExternalId(), correlationId);
        return response(group, members(group));
    }

    @Transactional
    public void deactivate(UUID publicId, String ifMatch, String correlationId) {
        DirectoryGroup group = requireForUpdate(publicId);
        requireScimOwned(group);
        ScimVersionPrecondition.verify(ifMatch, group.getVersion());
        group.setStatus("INACTIVE");
        group.setRevision(valueOrZero(group.getRevision()) + 1L);
        group = save(group);
        Set<Long> affectedUserIds = memberRepository.findByTenantIdAndGroupId(
                        group.getTenantId(), group.getGroupId())
                .stream()
                .filter(member -> SCIM.equals(member.getSourceType()))
                .map(DirectoryGroupMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        memberRepository.deleteByTenantIdAndGroupIdAndSourceType(
                group.getTenantId(), group.getGroupId(), SCIM);
        memberRepository.flush();
        invalidateIdentityContext(group.getTenantId(), affectedUserIds);
        auditService.success("DELETE", "GROUP", publicId.toString(), group.getExternalId(), correlationId);
    }

    private void applyPatch(
            DirectoryGroup group,
            Set<UUID> memberIds,
            ScimModels.PatchOperation operation) {
        String op = operation.op().trim().toLowerCase(Locale.ROOT);
        if (!List.of("add", "replace", "remove").contains(op)) {
            throw ScimException.invalidValue("Unsupported SCIM patch operation: " + operation.op());
        }
        String path = operation.path() == null ? "" : operation.path().trim();
        if (path.isEmpty() && operation.value() != null && operation.value().isObject()) {
            JsonNode value = operation.value();
            if (value.has("displayName")) group.setDisplayName(value.path("displayName").asText());
            if (value.has("members")) {
                if ("replace".equals(op)) memberIds.clear();
                memberIds.addAll(memberValues(value.path("members")));
            }
            return;
        }
        if (path.equalsIgnoreCase("displayName")) {
            if ("remove".equals(op)) throw ScimException.invalidValue("displayName cannot be removed.");
            group.setDisplayName(textValue(operation.value()));
            return;
        }
        if (path.equalsIgnoreCase("members")) {
            if ("replace".equals(op) || "remove".equals(op)) memberIds.clear();
            if (!"remove".equals(op)) memberIds.addAll(memberValues(operation.value()));
            return;
        }
        Matcher matcher = MEMBER_FILTER.matcher(path);
        if (matcher.matches() && "remove".equals(op)) {
            memberIds.remove(parseUuid(matcher.group(1)));
            return;
        }
        throw ScimException.invalidValue("Unsupported SCIM group patch path: " + operation.path());
    }

    private void replaceMembers(
            DirectoryGroup group,
            List<ScimModels.Member> members,
            String operation,
            String correlationId) {
        Set<UUID> ids = members == null
                ? Set.of()
                : members.stream().map(member -> parseUuid(member.value()))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        replaceMemberIds(group, ids, operation, correlationId);
    }

    private void replaceMemberIds(
            DirectoryGroup group,
            Collection<UUID> publicIds,
            String operation,
            String correlationId) {
        Long tenantId = group.getTenantId();
        List<User> users = publicIds.isEmpty()
                ? List.of()
                : userRepository.findByTenantIdAndPublicIdIn(tenantId, publicIds);
        if (users.size() != publicIds.size()) {
            throw ScimException.invalidValue("One or more SCIM group members do not exist in this tenant.");
        }
        List<DirectoryGroupMember> current = memberRepository.findByTenantIdAndGroupId(
                tenantId, group.getGroupId());
        Set<Long> currentScimIds = current.stream()
                .filter(member -> SCIM.equals(member.getSourceType()))
                .map(DirectoryGroupMember::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> nextScimIds = users.stream()
                .map(User::getUserId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> addedIds = new LinkedHashSet<>(nextScimIds);
        addedIds.removeAll(currentScimIds);
        groupRoleConflictGuard.evaluateMembershipAddition(
                        tenantId, group.getGroupId(), addedIds)
                .ifPresent(violation -> {
                    auditService.denied(
                            operation, "GROUP", group.getPublicId().toString(),
                            group.getExternalId(), correlationId, violation.reason());
                    throw ScimException.conflict(
                            "A requested group member would violate separation-of-duties policy.");
                });
        Set<Long> locallyManaged = current.stream()
                .filter(member -> !SCIM.equals(member.getSourceType()))
                .map(DirectoryGroupMember::getUserId)
                .collect(Collectors.toSet());
        memberRepository.deleteByTenantIdAndGroupIdAndSourceType(tenantId, group.getGroupId(), SCIM);
        memberRepository.flush();
        List<DirectoryGroupMember> next = users.stream()
                .filter(user -> !locallyManaged.contains(user.getUserId()))
                .map(user -> DirectoryGroupMember.builder()
                        .tenantId(tenantId)
                        .groupId(group.getGroupId())
                        .userId(user.getUserId())
                        .sourceType(SCIM)
                        .build())
                .toList();
        memberRepository.saveAll(next);
        Set<Long> changedUserIds = new LinkedHashSet<>(currentScimIds);
        changedUserIds.addAll(nextScimIds);
        Set<Long> unchangedUserIds = new LinkedHashSet<>(currentScimIds);
        unchangedUserIds.retainAll(nextScimIds);
        changedUserIds.removeAll(unchangedUserIds);
        invalidateIdentityContext(tenantId, changedUserIds);
    }

    private DirectoryGroup findIdempotent(Long tenantId, ScimModels.GroupRequest request) {
        if (request.externalId() != null && !request.externalId().isBlank()) {
            return groupRepository.findByTenantIdAndSourceTypeAndExternalId(
                    tenantId, SCIM, request.externalId().trim()).orElse(null);
        }
        return groupRepository.findByTenantIdAndSourceTypeAndDisplayName(
                tenantId, SCIM, request.displayName().trim()).orElse(null);
    }

    private DirectoryGroup require(UUID publicId) {
        return groupRepository.findByPublicIdAndTenantId(
                        publicId, ScimConnectorContext.require().tenantId())
                .orElseThrow(ScimException::notFound);
    }

    private DirectoryGroup requireForUpdate(UUID publicId) {
        return groupRepository.findByPublicIdAndTenantIdForUpdate(
                        publicId, ScimConnectorContext.require().tenantId())
                .orElseThrow(ScimException::notFound);
    }

    private void invalidateIdentityContext(Long tenantId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return;
        List<User> users = userRepository.findByTenantIdAndUserIdInForUpdate(tenantId, userIds);
        users.forEach(user -> user.setAccessRevision(valueOrZero(user.getAccessRevision()) + 1L));
        userRepository.saveAll(users);
        Instant now = Instant.now();
        List<AuthSession> sessions = sessionRepository
                .findByTenantIdAndUserIdInAndRevokedAtIsNull(tenantId, userIds);
        sessions.forEach(session -> session.setRevokedAt(now));
        sessionRepository.saveAll(sessions);
    }

    private void requireScimOwned(DirectoryGroup group) {
        if (!SCIM.equals(group.getSourceType())) {
            throw new ScimException(403, "mutability", "The group is not managed by this SCIM boundary.");
        }
    }

    private DirectoryGroup save(DirectoryGroup group) {
        try {
            return groupRepository.saveAndFlush(group);
        } catch (DataIntegrityViolationException exception) {
            throw ScimException.conflict("The SCIM group key or externalId is already in use.");
        }
    }

    private List<ScimModels.Member> members(DirectoryGroup group) {
        List<DirectoryGroupMember> memberships = memberRepository.findByTenantIdAndGroupId(
                group.getTenantId(), group.getGroupId());
        if (memberships.isEmpty()) return List.of();
        Map<Long, User> users = userRepository.findAllById(
                        memberships.stream().map(DirectoryGroupMember::getUserId).toList())
                .stream()
                .filter(user -> group.getTenantId().equals(user.getTenantId()))
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        return memberships.stream()
                .map(membership -> users.get(membership.getUserId()))
                .filter(java.util.Objects::nonNull)
                .map(user -> new ScimModels.Member(
                        user.getPublicId().toString(), user.getDisplayName(), "User"))
                .toList();
    }

    private Map<Long, List<ScimModels.Member>> membersByGroup(
            Long tenantId,
            List<DirectoryGroup> groups) {
        if (groups.isEmpty()) return Map.of();
        Map<Long, DirectoryGroup> byId = groups.stream()
                .collect(Collectors.toMap(DirectoryGroup::getGroupId, Function.identity()));
        List<DirectoryGroupMember> memberships = memberRepository.findByTenantIdAndGroupIdIn(
                tenantId, byId.keySet());
        Map<Long, User> users = userRepository.findAllById(
                        memberships.stream().map(DirectoryGroupMember::getUserId).distinct().toList())
                .stream().filter(user -> tenantId.equals(user.getTenantId()))
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        return memberships.stream()
                .filter(membership -> users.containsKey(membership.getUserId()))
                .collect(Collectors.groupingBy(
                        DirectoryGroupMember::getGroupId,
                        Collectors.mapping(membership -> {
                            User user = users.get(membership.getUserId());
                            return new ScimModels.Member(
                                    user.getPublicId().toString(), user.getDisplayName(), "User");
                        }, Collectors.toList())));
    }

    private ScimModels.GroupResponse response(
            DirectoryGroup group,
            List<ScimModels.Member> members) {
        String id = group.getPublicId().toString();
        return new ScimModels.GroupResponse(
                List.of(ScimModels.CORE_GROUP), id, group.getExternalId(),
                group.getDisplayName(), members,
                new ScimModels.Meta(
                        "Group", instant(group.getCreatedAt()), instant(group.getUpdatedAt()),
                        version(group.getVersion()), baseUrl + "/Groups/" + id));
    }

    private Specification<DirectoryGroup> specification(Long tenantId, String filter) {
        Specification<DirectoryGroup> specification = (root, ignored, builder) ->
                builder.equal(root.get("tenantId"), tenantId);
        if (filter == null || filter.isBlank()) return specification;
        Matcher matcher = FILTER.matcher(filter.trim());
        if (!matcher.matches()) {
            throw ScimException.invalidFilter("Only displayName eq and externalId eq filters are supported.");
        }
        String attribute = matcher.group(1).toLowerCase(Locale.ROOT);
        String value = matcher.group(2);
        return specification.and((root, ignored, builder) -> attribute.equals("displayname")
                ? builder.equal(root.get("displayName"), value)
                : builder.equal(root.get("externalId"), value));
    }

    private String groupKey(ScimModels.GroupRequest request) {
        String seed = trimToNull(request.externalId());
        if (seed == null) seed = request.displayName() + ":" + UUID.randomUUID();
        return "scim-" + sha256(seed).substring(0, 24);
    }

    private Set<UUID> memberValues(JsonNode value) {
        if (value == null) throw ScimException.invalidValue("A members value is required.");
        JsonNode values = value.isArray() ? value : value.path("members");
        if (!values.isArray()) {
            if (value.isObject() && value.has("value")) {
                return Set.of(parseUuid(value.path("value").asText()));
            }
            throw ScimException.invalidValue("The members patch value is invalid.");
        }
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        values.forEach(item -> result.add(parseUuid(item.path("value").asText())));
        return result;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ScimException.invalidValue("A SCIM member value must be a valid User id.");
        }
    }

    private String textValue(JsonNode value) {
        if (value == null || !value.isValueNode()) throw ScimException.invalidValue("A text value is required.");
        return value.asText();
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

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record MutationResult(ScimModels.GroupResponse response, boolean created) {
    }
}
