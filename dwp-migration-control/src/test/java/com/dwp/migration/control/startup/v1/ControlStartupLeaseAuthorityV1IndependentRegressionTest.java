package com.dwp.migration.control.startup.v1;

import com.dwp.core.database.authority.RuntimeStartupFreshnessPort.Activation;
import com.dwp.core.database.authority.RuntimeStartupFreshnessPort.Reservation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.SQLException;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Independent no-PG adversarial ingress tests; authenticated invocation is an explicit trusted fixture, not transport EE. */
class ControlStartupLeaseAuthorityV1IndependentRegressionTest {
    static final Instant NOW=Instant.parse("2026-09-14T05:00:00Z");
    static final String GENERIC="current external deployment authority rejected or unavailable";
    static final Reservation REQUEST=new Reservation("auth",UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,
            UUID.randomUUID().toString(),"a".repeat(64),"b".repeat(64));
    static KeyPair key;

    @BeforeAll static void ownedInMemoryKey() throws Exception { key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }

    enum Operation { RESERVE,ACTIVATE,CURRENT,RELEASE }

    @ParameterizedTest(name="{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(Operation.class)
    void transportProviderFailureMustBeRedactedBeforeOpeningPool(Operation operation) {
        AtomicInteger opens=new AtomicInteger();
        var authority=authority(opens,() -> { throw new IllegalStateException("private-transport-token:secret"); },Clock.fixed(NOW,ZoneOffset.UTC));
        var rejected=assertThrows(IllegalStateException.class,() -> invoke(authority,operation));
        assertEquals(GENERIC,rejected.getMessage());
        assertNull(rejected.getCause());
        assertEquals(0,opens.get());
    }

    @Test void nullTransportOptionalMustBecomeGenericDenialWithoutPool() {
        AtomicInteger opens=new AtomicInteger();
        var authority=authority(opens,() -> null,Clock.fixed(NOW,ZoneOffset.UTC));
        var rejected=assertThrows(IllegalStateException.class,() -> authority.reserve(REQUEST));
        assertEquals(GENERIC,rejected.getMessage()); assertNull(rejected.getCause()); assertEquals(0,opens.get());
    }

    @Test void clockProviderFailureMustBeRedactedBeforeOpeningPool() {
        AtomicInteger opens=new AtomicInteger();
        Clock broken=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { throw new IllegalStateException("private-clock-provider-secret"); }
        };
        var authority=authority(opens,ControlStartupLeaseAuthorityV1IndependentRegressionTest::invocation,broken);
        var rejected=assertThrows(IllegalStateException.class,() -> authority.reserve(REQUEST));
        assertEquals(GENERIC,rejected.getMessage()); assertNull(rejected.getCause()); assertEquals(0,opens.get());
    }

    @Test void nullClockInstantMustRejectBeforeOpeningPool() {
        AtomicInteger opens=new AtomicInteger();
        Clock invalid=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return null; }
        };
        var rejected=assertThrows(IllegalStateException.class,() -> authority(opens,
                ControlStartupLeaseAuthorityV1IndependentRegressionTest::invocation,invalid).reserve(REQUEST));
        assertEquals(GENERIC,rejected.getMessage()); assertNull(rejected.getCause()); assertEquals(0,opens.get());
    }

    @Test void missingAuthenticatedInvocationIsAlreadyFailClosedWithZeroPoolCalls() {
        AtomicInteger opens=new AtomicInteger();
        var rejected=assertThrows(IllegalStateException.class,() -> authority(opens,Optional::empty,Clock.fixed(NOW,ZoneOffset.UTC)).reserve(REQUEST));
        assertEquals(GENERIC,rejected.getMessage()); assertNull(rejected.getCause()); assertEquals(0,opens.get());
    }

    @Test void invocationInstanceMismatchIsAlreadyFailClosedBeforePool() {
        AtomicInteger opens=new AtomicInteger();
        var wrong=new ControlStartupLeaseAuthorityV1.Invocation(REQUEST.service(),REQUEST.deploymentId(),
                UUID.randomUUID().toString(),REQUEST.startupChallengeSha256());
        var rejected=assertThrows(IllegalStateException.class,() -> authority(opens,() -> Optional.of(wrong),Clock.fixed(NOW,ZoneOffset.UTC)).reserve(REQUEST));
        assertEquals(GENERIC,rejected.getMessage()); assertEquals(0,opens.get());
    }

    static Optional<ControlStartupLeaseAuthorityV1.Invocation> invocation() {
        return Optional.of(new ControlStartupLeaseAuthorityV1.Invocation(REQUEST.service(),REQUEST.deploymentId(),
                REQUEST.applicationInstanceId(),REQUEST.startupChallengeSha256()));
    }
    static void invoke(ControlStartupLeaseAuthorityV1 authority,Operation operation) {
        String lease=UUID.randomUUID().toString();
        switch(operation) {
            case RESERVE -> authority.reserve(REQUEST);
            case ACTIVATE -> authority.activate(new Activation(REQUEST,lease));
            case CURRENT -> authority.current(new Activation(REQUEST,lease));
            case RELEASE -> authority.release(REQUEST,lease);
        }
    }
    static ControlStartupLeaseAuthorityV1 authority(AtomicInteger opens,ControlStartupLeaseAuthorityV1.AuthenticatedInvocationPort invocation,Clock clock) {
        DataSource noOpen=(DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),new Class<?>[]{DataSource.class},(proxy,method,args) -> {
            if(method.getName().equals("getConnection")) { opens.incrementAndGet(); throw new SQLException("private-DS-credential"); }
            throw new UnsupportedOperationException("no pool method permitted in this fixture");
        });
        return new ControlStartupLeaseAuthorityV1(noOpen,"approved_catalog","approved_authority",invocation,
                new ControlStartupLeaseSignerV1("independent-key",1,key.getPrivate(),key.getPublic()),clock,Duration.ofSeconds(10));
    }
}
