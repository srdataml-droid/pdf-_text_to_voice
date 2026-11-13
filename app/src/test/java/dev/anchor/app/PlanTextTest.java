package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class PlanTextTest {
    @Test public void commaBeforeMinutesDoesNotBecomeAnotherTask() {
        List<Routine> items=PlanText.suggestions("Sort these tasks: Reading, 30 minutes; Rest, 45 minutes; Hackathon work, 60 minutes");
        assertEquals(3,items.size());assertEquals("Reading",items.get(0).title);assertEquals(45,items.get(1).durationMinutes);assertEquals("daily",items.get(1).category);
    }
    @Test public void plainCommaListsRetainAllActivitiesAndDurations() {
        List<Routine> items=PlanText.suggestions("Tasks: Reading, 30 minutes, Rest, 45 minutes, Hackathon work, 1 hour");
        assertEquals(3,items.size());assertEquals("Hackathon work",items.get(2).title);assertEquals(60,items.get(2).durationMinutes);
    }
    @Test public void aDurationWithoutATaskIsRejectedForManualCorrection() {
        try{PlanText.suggestions("Tasks:\n30 minutes\nResearch");fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("task name"));}
    }
}
