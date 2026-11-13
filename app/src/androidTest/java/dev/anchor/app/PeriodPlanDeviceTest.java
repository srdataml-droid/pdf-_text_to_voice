package dev.anchor.app;
import android.test.AndroidTestCase;
import java.time.*;
import java.util.*;
public class PeriodPlanDeviceTest extends AndroidTestCase {
    private AnchorDb db;private final String name="period-plan-test.db";private LocalDate first;private LocalDateTime now;
    @Override protected void setUp() throws Exception{super.setUp();getContext().deleteDatabase(name);db=new AnchorDb(getContext(),name);now=LocalDateTime.now();first=now.toLocalDate().plusDays(2);}
    @Override protected void tearDown() throws Exception{db.close();getContext().deleteDatabase(name);super.tearDown();}
    private List<Routine> items(){Routine a=new Routine();a.title="Reading";a.category="challenge";a.durationMinutes=30;Routine b=new Routine();b.title="Rest";b.durationMinutes=30;return Arrays.asList(a,b);}
    private PeriodPlan.Preview preview(){return db.previewPeriod(items(),first,first.plusDays(2),now,480,1260,60);}
    public void testBatchIsDurableCategorisedAndRepeatedSaveDoesNotDuplicate() {
        PeriodPlan.Preview p=preview();long id=db.savePeriod(p,now,480,1260,60);assertEquals(id,db.savePeriod(p,now,480,1260,60));
        db.close();db=new AnchorDb(getContext(),name);assertEquals(6,db.range(first.toString(),first.plusDays(2).toString()).size());assertEquals(2,db.routines().size());assertEquals(1,db.periodPlans().size());
        assertEquals("challenge",db.routines().get(0).category);assertTrue(db.periodPlans().get(0).canUndo);
    }
    public void testBatchUndoPersistsAndRefusesLaterChanges() {
        long id=db.savePeriod(preview(),now,480,1260,60);assertTrue(db.undoPeriod(id));assertTrue(db.range(first.toString(),first.plusDays(2).toString()).isEmpty());assertTrue(db.periodPlans().get(0).undone);
        id=db.savePeriod(preview(),now,480,1260,60);Task edited=db.tasks(first.toString()).get(0);edited.title="User changed this";db.save(edited);
        assertFalse(db.undoPeriod(id));assertEquals("User changed this",db.task(edited.id).title);
    }
    public void testStaleSettingsAndCalendarCannotBeOverwritten() {
        PeriodPlan.Preview p=preview();Task t=new Task();t.title="Protected";t.date=first.toString();t.time="08:00";db.save(t);
        try{db.savePeriod(p,now,480,1260,60);fail();}catch(IllegalArgumentException expected){}
        p=preview();try{db.savePeriod(p,now,540,1260,60);fail();}catch(IllegalArgumentException expected){}
        assertTrue(db.periodPlans().isEmpty());assertEquals(1,db.tasks(first.toString()).size());
    }
    public void testConflictInLaterRoutineRollsBackEntireBatch() {
        Routine existing=items().get(0);existing.startDate=first.toString();existing.days=3;
        db.saveRoutine(db.previewRoutine(existing,now,480,1260,60),now,480,1260,60);
        PeriodPlan.Preview p=preview();
        try{db.savePeriod(p,now,480,1260,60);fail();}catch(IllegalArgumentException expected){}
        assertEquals(1,db.routines().size());assertEquals(3,db.range(first.toString(),first.plusDays(2).toString()).size());assertTrue(db.periodPlans().isEmpty());
    }
    public void testExpiredPreviewCannotSaveAPartialPlan() {
        PeriodPlan.Preview p=preview();
        try{db.savePeriod(p,first.atTime(9,0),480,1260,60);fail();}catch(IllegalArgumentException expected){}
        assertTrue(db.routines().isEmpty());assertTrue(db.periodPlans().isEmpty());
    }
    public void testVersionFourUpgradePreservesExistingRoutineAndTask() {
        Routine r=items().get(0);r.startDate=first.toString();r.days=3;db.saveRoutine(db.previewRoutine(r,now,480,1260,60),now,480,1260,60);
        android.database.sqlite.SQLiteDatabase sql=db.getWritableDatabase();
        sql.execSQL("DROP TABLE period_plan_routines");sql.execSQL("DROP TABLE period_plans");
        sql.execSQL("CREATE TEMP TABLE keep_entries AS SELECT * FROM routine_entries");
        sql.execSQL("CREATE TEMP TABLE keep_routines AS SELECT id,title,start_date,days,weekdays,duration,reminder,stopped FROM routines");
        sql.execSQL("DROP TABLE routine_entries");sql.execSQL("DROP TABLE routines");
        sql.execSQL("CREATE TABLE routines (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,start_date TEXT NOT NULL,days INTEGER NOT NULL,weekdays INTEGER NOT NULL,duration INTEGER NOT NULL,reminder INTEGER NOT NULL,stopped INTEGER NOT NULL DEFAULT 0)");
        sql.execSQL("INSERT INTO routines SELECT * FROM keep_routines");
        sql.execSQL("CREATE TABLE routine_entries (routine_id INTEGER NOT NULL REFERENCES routines(id) ON DELETE CASCADE,task_id INTEGER NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,created_version INTEGER NOT NULL,PRIMARY KEY(routine_id,task_id))");
        sql.execSQL("INSERT INTO routine_entries SELECT * FROM keep_entries");sql.setVersion(4);db.close();db=new AnchorDb(getContext(),name);
        assertEquals(3,db.range(first.toString(),first.plusDays(2).toString()).size());assertEquals("daily",db.routines().get(0).category);assertEquals(6,db.getReadableDatabase().getVersion());
    }
}
