package dev.anchor.app;

import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import java.time.*;
import java.util.*;

/** Real SQLite migration, persistence, transaction and stale-Undo tests. */
public class SchedulerDeviceTest extends AndroidTestCase {
    private static final String NAME="anchor-scheduler-verification.db";
    private AnchorDb db;
    private final LocalDate day=LocalDate.of(2026,10,28);
    @Override protected void setUp() throws Exception { super.setUp();getContext().deleteDatabase(NAME);db=new AnchorDb(getContext(),NAME); }
    @Override protected void tearDown() throws Exception { db.close();getContext().deleteDatabase(NAME);super.tearDown(); }
    private Task add(String title,String time,int duration,boolean flexible) { Task t=new Task();t.title=title;t.date=day.toString();t.time=time;t.durationMinutes=duration;t.flexible=flexible;db.save(t);return t; }
    private long plan() { return db.replan(day,LocalTime.of(8,0),480,1080,100,"Verification"); }
    public void testMoveReportAndUndoSurviveRestart() {
        Task t=add("Focus","",60,true);long report=plan();assertTrue(report>0);assertEquals("08:00",db.task(t.id).time);
        db.close();db=new AnchorDb(getContext(),NAME);ScheduleChange change=db.changeReports().get(0);
        assertEquals(report,change.id);assertTrue(change.canUndo);assertEquals(1,change.moves.size());
        assertTrue(db.undoReport(report));assertEquals("",db.task(t.id).time);assertTrue(db.changeReports().get(0).undone);assertFalse(db.undoReport(report));
    }
    public void testUndoRejectsEditRatherThanOverwritingIt() {
        Task t=add("Focus","",60,true);long report=plan();Task edited=db.task(t.id);edited.title="Edited title";db.save(edited);
        assertFalse(db.changeReports().get(0).canUndo);assertFalse(db.undoReport(report));assertEquals("Edited title",db.task(t.id).title);assertEquals("08:00",db.task(t.id).time);
    }
    public void testUndoDoesNotRestoreAcrossANewCommitment() {
        add("Focus","09:30",60,true);add("Meeting","09:00",60,false);long report=plan();
        add("New appointment","09:30",30,false);assertFalse(db.undoReport(report));
    }
    public void testOverloadClearsStaleTimeAndRepeatedChecksDoNotSpamReports() {
        Task t=add("Too long","09:00",180,true);long first=db.replan(day,LocalTime.of(8,0),480,600,50,"Budget changed");
        assertTrue(first>0);assertEquals("",db.task(t.id).time);assertEquals(Collections.singletonList(t.id),db.changeReports().get(0).unscheduled);
        assertEquals(0L,db.replan(day,LocalTime.of(8,5),480,600,50,"Periodic check"));assertEquals(1,db.changeReports().size());
    }
    public void testMultipleOverloadedPlacementsDoNotGenerateSecondReportAfterClearingTimes() {
        add("Later ID first","11:00",180,true);add("Earlier ID second","09:00",180,true);
        assertTrue(db.replan(day,LocalTime.of(8,0),480,600,50,"Budget changed")>0);
        assertEquals(0L,db.replan(day,LocalTime.of(8,5),480,600,50,"Periodic check"));assertEquals(1,db.changeReports().size());
    }
    public void testNoOpHasNoReportAndActiveTaskIsStable() {
        Task t=add("Working now","09:00",60,true);
        assertEquals(0L,db.replan(day,LocalTime.of(9,15),480,1080,100,"Periodic check"));assertEquals("09:00",db.task(t.id).time);assertTrue(db.changeReports().isEmpty());
    }
    public void testCrossMidnightCommitmentIsIncludedByDatabasePlanner() {
        Task fixed=add("Late meeting","23:30",90,false);fixed.date=day.minusDays(1).toString();db.save(fixed);
        Task flex=add("Focus","",30,true);db.replan(day,LocalTime.MIDNIGHT,0,240,100,"Verification");assertEquals("01:00",db.task(flex.id).time);
    }
    public void testV1MigrationKeepsCalendarChatAndMemoryProtected() {
        db.close();getContext().deleteDatabase(NAME);
        SQLiteDatabase old=getContext().openOrCreateDatabase(NAME,0,null);
        old.execSQL("CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,date TEXT NOT NULL,time TEXT NOT NULL DEFAULT '',notes TEXT NOT NULL DEFAULT '',duration INTEGER NOT NULL,reminder INTEGER NOT NULL,done INTEGER NOT NULL DEFAULT 0,event INTEGER NOT NULL DEFAULT 0,completed_date TEXT NOT NULL DEFAULT '',updated_at INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT,text TEXT NOT NULL,created_at INTEGER NOT NULL)");
        old.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,content TEXT NOT NULL,created_at INTEGER NOT NULL)");
        old.execSQL("INSERT INTO tasks VALUES (17,'Keep appointment','2026-10-28','09:00','Private',30,10,0,1,'',123)");
        old.execSQL("INSERT INTO memories VALUES (2,'Keep memory',123)");old.execSQL("INSERT INTO messages VALUES (3,'user','Keep message',123)");old.setVersion(1);old.close();
        db=new AnchorDb(getContext(),NAME);Task restored=db.task(17);assertFalse(restored.flexible);assertEquals("Private",restored.notes);assertTrue(restored.event);assertEquals("Keep memory",db.memories().get(0).text);assertEquals("Keep message",db.messages().get(0).content);assertTrue(db.changeReports().isEmpty());
        assertEquals(0L,plan());assertEquals("09:00",db.task(17).time);
    }
    public void testSettingsPersistAndRejectInvalidWindowWithoutWriting() {
        Context context=new ContextWrapper(getContext()) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return super.getSharedPreferences("verification-"+name,mode); }
        };
        PlannerSettings s=PlannerSettings.load(context);s.dayStartMinute=540;s.dayEndMinute=1020;s.flexiblePercent=35;s.automaticPlanning=false;s.save(context);
        PlannerSettings restored=PlannerSettings.load(context);assertEquals(540,restored.dayStartMinute);assertEquals(1020,restored.dayEndMinute);assertEquals(35,restored.flexiblePercent);assertFalse(restored.automaticPlanning);
        restored.dayEndMinute=100;try { restored.save(context);fail("Invalid settings must be rejected"); } catch(IllegalArgumentException expected) {}
        assertEquals(1020,PlannerSettings.load(context).dayEndMinute);
        context.getSharedPreferences("planner-settings",0).edit().clear().commit();
    }
}
