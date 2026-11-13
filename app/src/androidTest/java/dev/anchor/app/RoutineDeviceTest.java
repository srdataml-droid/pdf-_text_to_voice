package dev.anchor.app;
import android.test.AndroidTestCase;
import java.time.*;
public class RoutineDeviceTest extends AndroidTestCase {
    private AnchorDb db;
    private final String name="routine-verification.db";
    private LocalDateTime now;
    @Override protected void setUp() throws Exception {super.setUp();getContext().deleteDatabase(name);db=new AnchorDb(getContext(),name);now=LocalDateTime.now();}
    @Override protected void tearDown() throws Exception {db.close();getContext().deleteDatabase(name);super.tearDown();}
    private Routine routine(){Routine r=new Routine();r.title="Reading routine";r.startDate=now.toLocalDate().plusDays(2).toString();r.days=3;return r;}
    private RoutinePlanner.Preview preview(){return db.previewRoutine(routine(),now,480,1260,60);}
    public void testSavePersistsRealTasksAndRepeatedSaveIsIdempotent() {
        RoutinePlanner.Preview p=preview();long id=db.saveRoutine(p,now,480,1260,60);
        assertEquals(id,db.saveRoutine(p,now,480,1260,60));db.close();db=new AnchorDb(getContext(),name);
        assertEquals(1,db.routines().size());assertEquals(3,db.range(routine().startDate,now.toLocalDate().plusDays(5).toString()).size());
        Task t=db.tasks(routine().startDate).get(0);assertEquals("08:00",t.time);assertEquals(0,t.reminderMinutes);assertTrue(t.flexible);
    }
    public void testStaleCalendarOrSettingsPreviewCannotBeSaved() {
        RoutinePlanner.Preview p=preview();Task fixed=new Task();fixed.title="New commitment";fixed.date=routine().startDate;fixed.time="08:00";db.save(fixed);
        try{db.saveRoutine(p,now,480,1260,60);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("preview"));}
        p=preview();try{db.saveRoutine(p,now,540,1260,60);fail();}catch(IllegalArgumentException expected){}
        assertTrue(db.routines().isEmpty());assertEquals(1,db.tasks(fixed.date).size());
    }
    public void testStopPreservesCompletedAndManuallyEditedSessions() {
        long id=db.saveRoutine(preview(),now,480,1260,60);
        java.util.List<Task> tasks=db.range(routine().startDate,now.toLocalDate().plusDays(5).toString());
        db.complete(tasks.get(0).id,true);Task edit=tasks.get(1);edit.title="Adjusted by user";db.save(edit);
        assertEquals(1,db.stopRoutine(id,now));assertNotNull(db.task(tasks.get(0).id));assertNotNull(db.task(edit.id));assertNull(db.task(tasks.get(2).id));
        assertTrue(db.routines().get(0).stopped);assertEquals(0,db.stopRoutine(id,now));
    }
    public void testNoRoomStillCreatesVisibleUntimedTask() {
        Routine r=routine();r.days=1;Task allDay=new Task();allDay.title="Away all day";allDay.date=r.startDate;allDay.event=true;db.save(allDay);
        RoutinePlanner.Preview p=db.previewRoutine(r,now,480,1260,60);assertEquals(1,p.needsRoom);db.saveRoutine(p,now,480,1260,60);
        Task session=db.tasks(r.startDate).get(1);assertEquals("",session.time);assertEquals(-1,DateRules.reminderAt(session,ZoneId.systemDefault()));
        assertEquals("Keep the requested reminder until a time becomes available",0,session.reminderMinutes);
        db.delete(allDay.id);db.replan(LocalDate.parse(r.startDate),LocalTime.MIDNIGHT,480,1260,60,"Room available");
        assertTrue(DateRules.reminderAt(db.task(session.id),ZoneId.systemDefault())>0);
    }
    public void testVersionThreeUpgradeKeepsTasksAndChat() {
        Task t=new Task();t.title="Before upgrade";t.date=routine().startDate;db.save(t);db.message("user","Saved chat");
        android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();sql.execSQL("DROP TABLE period_plan_routines");sql.execSQL("DROP TABLE period_plans");sql.execSQL("DROP TABLE routine_entries");sql.execSQL("DROP TABLE routines");sql.setVersion(3);db.close();db=new AnchorDb(getContext(),name);
        assertEquals(t.title,db.task(t.id).title);assertEquals("Saved chat",db.messages().get(0).content);assertTrue(db.routines().isEmpty());assertEquals(6,db.getReadableDatabase().getVersion());
    }
    public void testAutomaticallyMovedSessionsCanStillBeStopped() {
        long id=db.saveRoutine(preview(),now,480,1260,60);
        Task fixed=new Task();fixed.title="New protected meeting";fixed.date=routine().startDate;fixed.time="08:00";fixed.durationMinutes=60;db.save(fixed);
        db.replan(LocalDate.parse(fixed.date),LocalTime.MIDNIGHT,480,1260,60,"Calendar changed");
        assertEquals(3,db.stopRoutine(id,now));assertEquals(1,db.tasks(fixed.date).size());assertEquals(fixed.id,db.tasks(fixed.date).get(0).id);
    }
    public void testPreviewCannotSaveElapsedTimesOrDuplicateExistingRoutine() {
        RoutinePlanner.Preview p=preview();
        try{db.saveRoutine(p,LocalDate.parse(routine().startDate).atTime(8,1),480,1260,60);fail();}catch(IllegalArgumentException expected){}
        db.saveRoutine(p,now,480,1260,60);
        try{db.saveRoutine(preview(),now,480,1260,60);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("exists"));}
        assertEquals(1,db.routines().size());
    }
}
