package com.dwp.services.approval.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalInformationReceiptSource;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.transaction.PlatformTransactionManager;

class InformationReceiptPreSqlGateTest {
    @Test void disabledSourceNeverResolvesKeysInstalledAuthorityOrTransactions() {
        var source=mock(ApprovalInformationReceiptSource.class);var installed=mock(InformationReceiptInstalledContext.class);
        var transactions=mock(PlatformTransactionManager.class);
        var facade=new InformationReceiptFacade(false,()->{fail("Disabled keys must remain lazy");return null;},installed,source,transactions,Clock.systemUTC());
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->facade.read(null,null,null,null)).getErrorCode());
        verifyNoInteractions(source,installed,transactions);
    }
    @Test void missingDedicatedKeysFailBeforeOwnerAndSql() {
        var source=mock(ApprovalInformationReceiptSource.class);var installed=mock(InformationReceiptInstalledContext.class);
        var transactions=mock(PlatformTransactionManager.class);
        var facade=new InformationReceiptFacade(true,()->{throw new BeanCreationException("informationReplayRuntime");},installed,source,transactions,Clock.systemUTC());
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->facade.read(null,null,null,null)).getErrorCode());
        verifyNoInteractions(source,installed,transactions);
    }
}
