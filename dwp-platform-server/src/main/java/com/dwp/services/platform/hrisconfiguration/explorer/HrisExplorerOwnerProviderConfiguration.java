package com.dwp.services.platform.hrisconfiguration.explorer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

@Configuration(proxyBeanMethods = false)
class HrisExplorerOwnerProviderConfiguration {

    @Bean
    HrisExplorerOwnerEvidenceProvider xcon012PerformanceExplorerProvider(
            ObjectProvider<HrisExplorerXconOwnerEvidenceQuery> queries) {
        return provider("XCON-012", SourceKind.WORKFLOW_CASE, Module.PER, queries);
    }

    @Bean
    HrisExplorerOwnerEvidenceProvider xcon013PeopleExplorerProvider(
            ObjectProvider<HrisExplorerXconOwnerEvidenceQuery> queries) {
        return provider("XCON-013", SourceKind.SERVICE_REQUEST, Module.HRM, queries);
    }

    @Bean
    HrisExplorerOwnerEvidenceProvider xcon014TimeExplorerProvider(
            ObjectProvider<HrisExplorerXconOwnerEvidenceQuery> queries) {
        return provider("XCON-014", SourceKind.OPERATION_EXCEPTION, Module.TIM, queries);
    }

    @Bean
    HrisExplorerOwnerEvidenceProvider xcon015PayrollExplorerProvider(
            ObjectProvider<HrisExplorerXconOwnerEvidenceQuery> queries) {
        return provider("XCON-015", SourceKind.APPROVAL_CASE, Module.PAY, queries);
    }

    private HrisExplorerOwnerEvidenceProvider provider(
            String contractKey, SourceKind sourceKind, Module module,
            ObjectProvider<HrisExplorerXconOwnerEvidenceQuery> queries) {
        return new HrisExplorerOwnerEvidenceProvider() {
            @Override
            public SourceKind sourceKind() {
                return sourceKind;
            }

            @Override
            public Module module() {
                return module;
            }

            @Override
            public ProviderResult findInProgress(ProviderQuery query) {
                List<HrisExplorerXconOwnerEvidenceQuery> matches = queries.orderedStream()
                        .filter(candidate -> contractKey.equals(candidate.contractKey()))
                        .toList();
                if (matches.size() != 1) {
                    throw new IllegalStateException(contractKey + " owner evidence unavailable");
                }
                return matches.getFirst().findInProgress(query);
            }
        };
    }
}
