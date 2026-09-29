package com.dwp.services.payroll.foundation;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;

record ReceiptReservation(CommandReceipt receipt, boolean created) {
}
