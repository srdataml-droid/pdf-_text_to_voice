package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
public class RoutinePlannerTest {
    private final LocalDateTime now=LocalDateTime.of(2026,10,29,7,0);
    private Routine routine(int days) {Routine r=new Routine();r.title="Read a book";r.startDate="2026-10-29";r.days=days;r.durationMinutes=60;return r;}
    private Task task(String date,String time,int minutes,boolean flexible) {Task t=new Task();t.title="Existing";t.date=date;t.time=time;t.durationMinutes=minutes;t.flexible=flexible;return t;}
    @Test public void createsNinetyDistinctDailySessionsWithReminders() {
        RoutinePlanner.Preview p=RoutinePlanner.plan(routine(90),Collections.emptyList(),now,480,1260,60,7);
        assertEquals(90,p.tasks.size());assertEquals("2027-01-26",p.tasks.get(89).date);
        assertEquals("08:00",p.tasks.get(0).time);assertTrue(p.tasks.get(0).flexible);assertEquals(0,p.tasks.get(0).reminderMinutes);assertEquals(0,p.needsRoom);
    }
    @Test public void selectedWeekdaysAndFirstDateAreRespected() {
        Routine r=routine(7);r.weekdays=1|4|16; // Monday, Wednesday, Friday
        RoutinePlanner.Preview p=RoutinePlanner.plan(r,Collections.emptyList(),now,480,1260,60,0);
        assertEquals(3,p.tasks.size());assertEquals("2026-10-30",p.tasks.get(0).date);assertEquals("2026-11-04",p.tasks.get(2).date);
    }
    @Test public void protectsExistingFlexibleAndOvernightEntries() {
        List<Task> existing=Arrays.asList(task("2026-10-28","23:00",600,false),task("2026-10-29","09:00",60,true));
        RoutinePlanner.Preview p=RoutinePlanner.plan(routine(1),existing,now,480,1260,60,0);
        assertEquals("10:00",p.tasks.get(0).time);assertEquals("09:00",existing.get(1).time);
    }
    @Test public void allDayOrBudgetOverloadStaysVisibleWithoutFalseTime() {
        Task event=task("2026-10-29","",60,false);event.event=true;
        RoutinePlanner.Preview p=RoutinePlanner.plan(routine(1),Arrays.asList(event),now,480,600,50,0);
        assertEquals(1,p.needsRoom);assertEquals("",p.tasks.get(0).time);
        Task flex=task("2026-10-29","08:00",30,true);
        assertEquals(1,RoutinePlanner.plan(routine(1),Arrays.asList(flex),now,480,600,50,0).needsRoom);
    }
    @Test public void todayNeverUsesPastMinutesAndPreviewIsRepeatable() {
        LocalDateTime later=now.withHour(10).withMinute(15).withSecond(2);
        assertEquals("10:16",RoutinePlanner.plan(routine(1),Collections.emptyList(),later,480,1260,60,0).tasks.get(0).time);
        Routine r=routine(1);r.days=91;
        try {RoutinePlanner.plan(r,Collections.emptyList(),now,480,1260,60,0);fail();}catch(IllegalArgumentException expected){}
        r.days=1;r.weekdays=0;
        try {RoutinePlanner.plan(r,Collections.emptyList(),now,480,1260,60,0);fail();}catch(IllegalArgumentException expected){}
    }
}
