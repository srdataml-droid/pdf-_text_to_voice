package dev.anchor.app;

import android.content.*;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        try(AnchorDb db=new AnchorDb(c)) {
            Task t=RingingAlarms.consume(c,db,i,System.currentTimeMillis());
            if(t==null) return;
            // Refill without the LLM or automatic-planning setting; delivery itself advances the queue.
            Reminders.refillAfterDelivery(c,goAsync());
            long token=i.getLongExtra("token",-1);
            // Only an exact, user-requested alarm is exempt from background FGS restrictions.
            if(i.getBooleanExtra("exact",false) && RingingAlarms.canRing(c)
                    && AlarmRules.mayRing(i.getLongExtra("due",-1),System.currentTimeMillis())) {
                try { c.startForegroundService(new Intent(c,RingingAlarmService.class).putExtras(i));return; }
                catch(RuntimeException unavailable) { /* Fall back to a visible, stoppable notification. */ }
            }
            RingingAlarms.showReminder(c,t,token,i.getBooleanExtra("exact",false)
                ?t.time+" · "+t.durationMinutes+" min":"Approximate reminder · "+t.time+" · snooze or dismiss");
        }
    }
}
