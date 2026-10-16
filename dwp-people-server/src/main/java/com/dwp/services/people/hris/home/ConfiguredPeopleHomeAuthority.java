package com.dwp.services.people.hris.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fail-closed owner registry. Its values are current People-side grants, not caller
 * headers; changing any revision immediately invalidates an already issued proof.
 */
@Component
@ConditionalOnProperty(prefix = ConfiguredPeopleHomeAuthority.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class ConfiguredPeopleHomeAuthority implements PeopleHomeAuthorityPort {

    static final String PREFIX = "dwp.hris.home.people.";
    private static final Set<String> GRANT_FIELDS = Set.of(
            "tenantId", "subjectPrincipalPublicId", "capability", "browserAudience",
            "ownerPopulationScope", "scopeRevision", "populationScopeDigest",
            "effectivePermissionRevision", "fieldPolicyRevision", "widgetKey",
            "populationPersonPublicIds", "fieldDecisions");
    private static final Set<String> PROJECTION_FIELDS = Set.of(
            "employmentStatus", "organizationLabel", "pendingActionCount");

    private final List<CurrentAuthority> grants;
    private final Map<Key, CurrentAuthority> byKey;

    ConfiguredPeopleHomeAuthority(Environment environment, ObjectMapper mapper) {
        this(parse(mapper, environment.getProperty(PREFIX + "current-authority-grants-json", "")));
    }

    ConfiguredPeopleHomeAuthority(List<CurrentAuthority> grants) {
        if (grants == null || grants.isEmpty()) {
            throw new IllegalStateException(
                    "Enabled HRM Home producer requires current People authority grants.");
        }
        LinkedHashMap<Key, CurrentAuthority> indexed = new LinkedHashMap<>();
        for (CurrentAuthority grant : grants) {
            Key key = new Key(grant.tenantId(), grant.subjectPrincipalPublicId(), grant.widgetKey());
            if (indexed.putIfAbsent(key, grant) != null) {
                throw new IllegalStateException("Duplicate current People Home authority grant.");
            }
        }
        this.grants = List.copyOf(indexed.values());
        this.byKey = Map.copyOf(indexed);
    }

    @Override
    public Optional<CurrentAuthority> current(
            HomeMaterializationWorkloadProofV1.ProofClaims signedContext) {
        if (signedContext == null) return Optional.empty();
        CurrentAuthority current = byKey.get(new Key(
                signedContext.tenantId(), signedContext.subjectPrincipalPublicId(),
                signedContext.widgetKey()));
        return current != null && current.exactlyMatches(signedContext)
                ? Optional.of(current) : Optional.empty();
    }

    @Override
    public List<CurrentAuthority> currentGrants() {
        return grants;
    }

    private static List<CurrentAuthority> parse(ObjectMapper mapper, String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 262_144) return List.of();
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.isArray() || root.isEmpty() || root.size() > 2_000) {
                return List.of();
            }
            List<CurrentAuthority> result = new ArrayList<>();
            for (JsonNode grant : root) {
                if (!grant.isObject() || !fields(grant).equals(GRANT_FIELDS)) return List.of();
                JsonNode people = grant.get("populationPersonPublicIds");
                JsonNode decisions = grant.get("fieldDecisions");
                if (!people.isArray() || people.isEmpty() || !decisions.isObject()
                        || !fields(decisions).equals(PROJECTION_FIELDS)) return List.of();
                Set<UUID> population = new LinkedHashSet<>();
                for (JsonNode person : people) population.add(uuid(person));
                Map<String, HrisHomeContributionMaterializationV1.Decision> fieldPolicy =
                        new LinkedHashMap<>();
                for (String field : PROJECTION_FIELDS) {
                    fieldPolicy.put(field,
                            HrisHomeContributionMaterializationV1.Decision.valueOf(
                                    text(decisions.get(field))));
                }
                result.add(new CurrentAuthority(
                        positive(grant.get("tenantId")),
                        uuid(grant.get("subjectPrincipalPublicId")),
                        text(grant.get("capability")),
                        HrisHomeContributionMaterializationV1.BrowserAudience.valueOf(
                                text(grant.get("browserAudience"))),
                        HrisHomeContributionMaterializationV1.OwnerPopulationScope.valueOf(
                                text(grant.get("ownerPopulationScope"))),
                        positive(grant.get("scopeRevision")),
                        text(grant.get("populationScopeDigest")),
                        positive(grant.get("effectivePermissionRevision")),
                        positive(grant.get("fieldPolicyRevision")),
                        text(grant.get("widgetKey")), population, fieldPolicy));
            }
            return List.copyOf(result);
        } catch (RuntimeException | java.io.IOException exception) {
            return List.of();
        }
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> result = new HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual() || node.textValue().isBlank()
                || !node.textValue().equals(node.textValue().strip())) {
            throw new IllegalArgumentException("Invalid current authority text.");
        }
        return node.textValue();
    }

    private static long positive(JsonNode node) {
        if (node == null || !node.canConvertToLong() || !node.isIntegralNumber()
                || node.longValue() < 1) {
            throw new IllegalArgumentException("Invalid current authority revision.");
        }
        return node.longValue();
    }

    private static UUID uuid(JsonNode node) {
        String raw = text(node);
        UUID value = UUID.fromString(raw);
        if (!value.toString().equals(raw)) {
            throw new IllegalArgumentException("Non-canonical current authority UUID.");
        }
        return value;
    }

    private record Key(long tenantId, UUID subjectPrincipalPublicId, String widgetKey) {
    }
}
