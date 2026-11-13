package dev.anchor.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.time.*;
public class MoveRequestTest {
    private final LocalDateTime now=LocalDateTime.of(2026,10,29,12,0);
    @Test public void ordinaryDiscussionCannotGrantMoveIntent(){assertNull(MoveRequest.parse("Explain how to move reading tomorrow",now));assertNull(MoveRequest.parse("Don't move reading",now));}
    @Test public void exactIdAndTimeHaveAnExplicitDestination(){MoveRequest r=MoveRequest.parse("Move #12 to tomorrow at 2pm",now);assertEquals(12,r.id);assertEquals("2026-10-30",r.date);assertEquals("14:00",r.time);}
    @Test public void titleMatchesNeedSelectionAndEveningIsAnEditableSuggestion(){MoveRequest r=MoveRequest.parse("Could you reschedule reading to the evening",now);assertEquals("reading",r.query);assertEquals("17:00",r.time);assertEquals("",r.date);}
    @Test public void unsupportedDatesAndAlternativesAreNotSilentlyApplied(){MoveRequest r=MoveRequest.parse("Move reading to December 12 at 2pm",now);assertEquals("",r.date);assertEquals("",r.time);assertTrue(r.needsDetails);}
}
