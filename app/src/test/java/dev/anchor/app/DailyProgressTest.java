package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
public class DailyProgressTest {
    private final LocalDateTime now=LocalDateTime.of(2026,10,29,12,0);
    private Task task(String date,String time,boolean done) {Task t=new Task();t.title="Task";t.date=date;t.time=time;t.done=done;t.durationMinutes=60;return t;}
    @Test public void eachTaskContributesOneShareOfGreenRedOrNeutral() {
        List<Task> ts=Arrays.asList(task("2026-10-29","08:00",true),task("2026-10-29","10:00",false),task("2026-10-29","15:00",false),task("2026-10-29","",false));
        DailyProgress.Day d=DailyProgress.forDay(ts,now.toLocalDate(),now);
        assertEquals(4,d.total);assertEquals(1,d.completed);assertEquals(1,d.missed);assertEquals(2,d.pending);
        assertEquals(.25f,d.greenFraction(),.001f);assertEquals(.25f,d.redFraction(),.001f);
    }
    @Test public void runningAndFutureTasksAreNotMissedAndEventsDoNotCount() {
        Task event=task("2026-10-29","08:00",false);event.event=true;
        DailyProgress.Day d=DailyProgress.forDay(Arrays.asList(event,task("2026-10-29","11:30",false)),now.toLocalDate(),now);
        assertEquals(1,d.total);assertEquals(0,d.missed);
        assertEquals(0,DailyProgress.forDay(Arrays.asList(task("2026-10-30","08:00",false)),now.toLocalDate().plusDays(1),now).missed);
    }
    @Test public void lateCompletionReplacesRedAndEmptyDaysHaveNoInventedSuccess() {
        Task t=task("2026-10-28","",false);
        assertEquals(1,DailyProgress.forDay(Arrays.asList(t),LocalDate.parse(t.date),now).missed);
        t.done=true;
        assertEquals(1f,DailyProgress.forDay(Arrays.asList(t),LocalDate.parse(t.date),now).greenFraction(),.001f);
        assertEquals(0f,DailyProgress.forDay(Collections.emptyList(),now.toLocalDate(),now).greenFraction(),.001f);
    }
    @Test public void overnightTaskIsNotMissedUntilItsActualEnd() {
        Task t=task("2026-10-28","23:30",false);
        assertEquals(0,DailyProgress.forDay(Arrays.asList(t),LocalDate.parse(t.date),now.withHour(0).withMinute(10)).missed);
        assertEquals(1,DailyProgress.forDay(Arrays.asList(t),LocalDate.parse(t.date),now.withHour(0).withMinute(30)).missed);
    }
}
