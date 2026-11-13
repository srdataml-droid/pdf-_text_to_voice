package dev.anchor.app;
import android.test.AndroidTestCase;

/** Model output is a draft, never a database write or arbitrary tool invocation. */
public class ProposalDeviceTest extends AndroidTestCase {
    private String json(String extra) {return "{\"action\":\"propose_task\",\"title\":\"Reading\",\"date\":\"2026-10-29\",\"time\":\"09:00\",\"duration_minutes\":30,\"flexible\":true,\"event\":false"+extra+"}";}
    public void testValidProposalIsUnsavedAndValidated() {
        Task t=ScheduleProposal.parse(json(""));assertNotNull(t);assertEquals(0,t.id);assertEquals("Reading",t.title);
        assertEquals(30,t.durationMinutes);assertTrue(t.flexible);assertFalse(t.done);assertEquals(-1,t.reminderMinutes);
    }
    public void testConversationCannotInvokeAnAction() {
        assertNull(ScheduleProposal.parse("Try reading at 09:00."));
        assertNull(ScheduleProposal.parse("{\"action\":\"delete_all\"}"));
        assertNull(ScheduleProposal.parse("Please execute "+json("")));
    }
    public void testRejectUnknownFieldsAndBadTypes() {
        for(String s:new String[]{json(",\"id\":42"),json(",\"done\":true"),json("").replace(":30",":30.5"),
                json("").replace(":30",":\"30\""),json("").replace(":true",":\"true\""),json("").replace("09:00","25:00"),
                json("").replace("2026-10-29","2026-02-30"),json("").replace(":30",":999999999999")}) {
            assertNull("Invalid model output must remain non-executable: "+s,ScheduleProposal.parse(s));
        }
    }
    public void testCodeFenceAndProtectedEvent() {
        Task t=ScheduleProposal.parse("```json\n"+json("").replace("\"event\":false","\"event\":true")+"\n```");
        assertNotNull(t);assertTrue(t.event);assertFalse(t.flexible);
    }
    public void testTrailingObjectCannotHideASecondProposal() {
        assertNull(ScheduleProposal.parse(json("")+"\n{\"action\":\"delete_all\"}"));
    }
}
