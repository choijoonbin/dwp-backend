package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

@Repository
class JdbcHrisExplorerRepository implements HrisExplorerRepository {

    static final String PREFERENCES_ACTION = "EXPLORER_PREFERENCES_REPLACE";
    static final String RECENT_ACTION = "EXPLORER_RECENT_RECORD";
    static final String PURPOSE = "HRIS_APP_DISCOVERY";

    private final JdbcTemplate jdbc;

    JdbcHrisExplorerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ExplorerState readAndPurge(
            long tenantId, UUID subjectPrincipalPublicId,
            Set<String> allowedRouteKeys, OffsetDateTime now) {
        bind(tenantId, subjectPrincipalPublicId);
        LockedPreference preference = lockPreference(
                tenantId, subjectPrincipalPublicId, now);
        purge(tenantId, subjectPrincipalPublicId, preference.preferenceId(),
                allowedRouteKeys, now);
        List<FavoriteRow> favorites = jdbc.query("""
                SELECT route_key, position FROM sys_hris_explorer_favorites
                 WHERE tenant_id = ? AND principal_public_id = ?
                   AND explorer_preference_id = ?
                 ORDER BY position
                """, (rs, row) -> new FavoriteRow(
                rs.getString("route_key"), rs.getInt("position")),
                tenantId, subjectPrincipalPublicId, preference.preferenceId());
        List<RecentRow> recents = jdbc.query("""
                SELECT route_key, last_visited_at, expires_at
                  FROM sys_hris_explorer_recents
                 WHERE tenant_id = ? AND principal_public_id = ?
                 ORDER BY last_visited_at DESC, route_key
                 LIMIT 20
                """, (rs, row) -> new RecentRow(
                rs.getString("route_key"),
                rs.getObject("last_visited_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class)),
                tenantId, subjectPrincipalPublicId);
        return new ExplorerState(preference.revision(), favorites, recents);
    }

    @Override
    @Transactional
    public HrisExplorerReceipt replaceFavorites(
            CommandContext context, long expectedRevision, List<String> routeKeys,
            Set<String> allowedRouteKeys, String requestDigest, OffsetDateTime now) {
        bind(context.tenantId(), context.subjectPrincipalPublicId());
        lockIdempotency(context);
        Optional<StoredReceipt> existing = storedReceipt(context);
        if (existing.isPresent()) {
            HrisExplorerReceipt replayed = replay(existing.get(), requestDigest);
            LockedPreference preference = lockPreference(
                    context.tenantId(), context.subjectPrincipalPublicId(), now);
            purge(context.tenantId(), context.subjectPrincipalPublicId(),
                    preference.preferenceId(), allowedRouteKeys, now);
            return replayed;
        }
        if (routeKeys.size() > 20 || routeKeys.stream().distinct().count() != routeKeys.size()) {
            throw invalid("Explorer favorites must contain at most 20 unique route keys");
        }
        if (!allowedRouteKeys.containsAll(routeKeys)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Every Explorer favorite must be currently authorized");
        }
        LockedPreference preference = lockPreference(
                context.tenantId(), context.subjectPrincipalPublicId(), now);
        UUID preferenceId = preference.preferenceId();
        long current = preference.revision();
        if (current != expectedRevision) {
            throw conflict("HRIS_EXPLORER_PREFERENCE_REVISION_CONFLICT");
        }
        purge(context.tenantId(), context.subjectPrincipalPublicId(), preferenceId,
                allowedRouteKeys, now);
        jdbc.update("""
                DELETE FROM sys_hris_explorer_favorites
                 WHERE tenant_id = ? AND principal_public_id = ?
                   AND explorer_preference_id = ?
                """, context.tenantId(), context.subjectPrincipalPublicId(), preferenceId);
        for (int index = 0; index < routeKeys.size(); index++) {
            jdbc.update("""
                    INSERT INTO sys_hris_explorer_favorites (
                        favorite_id, tenant_id, principal_public_id, explorer_preference_id,
                        route_key, position, added_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), context.tenantId(),
                    context.subjectPrincipalPublicId(), preferenceId,
                    routeKeys.get(index), index + 1, now);
        }
        long nextRevision = current + 1;
        jdbc.update("""
                UPDATE sys_hris_explorer_preferences
                   SET preference_revision = ?, route_catalog_revision = ?,
                       entitlement_revision = ?, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND explorer_preference_id = ?
                """, nextRevision, context.authorizationRevision(),
                context.authorizationRevision(), now, context.subjectPrincipalPublicId(),
                context.tenantId(), preferenceId);
        HrisExplorerReceipt receipt = receipt(
                context, requestDigest, preferenceId, nextRevision,
                "preferences:revision:" + nextRevision, now);
        insertReceipt(context, requestDigest, preferenceId, nextRevision, receipt);
        return receipt;
    }

    @Override
    @Transactional
    public HrisExplorerReceipt recordRecent(
            CommandContext context, String routeKey, Set<String> allowedRouteKeys,
            String requestDigest, OffsetDateTime now) {
        bind(context.tenantId(), context.subjectPrincipalPublicId());
        lockIdempotency(context);
        Optional<StoredReceipt> existing = storedReceipt(context);
        if (existing.isPresent()) {
            HrisExplorerReceipt replayed = replay(existing.get(), requestDigest);
            LockedPreference preference = lockPreference(
                    context.tenantId(), context.subjectPrincipalPublicId(), now);
            purge(context.tenantId(), context.subjectPrincipalPublicId(),
                    preference.preferenceId(), allowedRouteKeys, now);
            return replayed;
        }
        if (!allowedRouteKeys.contains(routeKey)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The Explorer recent target must be currently authorized");
        }
        LockedPreference preference = lockPreference(
                context.tenantId(), context.subjectPrincipalPublicId(), now);
        UUID preferenceId = preference.preferenceId();
        long aggregateRevision = preference.revision();
        purge(context.tenantId(), context.subjectPrincipalPublicId(), preferenceId,
                allowedRouteKeys, now);
        jdbc.update("""
                INSERT INTO sys_hris_explorer_recents (
                    recent_id, tenant_id, principal_public_id, route_key,
                    last_visited_at, expires_at, route_catalog_revision,
                    entitlement_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, principal_public_id, route_key)
                DO UPDATE SET last_visited_at = EXCLUDED.last_visited_at,
                              expires_at = EXCLUDED.expires_at,
                              route_catalog_revision = EXCLUDED.route_catalog_revision,
                              entitlement_revision = EXCLUDED.entitlement_revision
                """, UUID.randomUUID(), context.tenantId(),
                context.subjectPrincipalPublicId(), routeKey, now, now.plusDays(30),
                context.authorizationRevision(), context.authorizationRevision());
        jdbc.update("""
                DELETE FROM sys_hris_explorer_recents r
                 WHERE r.tenant_id = ? AND r.principal_public_id = ?
                   AND r.recent_id IN (
                       SELECT recent_id FROM sys_hris_explorer_recents
                        WHERE tenant_id = ? AND principal_public_id = ?
                        ORDER BY last_visited_at DESC, route_key
                        OFFSET 20)
                """, context.tenantId(), context.subjectPrincipalPublicId(),
                context.tenantId(), context.subjectPrincipalPublicId());
        HrisExplorerReceipt receipt = receipt(
                context, requestDigest, preferenceId, aggregateRevision,
                "recents:" + routeKey, now);
        insertReceipt(context, requestDigest, preferenceId, aggregateRevision, receipt);
        return receipt;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HrisExplorerReceipt> findReceipt(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId) {
        bind(tenantId, subjectPrincipalPublicId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT * FROM sys_hris_command_receipts
                     WHERE tenant_id = ? AND subject_principal_public_id = ? AND public_id = ?
                    """, (rs, row) -> receipt(rs),
                    tenantId, subjectPrincipalPublicId, receiptId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceiptAuthority> findReceiptAuthority(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId) {
        bind(tenantId, subjectPrincipalPublicId);
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT subject_principal_public_id, originating_action,
                           population_scope_digest,
                           authority_population_scope_digest,
                           field_policy_revision, authority_field_policy_revision,
                           purpose_code, authorization_revision,
                           authority_authorization_revision,
                           origin_route_contract_key, origin_context_key,
                           origin_context_scope_key, origin_decision_revision
                      FROM sys_hris_command_receipts
                     WHERE tenant_id = ? AND subject_principal_public_id = ? AND public_id = ?
                    """, (rs, row) -> new ReceiptAuthority(
                    rs.getObject("subject_principal_public_id", UUID.class),
                    rs.getString("originating_action"),
                    rs.getString("population_scope_digest"),
                    rs.getString("authority_population_scope_digest"),
                    rs.getLong("field_policy_revision"),
                    rs.getLong("authority_field_policy_revision"),
                    rs.getString("purpose_code"),
                    rs.getLong("authorization_revision"),
                    rs.getLong("authority_authorization_revision"),
                    rs.getString("origin_route_contract_key"),
                    rs.getString("origin_context_key"),
                    rs.getString("origin_context_scope_key"),
                    rs.getString("origin_decision_revision")),
                    tenantId, subjectPrincipalPublicId, receiptId));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptActivityProjection findActiveReceiptActivities(
            long tenantId, UUID subjectPrincipalPublicId,
            Set<String> allowedRouteKeys, OffsetDateTime asOf) {
        bind(tenantId, subjectPrincipalPublicId);
        StoreSnapshot snapshot = jdbc.queryForObject("""
                SELECT txid_snapshot_xmax(txid_current_snapshot())::text::bigint
                           AS source_revision,
                       CURRENT_TIMESTAMP AS generated_at
                """, (rs, row) -> new StoreSnapshot(
                rs.getLong("source_revision"),
                rs.getObject("generated_at", OffsetDateTime.class)));
        List<String> allowedResultRefs = allowedRouteKeys == null
                ? List.of() : allowedRouteKeys.stream().sorted()
                .map(routeKey -> "route:" + routeKey).toList();
        if (snapshot == null) throw new IllegalStateException("Receipt store snapshot unavailable");
        if (allowedResultRefs.isEmpty()) {
            return new ReceiptActivityProjection(
                    snapshot.sourceRevision(), snapshot.generatedAt(), List.of());
        }
        String placeholders = String.join(", ",
                Collections.nCopies(allowedResultRefs.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        arguments.add(subjectPrincipalPublicId);
        arguments.addAll(allowedResultRefs);
        arguments.add(asOf);
        arguments.add(asOf);
        List<ReceiptActivityRow> rows = jdbc.query("""
                SELECT public_id, status, result_ref,
                       authority_population_scope_digest,
                       purpose_code, authority_authorization_revision,
                       authority_field_policy_revision, created_at,
                       COALESCE(completed_at, created_at) AS updated_at
                  FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND subject_principal_public_id = ?
                   AND status IN ('ACCEPTED', 'RUNNING', 'RESULT_UNKNOWN', 'FAILED')
                   AND result_ref IN (%s)
                   AND created_at <= ?
                   AND COALESCE(completed_at, created_at) <= ?
                 ORDER BY COALESCE(completed_at, created_at) DESC, public_id
                 LIMIT 20
                """.formatted(placeholders), (rs, row) -> new ReceiptActivityRow(
                rs.getObject("public_id", UUID.class), rs.getString("status"),
                rs.getString("result_ref"),
                rs.getString("authority_population_scope_digest"),
                rs.getString("purpose_code"),
                rs.getLong("authority_authorization_revision"),
                rs.getLong("authority_field_policy_revision"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)),
                arguments.toArray());
        return new ReceiptActivityProjection(
                snapshot.sourceRevision(), snapshot.generatedAt(), rows);
    }

    private UUID ensurePreference(
            long tenantId, UUID subjectPrincipalPublicId, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO sys_hris_explorer_preferences (
                    explorer_preference_id, tenant_id, principal_public_id,
                    preference_revision, route_catalog_revision,
                    entitlement_revision, updated_at, updated_by)
                VALUES (?, ?, ?, 1, 1, 1, ?, ?)
                ON CONFLICT (tenant_id, principal_public_id) DO NOTHING
                """, UUID.randomUUID(), tenantId, subjectPrincipalPublicId,
                now, subjectPrincipalPublicId);
        return jdbc.queryForObject("""
                SELECT explorer_preference_id FROM sys_hris_explorer_preferences
                 WHERE tenant_id = ? AND principal_public_id = ?
                """, UUID.class, tenantId, subjectPrincipalPublicId);
    }

    private LockedPreference lockPreference(
            long tenantId, UUID subjectPrincipalPublicId, OffsetDateTime now) {
        UUID preferenceId = ensurePreference(tenantId, subjectPrincipalPublicId, now);
        LockedPreference preference = jdbc.queryForObject("""
                SELECT explorer_preference_id, preference_revision
                  FROM sys_hris_explorer_preferences
                 WHERE tenant_id = ? AND principal_public_id = ?
                   AND explorer_preference_id = ?
                 FOR UPDATE
                """, (rs, row) -> new LockedPreference(
                rs.getObject("explorer_preference_id", UUID.class),
                rs.getLong("preference_revision")),
                tenantId, subjectPrincipalPublicId, preferenceId);
        if (preference == null) {
            throw new IllegalStateException("Explorer preference aggregate unavailable");
        }
        return preference;
    }

    private void purge(
            long tenantId, UUID subjectPrincipalPublicId, UUID preferenceId,
            Set<String> allowedRouteKeys, OffsetDateTime now) {
        List<String> allowed = List.copyOf(allowedRouteKeys);
        if (allowed.isEmpty()) {
            jdbc.update("DELETE FROM sys_hris_explorer_favorites "
                    + "WHERE tenant_id = ? AND principal_public_id = ? "
                    + "AND explorer_preference_id = ?",
                    tenantId, subjectPrincipalPublicId, preferenceId);
            jdbc.update("DELETE FROM sys_hris_explorer_recents "
                    + "WHERE tenant_id = ? AND principal_public_id = ?",
                    tenantId, subjectPrincipalPublicId);
            return;
        }
        String placeholders = String.join(", ",
                Collections.nCopies(allowed.size(), "?"));
        List<Object> favoriteArguments = new ArrayList<>();
        favoriteArguments.add(tenantId);
        favoriteArguments.add(subjectPrincipalPublicId);
        favoriteArguments.add(preferenceId);
        favoriteArguments.addAll(allowed);
        jdbc.update("DELETE FROM sys_hris_explorer_favorites "
                        + "WHERE tenant_id = ? AND principal_public_id = ? "
                        + "AND explorer_preference_id = ? "
                        + "AND route_key NOT IN (" + placeholders + ")",
                favoriteArguments.toArray());
        compactFavorites(tenantId, subjectPrincipalPublicId, preferenceId);
        List<Object> recentArguments = new ArrayList<>();
        recentArguments.add(tenantId);
        recentArguments.add(subjectPrincipalPublicId);
        recentArguments.add(now);
        recentArguments.addAll(allowed);
        jdbc.update("DELETE FROM sys_hris_explorer_recents "
                        + "WHERE tenant_id = ? AND principal_public_id = ? "
                        + "AND (expires_at <= ? OR route_key NOT IN ("
                        + placeholders + "))",
                recentArguments.toArray());
    }

    private void compactFavorites(
            long tenantId, UUID subjectPrincipalPublicId, UUID preferenceId) {
        List<StoredFavorite> retained = jdbc.query("""
                SELECT favorite_id, route_key, position, added_at
                  FROM sys_hris_explorer_favorites
                 WHERE tenant_id = ? AND principal_public_id = ?
                   AND explorer_preference_id = ?
                 ORDER BY position
                """, (rs, row) -> new StoredFavorite(
                rs.getObject("favorite_id", UUID.class),
                rs.getString("route_key"), rs.getInt("position"),
                rs.getObject("added_at", OffsetDateTime.class)),
                tenantId, subjectPrincipalPublicId, preferenceId);
        boolean contiguous = true;
        for (int index = 0; index < retained.size(); index++) {
            if (retained.get(index).position() != index + 1) {
                contiguous = false;
                break;
            }
        }
        if (contiguous) return;

        // Position uniqueness is immediate, so a direct multi-row renumber can
        // collide while retained rows move down. All purge callers already hold
        // the aggregate row lock; restoring at most 20 rows is atomic and keeps
        // stable IDs/timestamps without any transient duplicate position.
        jdbc.update("""
                DELETE FROM sys_hris_explorer_favorites
                 WHERE tenant_id = ? AND principal_public_id = ?
                   AND explorer_preference_id = ?
                """, tenantId, subjectPrincipalPublicId, preferenceId);
        for (int index = 0; index < retained.size(); index++) {
            StoredFavorite favorite = retained.get(index);
            jdbc.update("""
                    INSERT INTO sys_hris_explorer_favorites (
                        favorite_id, tenant_id, principal_public_id,
                        explorer_preference_id, route_key, position, added_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, favorite.favoriteId(), tenantId,
                    subjectPrincipalPublicId, preferenceId,
                    favorite.routeKey(), index + 1, favorite.addedAt());
        }
    }

    private void lockIdempotency(CommandContext context) {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", ignored -> null,
                advisoryKey(context));
    }

    private Optional<StoredReceipt> storedReceipt(CommandContext context) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    SELECT request_digest, public_id, command_type, originating_action,
                           subject_principal_public_id, population_scope_digest,
                           field_policy_revision, purpose_code, authorization_revision,
                           origin_route_contract_key, origin_context_key,
                           origin_context_scope_key, origin_decision_revision,
                           status, correlation_id, created_at, aggregate_revision, result_ref
                      FROM sys_hris_command_receipts
                     WHERE tenant_id = ? AND subject_principal_public_id = ?
                       AND originating_action = ? AND idempotency_key = ?
                    """, (rs, row) -> new StoredReceipt(
                    rs.getString("request_digest"), receipt(rs)),
                    context.tenantId(), context.subjectPrincipalPublicId(),
                    context.originatingAction(), context.idempotencyKey()));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private HrisExplorerReceipt replay(StoredReceipt stored, String requestDigest) {
        if (!stored.requestDigest().equals(requestDigest)) {
            throw conflict("HRIS_EXPLORER_IDEMPOTENCY_DIGEST_CONFLICT");
        }
        return stored.receipt();
    }

    private HrisExplorerReceipt receipt(
            CommandContext context, String requestDigest, UUID aggregateId,
            long aggregateRevision, String resultRef, OffsetDateTime now) {
        return new HrisExplorerReceipt(
                UUID.randomUUID(), context.originatingAction(), context.originatingAction(),
                context.subjectPrincipalPublicId(), context.populationScopeDigest(),
                HrisExplorerAuthoritySeals.publicRevision(
                        context.fieldPolicyRevision()),
                PURPOSE, HrisExplorerAuthoritySeals.publicRevision(
                        context.authorizationRevision()),
                ReceiptStatus.SUCCEEDED, context.correlationId(), requestDigest, now,
                aggregateRevision, resultRef);
    }

    private void insertReceipt(
            CommandContext context, String requestDigest, UUID aggregateId,
            long aggregateRevision, HrisExplorerReceipt receipt) {
        jdbc.update("""
                INSERT INTO sys_hris_command_receipts (
                    command_receipt_id, public_id, tenant_id, command_type,
                    originating_action, subject_principal_public_id,
                    population_scope_digest, authority_population_scope_digest,
                    field_policy_revision, authority_field_policy_revision, purpose_code,
                    authorization_revision, authority_authorization_revision,
                    origin_route_contract_key,
                    origin_context_key, origin_context_scope_key,
                    origin_decision_revision, idempotency_key, request_digest,
                    aggregate_public_id, aggregate_revision, status, result_ref,
                    correlation_id, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, receipt.receiptId(), receipt.receiptId(), context.tenantId(),
                receipt.commandType(), receipt.originatingAction(),
                receipt.subjectPrincipalPublicId(), receipt.populationScopeDigest(),
                context.authorityPopulationScopeDigest(),
                receipt.fieldPolicyRevision(), context.fieldPolicyRevision(),
                receipt.purposeCode(), receipt.authorizationRevision(),
                context.authorizationRevision(), context.originRouteContractKey(),
                context.originContextKey(), context.originContextScopeKey(),
                context.originDecisionRevision(), context.idempotencyKey(), requestDigest,
                aggregateId, aggregateRevision, receipt.status().name(),
                receipt.resultRef(), receipt.correlationId(), receipt.createdAt(),
                receipt.createdAt());
    }

    private HrisExplorerReceipt receipt(ResultSet rs) throws SQLException {
        Long aggregateRevision = rs.getObject("aggregate_revision") == null
                ? null : rs.getLong("aggregate_revision");
        return new HrisExplorerReceipt(
                rs.getObject("public_id", UUID.class),
                rs.getString("command_type"), rs.getString("originating_action"),
                rs.getObject("subject_principal_public_id", UUID.class),
                rs.getString("population_scope_digest"),
                rs.getLong("field_policy_revision"), rs.getString("purpose_code"),
                rs.getLong("authorization_revision"),
                ReceiptStatus.valueOf(rs.getString("status")),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("request_digest"),
                rs.getObject("created_at", OffsetDateTime.class),
                aggregateRevision, rs.getString("result_ref"));
    }

    private void bind(long tenantId, UUID subjectPrincipalPublicId) {
        if (tenantId < 1 || subjectPrincipalPublicId == null) {
            throw new BaseException(ErrorCode.FORBIDDEN, "Verified Explorer identity required");
        }
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)",
                String.class, Long.toString(tenantId));
        jdbc.queryForObject("SELECT set_config('dwp.principal_public_id', ?, true)",
                String.class, subjectPrincipalPublicId.toString());
    }

    private long advisoryKey(CommandContext context) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(context.tenantId()).getBytes(StandardCharsets.UTF_8));
            digest.update(context.subjectPrincipalPublicId().toString()
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(context.originatingAction().getBytes(StandardCharsets.UTF_8));
            digest.update(context.idempotencyKey().getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record StoredReceipt(String requestDigest, HrisExplorerReceipt receipt) {
    }

    private record StoreSnapshot(long sourceRevision, OffsetDateTime generatedAt) {
    }

    private record StoredFavorite(
            UUID favoriteId, String routeKey, int position,
            OffsetDateTime addedAt) {
    }

    private record LockedPreference(UUID preferenceId, long revision) {
    }
}
