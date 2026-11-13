package dev.anchor.app;

import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

/** Behavioral checks for the offline wall-clock planner. */
public class SchedulerTest {
    private final LocalDate day=LocalDate.of(2026,10,28);
    private Task task(long id,String time,int minutes,boolean flexible) {
        Task t=new Task();t.id=id;t.title="Entry "+id;t.date=day.toString();t.time=time;t.durationMinutes=minutes;t.flexible=flexible;return t;
    }
    private Scheduler.Plan plan(Task... tasks) { return Scheduler.plan(Arrays.asList(tasks),day,LocalTime.of(8,0),480,1080,100); }
    private String time(Scheduler.Plan p,long id) { for(Scheduler.Move m:p.moves) if(m.taskId==id)return m.toTime;return null; }
    @Test public void fixedCommitmentIsImmutableAndFlexibleCollisionMovesToWholeGap() {
        Task fixed=task(1,"09:00",90,false),flex=task(2,"09:30",60,true);
        Scheduler.Plan p=plan(fixed,flex);
        assertEquals("08:00",time(p,2));assertNull(time(p,1));assertEquals("09:00",fixed.time);assertEquals("09:30",flex.time);
    }
    @Test public void overlappingProtectedBlocksAreMergedBeforeGapPlacement() {
        Scheduler.Plan p=plan(task(1,"08:00",120,false),task(2,"09:00",120,false),task(3,"",60,true));
        assertEquals("11:00",time(p,3));
    }
    @Test public void fullyElapsedIncompleteFlexibleWorkMovesAfterNowRoundedUp() {
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(task(1,"08:00",30,true)),day,LocalTime.of(10,15,1),480,1080,100);
        assertEquals("10:16",time(p,1));
    }
    @Test public void inProgressWorkAndCompletedWorkRemainProtected() {
        Task active=task(1,"09:45",60,true),done=task(2,"11:00",60,true);done.done=true;
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(active,done,task(3,"",60,true)),day,LocalTime.of(10,0),480,1080,100);
        assertNull(time(p,1));assertNull(time(p,2));assertEquals("12:00",time(p,3));
    }
    @Test public void quotaOverloadReportsWholeUnscheduledTaskWithoutShortening() {
        Task first=task(1,"",90,true),second=task(2,"",60,true);
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(first,second),day,LocalTime.of(8,0),480,720,50);
        assertEquals("08:00",time(p,1));assertEquals(Collections.singletonList(2L),p.unscheduled);assertEquals(60,second.durationMinutes);
    }
    @Test public void fragmentTooSmallDoesNotCauseOverlapOrSplit() {
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(task(1,"08:30",30,false),task(2,"09:30",30,false),task(3,"",60,true)),day,LocalTime.of(8,0),480,600,100);
        assertEquals(Collections.singletonList(3L),p.unscheduled);assertNull(time(p,3));
    }
    @Test public void priorDayCrossMidnightCommitmentBlocksToday() {
        Task yesterday=task(1,"23:30",90,false);yesterday.date=day.minusDays(1).toString();
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(yesterday,task(2,"",30,true)),day,LocalTime.MIDNIGHT,0,240,100);
        assertEquals("01:00",time(p,2));
    }
    @Test public void untimedEventBlocksWholeDayWhileUntimedFixedTaskStaysUnchanged() {
        Task allDay=task(1,"",30,false);allDay.event=true;
        Scheduler.Plan p=plan(allDay,task(2,"",30,false),task(3,"",30,true));
        assertEquals(Collections.singletonList(3L),p.unscheduled);assertTrue(p.moves.isEmpty());
    }
    @Test public void stableValidFlexibleTimesAndInputOrderDoNotChangeOutput() {
        Task a=task(2,"13:00",60,true),b=task(1,"",60,true);
        Scheduler.Plan first=plan(a,b),second=plan(b,a);
        assertNull(time(first,2));assertEquals("08:00",time(first,1));assertEquals(time(first,1),time(second,1));
    }
    @Test public void noDayWindowAfterMidnightCanWrapOrTruncate() {
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(task(1,"",60,true)),day,LocalTime.of(23,30),0,1440,100);
        assertEquals(Collections.singletonList(1L),p.unscheduled);
    }
    @Test public void localScheduleUsesSameTimesAcrossDstDay() {
        Task flex=task(1,"",60,true);flex.date="2026-03-08";
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(flex),LocalDate.of(2026,3,8),LocalTime.MIDNIGHT,120,300,100);
        assertEquals("02:00",time(p,1));
    }
    @Test public void completedFlexibleWorkCountsAgainstDailyBudget() {
        Task completed=task(1,"08:00",60,true);completed.done=true;
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(completed,task(2,"",90,true)),day,LocalTime.of(10,0),480,720,50);
        assertEquals(Collections.singletonList(2L),p.unscheduled);
    }
    @Test public void currentFlexibleBlockCannotOverrideFixedCommitment() {
        Task active=task(1,"09:00",90,true),fixed=task(2,"09:30",60,false);
        Scheduler.Plan p=Scheduler.plan(Arrays.asList(active,fixed),day,LocalTime.of(9,45),480,1080,100);
        assertEquals("10:30",time(p,1));assertNull(time(p,2));
    }
    @Test public void unrelatedDistantDatesCannotOverflowMinuteArithmetic() {
        Task distant=task(1,"10:00",60,false);distant.date="9999-12-31";
        Scheduler.Plan p=plan(distant,task(2,"",60,true));assertEquals("08:00",time(p,2));
    }
    @Test(expected=IllegalArgumentException.class) public void backwardsWindowRejected() { Scheduler.plan(Collections.emptyList(),day,LocalTime.NOON,1000,600,50); }
    @Test(expected=IllegalArgumentException.class) public void quotaOutOfRangeRejected() { Scheduler.plan(Collections.emptyList(),day,LocalTime.NOON,480,1080,101); }
}
