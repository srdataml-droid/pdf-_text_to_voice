package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
import java.util.*;
public class PeriodPlanTest {
    private final LocalDate first=LocalDate.of(2026,11,1),last=LocalDate.of(2026,12,31);
    private final LocalDateTime now=LocalDateTime.of(2026,10,29,12,0);
    private Routine item(String title,String category,int minutes){Routine r=new Routine();r.title=title;r.category=category;r.durationMinutes=minutes;return r;}
    @Test public void novemberThroughDecemberIncludesSixtyOneDays() {
        PeriodPlan.Preview p=PeriodPlan.preview(Arrays.asList(item("Reading","challenge",30)),first,last,Collections.emptyList(),now,480,1260,60,1);
        assertEquals(61,p.taskCount);assertEquals("2026-12-31",p.routines.get(0).tasks.get(60).date);
    }
    @Test public void essentialsGetRoomBeforeChallengesAndShareOneBudget() {
        PeriodPlan.Preview p=PeriodPlan.preview(Arrays.asList(item("Research","challenge",60),item("Daily rest","daily",45)),first,first,Collections.emptyList(),now,480,600,75,1);
        assertEquals("Daily rest",p.routines.get(0).routine.title);assertEquals("08:00",p.routines.get(0).tasks.get(0).time);
        assertEquals("",p.routines.get(1).tasks.get(0).time);assertEquals(1,p.needsRoom);
    }
    @Test public void laterItemsCannotOverlapEarlierItemsOrExistingCommitments() {
        Task event=new Task();event.title="Protected";event.date=first.toString();event.time="08:00";event.durationMinutes=60;
        PeriodPlan.Preview p=PeriodPlan.preview(Arrays.asList(item("Rest","daily",30),item("Reading","challenge",30)),first,first,Arrays.asList(event),now,480,1260,60,1);
        assertEquals("09:00",p.routines.get(0).tasks.get(0).time);assertEquals("09:30",p.routines.get(1).tasks.get(0).time);assertEquals("08:00",event.time);
    }
    @Test public void snapshotsDoNotChangeWhenInputOrExposedTaskObjectsChange() {
        Routine r=item("Reading","challenge",30);PeriodPlan.Preview p=PeriodPlan.preview(Arrays.asList(r),first,first,Collections.emptyList(),now,480,1260,60,1);
        r.title="Changed outside";p.definitions().get(0).title="Changed copy";
        assertEquals("Reading",p.definitions().get(0).title);
    }
    @Test public void rejectsInvalidWindowsCategoriesAndDuplicates() {
        for(List<Routine> items:Arrays.asList(Collections.<Routine>emptyList(),Arrays.asList(item("Reading","unknown",30)),Arrays.asList(item("Reading","daily",30),item("reading","challenge",30)))) {
            try{PeriodPlan.preview(items,first,last,Collections.emptyList(),now,480,1260,60,1);fail();}catch(IllegalArgumentException expected){}
        }
        try{PeriodPlan.preview(Arrays.asList(item("A","daily",30)),first,first.plusDays(90),Collections.emptyList(),now,480,1260,60,1);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void listIntentDoesNotCaptureOrdinaryChatOrASingleRoutine() {
        assertTrue(PlanText.isPlanRequest("Organize these tasks: reading, rest, research"));
        assertTrue(PlanText.isPlanRequest("Plan my November to December routines"));
        assertFalse(PlanText.isPlanRequest("Can you create a read time schedule for me by 8am"));
        assertFalse(PlanText.isPlanRequest("Why do people plan their lives?"));
        List<Routine> items=PlanText.suggestions("Plan these tasks:\nRead about health\nWash dishes\nHackathon work");
        assertEquals(3,items.size());assertEquals("daily",items.get(1).category);assertEquals("challenge",items.get(2).category);
    }
}
