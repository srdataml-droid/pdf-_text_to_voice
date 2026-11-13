package dev.anchor.app;
import org.junit.Test;
import java.time.*;
import static org.junit.Assert.*;
public class ChatScheduleRequestTest {
    @org.junit.Test public void recurringAndUnsupportedDatesNeverBecomeASingleWrongTask() {
        java.time.LocalDateTime now=java.time.LocalDateTime.of(2026,10,29,7,0);
        assertNull(ChatScheduleRequest.resolve("Schedule reading for 60 days at 8am",now));
        assertTrue(ChatScheduleRequest.isRoutineRequest("Schedule reading for 60 days at 8am"));
        assertFalse(ChatScheduleRequest.isRoutineRequest("Schedule reading for 60 minutes at 8am"));
        assertNull(ChatScheduleRequest.resolve("Schedule reading on 12/11 at 8am",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading the day after tomorrow at 8am",now));
        assertEquals(90,ChatScheduleRequest.resolve("Schedule reading for 90m at 8am",now).durationMinutes);
        assertNull(ChatScheduleRequest.resolve("Schedule reading for several hours at 8am",now));
    }
    @org.junit.Test public void explicitWeekdaysCannotBeSilentlyReplacedWithToday() {
        java.time.LocalDateTime now=java.time.LocalDateTime.of(2026,10,29,7,0);
        assertEquals("2026-11-02",ChatScheduleRequest.resolve("Schedule reading next Monday at 8am",now).date);
        assertNull(ChatScheduleRequest.resolve("Schedule reading on December 10 at 8am",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading in two weeks at 8am",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading for one hour at 8am",now));
        assertNull(ChatScheduleRequest.resolve("Create a poem about reading at 8am",now));
        assertFalse(ChatScheduleRequest.isCreationRequest("Create a poem about reading at 8am"));
        assertNull(ChatScheduleRequest.resolve("Schedule reading for 1 hour 30 minutes tomorrow at 8am",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading at 8am or 9am tomorrow",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading at 8:00 UTC tomorrow",now));
    }
    private final LocalDateTime now=LocalDateTime.of(2026,10,29,3,0);
    @Test public void reportedReadingRequestCreatesARealToolRequest() {
        Task t=ChatScheduleRequest.resolve("Can you create a read time schedule for me by 8am",now);
        assertNotNull(t);assertEquals("Reading",t.title);assertEquals("08:00",t.time);assertEquals("2026-10-29",t.date);
        assertEquals(30,t.durationMinutes);assertTrue(t.notes.contains("30-minute"));assertFalse(t.flexible);
    }
    @Test public void passedUnspecifiedTimeUsesNextOccurrence() {
        Task t=ChatScheduleRequest.resolve("Schedule reading at 8am",now.withHour(9));assertEquals("2026-10-30",t.date);
    }
    @Test public void explicitDurationAndTomorrowAreRespected() {
        Task t=ChatScheduleRequest.resolve("Add a flexible 45-minute task called Study check tomorrow at 9:15pm",now);
        assertEquals("Study check",t.title);assertEquals("21:15",t.time);assertEquals(45,t.durationMinutes);assertTrue(t.flexible);assertEquals("2026-10-30",t.date);
    }
    @Test public void twelveAmAndPmAreCorrect() {
        assertEquals("00:00",ChatScheduleRequest.resolve("Schedule reading tomorrow at 12am",now).time);
        assertEquals("12:00",ChatScheduleRequest.resolve("Schedule reading at 12pm",now).time);
    }
    @Test public void noImplicitActionForQuestionsNegationOrMultipleEntries() {
        for(String s:new String[]{"How do I create a schedule at 8am?","Do not schedule reading at 8am","What would a reading schedule at 8am look like?","Schedule reading at 8am and coding at 9am","Here is an example: schedule reading at 8am"})
            assertNull(s,ChatScheduleRequest.resolve(s,now));
    }
    @Test public void unsupportedOrAmbiguousInputIsNotSilentlyInvented() {
        assertNull(ChatScheduleRequest.resolve("Create a schedule",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading at 25:00",now));
        assertNull(ChatScheduleRequest.resolve("Schedule reading for -5 minutes at 8am",now));
    }
}
