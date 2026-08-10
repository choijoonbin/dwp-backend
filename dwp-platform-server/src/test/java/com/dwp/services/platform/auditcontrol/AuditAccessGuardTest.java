package com.dwp.services.platform.auditcontrol;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAccessGuardTest {

    private final AuditAccessGuard guard = new AuditAccessGuard();

    @Test
    void enforcesEachAuditCapabilityIndependently() {
        String permissions = String.join(",",
                "ADMIN.AUDIT_VIEW:VIEW",
                "ADMIN.AUDIT_INVESTIGATE:UPDATE",
                "ADMIN.AUDIT_EXPORT:EXPORT",
                "ADMIN.AUDIT_CONFIGURE:MANAGE");

        assertThatCode(() -> guard.view(permissions)).doesNotThrowAnyException();
        assertThatCode(() -> guard.investigate(permissions)).doesNotThrowAnyException();
        assertThatCode(() -> guard.export(permissions)).doesNotThrowAnyException();
        assertThatCode(() -> guard.configure(permissions)).doesNotThrowAnyException();
    }

    @Test
    void doesNotTreatAViewPermissionAsMutationOrExportAccess() {
        String permissions = "ADMIN.AUDIT_VIEW:VIEW";

        assertThatCode(() -> guard.view(permissions)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.investigate(permissions))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> guard.export(permissions))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> guard.configure(permissions))
                .isInstanceOf(BaseException.class);
    }
}
