package com.dwp.platform.contract;

import java.net.URI;
import java.util.List;
import java.util.Set;

public interface KnowledgeSearchPort {

    List<SearchHit> search(SearchRequest request);

    record SearchRequest(
            ExecutionContext context,
            String query,
            Set<String> sourceScopes,
            int limit) {

        public SearchRequest {
            if (context == null) {
                throw new IllegalArgumentException("context is required");
            }
            query = ContractChecks.required(query, "query");
            sourceScopes = sourceScopes == null ? Set.of() : Set.copyOf(sourceScopes);
            limit = ContractChecks.limit(limit, 50);
        }
    }

    record SearchHit(
            String sourceId,
            String version,
            String title,
            String excerpt,
            double score,
            String permissionReference,
            URI sourceUrl) {

        public SearchHit {
            sourceId = ContractChecks.required(sourceId, "sourceId");
            version = ContractChecks.required(version, "version");
            title = ContractChecks.required(title, "title");
            permissionReference = ContractChecks.required(
                    permissionReference,
                    "permissionReference");
            if (sourceUrl == null || score < 0 || score > 1) {
                throw new IllegalArgumentException("sourceUrl and a score from 0 to 1 are required");
            }
        }
    }
}
