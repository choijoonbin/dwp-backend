package com.dwp.services.platform.hrisconfiguration.explorer;

/** Typed query hook implemented by the XCON-012 through XCON-015 owner adapters. */
public interface HrisExplorerXconOwnerEvidenceQuery {

    String contractKey();

    HrisExplorerOwnerEvidenceProvider.ProviderResult findInProgress(
            HrisExplorerOwnerEvidenceProvider.ProviderQuery query);
}
