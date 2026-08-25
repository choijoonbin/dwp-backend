package com.dwp.services.auth.service;

import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.dwp.services.auth.dto.PermissionDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Resolves the database-owned, scope-bound specialist duty evidence. */
@Service
public class ScopedAdminDutyEvidenceService {

    static final String APPROVAL_APP_RESOURCE = "APP.APPROVALS";
    static final String APPROVAL_AUDIT_DUTY = "APPROVAL_OPERATIONS_AUDIT";
    static final String APPROVAL_OPERATOR_DUTY = "APPROVAL_OPERATIONS_EXECUTE";

    private static final String EFFECTIVE_DUTIES_SQL = """
            SELECT tenant_id, user_id, scoped_duty_assignment_id, duty_code,
                   product_key, legacy_role_code, product_resource_key, resource_key,
                   audit_policy_exception, capability_contract_key,
                   resolved_capability_code, conflicting_duty_code,
                   resource_set_id, resource_set_key,
                   member_resource_type, member_resource_key, valid_to,
                   assignment_source, assignment_version, resource_set_version,
                   resource_member_version, responsibility_version,
                   subject_source_type, subject_source_ref, evidence_revision
              FROM auth_effective_scoped_duties
             WHERE tenant_id = ? AND user_id = ?
             ORDER BY scoped_duty_assignment_id, capability_contract_key,
                      member_resource_type, member_resource_key
            """;

    private static final String RECOVERY_DUTIES_SQL = """
            SELECT duty.tenant_id, duty.user_id, duty.scoped_duty_assignment_id,
                   duty.duty_code, duty.product_key, duty.legacy_role_code,
                   duty.product_resource_key, duty.resource_key,
                   duty.audit_policy_exception,
                   duty.capability_contract_key, duty.resolved_capability_code,
                   duty.conflicting_duty_code, duty.resource_set_id,
                   duty.resource_set_key, duty.member_resource_type,
                   duty.member_resource_key, duty.valid_to,
                   duty.assignment_source, duty.assignment_version,
                   duty.resource_set_version, duty.resource_member_version,
                   duty.responsibility_version, duty.subject_source_type,
                   duty.subject_source_ref, duty.evidence_revision
              FROM auth_effective_scoped_duties duty
              JOIN com_admin_resource_sets event_set
                ON event_set.tenant_id = duty.tenant_id
               AND event_set.resource_set_key = ?
               AND event_set.lifecycle_state = 'ACTIVE'
             WHERE duty.tenant_id = ?
               AND duty.duty_code IN (
                   'APPROVAL_OPERATIONS_AUDIT', 'APPROVAL_OPERATIONS_EXECUTE')
               AND (duty.resource_set_id = event_set.resource_set_id OR EXISTS (
                   SELECT 1
                     FROM com_admin_resource_set_members event_member
                     JOIN com_admin_resource_set_members duty_member
                       ON duty_member.tenant_id = event_member.tenant_id
                      AND duty_member.resource_type = event_member.resource_type
                      AND duty_member.resource_key = event_member.resource_key
                      AND duty_member.resource_set_id = duty.resource_set_id
                      AND duty_member.lifecycle_state = 'ACTIVE'
                    WHERE event_member.tenant_id = duty.tenant_id
                      AND event_member.resource_set_id = event_set.resource_set_id
                      AND event_member.lifecycle_state = 'ACTIVE'
                      AND event_member.resource_key <> duty.product_resource_key))
             ORDER BY duty.user_id, duty.scoped_duty_assignment_id,
                      duty.capability_contract_key, duty.member_resource_type,
                      duty.member_resource_key
            """;

    private final JdbcTemplate jdbc;

    public ScopedAdminDutyEvidenceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<EffectiveDuty> effectiveDuties(Long tenantId, Long userId) {
        return group(jdbc.query(EFFECTIVE_DUTIES_SQL, this::row, tenantId, userId));
    }

