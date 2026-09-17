package com.dwp.services.platform.mail;

import java.util.UUID;

interface MailPurgeDomainEventPort {

    UUID record(
            AdminMailCompletionRepository.PurgeLeaseRow job,
            AdminMailCompletionRepository.DeleteCounts counts);
}
