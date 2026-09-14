package com.dwp.gateway.productsurface;

import java.util.List;

/** Test-only access to the existing revision algorithm, not a Gateway HTTP or rollout-activation proof. */
public final class WorkflowNineRevisionProbe {
    private WorkflowNineRevisionProbe() { }
    public static String revision(long tenant,long actor,String auth,String policy,String person,List<String> roles,List<String> permissions) {
        var context=new ProductSurfaceContextDtos.RequestContext(tenant,actor,ProductSurfaceContextDtos.AccessMode.NORMAL,
                null,null,List.of(),"",null,null,person,roles,permissions);
        return ProductSurfaceContextAggregationSupport.compositeRevision(context,
                new ProductSurfaceContextDtos.SourceRevisions(auth,policy,null,null,null));
    }
}
