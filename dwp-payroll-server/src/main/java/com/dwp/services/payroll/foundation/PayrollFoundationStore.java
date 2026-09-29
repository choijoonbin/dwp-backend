package com.dwp.services.payroll.foundation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationSnapshot;

interface PayrollFoundationStore {

    Optional<ConfigurationSnapshot> current(long tenantId, UUID configurationId);

    List<ConfigurationSnapshot> currentForTenant(long tenantId);

    List<ConfigurationSnapshot> versions(long tenantId, UUID configurationId);

    void save(
            ConfigurationSnapshot previous,
            ConfigurationSnapshot updated,
            CommandReceipt command);

    ReceiptReservation reserve(CommandReceipt pendingReceipt);

    void replaceReceipt(CommandReceipt receipt);

    Optional<CommandReceipt> receipt(long tenantId, UUID commandId);

    Optional<ConfigurationSnapshot> byCommand(long tenantId, UUID commandId);

    Optional<ConfigurationSnapshot> version(
            long tenantId, UUID configurationId, long version);
}
