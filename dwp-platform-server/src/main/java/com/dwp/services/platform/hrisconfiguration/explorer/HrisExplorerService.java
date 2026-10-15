package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerRepository.*;

@Service
final class HrisExplorerService {

    static final int MAX_FAVORITES = 20;
    static final int MAX_RECENTS = 20;
    static final int RECENT_TTL_DAYS = 30;
    static final int MAX_IN_PROGRESS = 20;
    private static final ExplorerPolicy POLICY = new ExplorerPolicy(
            MAX_FAVORITES, MAX_RECENTS, RECENT_TTL_DAYS, MAX_IN_PROGRESS,
            true, "READ_AND_WRITE_THROUGH_PURGE");

    private final HrisExplorerRepository repository;
    private final HrisExplorerProviderCoordinator providers;
    private final Clock clock;

    @Autowired
    HrisExplorerService(
            HrisExplorerRepository repository,
            HrisExplorerProviderCoordinator providers) {
        this(repository, providers, Clock.systemUTC());
    }

    HrisExplorerService(
            HrisExplorerRepository repository,
            HrisExplorerProviderCoordinator providers,
            Clock clock) {
        this.repository = repository;
        this.providers = providers;
        this.clock = clock;
    }

    ExplorerResult explorer(
            AuthoritySnapshot snapshot, OffsetDateTime asOf, Freshness freshness,
            String filter, String cursor, int limit) {
        if (snapshot == null) throw forbidden("Verified Explorer authority is required");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (asOf == null || asOf.isAfter(now.plusMinutes(1))) {
            throw invalid("Explorer asOf is invalid");
        }
        if (freshness == null) throw invalid("Explorer freshness is required");
        // ALLOW_STALE applies only to non-authoritative projection data. Route, owner
        // scope and field-policy authority must be current for every response.
        if (snapshot.freshUntil() == null || !snapshot.freshUntil().isAfter(now)) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "A fresh Explorer authority projection is required");
        }

        Map<String, ExplorerRoute> authorized = authorizedRoutes(snapshot);
        Set<String> allowedKeys = authorized.keySet();
        ExplorerState state = repository.readAndPurge(
                snapshot.tenantId(), snapshot.subjectPrincipalPublicId(), allowedKeys, now);

        String normalizedFilter = filter == null ? null : filter.strip().toLowerCase(Locale.ROOT);
        if (filter != null && (normalizedFilter.isEmpty() || filter.length() > 200)) {
            throw invalid("Explorer filter must contain 1 through 200 characters");
        }
        String after = decodeCursor(cursor);
        List<ExplorerRoute> eligible = authorized.values().stream()
                .filter(route -> normalizedFilter == null || searchable(route)
                        .contains(normalizedFilter))
                .filter(route -> after == null || route.routeKey().compareTo(after) > 0)
                .sorted(Comparator.comparing(ExplorerRoute::routeKey))
                .toList();
        List<ExplorerRoute> routes = eligible.stream().limit(limit).toList();
        String nextCursor = eligible.size() > limit
                ? encodeCursor(routes.getLast().routeKey()) : null;

        List<ExplorerFavorite> favorites = favorites(state.favorites(), authorized);
        List<ExplorerRecent> recents = state.recents().stream()
                .map(row -> recent(authorized.get(row.routeKey()), row))
                .filter(java.util.Objects::nonNull).limit(MAX_RECENTS).toList();

        HrisExplorerOwnerEvidenceProvider.ProviderQuery query =
                new HrisExplorerOwnerEvidenceProvider.ProviderQuery(
                        snapshot.tenantId(), snapshot.subjectPrincipalPublicId(),
                        snapshot.contextScopeKey(),
                        HrisExplorerAuthoritySeals.populationScopeDigest(
                                snapshot.contextScopeKey()),
                        snapshot.purposeCode(), snapshot.authorizationRevision(),
                        snapshot.fieldPolicyRevision(), asOf, now,
                        authorized.keySet(),
                        authorized.values().stream().map(ExplorerRoute::target)
                                .collect(Collectors.toUnmodifiableSet()));
        HrisExplorerProviderCoordinator.ProviderProjection activity = providers.project(query);
        List<DegradedSource> degraded = new ArrayList<>(activity.degradedSources());
        ProjectionState projectionState = !activity.degradedSources().isEmpty()
                ? ProjectionState.PARTIAL
                : ProjectionState.FRESH;
        HrisExplorerResponse response = new HrisExplorerResponse(
                asOf, snapshot.projectionRevision(), snapshot.entitlementRevision(),
                state.preferenceRevision(), routes, favorites, recents,
                activity.activities(), nextCursor,
                new ProjectionStatus(projectionState, degraded), POLICY);
        return new ExplorerResult(response, preferenceEtag(state.preferenceRevision()));
    }

    CommandResult replacePreferences(
            AuthoritySnapshot snapshot, String idempotencyKey, String ifMatch,
            UUID correlationId, ExplorerPreferenceCommand command) {
        requireCurrentSnapshot(snapshot);
        if (command == null || command.orderedFavoriteRouteKeys() == null) {
            throw invalid("Explorer preference command is required");
        }
        List<String> routeKeys = command.orderedFavoriteRouteKeys();
        long expectedRevision = parsePreferenceEtag(ifMatch);
        String digest = commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                snapshot, ifMatch, routeKeys);
        HrisExplorerReceipt receipt = repository.replaceFavorites(
                context(snapshot, correlationId, idempotencyKey,
                        JdbcHrisExplorerRepository.PREFERENCES_ACTION),
                expectedRevision, routeKeys,
                authorizedRoutes(snapshot).keySet(), digest, now());
        return new CommandResult(receipt,
                preferenceEtag(receipt.aggregateVersion() == null
                        ? expectedRevision : receipt.aggregateVersion()));
    }

    CommandResult recordRecent(
            AuthoritySnapshot snapshot, String idempotencyKey,
            UUID correlationId, ExplorerRecentCommand command) {
        requireCurrentSnapshot(snapshot);
        if (command == null || command.routeKey() == null) {
            throw invalid("Explorer recent command is required");
        }
        String routeKey = command.routeKey();
        String digest = commandDigest(
                JdbcHrisExplorerRepository.RECENT_ACTION,
                snapshot, null, List.of(routeKey));
        HrisExplorerReceipt receipt = repository.recordRecent(
                context(snapshot, correlationId, idempotencyKey,
                        JdbcHrisExplorerRepository.RECENT_ACTION),
                routeKey, authorizedRoutes(snapshot).keySet(), digest, now());
        return new CommandResult(receipt, null);
    }

    HrisExplorerReceipt explorerReceipt(AuthoritySnapshot snapshot, UUID receiptId) {
        requireCurrentSnapshot(snapshot);
        HrisExplorerReceipt receipt = repository.findReceipt(
                        snapshot.tenantId(), snapshot.subjectPrincipalPublicId(), receiptId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "Explorer command receipt not found"));
        if (!Set.of(JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                JdbcHrisExplorerRepository.RECENT_ACTION)
                .contains(receipt.originatingAction())) {
            throw new BaseException(ErrorCode.NOT_FOUND,
                    "Explorer command receipt not found");
        }
        return receipt;
    }

    HrisExplorerReceipt platformReceipt(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId) {
        HrisExplorerReceipt receipt = repository.findReceipt(
                        tenantId, subjectPrincipalPublicId, receiptId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "HRIS command receipt not found"));
        if (Set.of(JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                JdbcHrisExplorerRepository.RECENT_ACTION)
                .contains(receipt.originatingAction())) {
            throw new BaseException(ErrorCode.NOT_FOUND,
                    "HRIS command receipt not found");
        }
        return receipt;
    }

    private Map<String, ExplorerRoute> authorizedRoutes(AuthoritySnapshot snapshot) {
        Map<String, ExplorerRoute> result = new LinkedHashMap<>();
        snapshot.allowedRouteDecisions().forEach((routeKey, authorization) -> {
            HrisExplorerCatalog.CatalogNode node = HrisExplorerCatalog.node(routeKey);
            if (node != null && HrisExplorerV43RouteAdapter.exactDecision(routeKey, authorization)
                    && (node.target().kind() != TargetKind.DWP_CONTROL
                    || HrisExplorerV43RouteAdapter.OPERATIONAL_DWP_CONTROL_ROUTE_KEY
                    .equals(routeKey))) {
                result.put(routeKey, new ExplorerRoute(
                        node.routeKey(), node.target(), node.labelKey(),
                        node.module(), node.workbench(), authorization));
            }
        });
        if (result.isEmpty()) {
            throw forbidden("At least one authorized Explorer route is required");
        }
        return Map.copyOf(result);
    }

    private ExplorerFavorite favorite(ExplorerRoute route, int position) {
        if (route == null) return null;
        return new ExplorerFavorite(route.routeKey(), route.target(), route.labelKey(),
                route.module(), route.workbench(), route.authorization(), position);
    }

    private List<ExplorerFavorite> favorites(
            List<FavoriteRow> rows, Map<String, ExplorerRoute> authorized) {
        List<ExplorerFavorite> result = new ArrayList<>();
        for (FavoriteRow row : rows) {
            ExplorerRoute route = authorized.get(row.routeKey());
            if (route != null) {
                result.add(favorite(route, result.size() + 1));
                if (result.size() == MAX_FAVORITES) break;
            }
        }
        return List.copyOf(result);
    }

    private ExplorerRecent recent(ExplorerRoute route, RecentRow row) {
        if (route == null) return null;
        return new ExplorerRecent(route.routeKey(), route.target(), route.labelKey(),
                route.module(), route.workbench(), route.authorization(),
                row.lastVisitedAt(), row.expiresAt());
    }

    private CommandContext context(
            AuthoritySnapshot snapshot, UUID correlationId,
            String idempotencyKey, String action) {
        return new CommandContext(
                snapshot.tenantId(), snapshot.subjectPrincipalPublicId(),
                snapshot.populationScopeDigest(),
                HrisExplorerAuthoritySeals.populationScopeDigest(
                        snapshot.contextScopeKey()),
                snapshot.fieldPolicyRevision(),
                snapshot.authorizationRevision(),
                action.equals(JdbcHrisExplorerRepository.PREFERENCES_ACTION)
                        ? "route.hcm.utility.explorer-preferences-update.action"
                        : "route.hcm.utility.explorer-recent-create.action",
                snapshot.contextKey(), snapshot.contextScopeKey(),
                snapshot.currentDecisionRevision(),
                correlationId == null ? UUID.randomUUID() : correlationId,
                idempotencyKey, action);
    }

    private void requireSnapshot(AuthoritySnapshot snapshot) {
        if (snapshot == null) throw forbidden("Verified Explorer authority is required");
    }

    private void requireCurrentSnapshot(AuthoritySnapshot snapshot) {
        requireSnapshot(snapshot);
        if (snapshot.freshUntil() == null || !snapshot.freshUntil().isAfter(now())) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current Explorer authority is required for mutation");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String searchable(ExplorerRoute route) {
        return (route.routeKey() + " " + route.labelKey() + " " + route.target().path())
                .toLowerCase(Locale.ROOT);
    }

    private String encodeCursor(String routeKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                routeKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeCursor(String cursor) {
        if (cursor == null) return null;
        if (cursor.isBlank() || cursor.length() > 2048 || !cursor.equals(cursor.strip())) {
            throw invalid("Explorer cursor is invalid");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            if (!decoded.matches("^[a-z][a-z0-9.-]{2,95}$")) {
                throw invalid("Explorer cursor is invalid");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw invalid("Explorer cursor is invalid");
        }
    }

    static String preferenceEtag(long revision) {
        return "\"pref-" + revision + "\"";
    }

    static long parsePreferenceEtag(String value) {
        if (value == null || !value.matches("^(W/)?\\\"[A-Za-z0-9._:-]+\\\"$")) {
            throw invalid("If-Match must be one valid Explorer preference ETag");
        }
        String token = value.startsWith("W/")
                ? value.substring(3, value.length() - 1)
                : value.substring(1, value.length() - 1);
        if (!token.matches("^pref-[1-9][0-9]*$")) {
            throw invalid("If-Match is not an Explorer preference ETag");
        }
        try {
            return Long.parseLong(token.substring(5));
        } catch (NumberFormatException exception) {
            throw invalid("If-Match is not an Explorer preference ETag");
        }
    }

    static String commandDigest(
            String action, AuthoritySnapshot snapshot,
            String precondition, List<String> payloadValues) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digestPart(digest, action);
            digestPart(digest, Long.toString(snapshot.tenantId()));
            digestPart(digest, snapshot.subjectPrincipalPublicId().toString());
            digestPart(digest, snapshot.contextKey());
            digestPart(digest, snapshot.contextScopeKey());
            digestPart(digest, snapshot.currentDecisionRevision());
            digestPart(digest, snapshot.populationScopeDigest());
            digestPart(digest, HrisExplorerAuthoritySeals.populationScopeDigest(
                    snapshot.contextScopeKey()));
            digestPart(digest, Long.toString(snapshot.authorizationRevision()));
            digestPart(digest, Long.toString(snapshot.fieldPolicyRevision()));
            digestPart(digest, precondition);
            digestPart(digest, Integer.toString(payloadValues.size()));
            payloadValues.forEach(value -> digestPart(digest, value));
            byte[] value = digest.digest();
            return java.util.HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void digestPart(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(new byte[]{-1, -1, -1, -1});
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(new byte[]{
                (byte) (bytes.length >>> 24),
                (byte) (bytes.length >>> 16),
                (byte) (bytes.length >>> 8),
                (byte) bytes.length});
        digest.update(bytes);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    record ExplorerResult(HrisExplorerResponse response, String etag) {
    }

    record CommandResult(HrisExplorerReceipt receipt, String etag) {
    }
}
