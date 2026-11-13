package dev.anchor.app;

import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public class PlannerTest {
    private Task task(String date, String time) { Task t = new Task(); t.title="Focus"; t.date=date; t.time=time; return t; }
    @Test public void leapDayAccepted() { DateRules.validate(task("2028-02-29","09:30")); }
    @Test(expected=IllegalArgumentException.class) public void invalidLeapDayRejected() { DateRules.validate(task("2026-02-29","09:30")); }
    @Test(expected=IllegalArgumentException.class) public void impossibleTimeRejected() { DateRules.validate(task("2026-10-28","25:00")); }
    @Test(expected=IllegalArgumentException.class) public void titleRequired() { Task t=task("2026-10-28",""); t.title="  "; DateRules.validate(t); }
    @Test(expected=IllegalArgumentException.class) public void negativeDurationRejected() { Task t=task("2026-10-28",""); t.durationMinutes=-1; DateRules.validate(t); }
    @Test public void allDayHasNoReminderInstant() { Task t=task("2026-10-28",""); t.reminderMinutes=0; assertEquals(-1,DateRules.reminderAt(t,ZoneId.of("Africa/Lagos"))); }
    @Test public void reminderUsesLocalZone() { Task t=task("2026-10-28","09:30"); t.reminderMinutes=10; assertEquals(Instant.parse("2026-10-28T08:20:00Z").toEpochMilli(),DateRules.reminderAt(t,ZoneId.of("Africa/Lagos"))); }
    @Test public void streakAllowsTodayNotYetDone() { Map<String,Integer> c=new HashMap<>(); c.put("2026-10-27",1); c.put("2026-10-26",2); assertEquals(2,DateRules.streak(c,LocalDate.parse("2026-10-28"))); }
    @Test public void streakCountsTodayAndStopsAtGap() { Map<String,Integer> c=new HashMap<>(); c.put("2026-10-28",1); c.put("2026-10-27",1); c.put("2026-10-25",1); assertEquals(2,DateRules.streak(c,LocalDate.parse("2026-10-28"))); }
    @Test public void emptyHistoryHasNoStreak() { assertEquals(0,DateRules.streak(Collections.emptyMap(),LocalDate.parse("2026-10-28"))); }
    @Test public void dstGapMovesForwardByGap() { Task t=task("2026-03-08","02:30"); t.reminderMinutes=0; assertEquals(Instant.parse("2026-03-08T07:30:00Z").toEpochMilli(),DateRules.reminderAt(t,ZoneId.of("America/New_York"))); }
    @Test public void dstOverlapUsesEarlierOffset() { Task t=task("2026-11-01","01:30");t.reminderMinutes=0;assertEquals(Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(),DateRules.reminderAt(t,ZoneId.of("America/New_York"))); }
}
