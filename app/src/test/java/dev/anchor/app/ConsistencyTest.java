package dev.anchor.app;
import org.junit.Test;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;
public class ConsistencyTest {
    @Test public void sixtyDaysContinueThroughMissedDays() {
        LocalDate start=LocalDate.of(2026,10,27);
        Map<String,Integer> counts=new HashMap<>(); counts.put("2026-10-27",2); counts.put("2026-10-29",1);
        Consistency.Snapshot s=Consistency.snapshot(start,start.plusDays(3),counts);
        assertEquals(4,s.day); assertEquals(2,s.activeDays); assertEquals(3,s.completedTasks); assertFalse(s.finished);
        assertEquals(1,s.currentStreak);
    }
    @Test public void historyOutsideChallengeDoesNotCount() {
        LocalDate start=LocalDate.of(2026,1,1);
        Map<String,Integer> counts=new HashMap<>(); counts.put(start.minusDays(1).toString(),100); counts.put(start.plusDays(60).toString(),100);
        Consistency.Snapshot s=Consistency.snapshot(start,start.plusDays(70),counts);
        assertEquals(60,s.day); assertEquals(0,s.activeDays); assertTrue(s.finished);
    }
    @Test public void futureStartHasNoInventedProgress() {
        Consistency.Snapshot s=Consistency.snapshot(LocalDate.of(2026,11,1),LocalDate.of(2026,10,28),Collections.emptyMap());
        assertEquals(0,s.day); assertEquals(0,s.activeDays); assertFalse(s.finished);
    }
    @Test public void sixtiethDayRemainsAvailableToCompleteWork() {
        LocalDate start=LocalDate.of(2026,1,1);
        assertFalse(Consistency.snapshot(start,start.plusDays(59),Collections.emptyMap()).finished);
        assertTrue(Consistency.snapshot(start,start.plusDays(60),Collections.emptyMap()).finished);
    }
}
