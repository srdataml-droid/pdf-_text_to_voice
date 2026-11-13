package dev.anchor.app;
import android.content.Context;
import android.test.InstrumentationTestCase;
import java.time.*;

/** End-to-end delivery check. Creates one labeled temporary task, briefly rings, then cleans it up. */
public class AlarmLiveDeviceTest extends InstrumentationTestCase {
    public void testRealAlarmDeliverySnoozeAndDismiss() throws Exception {
        Context c=getInstrumentation().getTargetContext();
        assertTrue("Allow exact alarms before this explicit hardware check",Reminders.exactAllowed(c));
        assertTrue("Allow notification controls before this check",RingingAlarms.notificationsAllowed(c,Reminders.CHANNEL));
        boolean ringing=RingingAlarms.ringingEnabled(c);long id=0;
        try(AnchorDb db=new AnchorDb(c)) {
            try {
                RingingAlarms.setRingingEnabled(c,true);
                LocalDateTime at=LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0);
                Task t=new Task();t.title="Anchor temporary alarm validation";t.date=at.toLocalDate().toString();
                t.time=at.toLocalTime().toString();t.durationMinutes=1;t.reminderMinutes=0;id=db.save(t);Reminders.reconcile(c,db);
                long token=RingingAlarms.token(c,id),deadline=System.currentTimeMillis()+75_000;
                while(!RingingAlarmService.isRunning(id,token) && System.currentTimeMillis()<deadline)Thread.sleep(100);
                assertTrue("AlarmManager must deliver the occurrence to the ringing service",RingingAlarmService.isRunning(id,token));
                Thread.sleep(1200);
                assertEquals("ringing",RingingAlarms.preferences(c).getString("state"+id,""));
                RingingAlarms.snooze(c,id,5);
                assertEquals("pending",RingingAlarms.preferences(c).getString("state"+id,""));
                assertTrue(RingingAlarms.preferences(c).getLong("due"+id,0)>System.currentTimeMillis()+295_000);
                assertEquals(t.time,db.task(id).time);
                RingingAlarms.dismiss(c,id);assertEquals("dismissed",RingingAlarms.preferences(c).getString("state"+id,""));
            } finally {
                if(id>0){Reminders.cancel(c,id);db.delete(id);}
                RingingAlarms.setRingingEnabled(c,ringing);Reminders.reconcile(c,db);
            }
        }
    }
}
