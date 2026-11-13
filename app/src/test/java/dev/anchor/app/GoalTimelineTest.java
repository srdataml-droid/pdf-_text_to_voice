package dev.anchor.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;

public class GoalTimelineTest {
    private final LocalDate first=LocalDate.of(2026,11,13);
    private Routine goal(){Routine r=new Routine();r.title="Python practice";r.startDate=first.toString();r.days=3;r.category="challenge";return r;}
    private Task session(String date,String time,boolean done){Task t=new Task();t.title="Practice";t.date=date;t.time=time;t.done=done;return t;}

    @Test public void scheduledSessionsAreNotCompletedProgress(){
        GoalTimeline.Summary s=GoalTimeline.summarize(goal(),Arrays.asList(session("2026-11-13","10:00",true),session("2026-11-14","11:00",false),session("2026-11-15","",false)),first.atTime(12,0));
        assertEquals(3,s.planned);assertEquals(1,s.completed);assertEquals(33,s.percent());
        assertEquals(LocalDate.of(2026,11,15),s.deadline);assertEquals("2026-11-14",s.next.date);assertEquals(1,s.needsRoom);
    }
    @Test public void deletingOrStoppingSessionsCannotTurnPartialWorkIntoCompletion(){
        Routine r=goal();r.stopped=true;
        GoalTimeline.Summary s=GoalTimeline.summarize(r,Arrays.asList(session("2026-11-13","10:00",true)),first.atTime(12,0));
        assertEquals(3,s.planned);assertEquals(1,s.completed);assertEquals(2,s.removed);assertEquals(33,s.percent());assertEquals("Stopped",s.status);
    }
    @Test public void pastUnfinishedWorkIsOverdueAndTheNextSessionUsesActualMovedDate(){
        Task past=session("2026-11-13","09:00",false),moved=session("2026-11-17","10:00",false);
        GoalTimeline.Summary s=GoalTimeline.summarize(goal(),Arrays.asList(moved,past),LocalDateTime.of(2026,11,16,12,0));
        assertEquals(1,s.overdue);assertEquals("Period ended",s.status);assertEquals("2026-11-17",s.next.date);
        assertFalse(moved.done);assertEquals("09:00",past.time);
    }
    @Test public void anUntimedSessionIsPendingUntilItsDayEnds(){
        Task untimed=session("2026-11-13","",false);
        assertEquals(0,GoalTimeline.summarize(goal(),Arrays.asList(untimed),first.atTime(23,59)).overdue);
        assertEquals(1,GoalTimeline.summarize(goal(),Arrays.asList(untimed),first.plusDays(1).atStartOfDay()).overdue);
    }
    @Test public void weekdayPatternDefinesTheTargetEvenWhenRowsAreMissing(){
        Routine r=goal();r.days=7;r.weekdays=31;
        GoalTimeline.Summary s=GoalTimeline.summarize(r,Collections.emptyList(),first.atTime(12,0));
        assertEquals(5,s.planned);assertEquals(5,s.removed);assertEquals(0,s.percent());assertNull(s.next);
    }
    @Test public void fullCompletionMeansAllSessionsCompleteAndNeverCountsEvents(){
        List<Task> tasks=new ArrayList<>();for(int i=0;i<3;i++)tasks.add(session(first.plusDays(i).toString(),"10:00",true));
        Task meeting=session("2026-11-16","10:00",true);meeting.event=true;tasks.add(meeting);
        GoalTimeline.Summary s=GoalTimeline.summarize(goal(),tasks,first.atTime(12,0));
        assertEquals(3,s.completed);assertEquals(100,s.percent());assertEquals("All sessions complete",s.status);
    }
    @Test public void planningDefaultsStartTodayAndCoverThirtyDays(){
        assertEquals(first,PeriodPlan.suggestedStart(first));
        assertEquals(LocalDate.of(2026,12,12),PeriodPlan.suggestedEnd(first));
    }
}
