package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.VersionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;

import java.time.OffsetDateTime;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationServiceInternals.*;

/** Internal collaboration used by the non-Spring command helpers. */
interface AutomationServiceOperations {

    VerifiedArtifact revalidate(VersionRecord version, OffsetDateTime now);

    void bind(Authority authority);

    OffsetDateTime now();
}
