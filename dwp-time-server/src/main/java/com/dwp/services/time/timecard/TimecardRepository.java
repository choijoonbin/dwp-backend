package com.dwp.services.time.timecard;

import com.dwp.services.time.timecard.TimecardModels.StoredTimecardReceipt;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardCommand;
import com.dwp.services.time.timecard.TimecardModels.TimecardMaterialization;
import com.dwp.services.time.timecard.TimecardModels.TimecardRecord;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped atomic persistence boundary for Timecard commands and event evidence. */
public interface TimecardRepository {

    Optional<TimecardRecord> find(long tenantId, UUID timecardId);

    TimeCommandReceiptV1 execute(TimecardCommand command);

    Optional<StoredTimecardReceipt> findReceipt(long tenantId, UUID receiptId);

    TimecardRecord materialize(TimecardMaterialization materialization);
}
