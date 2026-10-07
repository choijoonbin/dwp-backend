package com.dwp.services.platform.workplace.workplaceservices;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;

record RevokePreparation(
        TaskContextRow context,
        AccessGrantRow grant,
        OperationsCommandRow command,
        boolean replayed,
        boolean recoveryLookup) { }
