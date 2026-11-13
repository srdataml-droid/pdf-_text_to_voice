package dev.anchor.app;

import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.test.AndroidTestCase;
import java.io.File;
import java.time.LocalDate;

/** Disposable database and preferences. These tests never play audio or edit the person's entries. */
public class AlarmDeviceTest extends AndroidTestCase {
    private Context isolated;
    private AnchorDb db;
    @Override protected void setUp() throws Exception {
        super.setUp();
        isolated=new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return super.getSharedPreferences("alarm-verification-"+name,mode); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) {
                return super.openOrCreateDatabase("alarm-verification-"+name,mode,factory,handler);
            }
            @Override public File getDatabasePath(String name) { return super.getDatabasePath("alarm-verification-"+name); }
            @Override public boolean deleteDatabase(String name) { return super.deleteDatabase("alarm-verification-"+name); }
        };
        isolated.deleteDatabase("anchor.db");RingingAlarms.preferences(isolated).edit().clear().commit();
        db=new AnchorDb(isolated);
        db.getWritableDatabase().execSQL("INSERT OR REPLACE INTO sqlite_sequence(name,seq) VALUES ('tasks',910000000)");
    }
    @Override protected void tearDown() throws Exception {
        for(Task t:db.range("0001-01-01","9999-12-31")) Reminders.cancel(isolated,t.id);
        db.close();isolated.deleteDatabase("anchor.db");RingingAlarms.preferences(isolated).edit().clear().commit();super.tearDown();
    }
    private Task entry(boolean event) {
        Task t=new Task();t.title="Alarm verification";t.date=LocalDate.now().plusDays(1).toString();
        t.time="12:00";t.reminderMinutes=0;t.event=event;db.save(t);Reminders.reconcile(isolated,db);return t;
    }
    private Intent pending(Task t) {
        SharedPreferences p=RingingAlarms.preferences(isolated);
        return RingingAlarms.occurrenceIntent(isolated,t.id,t.updatedAt,p.getLong("base"+t.id,-1),
            p.getLong("due"+t.id,-1),RingingAlarms.token(isolated,t.id),true);
    }
    public void testRingingRequiresExplicitOptIn() { assertFalse(RingingAlarms.ringingEnabled(isolated)); }
    public void testDuplicateBroadcastIsConsumedOnce() {
        Task t=entry(false);Intent due=pending(t);long now=due.getLongExtra("due",-1);
        assertNotNull(RingingAlarms.consume(isolated,db,due,now));
        assertNull(RingingAlarms.consume(isolated,db,due,now));
    }
    public void testSnoozePersistsWithoutMovingProtectedEvent() {
        Task t=entry(true);Intent original=pending(t);
        assertNotNull(RingingAlarms.consume(isolated,db,original,original.getLongExtra("due",-1)));
        long before=System.currentTimeMillis();RingingAlarms.snooze(isolated,t.id,5);
        SharedPreferences p=RingingAlarms.preferences(isolated);long snoozed=p.getLong("due"+t.id,-1);
        assertTrue(snoozed>=before+300_000 && snoozed<=System.currentTimeMillis()+300_000);
        Task saved=db.task(t.id);assertEquals(t.date,saved.date);assertEquals(t.time,saved.time);assertEquals(t.updatedAt,saved.updatedAt);
        assertTrue(saved.event);db.close();db=new AnchorDb(isolated);Reminders.reconcile(isolated,db);
        assertEquals(snoozed,p.getLong("due"+t.id,-1));assertEquals("pending",p.getString("state"+t.id,""));
        assertNull(RingingAlarms.consume(isolated,db,original,snoozed));
    }
    public void testOldNotificationCannotDismissNewSnoozedOccurrence() {
        Task t=entry(false);Intent original=pending(t);
        RingingAlarms.consume(isolated,db,original,original.getLongExtra("due",-1));
        long oldToken=RingingAlarms.token(isolated,t.id);RingingAlarms.snooze(isolated,t.id,10);
        Intent next=pending(t);assertNotNull(RingingAlarms.consume(isolated,db,next,next.getLongExtra("due",-1)));
        RingingAlarms.handleAction(isolated,new Intent(RingingAlarms.ACTION_DISMISS).putExtra("id",t.id).putExtra("token",oldToken));
        assertEquals("delivered",RingingAlarms.preferences(isolated).getString("state"+t.id,""));
        RingingAlarms.dismiss(isolated,t.id);Reminders.reconcile(isolated,db);
        assertEquals("dismissed",RingingAlarms.preferences(isolated).getString("state"+t.id,""));
    }
    public void testClockCorrectionPreservesRemainingSnooze() {
        Task t=entry(false);Intent original=pending(t);
        RingingAlarms.consume(isolated,db,original,original.getLongExtra("due",-1));RingingAlarms.snooze(isolated,t.id,5);
        SharedPreferences p=RingingAlarms.preferences(isolated);long token=RingingAlarms.token(isolated,t.id);
        // Simulate persisted RTC values made stale by a clock/zone change; elapsed time stays authoritative.
        p.edit().putLong("due"+t.id,System.currentTimeMillis()+3_900_000).putLong("base"+t.id,1).commit();
        Reminders.reconcile(isolated,db);long due=p.getLong("due"+t.id,-1);
        assertTrue(due>System.currentTimeMillis()+290_000 && due<=System.currentTimeMillis()+300_000);
        assertTrue(RingingAlarms.token(isolated,t.id)!=token);assertEquals(t.updatedAt,db.task(t.id).updatedAt);
    }
    public void testEditCompleteAndDeletionInvalidatePendingBroadcast() {
        Task t=entry(false);Intent old=pending(t);t.title="Edited";db.save(t);Reminders.reconcile(isolated,db);
        assertNull(RingingAlarms.consume(isolated,db,old,old.getLongExtra("due",-1)));
        Intent edited=pending(t);db.complete(t.id,true);Reminders.reconcile(isolated,db);
        assertNull(RingingAlarms.consume(isolated,db,edited,edited.getLongExtra("due",-1)));
        db.complete(t.id,false);Reminders.reconcile(isolated,db);Task restored=db.task(t.id);Intent restoredIntent=pending(restored);
        db.delete(t.id);Reminders.reconcile(isolated,db);
        assertNull(RingingAlarms.consume(isolated,db,restoredIntent,restoredIntent.getLongExtra("due",-1)));
    }
    public void testRoutineAlarmQueueIsBoundedAndRefillsAfterConsumption() {
        java.util.List<Task> tasks=new java.util.ArrayList<>();
        for(int i=0;i<205;i++) {
            Task t=new Task();t.title="Queue verification";t.date=LocalDate.now().plusDays(i+1).toString();t.time="12:00";t.reminderMinutes=0;db.save(t);tasks.add(t);
        }
        Reminders.reconcile(isolated,db);
        assertEquals(200,countPending(tasks));assertFalse(hasPending(tasks.get(200)));
        Task first=tasks.get(0);Intent due=pending(first);assertNotNull(RingingAlarms.consume(isolated,db,due,due.getLongExtra("due",-1)));
        // Same path the receiver refills; independent of the automatic-planning preference.
        Reminders.reconcile(isolated,db);assertEquals(200,countPending(tasks));assertTrue(hasPending(tasks.get(200)));assertFalse(hasPending(first));
    }
    private boolean hasPending(Task t) {
        Intent intent=new Intent(isolated,ReminderReceiver.class).setData(android.net.Uri.parse("anchor://reminder/"+t.id));
        return android.app.PendingIntent.getBroadcast(isolated,0,intent,android.app.PendingIntent.FLAG_NO_CREATE|android.app.PendingIntent.FLAG_IMMUTABLE)!=null;
    }
    private int countPending(java.util.List<Task> tasks){int n=0;for(Task t:tasks)if(hasPending(t))n++;return n;}
}
