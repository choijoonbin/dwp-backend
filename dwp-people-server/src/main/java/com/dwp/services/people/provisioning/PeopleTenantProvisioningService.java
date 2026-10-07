package com.dwp.services.people.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.dwp.core.provisioning.ProviderTenantCommandReceiptStore;
import com.dwp.services.people.hr.HrDomainFoundationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PeopleTenantProvisioningService {

    static final List<AssignmentChangeReasonSeed> STANDARD_ASSIGNMENT_CHANGE_REASONS = List.of(
            new AssignmentChangeReasonSeed(
                    "SEED_IMPORT", "Initial import",
                    "Initial workforce projection import.",
                    "{\"ko\":\"최초 가져오기\",\"en\":\"Initial import\"}", 10),
            new AssignmentChangeReasonSeed(
                    "REFERENCE_PROFILE", "Reference profile",
                    "Synthetic enterprise reference profile seed.",
                    "{\"ko\":\"참조 프로필\",\"en\":\"Reference profile\"}", 20),
            new AssignmentChangeReasonSeed(
                    "INTERNAL_TRANSFER", "Internal transfer",
                    "Assignment moved within the tenant.",
                    "{\"ko\":\"사내 이동\",\"en\":\"Internal transfer\"}", 30),
            new AssignmentChangeReasonSeed(
                    "PROMOTION", "Promotion",
                    "Assignment changed due to promotion.",
                    "{\"ko\":\"승진\",\"en\":\"Promotion\"}", 40),
            new AssignmentChangeReasonSeed(
                    "HIRE", "Hire",
                    "Initial employment or contingent worker engagement.",
                    "{\"ko\":\"입사\",\"en\":\"Hire\"}", 100),
            new AssignmentChangeReasonSeed(
                    "REHIRE", "Rehire",
                    "Employment resumed after a prior termination.",
                    "{\"ko\":\"재입사\",\"en\":\"Rehire\"}", 110),
            new AssignmentChangeReasonSeed(
                    "TERMINATION", "Termination",
                    "Employment or engagement ended.",
                    "{\"ko\":\"퇴직\",\"en\":\"Termination\"}", 120),
            new AssignmentChangeReasonSeed(
                    "LEAVE_START", "Leave start",
                    "Worker entered an approved leave period.",
                    "{\"ko\":\"휴직\",\"en\":\"Leave start\"}", 130),
            new AssignmentChangeReasonSeed(
                    "RETURN_FROM_LEAVE", "Return from leave",
                    "Worker returned from an approved leave period.",
                    "{\"ko\":\"복직\",\"en\":\"Return from leave\"}", 140),
            new AssignmentChangeReasonSeed(
                    "DEMOTION", "Demotion",
                    "Worker moved to a lower job or grade.",
                    "{\"ko\":\"강등\",\"en\":\"Demotion\"}", 150),
            new AssignmentChangeReasonSeed(
                    "MANAGER_CHANGE", "Manager change",
                    "The assignment reporting manager changed.",
                    "{\"ko\":\"관리자 변경\",\"en\":\"Manager change\"}", 160),
            new AssignmentChangeReasonSeed(
                    "LOCATION_CHANGE", "Location change",
                    "The assignment work location changed.",
                    "{\"ko\":\"근무지 변경\",\"en\":\"Location change\"}", 170),
            new AssignmentChangeReasonSeed(
                    "COMPENSATION_CHANGE", "Compensation change",
                    "Compensation attributes changed without another staffing event.",
                    "{\"ko\":\"보상 변경\",\"en\":\"Compensation change\"}", 180),
            new AssignmentChangeReasonSeed(
                    "CONTRACT_CHANGE", "Contract change",
                    "Employment or engagement contract terms changed.",
                    "{\"ko\":\"계약 변경\",\"en\":\"Contract change\"}", 190),
            new AssignmentChangeReasonSeed(
                    "CORRECTION", "Correction",
                    "Source data was corrected without a new business event.",
                    "{\"ko\":\"정보 정정\",\"en\":\"Correction\"}", 200),
            new AssignmentChangeReasonSeed(
                    "CONTRACT_START", "Contract start",
                    "A contingent or fixed-term engagement started.",
                    "{\"ko\":\"계약 시작\",\"en\":\"Contract start\"}", 210),
            new AssignmentChangeReasonSeed(
                    "CONTRACT_END", "Contract end",
                    "A contingent or fixed-term engagement ended.",
                    "{\"ko\":\"계약 종료\",\"en\":\"Contract end\"}", 220),
            new AssignmentChangeReasonSeed(
                    "SOURCE_OTHER", "Other source event",
                    "A provider event was preserved after mapping to the governed fallback reason.",
                    "{\"ko\":\"기타 원천 이벤트\",\"en\":\"Other source event\"}", 900));

    static final String ASSIGNMENT_CHANGE_REASON_UPSERT_SQL = """
            INSERT INTO ppl_assignment_change_reason_catalog (
                tenant_id, reason_code, display_name, description,
                label_i18n, sort_order, predefined, lifecycle_state,
                created_by, updated_by)
            VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, TRUE, 'ACTIVE', 1, 1)
            ON CONFLICT (tenant_id, reason_code) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                description = EXCLUDED.description,
                label_i18n = EXCLUDED.label_i18n,
                sort_order = EXCLUDED.sort_order,
                predefined = EXCLUDED.predefined,
                lifecycle_state = EXCLUDED.lifecycle_state,
                version = ppl_assignment_change_reason_catalog.version + 1,
                updated_at = CURRENT_TIMESTAMP,
                updated_by = EXCLUDED.updated_by
            WHERE ppl_assignment_change_reason_catalog.display_name
                        IS DISTINCT FROM EXCLUDED.display_name
               OR ppl_assignment_change_reason_catalog.description
                        IS DISTINCT FROM EXCLUDED.description
               OR ppl_assignment_change_reason_catalog.label_i18n
                        IS DISTINCT FROM EXCLUDED.label_i18n
               OR ppl_assignment_change_reason_catalog.sort_order
                        IS DISTINCT FROM EXCLUDED.sort_order
               OR ppl_assignment_change_reason_catalog.predefined
                        IS DISTINCT FROM EXCLUDED.predefined
               OR ppl_assignment_change_reason_catalog.lifecycle_state
                        IS DISTINCT FROM EXCLUDED.lifecycle_state
            """;

    private final JdbcTemplate jdbc;
    private final HrDomainFoundationService hrDomainFoundation;
    private final ObjectMapper objectMapper;
    private final ProviderTenantCommandReceiptStore commandReceipts;

    public PeopleTenantProvisioningService(
            JdbcTemplate jdbc,
            HrDomainFoundationService hrDomainFoundation) {
        this(jdbc, hrDomainFoundation, new ObjectMapper());
    }

    @Autowired
    public PeopleTenantProvisioningService(
            JdbcTemplate jdbc,
            HrDomainFoundationService hrDomainFoundation,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.hrDomainFoundation = hrDomainFoundation;
        this.objectMapper = objectMapper;
        this.commandReceipts = new ProviderTenantCommandReceiptStore(jdbc, objectMapper, "people");
    }

    @Transactional
    public ProviderTenantCommand.Receipt command(
            UUID providerTenantId,
            ProviderTenantCommand.Request command) {
        if (!"LIFECYCLE".equals(command.commandType())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "People only accepts lifecycle commands.");
        }
        return commandReceipts.execute(providerTenantId, command, () -> {
            String state = command.payload().path("lifecycleState").asText("");
            if (!java.util.Set.of("ACTIVE", "SUSPENDED", "RETIRED").contains(state)) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid lifecycle command payload.");
            }
            return objectMapper.valueToTree(lifecycle(
                    providerTenantId,
                    new PeopleTenantProvisioningDtos.UpdateLifecycleRequest(state)));
        });
    }

    @Transactional
    public PeopleTenantProvisioningDtos.ProvisionTenantResponse provision(
            PeopleTenantProvisioningDtos.ProvisionTenantRequest request) {
        validateIdentity(request.providerTenantId(), request.tenantId(), request.tenantKey());
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name,
                    lifecycle_state, data_region, isolation_model)
                VALUES (?, ?, ?, ?, 'PROVISIONING', ?, ?)
                ON CONFLICT (provider_tenant_id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    data_region = EXCLUDED.data_region,
                    isolation_model = EXCLUDED.isolation_model,
                    updated_at = CURRENT_TIMESTAMP,
                    version = sys_service_tenants.version + 1
                """, request.providerTenantId(), request.tenantId(), request.tenantKey(),
                request.displayName(), request.dataRegion(), request.isolationModel());
        jdbc.update("""
                INSERT INTO ppl_organization_type_catalog (
                    tenant_id, type_key, display_name, description,
                    hierarchy_rank, root_candidate, icon_key, created_by, updated_by)
                SELECT ?, seed.type_key, seed.display_name, seed.description,
                       seed.hierarchy_rank, seed.root_candidate, seed.icon_key, 1, 1
                  FROM (VALUES
                        ('COMPANY', 'Company', 'Default enterprise root.', 10, TRUE, 'building-2'),
                        ('BUSINESS_UNIT', 'Business unit', 'Accountable business portfolio.', 20, FALSE, 'briefcase-business'),
                        ('DIVISION', 'Division', 'Major operating or functional division.', 30, FALSE, 'network'),
                        ('DEPARTMENT', 'Department', 'Department or functional unit.', 40, FALSE, 'landmark'),
                        ('SUPERVISORY', 'Team', 'Supervisory organization with a people leader.', 50, FALSE, 'users-round'),
                        ('COST_CENTER', 'Cost center', 'Financial responsibility unit.', 60, FALSE, 'badge-dollar-sign'),
                        ('CUSTOM', 'Custom unit', 'Tenant-defined organization unit.', 500, FALSE, 'shapes'))
                       seed(type_key, display_name, description, hierarchy_rank, root_candidate, icon_key)
                ON CONFLICT (tenant_id, type_key) DO UPDATE SET
                    lifecycle_state = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP,
                    version = ppl_organization_type_catalog.version + 1
                """, request.tenantId());
        seedAssignmentChangeReasons(request.tenantId());
        seedOrganizationRoles(request.tenantId());
        jdbc.update("""
                INSERT INTO ppl_organizations (
                    tenant_id, organization_key, organization_type, name, lifecycle_state)
                VALUES (?, 'ROOT', 'COMPANY', ?, 'INACTIVE')
                ON CONFLICT (tenant_id, organization_key) DO UPDATE
                SET name = EXCLUDED.name, updated_at = CURRENT_TIMESTAMP
                """, request.tenantId(), request.displayName());
        seedWorkforceAccessPolicies(request.tenantId());
        hrDomainFoundation.ensure(request.tenantId());
        return new PeopleTenantProvisioningDtos.ProvisionTenantResponse(
                request.providerTenantId(), request.tenantId(), "PROVISIONING", 1,
                "people-tenant:" + request.tenantId());
    }

    private void seedAssignmentChangeReasons(Long tenantId) {
        jdbc.batchUpdate(ASSIGNMENT_CHANGE_REASON_UPSERT_SQL,
                STANDARD_ASSIGNMENT_CHANGE_REASONS.stream()
                .map(seed -> new Object[] {
                        tenantId,
                        seed.reasonCode(),
                        seed.displayName(),
                        seed.description(),
                        seed.labelI18n(),
                        seed.sortOrder()})
                .toList());
    }

    private void seedOrganizationRoles(Long tenantId) {
        jdbc.update("""
                INSERT INTO ppl_organization_role_catalog (
                    tenant_id, role_code, display_name, description, label_i18n,
                    icon_key, sort_order, predefined, allows_person_holder,
                    allows_position_holder, created_by, updated_by)
                SELECT ?, seed.role_code, seed.display_name, seed.description,
                       seed.label_i18n::jsonb, seed.icon_key, seed.sort_order,
                       TRUE, seed.allows_person_holder, seed.allows_position_holder, 1, 1
                  FROM (VALUES
                        ('LEADER', 'Leader', 'Primary accountable leader for an organization.',
                         '{"ko":"조직장","en":"Leader"}', 'user-round-check', 10, TRUE, TRUE),
                        ('HR_BUSINESS_PARTNER', 'HR business partner', 'People partner accountable for the organization.',
                         '{"ko":"HR 비즈니스 파트너","en":"HR business partner"}', 'users-round', 20, TRUE, FALSE),
                        ('FINANCE_PARTNER', 'Finance partner', 'Finance partner accountable for the organization.',
                         '{"ko":"재무 파트너","en":"Finance partner"}', 'badge-dollar-sign', 30, TRUE, FALSE),
                        ('MATRIX_MANAGER', 'Matrix manager', 'Additional matrix reporting leader.',
                         '{"ko":"매트릭스 관리자","en":"Matrix manager"}', 'network', 40, TRUE, TRUE),
                        ('SECURITY_ADMIN', 'Security administrator', 'Delegated security administrator for the organization.',
                         '{"ko":"보안 관리자","en":"Security administrator"}', 'shield-check', 50, TRUE, FALSE))
                       seed(role_code, display_name, description, label_i18n,
                            icon_key, sort_order, allows_person_holder, allows_position_holder)
                ON CONFLICT (tenant_id, role_code) DO UPDATE SET
                    display_name = EXCLUDED.display_name,
                    description = EXCLUDED.description,
                    label_i18n = EXCLUDED.label_i18n,
                    icon_key = EXCLUDED.icon_key,
                    lifecycle_state = 'ACTIVE',
                    version = ppl_organization_role_catalog.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """, tenantId);
    }

    private void seedWorkforceAccessPolicies(Long tenantId) {
        jdbc.update("""
                INSERT INTO ppl_workforce_access_policies (
                    tenant_id, subject_type, subject_ref, population_type,
                    organization_public_id, field_groups, action_codes,
                    justification, created_by, updated_by)
                SELECT ?, 'ROLE', seed.role_code, 'TENANT', NULL,
                       seed.field_groups, seed.action_codes,
                       seed.justification, 1, 1
                  FROM (VALUES
                        ('HR_ADMIN',
                         ARRAY['DIRECTORY', 'EMPLOYMENT', 'JOB_GRADE']::VARCHAR[],
                         ARRAY['READ']::VARCHAR[],
                         'Built-in HCM domain operations boundary without worker identifiers or export.'),
                        ('PEOPLE_ADMIN',
                         ARRAY['DIRECTORY', 'WORKER_IDENTIFIERS', 'EMPLOYMENT', 'JOB_GRADE']::VARCHAR[],
                         ARRAY['READ', 'EXPORT']::VARCHAR[],
                         'Built-in Core HR administration boundary with governed export capability.'))
                       seed(role_code, field_groups, action_codes, justification)
                ON CONFLICT DO NOTHING
                """, tenantId);
    }

    @Transactional
    public PeopleTenantProvisioningDtos.ProvisionTenantResponse lifecycle(
            UUID providerTenantId,
            PeopleTenantProvisioningDtos.UpdateLifecycleRequest request) {
        ServiceTenant tenant = requireTenant(providerTenantId);
        jdbc.update("""
                UPDATE sys_service_tenants
                   SET lifecycle_state = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE provider_tenant_id = ?
                """, request.lifecycleState(), providerTenantId);
        jdbc.update("""
                UPDATE ppl_organizations
                   SET lifecycle_state = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE tenant_id = ? AND organization_key = 'ROOT'
                """, "ACTIVE".equals(request.lifecycleState()) ? "ACTIVE" : "INACTIVE", tenant.tenantId());
        return new PeopleTenantProvisioningDtos.ProvisionTenantResponse(
                providerTenantId, tenant.tenantId(), request.lifecycleState(), 1,
                "people-tenant:" + tenant.tenantId());
    }

    private void validateIdentity(UUID providerTenantId, Long tenantId, String tenantKey) {
        List<ServiceTenant> existing = jdbc.query("""
                SELECT provider_tenant_id, tenant_id, tenant_key, lifecycle_state
                  FROM sys_service_tenants WHERE provider_tenant_id = ?
                """, (result, ignored) -> new ServiceTenant(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("tenant_key"),
                        result.getString("lifecycle_state")), providerTenantId);
        if (!existing.isEmpty()
                && (!existing.get(0).tenantId().equals(tenantId)
                || !existing.get(0).tenantKey().equals(tenantKey))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The provider tenant is already mapped to another people tenant.");
        }
    }

    private ServiceTenant requireTenant(UUID providerTenantId) {
        return jdbc.query("""
                SELECT provider_tenant_id, tenant_id, tenant_key, lifecycle_state
                  FROM sys_service_tenants WHERE provider_tenant_id = ?
                """, (result, ignored) -> new ServiceTenant(
                        result.getObject("provider_tenant_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("tenant_key"),
                        result.getString("lifecycle_state")), providerTenantId)
                .stream().findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private record ServiceTenant(
            UUID providerTenantId,
            Long tenantId,
            String tenantKey,
            String lifecycleState) {
    }

    record AssignmentChangeReasonSeed(
            String reasonCode,
            String displayName,
            String description,
            String labelI18n,
            int sortOrder) {
    }
}