    @Transactional(readOnly = true)
    public List<AppGovernanceDtos.ResourceRole> resourceRoles(Long tenantId, Long userId) {
        return effectiveDuties(tenantId, userId).stream()
                .flatMap(duty -> duty.capabilityAuthorities().entrySet().stream().map(
                        authority ->
                        new AppGovernanceDtos.ResourceRole(
                                ScopedAuthorityToken.responsibilityCode(
                                        authority.getKey(), authority.getValue()),
                                resourceType(authority.getValue()),
                                resourceKey(authority.getValue()),
                                duty.resourceSetId(), duty.resourceSetKey(), duty.validTo())))
                .distinct()
                .sorted(Comparator.comparing(AppGovernanceDtos.ResourceRole::responsibilityCode)
                        .thenComparing(AppGovernanceDtos.ResourceRole::resourceSetKey)
                        .thenComparing(AppGovernanceDtos.ResourceRole::resourceKey))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionDTO> capabilityPermissions(Long tenantId, Long userId) {
        return effectiveDuties(tenantId, userId).stream()
                .flatMap(duty -> duty.capabilityAuthorities().values().stream())
                .distinct()
                .sorted()
                .map(authority -> PermissionDTO.builder()
                        .resourceType(resourceType(authority))
                        .resourceKey(resourceKey(authority))
                        .resourceName(resourceKey(authority))
                        .permissionCode(permissionCode(authority))
                        .permissionName(permissionCode(authority))
                        .effect("ALLOW")
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EffectiveDuty> recoveryDuties(Long tenantId, String eventResourceSetKey) {
        if (eventResourceSetKey == null || eventResourceSetKey.isBlank()) return List.of();
        return group(jdbc.query(
                RECOVERY_DUTIES_SQL, this::row, eventResourceSetKey, tenantId));
    }

    private FlatRow row(java.sql.ResultSet result, int ignored) throws java.sql.SQLException {
        return new FlatRow(
                result.getLong("tenant_id"), result.getLong("user_id"),
                result.getObject("scoped_duty_assignment_id", UUID.class),
                result.getString("duty_code"), result.getString("product_key"),
                result.getString("legacy_role_code"),
                result.getString("product_resource_key"),
                result.getString("resource_key"),
                result.getBoolean("audit_policy_exception"),
                result.getString("capability_contract_key"),
                result.getString("resolved_capability_code"),
                result.getString("conflicting_duty_code"),
                result.getObject("resource_set_id", UUID.class),
                result.getString("resource_set_key"),
                result.getString("member_resource_type"),
                result.getString("member_resource_key"),
                result.getObject("valid_to", OffsetDateTime.class),
                result.getString("assignment_source"),
                result.getLong("assignment_version"),
                result.getLong("resource_set_version"),
                result.getLong("resource_member_version"),
                (Long) result.getObject("responsibility_version"),
                result.getString("subject_source_type"),
                result.getString("subject_source_ref"),
                result.getString("evidence_revision"));
    }

    private List<EffectiveDuty> group(List<FlatRow> rows) {
        Map<DutyKey, Accumulator> grouped = new LinkedHashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(
                        new DutyKey(row.userId(), row.assignmentId()),
                        ignored -> new Accumulator(row))
                .add(row));
        return grouped.values().stream()
                .map(Accumulator::build)
                .sorted(Comparator.comparing(EffectiveDuty::userId)
                        .thenComparing(EffectiveDuty::dutyCode)
                        .thenComparing(EffectiveDuty::resourceSetKey)
                        .thenComparing(EffectiveDuty::assignmentId))
                .toList();
    }

    static boolean overlaps(EffectiveDuty left, EffectiveDuty right) {
        if (left.resourceSetId().equals(right.resourceSetId())) return true;
        Set<ResourceMember> smaller = left.members().size() <= right.members().size()
                ? left.members() : right.members();
        Set<ResourceMember> larger = smaller == left.members()
                ? right.members() : left.members();
        return smaller.stream()
                .filter(member -> !member.resourceKey().equals(left.productResourceKey()))
                .filter(member -> !member.resourceKey().equals(right.productResourceKey()))
                .anyMatch(larger::contains);
    }

    private static String resourceKey(String authority) {
        return authority.substring(0, authority.indexOf(':'));
    }

    private static String permissionCode(String authority) {
        return authority.substring(authority.indexOf(':') + 1);
    }

    private static String resourceType(String authority) {
        return resourceKey(authority).substring(0, resourceKey(authority).indexOf('.'));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record EffectiveDuty(
            Long tenantId,
            Long userId,
            UUID assignmentId,
            String dutyCode,
            String productKey,
            String legacyRoleCode,
            String productResourceKey,
            String resourceKey,
            boolean auditPolicyException,
            UUID resourceSetId,
            String resourceSetKey,
            Map<String, String> capabilityAuthorities,
            Set<String> conflictingDutyCodes,
            Set<ResourceMember> members,
            OffsetDateTime validTo,
            String assignmentSource,
            String subjectSourceType,
            String subjectSourceRef,
            String evidenceRevision) {

        public EffectiveDuty {
            capabilityAuthorities = Map.copyOf(capabilityAuthorities);
            conflictingDutyCodes = Set.copyOf(conflictingDutyCodes);
            members = Set.copyOf(members);
        }

        public boolean grants(
                String capabilityContractKey,
                String resolvedCapabilityCode) {
            return resolvedCapabilityCode != null && resolvedCapabilityCode.equals(
                    capabilityAuthorities.get(capabilityContractKey));
        }

        public boolean containsResource(String resourceKey) {
            return members.stream().anyMatch(member -> member.resourceKey().equals(resourceKey));
        }
    }

    public record ResourceMember(String resourceType, String resourceKey) {
    }

    private record DutyKey(Long userId, UUID assignmentId) {
    }

    private record FlatRow(
            Long tenantId, Long userId, UUID assignmentId, String dutyCode,
            String productKey, String legacyRoleCode, String productResourceKey,
            String resourceKey,
            boolean auditPolicyException, String capabilityContractKey,
            String resolvedCapabilityCode, String conflictingDutyCode,
            UUID resourceSetId, String resourceSetKey,
            String memberResourceType, String memberResourceKey,
            OffsetDateTime validTo, String assignmentSource,
            long assignmentVersion, long resourceSetVersion,
            long resourceMemberVersion, Long responsibilityVersion,
            String subjectSourceType, String subjectSourceRef,
            String evidenceRevision) {
    }

    private static final class Accumulator {
        private final FlatRow first;
        private final Map<String, String> capabilities = new LinkedHashMap<>();
        private final Set<String> conflictingDutyCodes = new LinkedHashSet<>();
        private final Set<ResourceMember> members = new LinkedHashSet<>();
        private final List<String> revisions = new ArrayList<>();

        private Accumulator(FlatRow first) {
            this.first = first;
        }

        private void add(FlatRow row) {
            String previous = capabilities.putIfAbsent(
                    row.capabilityContractKey(), row.resolvedCapabilityCode());
            if (previous != null && !previous.equals(row.resolvedCapabilityCode())) {
                throw new IllegalStateException("Scoped duty capability mapping is ambiguous");
            }
            if (row.conflictingDutyCode() != null) {
                conflictingDutyCodes.add(row.conflictingDutyCode());
            }
            members.add(new ResourceMember(row.memberResourceType(), row.memberResourceKey()));
            revisions.add(String.join("|",
                    row.capabilityContractKey(), row.resolvedCapabilityCode(),
                    String.valueOf(row.conflictingDutyCode()), row.memberResourceType(),
                    row.memberResourceKey(), row.evidenceRevision(),
                    Long.toString(row.assignmentVersion()),
                    Long.toString(row.resourceSetVersion()),
                    Long.toString(row.resourceMemberVersion()),
                    String.valueOf(row.responsibilityVersion())));
        }

        private EffectiveDuty build() {
            String material = revisions.stream().sorted().collect(Collectors.joining("\n"));
            return new EffectiveDuty(
                    first.tenantId(), first.userId(), first.assignmentId(), first.dutyCode(),
                    first.productKey(), first.legacyRoleCode(), first.productResourceKey(),
                    first.resourceKey(),
                    first.auditPolicyException(), first.resourceSetId(), first.resourceSetKey(),
                    capabilities, conflictingDutyCodes, members,
                    first.validTo(), first.assignmentSource(),
                    first.subjectSourceType(), first.subjectSourceRef(),
                    "scoped-duty-" + sha256(material));
        }
    }
}
