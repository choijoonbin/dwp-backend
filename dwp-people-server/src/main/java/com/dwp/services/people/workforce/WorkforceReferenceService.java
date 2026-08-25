package com.dwp.services.people.workforce;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmPepContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class WorkforceReferenceService {

    private final WorkforceReferenceRepository repository;
    private final AuditOutboxRecorder audit;

    public WorkforceReferenceService(
            WorkforceReferenceRepository repository,
            AuditOutboxRecorder audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<WorkforceReferenceDtos.ReferenceCatalog> catalogs(String locale) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        return repository.catalogs(actor.tenantId(), locale);
    }

    @Transactional
    public WorkforceReferenceDtos.ReferenceValue update(
            String catalogKey,
            String code,
            String locale,
            WorkforceReferenceDtos.UpdateReferenceValueRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (HcmPepContext.current() == null
                && !actor.hasAnyRole("ADMIN", "HR_ADMIN")) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "HR administrator permission is required to change workforce reference data.");
        }
        String normalizedCatalog = catalogKey.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedCode = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!repository.update(actor.tenantId(), normalizedCatalog, normalizedCode, request, actor.userId())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The reference value was changed by another user or is not tenant-editable.");
        }
        WorkforceReferenceDtos.ReferenceValue updated = repository.catalogs(actor.tenantId(), locale).stream()
                .filter(catalog -> catalog.catalogKey().equals(normalizedCatalog))
                .flatMap(catalog -> catalog.values().stream())
                .filter(value -> value.code().equals(normalizedCode))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action("workforce.reference-value.updated")
                .outcome("SUCCESS")
                .severity("MEDIUM")
                .riskScore(35)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("workforce-reference-data")
                .targetType("WORKFORCE_REFERENCE_VALUE")
                .targetId(normalizedCatalog + ":" + normalizedCode)
                .correlationId(correlationId)
                .metadata(Map.of(
                        "catalogKey", normalizedCatalog,
                        "code", normalizedCode,
                        "lifecycleState", updated.lifecycleState(),
                        "version", updated.version()))
                .retentionClass("EXTENDED")
                .build());
        return updated;
    }
}
