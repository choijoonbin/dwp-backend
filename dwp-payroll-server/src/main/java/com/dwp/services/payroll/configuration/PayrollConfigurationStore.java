package com.dwp.services.payroll.configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandReceipt;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.CommandType;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.ElementVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaVersion;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayrollConfigurationPublished;

interface PayrollConfigurationStore {

    Optional<ElementVersion> currentElement(long tenantId, UUID elementId);

    Optional<ElementVersion> elementVersion(long tenantId, UUID elementId, long version);

    List<ElementVersion> currentElements(long tenantId);

    Optional<FormulaVersion> currentFormula(long tenantId, UUID formulaId);

    Optional<FormulaVersion> formulaVersion(long tenantId, UUID formulaId, long version);

    List<FormulaVersion> currentFormulas(long tenantId);

    Optional<CommandReceipt> command(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String originatingAction,
            UUID commandId);

    Optional<PayrollConfigurationPublished> publicationEvent(long tenantId, UUID receiptId);

    void saveElement(ElementVersion previous, ElementVersion updated, CommandReceipt receipt);

    void saveFormula(
            FormulaVersion previous,
            FormulaVersion updated,
            CommandReceipt receipt,
            PayrollConfigurationPublished event);
}
