package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.Per004TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class Per004ProjectionQueryTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final List<ProjectionTarget> TARGETS = List.of(new ProjectionTarget(A, SCOPE), new ProjectionTarget(B, SCOPE));

    @Test void closedPageUsesStableOrderAndScopeSnapshotBoundCursor() {
        var query = query(null, TARGETS);
        var rows = List.of(row(B, 2L), row(A, 1L));
        var first = Per004ProjectionQuery.page(42, query, rows, MAPPER);
        assertThat(first.items()).extracting(OwnerProjection::configurationId).containsExactly(A);
        assertThat(first.nextCursor()).isNotBlank();
        var next = query(first.nextCursor(), TARGETS);
        var second = Per004ProjectionQuery.page(42, next, rows, MAPPER);
        assertThat(second.items()).extracting(OwnerProjection::configurationId).containsExactly(B);
        assertThat(second.nextCursor()).isNull();
        assertThatThrownBy(() -> Per004ProjectionQuery.page(43, next, rows, MAPPER)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> Per004ProjectionQuery.page(42, next, List.of(row(A, 1L), row(B, 3L)), MAPPER)).isInstanceOf(RuntimeException.class);
        var changedScope = query(first.nextCursor(), List.of(new ProjectionTarget(A, "f".repeat(64)), new ProjectionTarget(B, SCOPE)));
        assertThatThrownBy(() -> Per004ProjectionQuery.page(42, changedScope, rows, MAPPER)).isInstanceOf(RuntimeException.class);
    }

    @Test void declaredSchemaFilterSortAndLimitAreClosedAndExactTargetsRemainBounded() {
        var query = Per004ProjectionQuery.normalize(new OwnerProjectionRequest(NOW, TARGETS, null, 100, Set.of(SCHEMA),
                new OwnerProjectionFilter(Set.of("DRAFT"), 2L), List.of("configurationId:DESC")), NOW);
        assertThat(Per004ProjectionQuery.page(42, query, List.of(row(A, 1L), row(B, 2L)), MAPPER).items())
                .extracting(OwnerProjection::configurationId).containsExactly(B);
        for (OwnerProjectionRequest bad : List.of(
                new OwnerProjectionRequest(NOW, TARGETS, null, 101, SCHEMAS, new OwnerProjectionFilter(Set.of(), null), List.of("configurationId:ASC")),
                new OwnerProjectionRequest(NOW, TARGETS, null, 100, Set.of("unknown"), new OwnerProjectionFilter(Set.of(), null), List.of("configurationId:ASC")),
                new OwnerProjectionRequest(NOW, TARGETS, null, 100, SCHEMAS, new OwnerProjectionFilter(Set.of("UNKNOWN"), null), List.of("configurationId:ASC")),
                new OwnerProjectionRequest(NOW, TARGETS, null, 100, SCHEMAS, new OwnerProjectionFilter(Set.of(), null), List.of("payloadReference:ASC")),
                new OwnerProjectionRequest(NOW, List.of(TARGETS.getFirst(), TARGETS.getFirst())))) {
            assertThatThrownBy(() -> Per004ProjectionQuery.normalize(bad, NOW)).isInstanceOf(RuntimeException.class);
        }
    }
    @Test void cursorBindingIgnoresSetIterationOrderAcrossMapperInstances() {
        var schemas = SCHEMAS.stream().sorted().toList();
        var states = List.of("DRAFT", "VALIDATED");
        var forward = new OwnerProjectionRequest(NOW, TARGETS, null, 1,
                new java.util.LinkedHashSet<>(schemas),
                new OwnerProjectionFilter(new java.util.LinkedHashSet<>(states), null), List.of("configurationId:ASC"));
        var reverse = new OwnerProjectionRequest(NOW, TARGETS, null, 1,
                new java.util.LinkedHashSet<>(schemas.reversed()),
                new OwnerProjectionFilter(new java.util.LinkedHashSet<>(states.reversed()), null), List.of("configurationId:ASC"));
        var otherMapper = com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
        var rows = List.of(row(A, 1L), row(B, 2L));
        var first = Per004ProjectionQuery.page(42, forward, rows, MAPPER);
        assertThat(Per004ProjectionQuery.page(42, reverse, rows, otherMapper).nextCursor()).isEqualTo(first.nextCursor());
        var next = new OwnerProjectionRequest(NOW, TARGETS, first.nextCursor(), 1, reverse.schemaKeys(), reverse.filter(), reverse.sort());
        assertThat(Per004ProjectionQuery.page(42, next, rows, otherMapper).items())
                .extracting(OwnerProjection::configurationId).containsExactly(B);
    }
    private OwnerProjectionRequest query(String cursor, List<ProjectionTarget> targets) {
        return Per004ProjectionQuery.normalize(new OwnerProjectionRequest(NOW, targets, cursor, 1, SCHEMAS,
                new OwnerProjectionFilter(Set.of(), null), List.of("configurationId:ASC")), NOW);
    }
    private OwnerProjection row(UUID id, long aggregate) {
        return new OwnerProjection(id, aggregate, aggregate, "DRAFT", SCHEMA, SCOPE, NOW, null, true, false,
                SCHEMA, null, 1, PER_REFERENCE, PAYLOAD);
    }
}
