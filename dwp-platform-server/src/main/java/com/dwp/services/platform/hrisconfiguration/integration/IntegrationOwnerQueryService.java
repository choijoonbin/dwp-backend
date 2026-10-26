package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.dwp.services.platform.hrisconfiguration.integration.Sys008ProjectionContract.*;

/** Single IAQ owner truth projected into the two sealed policy views. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class IntegrationOwnerQueryService {

    private final IntegrationRepository repository;
    private final IntegrationCursorCodec cursors;

    IntegrationOwnerQueryService(
            IntegrationRepository repository,
            IntegrationCursorCodec cursors) {
        this.repository = repository;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    public ProjectionResponse query(
            Authority authority,
            Projection projection,
            Map<String, String> filters,
            String sort,
            Instant asOf,
            String locale,
            String timeZone,
            int limit,
            String cursor) {
        return queryPage(authority, projection, filters, sort, asOf, locale,
                timeZone, limit, cursor).response();
    }

    @Transactional(readOnly = true)
    public ProjectionPage queryPage(
            Authority authority,
            Projection projection,
            Map<String, String> filters,
            String sort,
            Instant asOf,
            String locale,
            String timeZone,
            int limit,
            String cursor) {
        if (authority == null || !authority.reader()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_OWNER_QUERY_AUTHORITY_REQUIRED");
        }
        repository.bindTenantPrincipal(
                authority.tenantId(), authority.subjectPrincipalPublicId());
        AuthenticatedCursor authenticated;
        TrustedOwnerQuery trusted;
        String after;
        try {
            authenticated = cursor == null ? null : cursors.authenticate(cursor);
            trusted = new TrustedOwnerQuery(
                    authority.tenantId(), authority.actorId(), projection,
                    filters == null ? Map.of() : filters,
                    sort == null ? DEFAULT_SORT : sort,
                    asOf, locale, timeZone, authority.scopeRevision(),
                    authority.fieldPolicyRevision(),
                    authority.authorizationRevision(), limit, authenticated);
            after = cursor == null ? null
                    : cursors.decode(cursor).lastItemPublicId();
        } catch (IllegalArgumentException invalidCursorOrQuery) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "SYS008_OWNER_QUERY_INVALID");
        }
        List<String> allowedActions = ACTION_KEYS.stream()
                .filter(action -> actionAllowed(authority, action))
                .sorted()
                .toList();
        IntegrationRepository.OwnerPage page = repository.ownerPage(
                trusted, after, allowedActions);
        OwnerResult owner = new OwnerResult(
                page.state(), page.evidenceCurrent(), page.items(),
                page.ownerReceipt());
        ProjectionResponse response = Sys008ProjectionContract.project(
                projection, owner, fieldDecisions(authority, projection));
        String nextCursor = page.nextItemPublicId() == null ? null
                : cursors.encode(new IntegrationCursorCodec.CursorBinding(
                        authority.tenantId(), authority.actorId(), projection,
                        trusted.filters(), trusted.sort(), trusted.asOf(),
                        trusted.locale(), trusted.timeZone(),
                        trusted.scopeRevision(), trusted.fieldPolicyRevision(),
                        trusted.authorizationRevision(),
                        page.nextItemPublicId()));
        return new ProjectionPage(response, nextCursor);
    }

    record ProjectionPage(ProjectionResponse response, String nextCursor) {}

    private static Map<FieldGroup, FieldDecision> fieldDecisions(
            Authority authority, Projection projection) {
        EnumMap<FieldGroup, FieldDecision> decisions =
                new EnumMap<>(FieldGroup.class);
        for (FieldGroup group : PROJECTIONS.get(projection).fieldGroups()) {
            FieldDecision decision = authority.omittedFields().contains(group.name())
                    ? FieldDecision.OMIT
                    : authority.maskedFields().contains(group.name())
                    ? FieldDecision.MASK : FieldDecision.VIEW;
            decisions.put(group, decision);
        }
        return Map.copyOf(decisions);
    }

    private static boolean actionAllowed(Authority authority, String action) {
        if (action.endsWith(".approve") || action.endsWith(".activate")
                || action.contains("connector-executions")
                || action.endsWith(".executions")) {
            return authority.executor();
        }
        return authority.author();
    }
}
