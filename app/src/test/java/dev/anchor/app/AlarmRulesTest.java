package dev.anchor.app;

import org.junit.Test;
import static org.junit.Assert.*;

/** These checks fail if an old broadcast or notification action can revive an edited alarm. */
public class AlarmRulesTest {
    @Test public void onlyCurrentPendingOccurrenceMayFire() {
        assertTrue(AlarmRules.acceptOccurrence(42,1000,7,"pending",42,1000,7,1000));
        assertFalse(AlarmRules.acceptOccurrence(43,1000,7,"pending",42,1000,7,1000));
        assertFalse(AlarmRules.acceptOccurrence(42,1300,8,"pending",42,1000,7,1300));
        assertFalse(AlarmRules.acceptOccurrence(42,1000,7,"dismissed",42,1000,7,1000));
        assertFalse(AlarmRules.acceptOccurrence(42,1000,7,"delivered",42,1000,7,1000));
        assertFalse(AlarmRules.acceptOccurrence(42,1000,7,"pending",42,1000,7,999));
    }
    @Test public void fiveAndTenMinuteSnoozesAreMeasuredFromNow() {
        assertEquals(301000,AlarmRules.snoozeAt(1000,5));
        assertEquals(601000,AlarmRules.snoozeAt(1000,10));
    }
    @Test public void unsupportedSnoozeIsRejected() {
        for(int minutes:new int[]{-1,0,1,15,Integer.MAX_VALUE}) {
            try { AlarmRules.snoozeAt(1000,minutes); fail("Unsupported snooze accepted: "+minutes); }
            catch(IllegalArgumentException expected) {}
        }
    }
    @Test public void staleDelayedAlarmsCanNotifyWithoutStartingLongLateRinging() {
        assertTrue(AlarmRules.mayRing(1000,121000));
        assertFalse(AlarmRules.mayRing(1000,121001));
        assertFalse(AlarmRules.mayRing(1000,999));
    }
}
