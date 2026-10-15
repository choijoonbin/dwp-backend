package com.dwp.services.platform.hrisconfiguration.explorer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;

@Component
final class QueryBackedHrisExplorerAuthoritySource implements HrisExplorerAuthoritySource {

    private final ObjectProvider<HrisExplorerAuthorityEvidenceQuery> queries;

    QueryBackedHrisExplorerAuthoritySource(
            ObjectProvider<HrisExplorerAuthorityEvidenceQuery> queries) {
        this.queries = queries;
    }

    @Override
    public Optional<AuthoritySnapshot> resolve(AuthorityRequest request) {
        List<HrisExplorerAuthorityEvidenceQuery> available = queries.orderedStream().toList();
        if (available.size() != 1) return Optional.empty();
        return available.getFirst().resolve(request);
    }
}
