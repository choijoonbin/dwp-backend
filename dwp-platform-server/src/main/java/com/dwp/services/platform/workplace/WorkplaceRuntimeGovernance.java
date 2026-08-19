package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.AccessPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.PolicyScopeType;

@Component
class WorkplaceRuntimeGovernance {

    private final WorkplaceSpatialGovernanceService governance;

    WorkplaceRuntimeGovernance(WorkplaceSpatialGovernanceService governance) {
        this.governance = governance;
    }

    void requireViewAccess(
            Long tenantId, Long userId, String verifiedGroupRefs, UUID siteId) {
        requireAccess(tenantId, userId, verifiedGroupRefs, siteId, AccessPermission.VIEW);
    }

    boolean canViewAccess(
            Long tenantId, Long userId, String verifiedGroupRefs, UUID siteId) {
        return governance.evaluateSiteAccess(
                tenantId, userId, verifiedGroupRefs, siteId, AccessPermission.VIEW).allowed();
    }

    void requireBookAccess(
            Long tenantId, Long userId, String verifiedGroupRefs, UUID siteId) {
        requireAccess(tenantId, userId, verifiedGroupRefs, siteId, AccessPermission.BOOK);
    }

    WorkplaceCatalogRepository.PolicyRow effectivePolicy(
            Long tenantId,
            PolicyScopeType scopeType,
            UUID scopeId,
            WorkplaceCatalogRepository.PolicyRow base) {
        JsonNode value = governance.previewPolicy(tenantId, scopeType, scopeId).effectivePolicy();
        return new WorkplaceCatalogRepository.PolicyRow(
                integer(value, "bookingWindowDays"),
                integer(value, "maximumActiveBookings"),
                integer(value, "minimumBookingMinutes"),
                integer(value, "maximumBookingMinutes"),
                integer(value, "maximumConsecutiveDays"),
                LocalTime.parse(text(value, "workingDayStart")),
                LocalTime.parse(text(value, "workingDayEnd")),
                bool(value, "allowRecurring"),
                bool(value, "requireCheckIn"),
                integer(value, "checkInLeadMinutes"),
                integer(value, "autoReleaseMinutes"),
                bool(value, "allowAssignedDeskLending"),
                bool(value, "showColleagueNames"),
                integer(value, "bookingRetentionDays"),
                base.version());
    }

    private void requireAccess(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID siteId,
            AccessPermission permission) {
        WorkplaceSpatialGovernanceDtos.SiteAccessDecision decision =
                governance.evaluateSiteAccess(
                        tenantId, userId, verifiedGroupRefs, siteId, permission);
        if (!decision.allowed()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "This Workplace location is not available to the current member.");
        }
    }

    private int integer(JsonNode value, String field) {
        JsonNode item = required(value, field);
        if (!item.canConvertToInt()) throw invalid(field);
        return item.intValue();
    }

    private boolean bool(JsonNode value, String field) {
        JsonNode item = required(value, field);
        if (!item.isBoolean()) throw invalid(field);
        return item.booleanValue();
    }

    private String text(JsonNode value, String field) {
        JsonNode item = required(value, field);
        if (!item.isTextual()) throw invalid(field);
        return item.textValue();
    }

    private JsonNode required(JsonNode value, String field) {
        if (value == null || !value.isObject() || !value.hasNonNull(field)) throw invalid(field);
        return value.get(field);
    }

    private IllegalStateException invalid(String field) {
        return new IllegalStateException(
                "Invalid effective Workplace policy field: " + field);
    }
}
