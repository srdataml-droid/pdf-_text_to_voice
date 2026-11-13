package dev.anchor.app;

import android.content.Context;
import android.test.AndroidTestCase;
import java.time.*;
import java.util.*;

/** Real SQLite tests on a disposable test database; never touches the person's calendar. */
public class PlannerDeviceTest extends AndroidTestCase {
    private AnchorDb db;
    private static final String TEST_DB="anchor-verification.db";
    @Override protected void setUp() throws Exception { super.setUp(); getContext().deleteDatabase(TEST_DB); db=new AnchorDb(getContext(),TEST_DB); }
    @Override protected void tearDown() throws Exception { db.close(); getContext().deleteDatabase(TEST_DB); super.tearDown(); }
    private Task task(String title,String date,String time) { Task t=new Task();t.title=title;t.date=date;t.time=time;return t; }
    public void testEmptyFirstLaunch() { assertTrue(db.tasks(DateRules.today()).isEmpty());assertTrue(db.memories().isEmpty());assertTrue(db.messages().isEmpty()); }
    public void testRestartPreservesCalendar() {
        Task t=task("Focus","2026-12-31","23:30");t.notes="Private notes";long id=db.save(t);db.close();db=new AnchorDb(getContext(),TEST_DB);
        assertEquals("Focus",db.task(id).title);assertEquals("Private notes",db.task(id).notes);assertEquals(1,db.tasks("2026-12-31").size());assertEquals(0,db.tasks("2027-01-01").size());
    }
    public void testLocalCompletionAndUndo() {
        Task t=task("Earlier task","2026-01-01","09:00");long id=db.save(t);db.complete(id,true);
        assertEquals(DateRules.today(),db.task(id).completedDate);assertEquals(Integer.valueOf(1),db.completionCounts(DateRules.today(),DateRules.today()).get(DateRules.today()));
        db.complete(id,false);assertFalse(db.task(id).done);assertTrue(db.completionCounts(DateRules.today(),DateRules.today()).isEmpty());
    }
    public void testEventsDoNotInflateConsistency() { Task t=task("Appointment",DateRules.today(),"10:00");t.event=true;long id=db.save(t);db.complete(id,true);assertFalse(db.task(id).done);assertTrue(db.completionCounts(DateRules.today(),DateRules.today()).isEmpty()); }
    public void testEditingPreservesCompletion() { Task t=task("Task",DateRules.today(),"11:00");long id=db.save(t);Task stale=db.task(id);db.complete(id,true);stale.title="Renamed";db.save(stale);assertTrue(db.task(id).done); }
    public void testRepeatedCompletionDoesNotMoveHistory() {
        long id=db.save(task("Done yesterday",DateRules.today(),""));db.complete(id,true);
        String yesterday=LocalDate.now().minusDays(1).toString();
        android.content.ContentValues v=new android.content.ContentValues();v.put("completed_date",yesterday);
        db.getWritableDatabase().update("tasks",v,"id=?",new String[]{Long.toString(id)});
        db.complete(id,true);assertEquals(yesterday,db.task(id).completedDate);
    }
    public void testDeletionRemovesCalendarEntry() { long id=db.save(task("Delete",DateRules.today(),""));db.delete(id);assertNull(db.task(id));assertTrue(db.tasks(DateRules.today()).isEmpty()); }
    public void testMemoryAndChatAreExplicitAndRemovable() {
        long id=db.remember("I focus best in the morning.");db.message("user","Hello");db.message("assistant","Hello back");db.close();db=new AnchorDb(getContext(),TEST_DB);
        assertEquals(1,db.memories().size());assertEquals(2,db.messages().size());assertEquals("user",db.messages().get(0).role);
        db.forget(id);db.clearMessages();assertTrue(db.memories().isEmpty());assertTrue(db.messages().isEmpty());
    }
    public void testUserTextCannotChangeSchema() { long id=db.save(task("Robert'); DROP TABLE tasks;--",DateRules.today(),""));assertEquals("Robert'); DROP TABLE tasks;--",db.task(id).title);assertEquals(1,db.tasks(DateRules.today()).size()); }
    public void testReminderRejectsStaleDeletedCompletedEntries() {
        Task t=task("Remind",LocalDate.now().plusDays(1).toString(),"10:00");t.reminderMinutes=0;long id=db.save(t);long oldVersion=t.updatedAt;long at=DateRules.reminderAt(t,ZoneId.systemDefault());
        assertTrue(Reminders.shouldDeliver(db.task(id),oldVersion,at));t.title="Edited";db.save(t);assertFalse(Reminders.shouldDeliver(db.task(id),oldVersion,at));
        long version=t.updatedAt;db.complete(id,true);assertFalse(Reminders.shouldDeliver(db.task(id),version,at));db.delete(id);assertFalse(Reminders.shouldDeliver(db.task(id),version,at));
    }
    public void testTaskSortByDayAndTime() {
        db.save(task("Later",DateRules.today(),"16:00"));db.save(task("Any time",DateRules.today(),""));db.save(task("Earlier",DateRules.today(),"09:00"));
        List<Task> tasks=db.tasks(DateRules.today());assertEquals("Any time",tasks.get(0).title);assertEquals("Earlier",tasks.get(1).title);assertEquals("Later",tasks.get(2).title);
    }
    public void testUnrelatedChangesKeepDeliveredReminder() throws Exception {
        Context isolated=new android.content.ContextWrapper(getContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) { return super.getSharedPreferences("test-"+name,mode); }
        };
        isolated.getSharedPreferences("reminders",0).edit().clear().commit();
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO sqlite_sequence(name,seq) VALUES ('tasks',900000000)");
        Task t=task("Delivered test reminder","2000-01-01","10:00");t.reminderMinutes=0;db.save(t);
        android.app.NotificationManager manager=isolated.getSystemService(android.app.NotificationManager.class);
        try {
            Reminders.reconcile(isolated,db);
            manager.notify((int)t.id,new android.app.Notification.Builder(isolated,Reminders.CHANNEL).setSmallIcon(R.drawable.ic_anchor).setContentTitle("Anchor verification").build());
            Thread.sleep(150);
            assertTrue(hasNotification(manager,(int)t.id));
            db.save(task("Unrelated",DateRules.today(),""));Reminders.reconcile(isolated,db);
            assertTrue("Unrelated changes must keep an unread reminder",hasNotification(manager,(int)t.id));
            db.complete(t.id,true);Reminders.reconcile(isolated,db);
            assertFalse("Completed task must leave reminder tracking",RingingAlarms.preferences(isolated).getStringSet("scheduled",java.util.Collections.emptySet()).contains(Long.toString(t.id)));
            // NotificationManager cancellation is dispatched by the system service asynchronously.
            long deadline=android.os.SystemClock.elapsedRealtime()+2000;
            while(hasNotification(manager,(int)t.id)&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);
            assertFalse("Completing the entry dismisses its reminder within 2 seconds",hasNotification(manager,(int)t.id));
        } finally { Reminders.cancel(isolated,t.id);isolated.getSharedPreferences("reminders",0).edit().clear().commit(); }
    }
    private boolean hasNotification(android.app.NotificationManager manager,int id) {
        for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications()) if(n.getId()==id)return true;
        return false;
    }
}
