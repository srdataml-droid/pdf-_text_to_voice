package dev.anchor.app;
import android.test.AndroidTestCase;
import java.time.*;
public class AgentRescheduleDeviceTest extends AndroidTestCase {
    private AnchorDb db;private LocalDateTime now;private final String name="agent-move-test.db";
    @Override protected void setUp()throws Exception{super.setUp();getContext().deleteDatabase(name);db=new AnchorDb(getContext(),name);now=LocalDateTime.now();}
    @Override protected void tearDown()throws Exception{db.close();getContext().deleteDatabase(name);super.tearDown();}
    private Task task(){Task t=new Task();t.title="Reading";t.date=now.toLocalDate().plusDays(2).toString();t.time="08:00";t.flexible=true;t.reminderMinutes=0;db.save(t);return t;}
    public void testMoveAndUndoSurviveRestart(){Task t=task();long report=db.requestMove(t.id,t.updatedAt,t.date,"17:00",now);db.close();db=new AnchorDb(getContext(),name);assertEquals("17:00",db.task(t.id).time);assertTrue(db.changeReports().get(0).canUndo);assertTrue(db.undoReport(report));assertEquals("08:00",db.task(t.id).time);}
    public void testStaleOrCompletedOrProtectedSessionsCannotMove(){Task t=task();Task changed=db.task(t.id);changed.title="New title";db.save(changed);try{db.requestMove(t.id,t.updatedAt,t.date,"17:00",now);fail();}catch(IllegalArgumentException expected){}
        t=db.task(t.id);t.flexible=false;db.save(t);try{db.requestMove(t.id,t.updatedAt,t.date,"17:00",now);fail();}catch(IllegalArgumentException expected){}
        t.flexible=true;db.save(t);db.complete(t.id,true);t=db.task(t.id);try{db.requestMove(t.id,t.updatedAt,t.date,"17:00",now);fail();}catch(IllegalArgumentException expected){}
        assertEquals("08:00",db.task(t.id).time);assertTrue(db.changeReports().isEmpty());}
    public void testOverlapAndAllDayEventsBlockMoveWithoutReports(){Task t=task();Task fixed=new Task();fixed.title="Protected event";fixed.date=t.date;fixed.time="17:00";fixed.durationMinutes=60;fixed.event=true;db.save(fixed);
        try{db.requestMove(t.id,t.updatedAt,t.date,"17:30",now);fail();}catch(IllegalArgumentException expected){}
        fixed.time="";db.save(fixed);try{db.requestMove(t.id,t.updatedAt,t.date,"19:00",now);fail();}catch(IllegalArgumentException expected){}
        assertEquals("08:00",db.task(t.id).time);assertTrue(db.changeReports().isEmpty());}
}
