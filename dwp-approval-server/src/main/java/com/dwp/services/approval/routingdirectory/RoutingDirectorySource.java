package com.dwp.services.approval.routingdirectory;

import java.time.Instant;
import java.util.List;

@FunctionalInterface
public interface RoutingDirectorySource {
    List<RoutingDirectoryModels.Candidate> resolve(
            RoutingDirectoryModels.Context context,
            RoutingDirectoryModels.ResolverView resolver,
            Instant effectiveAt);
}
