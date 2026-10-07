package com.dwp.services.platform.workplace.workplaceservices;

import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;

record CredentialPreparation(
        TaskContextRow context,
        AccessGrantRow grant,
        OperationsCommandRow command,
        UUID grantId,
        boolean replayed,
        boolean recoveryLookup) { }
