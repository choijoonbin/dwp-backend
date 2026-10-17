package com.dwp.services.people.hris.workbench.hrm007;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Read-only owner service for the exact three HRM-007 IA entry queries. */
@Service
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
class Hrm007IaEntryQueryService {

    private final Hrm007RolloutGate rollout;
    private final Hrm007Projector projector;
    private final Hrm007Authority authority;
    private final Hrm007ProjectionSource source;
    private final Hrm007CursorCodec cursors;
    private final Clock clock;

    @Autowired
    Hrm007IaEntryQueryService(
            Hrm007RolloutGate rollout,
            Hrm007Projector projector,
            Hrm007Authority authority,
            Hrm007ProjectionSource source,
            Hrm007CursorCodec cursors) {
        this(rollout, projector, authority, source, cursors, Clock.systemUTC());
    }

    Hrm007IaEntryQueryService(
            Hrm007RolloutGate rollout,
            Hrm007Projector projector,
            Hrm007Authority authority,
            Hrm007ProjectionSource source,
            Hrm007CursorCodec cursors,
            Clock clock) {
        this.rollout = Objects.requireNonNull(rollout);
        this.projector = Objects.requireNonNull(projector);
        this.authority = Objects.requireNonNull(authority);
        this.source = Objects.requireNonNull(source);
        this.cursors = Objects.requireNonNull(cursors);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public QueryResult query(
            Hrm007QueryDefinition definition,
            Hrm007Dtos.QueryRequest request) {
        // This must remain the first operation: unenrolled tenants perform zero
        // projection, authority, cursor, population, or repository work.
        long tenantId = rollout.requireEnrolledTenant();
        Hrm007Projector.PreparedQuery prepared =
                projector.prepare(definition, request, clock.instant());
        Hrm007Authority.Decision decision = authority.authorize(definition);
        if (tenantId != decision.tenantId()) {
            throw Hrm007Exception.denied(
                    "The HRM-007 tenant authority does not match.");
        }

        Hrm007CursorCodec.Binding expected = binding(prepared, decision, null);
        String lastItemPublicId = null;
        if (prepared.cursor() != null) {
            if (!prepared.explicitAsOf()) {
                expected = new Hrm007CursorCodec.Binding(
                        expected.tenantId(), expected.queryId(),
                        expected.targetPopulation(), expected.scopeDecisionRevision(),
                        expected.authorizationDecisionRevision(), null, expected.sort(),
                        expected.pageLimit(), null, expected.filterDigest(),
                        expected.locale(), expected.timeZone());
            }
            Hrm007CursorCodec.Binding verified =
                    cursors.verifyBinding(prepared.cursor(), expected);
            lastItemPublicId = verified.lastItemPublicId();
            if (!prepared.explicitAsOf()) prepared = prepared.withAsOf(verified.asOf());
        }

        Hrm007Dtos.Projection projection;
        try {
            LocalDate effectiveOn = projector.effectiveOn(prepared);
            LocalDate scopeDate = effectiveOn == null
                    ? prepared.asOf().atZone(prepared.zoneId()).toLocalDate()
                    : effectiveOn;
            Hrm007ProjectionSource.SourceRequest sourceRequest =
                    new Hrm007ProjectionSource.SourceRequest(
                            tenantId, prepared.asOf(), scopeDate,
                            effectiveOn, decision.population());
            Hrm007Projector.Page page = projector.project(
                    prepared, source.read(definition, sourceRequest),
                    decision, lastItemPublicId);
            String nextCursor = page.hasMore()
                    ? cursors.issue(binding(
                    prepared, decision, page.lastItemPublicId()))
                    : null;
            projection = projector.projection(prepared, decision, page, nextCursor);
        } catch (DataAccessException exception) {
            projection = projector.unavailable(prepared, decision);
        }
        String etag = '"' + Hrm007CursorCodec.digest(projection.toString()).substring(0, 32)
                + '"';
        return new QueryResult(
                projection, decision.authorizationDecisionRevision(), etag);
    }

    private Hrm007CursorCodec.Binding binding(
            Hrm007Projector.PreparedQuery query,
            Hrm007Authority.Decision authority,
            String lastItemPublicId) {
        return new Hrm007CursorCodec.Binding(
                authority.tenantId(), query.definition().queryId(),
                authority.targetPopulationBinding(),
                authority.scopeDecisionRevision(),
                authority.authorizationDecisionRevision(), query.asOf(), query.sort(),
                query.limit(), lastItemPublicId, query.filterDigest(),
                query.locale(), query.timeZone());
    }

    record QueryResult(
            Hrm007Dtos.Projection projection,
            String authorizationDecisionRevision,
            String etag) {
    }
}
